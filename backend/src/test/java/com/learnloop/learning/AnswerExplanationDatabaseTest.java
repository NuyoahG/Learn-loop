package com.learnloop.learning;

import com.learnloop.generation.GenerationClient;
import com.learnloop.modelsettings.ModelCredentialService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只对显式启用的13316隔离库执行。验证真实SQL的所有权/提交门槛以及旧题不可变语义。 */
@EnabledIfEnvironmentVariable(named="LEARNLOOP_RUN_EXPLANATION_DB_TESTS",matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
    "spring.datasource.username=root","spring.datasource.password=",
    "learnloop.learning.enabled=true","learnloop.learning.worker-enabled=false",
    "learnloop.generation.enabled=true","learnloop.generation.worker-enabled=false",
    "learnloop.material.parse.recovery-enabled=false"})
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class AnswerExplanationDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningService learning;
    @Autowired AnswerExplanationMapper mapper;
    @Autowired AnswerExplanationService explanations;
    @Autowired com.learnloop.answer.AnswerMapper histories;
    @MockitoBean GenerationClient model;
    @MockitoBean ModelCredentialService credentials;
    final List<Long> users=new ArrayList<>();
    long user,other,material,block,question;
    String concept,version;
    static final String SOURCE="事务提交之后，所有修改应当持久保存。";

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(13316);
        assertThat(jdbc.queryForObject("SELECT @@datadir",String.class)).contains("learnloop-dev-m1");
        user=newUser();other=newUser();String request=uuid();
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,
                parse_status,parse_attempt,block_count,text_char_count,updated_at)
            VALUES(?,'explanation-test.txt','txt',1,?,?,?,'PARSED',1,1,30,UTC_TIMESTAMP(6))
            """,user,"a".repeat(64),uuid()+".txt",request);
        material=jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?",Long.class,user,request);
        jdbc.update("INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator) VALUES(?,1,'text',?,'{}')",material,SOURCE);
        block=jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=?",Long.class,material);
        concept=learning.createConcept(user,new LearningApi.CreateConcept(material,block,"持久性","提交的结果应当持久保存。","MEDIUM",uuid())).id();
        jdbc.update("""
            INSERT INTO questions(user_id,content,option_a,option_b,option_c,option_d,correct_answer,explanation)
            VALUES(?,'提交后应如何保存修改？','持久保存','随机丢弃','撤销修改','只留内存','A','提交后应持久保存，因此选A。')
            """,user);
        question=jdbc.queryForObject("SELECT id FROM questions WHERE user_id=?",Long.class,user);
        version=learning.bindQuestion(user,question,new LearningApi.BindQuestion(concept,null)).versionId();
    }
    long newUser() {
        String username="explain_"+uuid().substring(0,16);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,'test-disabled')",username);
        long id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);users.add(id);return id;
    }
    @AfterEach void cleanup() {
        for(long owner:users) {
            for(String table:List.of("topic_review_attempts","topic_review_cards","adaptive_topics","learning_events",
                    "learning_evidence_claims","learning_attempts","learning_mastery","learning_questions","learning_concepts",
                    "answer_records","questions")) jdbc.update("DELETE FROM "+table+" WHERE user_id=?",owner);
            jdbc.update("DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id WHERE m.user_id=?",owner);
            jdbc.update("DELETE FROM materials WHERE user_id=?",owner);jdbc.update("DELETE FROM users WHERE id=?",owner);
        }
    }
    String submitted(String selected) {
        String attempt=learning.createAttempt(user,new LearningApi.CreateAttempt(version,uuid())).attemptId();
        learning.submit(user,attempt,new LearningApi.Submit(selected,uuid(),"SOMEWHAT_SURE"));
        return attempt;
    }
    @Test void realQueryRejectsUnsubmittedAndOtherUserButExplainsBothOutcomesWithoutModel() {
        String open=learning.createAttempt(user,new LearningApi.CreateAttempt(version,uuid())).attemptId();
        assertThat(mapper.practice(user,open)).isNull();
        assertThatThrownBy(()->explanations.brief(user,"PRACTICE",open)).isInstanceOf(LearningException.class);
        learning.submit(user,open,new LearningApi.Submit("B",uuid(),"UNSURE"));
        assertThat(explanations.brief(user,"PRACTICE",open).brief()).isEqualTo("提交后应持久保存，因此选A。");
        assertThat(mapper.practice(user,open).selected()).isEqualTo("B");
        assertThat(explanations.brief(user,"PRACTICE",submitted("A")).brief()).contains("选A");
        assertThat(mapper.practice(other,open)).isNull();
        assertThatThrownBy(()->explanations.detailed(other,"PRACTICE",open)).isInstanceOf(LearningException.class);
        verifyNoInteractions(model,credentials);
    }
    @Test void changedOriginalDoesNotAttachNewExplanationToOldAnswerAndUsesFrozenSource() {
        String attempt=submitted("B");
        jdbc.update("UPDATE questions SET content='新的不相关题目',correct_answer='B',explanation='新题应选B' WHERE user_id=? AND id=?",user,question);
        jdbc.update("UPDATE material_blocks SET text_content='重解析后不同正文' WHERE material_id=?",material);
        var row=mapper.practice(user,attempt);
        assertThat(row.explanation()).isNull();assertThat(row.correctAnswer()).isEqualTo("A");
        assertThat(histories.findPage(user,10,0,null,"desc")).hasSize(1)
                .allSatisfy(record -> assertThat(record.content()).isEqualTo("提交后应如何保存修改？"));
        assertThat(histories.findPage(other,10,0,null,"desc")).isEmpty();
        assertThat(row.sourceText()).isEqualTo(SOURCE);
        assertThat(explanations.brief(user,"PRACTICE",attempt).brief()).contains("正确选项是 A").doesNotContain("新题应选B");
        when(credentials.requireKey(user)).thenReturn("synthetic");
        when(model.explain(anyString(),anyString(),anyString(),eq("DETAILED"),eq("synthetic"))).thenAnswer(call->{
            assertThat((String)call.getArgument(0)).contains(SOURCE,"提交后应如何保存修改").doesNotContain("新的不相关题目","重解析后不同正文");
            return "原来的题目应该选择A。";
        });
        assertThat(explanations.detailed(user,"PRACTICE",attempt).answer()).contains("选择A");
    }
    @Test void reviewCanBeExplainedAfterAnswerBeforeRating() {
        String topic=uuid(),attempt=uuid();
        jdbc.update("INSERT INTO adaptive_topics(id,user_id,material_id,topic_key,chapter,group_name,name,definition) VALUES(?,?,?,?,'事务','事务属性','持久性','持久保存')",topic,user,material,"b".repeat(64));
        var due=LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbc.update("INSERT INTO topic_review_cards(user_id,material_id,topic_id,locked_job_id,created_at,due_at) VALUES(?,?,?,?,?,?)",user,material,topic,uuid(),due,due);
        jdbc.update("INSERT INTO topic_review_attempts(id,user_id,material_id,topic_id,question_version_id,prior_due,selected) VALUES(?,?,?,?,?,?,'B')",attempt,user,material,topic,version,due);
        assertThat(jdbc.queryForObject("SELECT rating FROM topic_review_attempts WHERE id=?",Integer.class,attempt)).isNull();
        assertThat(explanations.brief(user,"REVIEW",attempt).brief()).contains("因此选A");
        assertThat(mapper.review(user,attempt).selected()).isEqualTo("B");
        assertThat(mapper.review(other,attempt)).isNull();verifyNoInteractions(model,credentials);
    }
    static String uuid(){return UUID.randomUUID().toString();}

    @Test void legacyHistoryUsesOwnedCurrentQuestionWithoutBreakingFiltersAndPaging() {
        jdbc.update("INSERT INTO answer_records(user_id,question_id,selected_option,correct_option_snapshot,is_correct) VALUES(?,?,'B','A',FALSE)",user,question);
        jdbc.update("INSERT INTO answer_records(user_id,question_id,selected_option,correct_option_snapshot,is_correct) VALUES(?,?,'A','A',TRUE)",user,question);
        var first=histories.findPage(user,1,0,null,"asc").getFirst();
        var second=histories.findPage(user,1,1,null,"asc").getFirst();
        assertThat(first.recordId()).isLessThan(second.recordId());
        assertThat(first.content()).isEqualTo("提交后应如何保存修改？");
        assertThat(histories.findPage(user,10,0,false,"desc")).hasSize(1)
                .allSatisfy(record -> assertThat(record.recordId()).isEqualTo(first.recordId()));
        assertThat(histories.countByCorrect(user,false)).isEqualTo(1);
        // 没有快照的旧记录也不能借当前题目表读取其他账号的正文。
        jdbc.update("UPDATE questions SET user_id=?,content='其他用户的题目' WHERE id=?",other,question);
        assertThat(histories.findPage(user,10,0,null,"desc")).hasSize(2)
                .allSatisfy(record -> assertThat(record.content()).isNull());
    }
}
