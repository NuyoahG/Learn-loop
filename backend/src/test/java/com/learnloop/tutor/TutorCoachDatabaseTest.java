package com.learnloop.tutor;

import com.learnloop.generation.GenerationClient;
import com.learnloop.modelsettings.ModelCredentialService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 MySQL + Spring 安全链；模型边界用替身，不读取本机商业模型凭据。 */
@EnabledIfEnvironmentVariable(named="LEARNLOOP_RUN_TUTOR_DB_TESTS",matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
    "spring.datasource.username=root","spring.datasource.password=",
    "learnloop.material.parse.recovery-enabled=false","learnloop.learning.worker-enabled=false",
    "learnloop.generation.worker-enabled=false"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class TutorCoachDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TutorCoachService coach;
    @Autowired TutorRetrievalService retrieval;
    @Autowired TutorRetrievalMapper tools;
    @Autowired MockMvc mvc;
    @MockitoBean GenerationClient model;
    @MockitoBean ModelCredentialService credentials;
    @MockitoBean LearningSummaryService summaries;
    List<Long> users=new ArrayList<>();
    long user,other,material,otherMaterial;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(13316);
        assertThat(jdbc.queryForObject("SELECT @@datadir",String.class)).contains("learnloop-dev-m1");
        var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/manual/stage4_9_tutor_lessons.sql"));
        migration.execute(jdbc.getDataSource()); migration.execute(jdbc.getDataSource());
        user=newUser();other=newUser();material=newMaterial(user);otherMaterial=newMaterial(other);
        for(long[] pair:List.of(new long[]{user,material},new long[]{other,otherMaterial})) {
            jdbc.update("INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator) VALUES(?,1,'text','事务提交持久保存','{}')",pair[1]);
            jdbc.update("INSERT INTO reading_notes(id,user_id,material_id,quote,question,content,revision,updated_at) VALUES(?,?,?,'事务','怎么理解','提交与回滚的区别',?,UTC_TIMESTAMP(6))",UUID.randomUUID().toString(),pair[0],pair[1],UUID.randomUUID().toString());
        }
        var empty=new LearningSummaryService.Summary(material,List.of(),List.of(),List.of(),List.of(),List.of());
        when(summaries.load(user,material)).thenReturn(empty);
        when(summaries.context(empty,true)).thenReturn("{}");
        when(credentials.requireKey(user)).thenReturn("synthetic");
    }
    @AfterEach void cleanup() {
        for(long owner:users) {
            jdbc.update("DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id WHERE m.user_id=?",owner);
            jdbc.update("DELETE FROM materials WHERE user_id=?",owner);
            jdbc.update("DELETE FROM users WHERE id=?",owner);
        }
    }
    long newUser() {
        String username="coach_"+UUID.randomUUID().toString().substring(0,18);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,'test-disabled')",username);
        long id=jdbc.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);users.add(id);return id;
    }
    long newMaterial(long owner) {
        String request=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,
                parse_status,parse_attempt,block_count,text_char_count,updated_at)
            VALUES(?,'事务资料.txt','txt',1,?,?,?,'PARSED',1,1,20,UTC_TIMESTAMP(6))
            """,owner,"a".repeat(64),request+".txt",request);
        return jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?",Long.class,owner,request);
    }

    @Test void actualSqlNeverCrossesOwnerOrMaterialAndTreatsWildcardsLiterally() {
        assertThat(tools.sources(user,null,"事务")).hasSize(1).allMatch(row -> row.materialId()==material);
        assertThat(tools.sources(user,otherMaterial,"事务")).isEmpty();
        assertThat(tools.notes(user,null,"事务")).hasSize(1).allMatch(row -> row.materialId()==material);
        assertThat(tools.notes(user,otherMaterial,"事务")).isEmpty();
        assertThat(tools.notes(user,material,"%")).isEmpty();
    }
    @Test void callbackRequiresCapabilityBeforeBodyAndKeepsBrowserCsrf() throws Exception {
        mvc.perform(post("/internal/tutor/retrieve").contentType("application/json").content("not-json")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tutor/chat").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        String token=retrieval.open(user,material,List.of());
        mvc.perform(post("/internal/tutor/retrieve").header("X-LearnLoop-Tutor-Token",token).contentType("application/json")
            .content("{\"tool\":\"search_material\",\"query\":\"事务\",\"ref\":\"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        retrieval.close(token);
        mvc.perform(post("/internal/tutor/retrieve").header("X-LearnLoop-Tutor-Token",token).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }
    @Test void retrievalAnswerFeedbackAndNextPromptFormOnePersistentLoop() {
        List<String> prompts=new ArrayList<>();
        when(model.tutorCoach(anyString(),anyString(),anyString(),eq("synthetic"))).thenAnswer(call -> {
            prompts.add(call.getArgument(1));String token=call.getArgument(2);
            var result=retrieval.retrieve(token,new TutorRetrievalService.Query("search_material","事务",""));
            return new GenerationClient.TutorCoach("可以用提交与回滚对比。","COMPARE",List.of(result.items().getFirst().ref()),retrieval.trace(token));
        });
        String id=UUID.randomUUID().toString();
        var input=new TutorCoachService.Chat("事务是什么",material,null,id);
        var first=coach.chat(user,input);
        assertThat(first.evidence()).hasSize(1);assertThat(coach.chat(user,input)).isEqualTo(first);
        coach.feedback(user,id,new TutorCoachService.Feedback("INCORRECT","我不理解隔离性，不是持久性"));
        coach.feedback(user,id,new TutorCoachService.Feedback("INCORRECT","我不理解隔离性，不是持久性"));
        assertThat(coach.recent(user,material).getFirst().detail()).contains("隔离性");
        assertThat(coach.recent(other,null)).isEmpty();
        assertThatThrownBy(() -> coach.feedback(other,id,new TutorCoachService.Feedback("HELPFUL",""))).isInstanceOf(ResponseStatusException.class);
        coach.chat(user,new TutorCoachService.Chat("换一种解释",material,null,UUID.randomUUID().toString()));
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("INCORRECT","我不理解隔离性");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tutor_lessons WHERE user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_events WHERE user_id=?",Integer.class,user)).isZero();
    }
}
