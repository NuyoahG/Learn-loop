package com.learnloop.tutor;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 临时查询能力。身份与资料范围在 Java 固定；Python/模型只能选择白名单工具和查询词。 */
@Service
public class TutorRetrievalService {
    private final TutorRetrievalMapper mapper;
    private final LearningSummaryMapper summaries;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,Lease> leases = new ConcurrentHashMap<>();

    public record Query(String tool,String query,String ref) {}
    public record Item(String ref,String label,Long materialId,String kind,String id,Object data) {}
    public record Result(List<Item> items) {}
    public record Trace(String tool,int count) {}
    static class Lease {
        final long user; final Long material; final Instant expires;
        final Map<String,Item> evidence = new LinkedHashMap<>();
        final List<Trace> trace = new ArrayList<>();
        final Set<Query> queries = new HashSet<>();
        Lease(long user,Long material,Instant expires,List<Item> initial) {
            this.user=user; this.material=material; this.expires=expires;
            initial.forEach(it -> evidence.put(it.ref(),it));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TutorRetrievalService(TutorRetrievalMapper mapper,LearningSummaryMapper summaries) {
        this(mapper,summaries,Clock.systemUTC());
    }
    TutorRetrievalService(TutorRetrievalMapper mapper,LearningSummaryMapper summaries,Clock clock) {
        this.mapper=mapper; this.summaries=summaries; this.clock=clock;
    }

    public synchronized String open(long user,Long material,List<Item> evidence) {
        leases.entrySet().removeIf(entry -> !entry.getValue().expires.isAfter(clock.instant()));
        if(leases.size()>=64) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"辅导请求较多，请稍后重试。");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);
        leases.put(token,new Lease(user,material,clock.instant().plusSeconds(65),evidence));
        return token;
    }
    public void close(String token) { leases.remove(token); }
    public boolean authorized(String token) {
        Lease lease=token==null?null:leases.get(token);
        return lease!=null && lease.expires.isAfter(clock.instant());
    }
    private Lease require(String token) {
        Lease lease=token==null?null:leases.get(token);
        if(lease==null || !lease.expires.isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"查询凭据无效。");
        return lease;
    }
    public Result retrieve(String token,Query query) {
        Lease lease=require(token);
        synchronized(lease) {
            if(query==null || query.tool()==null || query.query()==null || query.ref()==null
                    || query.query().length()>80 || query.ref().length()>100
                    || query.query().codePoints().anyMatch(Character::isISOControl))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"查询参数无效。");
            if(!Set.of("attempt_detail","search_material","search_notes").contains(query.tool()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持此查询。");
            if(lease.queries.size()>=2 || !lease.queries.add(query))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"查询次数已达上限。");
            List<Item> items=new ArrayList<>();
            if("attempt_detail".equals(query.tool())) {
                if(!query.query().isEmpty() || !lease.evidence.containsKey(query.ref()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"答题记录不在本次范围内。");
                // 再查数据库，且只返回已提交/已评分的记录；摘要中的 ID 不是绕过所有权的通行证。
                summaries.attempts(lease.user,lease.material).stream()
                    .filter(row -> (row.kind()+":"+row.id()).equals(query.ref())).findFirst()
                    .ifPresent(row -> items.add(new Item(query.ref(),row.materialName()+" · "+row.content(),
                        row.materialId(),row.kind(),row.id(),row)));
            } else {
                if(query.query().isBlank() || !query.ref().isEmpty())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供查询关键词。");
                if("search_material".equals(query.tool())) {
                    mapper.sources(lease.user,lease.material,query.query().strip()).forEach(row ->
                        items.add(new Item(row.ref(),row.label(),row.materialId(),"SOURCE",row.ref().substring(7),row)));
                } else {
                    mapper.notes(lease.user,lease.material,query.query().strip()).forEach(row -> {
                        int colon=row.ref().indexOf(':');
                        items.add(new Item(row.ref(),row.label()+" · "+row.quote(),row.materialId(),
                            row.ref().substring(0,colon),row.ref().substring(colon+1),row));
                    });
                }
            }
            items.forEach(item -> lease.evidence.put(item.ref(),item));
            lease.trace.add(new Trace(query.tool(),items.size()));
            return new Result(List.copyOf(items));
        }
    }
    // 最终引用和轨迹从 Java 实际执行的结果产生，不能信任模型自称调用过某个工具。
    public List<Item> cited(String token,List<String> refs) {
        Lease lease=require(token);
        synchronized(lease) {
            if(refs==null || refs.size()>6 || new HashSet<>(refs).size()!=refs.size()
                    || refs.stream().anyMatch(ref -> !lease.evidence.containsKey(ref)))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"辅导引用无效。");
            return refs.stream().map(lease.evidence::get).toList();
        }
    }
    public List<Trace> trace(String token) {
        Lease lease=require(token);
        synchronized(lease) { return List.copyOf(lease.trace); }
    }
}
