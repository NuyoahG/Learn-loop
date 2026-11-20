package com.learnloop.material;

import com.learnloop.config.MaterialParseProperties;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 三段式：短事务认领 → 事务外 HTTP → 短事务发布。
 * 当前限定单个 Java 实例；内存队列不是持久任务队列，重启遗留任务要求手动重试。
 */
@Service
public class MaterialParsingService {
    private static final Logger log=LoggerFactory.getLogger(MaterialParsingService.class);
    private final MaterialParseMapper mapper;
    private final MaterialMapper materials;
    private final MaterialService service;
    private final MaterialFileStore store;
    private final MaterialParserClient parser;
    private final MaterialParseProperties properties;
    private final TransactionTemplate transaction;
    private final JsonMapper json=JsonMapper.builder().build();
    // AbortPolicy 确保 HTTP 请求线程不会在队列满时自己执行耗时解析。
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"material-parser");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    record Claim(MaterialRow previous,MaterialRow current,boolean submitted) {}
    public record ParseReply(MaterialResponse material,int status) {}
    public record BlockView(Long blockId,Long materialId,int blockNo,String blockType,String text,Map<?,?> sourceLocator) {}
    public record BlockPage(int page,int size,long total,long totalPages,List<BlockView> records) {}

    public MaterialParsingService(MaterialParseMapper mapper,MaterialMapper materials,MaterialService service,
            MaterialFileStore store,MaterialParserClient parser,MaterialParseProperties properties,PlatformTransactionManager manager) {
        this.mapper=mapper;this.materials=materials;this.service=service;this.store=store;
        this.parser=parser;this.properties=properties;this.transaction=new TransactionTemplate(manager);
    }
    private LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
    public ParseReply start(Long userId,Long id) {
        // 先做详情校验，让非法 ID/他人 ID 与已有资料接口使用同一错误契约。
        service.detail(userId,id);
        Claim claim=transaction.execute(tx->{
            if(mapper.lockUser(userId)==null) throw new MaterialException(401,"LOGIN_REQUIRED","请先登录。");
            MaterialRow before=mapper.lockOwned(userId,id);
            if(before==null) throw new MaterialException(404,"MATERIAL_NOT_FOUND","没有找到可访问的资料。");
            if(Set.of("PARSED","PARSING").contains(before.getParseStatus())) return new Claim(before,before,false);
            if(mapper.activeForUser(userId)>0) throw new MaterialException(429,"USER_PARSE_BUSY","你已有一份资料正在解析，请稍候。");
            if(before.getParseAttempt()==Long.MAX_VALUE) throw new MaterialException(409,"ATTEMPT_LIMIT","解析尝试次数已达上限。");
            LocalDateTime claimedAt=now();
            if(mapper.claim(userId,id,before.getParseAttempt(),claimedAt)!=1) throw new IllegalStateException("Claim lost");
            // 用独立对象保留 prior，提交执行器失败时恢复原业务状态但不倒退 attempt。
            MaterialRow current=new MaterialRow();current.setId(id);current.setUserId(userId);
            current.setParseAttempt(before.getParseAttempt()+1);current.setUpdatedAt(claimedAt);
            current.setFileType(before.getFileType());current.setStorageKey(before.getStorageKey());
            current.setSha256(before.getSha256());current.setSizeBytes(before.getSizeBytes());
            return new Claim(before,current,true);
        });
        if(claim.submitted()) {
            try {executor.execute(()->execute(claim.current()));}
            catch(RejectedExecutionException ex) {
                // 若补偿写入也失败，周期恢复仍会让 PARSING 收敛；不伪装成已恢复。
                try {transaction.executeWithoutResult(tx->mapper.restoreRejected(claim.previous(),claim.current().getParseAttempt(),now()));}
                catch(RuntimeException failure){log.error("Parse rejection compensation failed material={} attempt={}",id,claim.current().getParseAttempt());}
                throw new MaterialException(503,"PARSER_QUEUE_FULL","解析队列已满，请稍后重新解析。");
            }
        }
        MaterialResponse response=service.detail(userId,id);
        int status=!claim.submitted() && "PARSED".equals(claim.current().getParseStatus())?200:202;
        return new ParseReply(response,status);
    }
    private void execute(MaterialRow claim) {
        long began=System.nanoTime(); String outcome="STALE_RESULT";
        try {
            // 排队也算处理时间；超期任务不再调用 Python。
            if(claim.getUpdatedAt().plusSeconds(120).isBefore(now())) throw new MaterialException(504,"PARSER_TOTAL_TIMEOUT","处理超过总截止时间，请重试。");
            var latest=materials.findById(claim.getUserId(),claim.getId());
            if(!current(latest,claim.getParseAttempt())) return;
            String attempt=claim.getId()+":"+claim.getParseAttempt();
            var result=parser.parse(store.verifiedOriginal(claim),claim.getFileType(),attempt);
            outcome=publish(claim,result)?"PARSED":"STALE_RESULT";
        } catch(MaterialException ex) {
            outcome=ex.getErrorCode();failSafely(claim,outcome,ex.getMessage());
        } catch(RuntimeException ex) {
            outcome="PARSER_PUBLISH_FAILED";failSafely(claim,outcome,"解析结果未能保存，请稍后重试。");
        } finally {
            log.info("Material parse material={} attempt={} result={} elapsedMs={}",claim.getId(),claim.getParseAttempt(),outcome,(System.nanoTime()-began)/1000000);
        }
    }
    static boolean current(MaterialRow row,long attempt) {
        return row!=null && "PARSING".equals(row.getParseStatus()) && row.getParseAttempt()==attempt;
    }
    boolean publish(MaterialRow claim,ParserResultValidator.Result result) {
        return Boolean.TRUE.equals(transaction.execute(tx->{
            MaterialRow row=mapper.lockOwned(claim.getUserId(),claim.getId());
            if(!current(row,claim.getParseAttempt()) || row.getUpdatedAt().plusSeconds(120).isBefore(now())) return false;
            mapper.clearBlocks(claim.getUserId(),claim.getId());
            for(var block:result.blocks()) {
                MaterialBlockRow b=new MaterialBlockRow();b.setMaterialId(claim.getId());b.setBlockNo(block.number());
                b.setBlockType(block.type());b.setTextContent(block.text());b.setSourceLocator(json.writeValueAsString(block.source()));
                if(mapper.insertBlock(b)!=1) throw new IllegalStateException("Block insert failed");
                // 噪声区间单独保存，阅读和笔记仍使用原 text_content 与原字符偏移。
                if(block.noiseJson()!=null) mapper.insertModelNoise(claim.getId(),claim.getParseAttempt(),block.number(),block.noiseJson());
            }
            if(mapper.publish(claim.getUserId(),claim.getId(),claim.getParseAttempt(),result.version(),
                    result.warningsJson(),result.blocks().size(),result.characters(),now())!=1) throw new IllegalStateException("Publish lost");
            return true;
        }));
    }
    private void failSafely(MaterialRow claim,String code,String message) {
        try {transaction.executeWithoutResult(tx->mapper.fail(claim.getUserId(),claim.getId(),claim.getParseAttempt(),code,message,now()));}
        catch(RuntimeException ex){log.error("Parse failure state write failed material={} attempt={}; recovery will retry",claim.getId(),claim.getParseAttempt());}
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStartup() {
        if(!properties.isRecoveryEnabled()) return;
        try {int count=mapper.interruptOnStartup(now());log.info("Interrupted parse recovery count={}",count);}
        catch(RuntimeException ex){log.error("Startup parse recovery unavailable; scheduled recovery remains active");}
    }
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public void recoverExpired() {
        if(!properties.isRecoveryEnabled()) return;
        try {
            for(MaterialRow row:mapper.stale(now().minusSeconds(120))) failSafely(row,"PARSER_TOTAL_TIMEOUT","处理超过总截止时间，请手动重试。");
        } catch(RuntimeException ex){log.error("Periodic parse recovery unavailable; will retry");}
    }
    public BlockPage blocks(Long userId,Long id,int page,int size) {
        MaterialResponse m=service.detail(userId,id); // 必须先检查所有者，再暴露状态。
        if(page<1 || size<1 || size>50) throw new MaterialException(400,"INVALID_PARAMETER","page 至少为 1，size 在 1 到 50 之间。");
        if(!"PARSED".equals(m.parseStatus())) throw new MaterialException(409,"MATERIAL_NOT_PARSED","资料尚未解析完成，暂无正文预览。");
        var records=mapper.blocks(userId,id,size,((long)page-1)*size).stream().map(b->new BlockView(b.getId(),id,b.getBlockNo(),b.getBlockType(),b.getTextContent(),json.readValue(b.getSourceLocator(),Map.class))).toList();
        long total=m.blockCount();return new BlockPage(page,size,total,total/size+(total%size==0?0:1),records);
    }
    @PreDestroy public void shutdown(){executor.shutdownNow();}
}
