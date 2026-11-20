package com.learnloop.journal;

import static com.learnloop.journal.PdfAnnotationApi.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learnloop.generation.GenerationClient;
import com.learnloop.modelsettings.ModelCredentialService;
import com.learnloop.support.ExperimentClientTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named = "LEARNLOOP_RUN_PDF_ANNOTATIONS_DB_TESTS", matches = "true")
@Import(ExperimentClientTestSupport.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "learnloop.generation.enabled=true",
        "learnloop.generation.worker-enabled=false",
        "learnloop.material.parse.recovery-enabled=false",
        "learnloop.material.storage-root=${java.io.tmpdir}/learnloop-pdf-annotations-test"
})
class PdfAnnotationDatabaseTest {
    @Autowired PdfAnnotationService annotations;
    @Autowired PdfAnnotationMapper mapper;
    @Autowired JournalService journal;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GenerationClient model;
    @MockitoBean ModelCredentialService credentials;
    private Long owner;
    private Long other;
    private long material;
    private long block;

    @BeforeEach
    void createSource() {
        assertEquals(13316, jdbc.queryForObject("SELECT @@port", Integer.class));
        assertTrue(mapper.ready(), "先在隔离库运行 stage4_4_pdf_annotations.sql");
        owner = createUser();
        other = createUser();
        String request = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,
                request_id,parse_status,parse_attempt,block_count,text_char_count,updated_at)
            VALUES(?,'PDF标注测试.pdf','pdf',1,?,?,?,'PARSED',1,1,4,?)
            """, owner, "c".repeat(64), UUID.randomUUID() + ".bin", request, LocalDateTime.now());
        material = jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?", Long.class, owner, request);
        jdbc.update("""
            INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
            VALUES(?,1,'text','事务提交','{"kind":"pdf","pageNumber":1,"partIndex":1}')
            """, material);
        block = jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=?", Long.class, material);
        when(credentials.requireKey(owner)).thenReturn("synthetic");
        when(model.explain("事务提交", "事务", "", "BRIEF", "synthetic")).thenReturn("模型回答");
    }

    @AfterEach
    void cleanOnlyOwnedData() {
        if (owner != null && material > 0) {
            jdbc.update("DELETE FROM material_blocks WHERE material_id=?", material);
            jdbc.update("DELETE FROM materials WHERE id=? AND user_id=?", material, owner);
        }
        if (owner != null) jdbc.update("DELETE FROM users WHERE id=?", owner);
        if (other != null) jdbc.update("DELETE FROM users WHERE id=?", other);
    }

    @Test
    void savesOnceThenEditsInBothPlacesWithoutLosingChanges() {
        Create request = create("自己的笔记");
        Annotation original = annotations.create(owner, material, request);
        assertEquals(original, annotations.create(owner, material, request));
        assertEquals(1, annotations.list(owner, material).records().size());
        assertEquals("自己的笔记", original.note());
        var note = journal.notes(owner, 1, "自己的", 12).records().getFirst();
        assertEquals(original.id(), note.annotationId());
        assertEquals(original.noteId(), note.readingNoteId());
        assertThrows(RuntimeException.class, () -> annotations.list(other, material));
        var edit = journal.updateReadingNote(owner, note.readingNoteId(),
                new JournalApi.NoteEdit("在笔记页更新", note.revision()));
        assertEquals(original.id(), edit.annotationId());
        assertEquals(original.id(), journal.updateReadingNote(owner, note.readingNoteId(),
                new JournalApi.NoteEdit("在笔记页更新", note.revision())).annotationId());
        assertEquals("PDF_ANNOTATION_CHANGED", assertThrows(JournalException.class,
                () -> annotations.update(owner, material, original.id(),
                        new Edit(original.revision(), "SKY", "旧窗口草稿", original.noteRevision()))).code);
        Annotation latest = annotations.list(owner, material).records().getFirst();
        Annotation changed = annotations.update(owner, material, latest.id(),
                new Edit(latest.revision(), "MINT", "已核对的新笔记", latest.noteRevision()));
        assertEquals(changed, annotations.update(owner, material, latest.id(),
                new Edit(latest.revision(), "MINT", "已核对的新笔记", latest.noteRevision())));
        assertEquals("MINT", changed.color());
        assertEquals("已核对的新笔记", journal.notes(owner, 1, "", 12).records().getFirst().content());
        annotations.delete(owner, material, changed.id(), changed.revision());
        annotations.delete(owner, material, changed.id(), changed.revision());
        assertTrue(annotations.list(owner, material).records().isEmpty());
        assertTrue(journal.notes(owner, 1, "", 12).records().isEmpty());
        assertEquals("PDF_ANNOTATION_DELETED", assertThrows(JournalException.class,
                () -> annotations.create(owner, material, request)).code);
    }

    @Test
    void pureHighlightCanGainAndLoseANote() {
        Annotation highlight = annotations.create(owner, material, create(null));
        assertNull(highlight.noteId());
        assertTrue(journal.notes(owner, 1, "", 12).records().isEmpty());
        Annotation withNote = annotations.update(owner, material, highlight.id(),
                new Edit(highlight.revision(), "LEMON", "补充笔记", null));
        assertNotNull(withNote.noteId());
        Annotation cleared = annotations.update(owner, material, withNote.id(),
                new Edit(withNote.revision(), "PEACH", "", withNote.noteRevision()));
        assertNull(cleared.noteId());
        assertEquals(1, annotations.list(owner, material).records().size());
        assertTrue(journal.notes(owner, 1, "", 12).records().isEmpty());
    }

    @Test
    void invalidGeometryAndChangedCreateAreRejected() {
        Create first = create(null);
        annotations.create(owner, material, first);
        assertEquals("PDF_ANNOTATION_REQUEST_CHANGED", assertThrows(JournalException.class,
                () -> annotations.create(owner, material,
                        new Create(first.id(), 2, first.quote(), first.rects(), first.color(), null))).code);
        assertEquals("INVALID_JOURNAL_REQUEST", assertThrows(JournalException.class,
                () -> annotations.create(owner, material, new Create(UUID.randomUUID().toString(),
                        1, "事务", List.of(new Rect(.8, .1, .4, .1)), "LEMON", null))).code);
        assertThrows(JournalException.class, () -> PdfAnnotationService.validatePosition(
                new Position(1, List.of(new Rect(Double.NaN, 0, .1, .1)))));
        assertEquals(1, annotations.list(owner, material).records().size());
    }

    @Test
    void aiAnswerSavesTextHighlightNoteAndOriginalPositionTogether() {
        var anchor = new JournalApi.Anchor(material, 1, block, 0, 2, "事务");
        var draft = journal.ask(owner, material, new JournalApi.Ask(anchor, "", "BRIEF"));
        Position position = new Position(1, rects());
        var request = new JournalApi.SaveHighlight(draft, "SKY", true, position);
        journal.saveHighlight(owner, material, request);
        journal.saveHighlight(owner, material, request);
        Annotation saved = annotations.list(owner, material).records().getFirst();
        assertEquals(draft.receipt(), saved.id());
        assertEquals("模型回答", saved.note());
        assertEquals(saved.id(), journal.notes(owner, 1, "", 12).records().getFirst().annotationId());
        annotations.delete(owner, material, saved.id(), saved.revision());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reading_highlights WHERE id=?", Integer.class, draft.receipt()));
    }

    @Test
    void deletingTextHighlightHidesOriginalMarkAndPreservesIndependentNote() {
        var anchor = new JournalApi.Anchor(material, 1, block, 0, 2, "事务");
        var draft = journal.ask(owner, material, new JournalApi.Ask(anchor, "", "BRIEF"));
        var request = new JournalApi.SaveHighlight(draft, "SKY", true, new Position(1, rects()));
        journal.saveHighlight(owner, material, request);
        journal.deleteHighlight(owner, material, draft.receipt());
        assertTrue(annotations.list(owner, material).records().isEmpty());
        assertEquals(1, journal.notes(owner, 1, "", 12).records().size());
        assertThrows(JournalException.class, () -> journal.saveHighlight(owner, material, request));
    }

    private Create create(String note) {
        return new Create(UUID.randomUUID().toString(), 1, "事务", rects(), "LEMON", note);
    }
    private List<Rect> rects() { return List.of(new Rect(.1, .2, .3, .02)); }
    private Long createUser() {
        String username = "pdf_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,?)", username, "synthetic");
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }
}
