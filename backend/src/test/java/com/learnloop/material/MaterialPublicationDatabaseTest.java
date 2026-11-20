package com.learnloop.material;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 显式启用的本机 MySQL 集成测试：真实 InnoDB 回滚，不用 mock 宣称数据库原子性。
 * 随机账号只含本测试资料；finally 精确清理，不修改已有账号或记录。
 */
@EnabledIfEnvironmentVariable(named="LEARNLOOP_RUN_DB_TESTS",matches="true")
@SpringBootTest(properties="learnloop.material.parse.recovery-enabled=false")
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class MaterialPublicationDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired MaterialParsingService service;
    @Autowired com.learnloop.config.MaterialParseProperties parseProperties;
    @MockitoSpyBean MaterialParseMapper mapper;
    @MockitoSpyBean MaterialFileStore store;
    @MockitoBean MaterialParserClient parser;
    private final List<Long> extraUsers=new ArrayList<>();
    private CountDownLatch release;
    private Long userId,materialId;
    private String username;
    private MaterialRow claim;
    @BeforeEach void createOwnedFixture(){
        username="ll31bdb_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,?)",username,"disabled-test-account");
        userId=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,
                parse_status,parse_attempt,updated_at)
            VALUES(?,'atomic-test.txt','txt',1,?,?,?,'PARSING',1,?)
            """,userId,"0".repeat(64),UUID.randomUUID()+".bin",UUID.randomUUID().toString(),LocalDateTime.now(ZoneOffset.UTC));
        materialId=jdbc.queryForObject("SELECT id FROM materials WHERE user_id=?",Long.class,userId);
        claim=new MaterialRow();claim.setId(materialId);claim.setUserId(userId);claim.setParseAttempt(1L);
    }
    @AfterEach void cleanup(){
        if(release!=null){
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE parse_status='PARSING' AND user_id IN (SELECT id FROM users WHERE username LIKE 'll31bdb_%')",Integer.class)>0) {
                try{Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}
            }
        }
        reset(mapper);
        for(Long owner:extraUsers){
            jdbc.update("DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id WHERE m.user_id=?",owner);
            jdbc.update("DELETE FROM materials WHERE user_id=?",owner);
            jdbc.update("DELETE FROM users WHERE id=? AND username LIKE 'll31bdb_%'",owner);
        }
        if(materialId!=null){jdbc.update("DELETE FROM material_blocks WHERE material_id=?",materialId);jdbc.update("DELETE FROM materials WHERE id=? AND user_id=?",materialId,userId);}
        if(userId!=null){jdbc.update("DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id WHERE m.user_id=?",userId);jdbc.update("DELETE FROM materials WHERE user_id=?",userId);}
        if(userId!=null)jdbc.update("DELETE FROM users WHERE id=? AND username=?",userId,username);
    }
    private ParserResultValidator.Result result(){return new ParserResultValidator.Result("learnloop-parser/0.1.0",List.of(
        new ParserResultValidator.Block(1,"text","first",Map.of("kind","text","lineStart",1,"lineEnd",1,"partIndex",1)),
        new ParserResultValidator.Block(2,"text","second",Map.of("kind","text","lineStart",3,"lineEnd",3,"partIndex",1))),11,"[]");}
    @Test void failedSecondBlockRollsBackFirstAndRetryPublishesAll(){
        AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation->{if(calls.incrementAndGet()==2){
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId),"故障注入前第一块必须已真实写入当前事务");
            throw new IllegalStateException("controlled second insert failure");
        }return sqlSession.getMapper(MaterialParseMapper.class).insertBlock(invocation.getArgument(0));}).when(mapper).insertBlock(any());
        assertThrows(RuntimeException.class,()->service.publish(claim,result()));
        assertEquals(2,calls.get(),"必须在第二块制造故障，不能把第一块没写入当成回滚成功");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId));
        assertEquals("PARSING",jdbc.queryForObject("SELECT parse_status FROM materials WHERE id=?",String.class,materialId));
        reset(mapper);
        assertTrue(service.publish(claim,result()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId));
        assertEquals("PARSED",jdbc.queryForObject("SELECT parse_status FROM materials WHERE id=?",String.class,materialId));
        assertFalse(service.publish(claim,result()),"成功资料不可重复发布");
    }
    @Test void lateAttemptCannotOverwriteNewAttempt(){
        jdbc.update("UPDATE materials SET parse_attempt=2 WHERE id=?",materialId);
        assertFalse(service.publish(claim,result()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId));
        claim.setParseAttempt(2L);assertTrue(service.publish(claim,result()));
    }
    @Test void expiredAttemptCannotPublishEvenBeforeWatchdogRuns(){
        jdbc.update("UPDATE materials SET updated_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(130),materialId);
        assertFalse(service.publish(claim,result()));
        service.recoverExpired(); // 此测试关闭恢复扫描，验证发布本身仍有截止栅栏。
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId));
    }
    private void blockParser() {
        release=new CountDownLatch(1);
        doReturn(java.nio.file.Path.of("controlled-test-only")).when(store).verifiedOriginal(any());
        when(parser.parse(any(),anyString(),anyString())).thenAnswer(invocation->{
            if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test latch timed out");
            return result();
        });
        jdbc.update("UPDATE materials SET parse_status='UPLOADED',parse_attempt=0 WHERE id=?",materialId);
    }
    private Long additionalMaterial(Long owner) {
        String request=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id) VALUES(?,'queue.txt','txt',1,?,?,?)",
            owner,"0".repeat(64),UUID.randomUUID()+".bin",request);
        return jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?",Long.class,owner,request);
    }
    private Long additionalUser() {
        String name="ll31bdb_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,'disabled-test-account')",name);
        Long owner=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);extraUsers.add(owner);return owner;
    }
    private int startStatus(Long owner,Long id) {
        try{return service.start(owner,id).status();}catch(MaterialException ex){return ex.getStatus();}
    }
    @Test void simultaneousSameMaterialClicksClaimExactlyOneAttempt() throws Exception {
        blockParser();
        try(var threads=Executors.newFixedThreadPool(2)){
            CountDownLatch launch=new CountDownLatch(1);
            var one=threads.submit(()->{launch.await();return startStatus(userId,materialId);});
            var two=threads.submit(()->{launch.await();return startStatus(userId,materialId);});launch.countDown();
            assertEquals(202,one.get(5,TimeUnit.SECONDS));assertEquals(202,two.get(5,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT parse_attempt FROM materials WHERE id=?",Integer.class,materialId));
            verify(parser,timeout(2000).times(1)).parse(any(),anyString(),anyString());
        }
    }
    @Test void simultaneousDifferentMaterialsRespectUserLimit() throws Exception {
        blockParser();Long other=additionalMaterial(userId);
        try(var threads=Executors.newFixedThreadPool(2)){
            CountDownLatch launch=new CountDownLatch(1);
            var one=threads.submit(()->{launch.await();return startStatus(userId,materialId);});
            var two=threads.submit(()->{launch.await();return startStatus(userId,other);});launch.countDown();
            List<Integer> statuses=new ArrayList<>(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(202,429),statuses);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE user_id=? AND parse_status='PARSING'",Integer.class,userId));
        }
    }
    @Test void boundedQueueRejectsFourthOwnerAndRestoresBusinessState() {
        blockParser();assertEquals(202,startStatus(userId,materialId));
        verify(parser,timeout(2000)).parse(any(),anyString(),anyString());
        for(int i=0;i<2;i++){Long owner=additionalUser();assertEquals(202,startStatus(owner,additionalMaterial(owner)));}
        Long rejectedOwner=additionalUser(),rejected=additionalMaterial(rejectedOwner);
        assertEquals(503,startStatus(rejectedOwner,rejected));
        assertEquals("UPLOADED",jdbc.queryForObject("SELECT parse_status FROM materials WHERE id=?",String.class,rejected));
        assertEquals(1,jdbc.queryForObject("SELECT parse_attempt FROM materials WHERE id=?",Integer.class,rejected),"拒绝后保留递增栅栏");
    }
    @Test void failedStateWriteConvergesAfterDatabaseOperationRecovers() throws Exception {
        // 不停止用户的 MySQL：只注入本次临时资料的一次持久化错误。
        // 第一次解析失败无法写 FAILED，模拟数据库操作恢复后调用同一个周期恢复入口。
        jdbc.update("UPDATE materials SET parse_status='UPLOADED',parse_attempt=0 WHERE id=?",materialId);
        doReturn(java.nio.file.Path.of("controlled-test-only")).when(store).verifiedOriginal(any());
        when(parser.parse(any(),anyString(),anyString())).thenThrow(new MaterialException(503,"PARSER_UNAVAILABLE","受控解析失败"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled database write outage"))
            .when(mapper).fail(eq(userId),eq(materialId),anyLong(),anyString(),anyString(),any());
        assertEquals(202,startStatus(userId,materialId));
        verify(mapper,timeout(2000)).fail(eq(userId),eq(materialId),eq(1L),anyString(),anyString(),any());
        assertEquals("PARSING",jdbc.queryForObject("SELECT parse_status FROM materials WHERE id=?",String.class,materialId));
        reset(mapper); // 恢复实际 MyBatis 操作，而不是直接在测试里写 FAILED。
        jdbc.update("UPDATE materials SET updated_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(130),materialId);
        parseProperties.setRecoveryEnabled(true);
        try { service.recoverExpired(); }
        finally { parseProperties.setRecoveryEnabled(false); }
        assertEquals("FAILED",jdbc.queryForObject("SELECT parse_status FROM materials WHERE id=?",String.class,materialId));
        assertEquals("PARSER_TOTAL_TIMEOUT",jdbc.queryForObject("SELECT error_code FROM materials WHERE id=?",String.class,materialId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_blocks WHERE material_id=?",Integer.class,materialId));
    }
}
