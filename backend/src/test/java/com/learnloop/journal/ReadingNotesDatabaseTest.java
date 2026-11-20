package com.learnloop.journal;

import static com.learnloop.journal.JournalApi.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learnloop.generation.GenerationClient;
import com.learnloop.modelsettings.ModelCredentialService;
import com.learnloop.support.ExperimentClientTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named = "LEARNLOOP_RUN_READING_NOTES_DB_TESTS", matches = "true")
@Import(ExperimentClientTestSupport.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "learnloop.generation.enabled=true",
        "learnloop.generation.worker-enabled=false",
        "learnloop.material.parse.recovery-enabled=false",
        "learnloop.material.storage-root=${java.io.tmpdir}/learnloop-reading-notes-test"
})
class ReadingNotesDatabaseTest {
    @Autowired JournalService service;
    @Autowired JournalMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GenerationClient model;
    @MockitoBean ModelCredentialService credentials;

    private Long owner;
    private Long other;
    private long materialId;
    private Anchor anchor;

    @BeforeEach
    void createOwnedSource() {
        // 数据库用例只允许访问独立实例，且只清理本用例创建的随机账号。
        assertEquals(13316, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertTrue(mapper.readingNotesReady(), "先在隔离数据库运行 stage4_3_reading_notes.sql");
        owner = createUser();
        other = createUser();
        String requestId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,
                request_id,parse_status,parse_attempt,block_count,text_char_count,updated_at)
            VALUES(?,'数据库笔记测试.pdf','pdf',1,?,?,?,'PARSED',1,1,4,?)
            """, owner, "a".repeat(64), UUID.randomUUID() + ".bin", requestId, LocalDateTime.now());
        materialId = jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?",
                Long.class, owner, requestId);
        jdbc.update("""
            INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
            VALUES(?,1,'text','事务提交',?)
            """, materialId, "{\"kind\":\"pdf\",\"pageNumber\":1,\"partIndex\":1}");
        long blockId = jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=?", Long.class, materialId);
        anchor = new Anchor(materialId, 1, blockId, 0, 2, "事务");
        when(credentials.requireKey(owner)).thenReturn("synthetic");
        when(model.explain("事务提交", "事务", "", "BRIEF", "synthetic")).thenReturn("模型回答");
    }

    @AfterEach
    void clearOnlyOwnedRows() {
        if (owner != null && materialId > 0) {
            jdbc.update("""
                DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id
                WHERE m.id=? AND m.user_id=?
                """, materialId, owner);
            jdbc.update("DELETE FROM materials WHERE id=? AND user_id=?", materialId, owner);
        }
        if (owner != null) jdbc.update("DELETE FROM users WHERE id=?", owner);
        if (other != null) jdbc.update("DELETE FROM users WHERE id=?", other);
    }

    @Test
    void savesOnceAndEditsNoteWithoutChangingHighlight() {
        Draft draft = service.ask(owner, materialId, new Ask(anchor, null, "BRIEF"));
        var request = new SaveHighlight(draft, "MINT", true);
        assertEquals(draft.receipt(), service.saveHighlight(owner, materialId, request).id());
        assertEquals(draft.receipt(), service.saveHighlight(owner, materialId, request).id());
        var notes = service.notes(owner, 1, "事务", 12).records();
        assertEquals(1, notes.size());
        Note note = notes.getFirst();
        assertEquals(draft.receipt(), note.readingNoteId());
        assertEquals("解释所选原文", note.question());
        assertEquals("模型回答", note.content());
        assertTrue(service.notes(other, 1, "事务", 12).records().isEmpty());
        assertEquals("JOURNAL_NOT_FOUND", assertThrows(JournalException.class,
                () -> service.updateReadingNote(other, note.readingNoteId(), new NoteEdit("越权编辑", note.revision()))).code);
        Note edited = service.updateReadingNote(owner, note.readingNoteId(), new NoteEdit("我自己的笔记", note.revision()));
        assertEquals("我自己的笔记", edited.content());
        assertEquals("模型回答", mapper.highlight(owner, draft.receipt()).answer());
        service.deleteReadingNote(owner, edited.readingNoteId(), edited.revision());
        assertNotNull(mapper.highlight(owner, draft.receipt()));
        assertEquals("READING_NOTE_NOT_SAVED", assertThrows(JournalException.class,
                () -> service.saveHighlight(owner, materialId, request)).code);
        assertTrue(service.notes(owner, 1, "", 12).records().isEmpty());
    }

    @Test
    void noteWriteFailureRollsBackNewHighlight() {
        Draft draft = service.ask(owner, materialId, new Ask(anchor, null, "BRIEF"));
        jdbc.update("""
            INSERT INTO reading_notes(id,user_id,material_id,quote,question,content,revision,updated_at)
            VALUES(?,?,?,'引文','问题','独立笔记',?,?)
            """, draft.receipt(), owner, materialId, UUID.randomUUID().toString(), LocalDateTime.now());
        assertThrows(DuplicateKeyException.class,
                () -> service.saveHighlight(owner, materialId, new SaveHighlight(draft, "MINT", true)));
        assertNull(mapper.highlight(owner, draft.receipt()));
        assertEquals("独立笔记", mapper.readingNote(owner, draft.receipt()).content());
    }

    private Long createUser() {
        String username = "read_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,?)", username, "synthetic");
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }
}
