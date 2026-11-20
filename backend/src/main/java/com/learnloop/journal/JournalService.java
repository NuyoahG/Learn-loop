package com.learnloop.journal;

import static com.learnloop.journal.JournalApi.*;
import static com.learnloop.journal.JournalException.*;
import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationProperties;
import com.learnloop.learning.LearningMapper;
import com.learnloop.material.MaterialParsingService;
import com.learnloop.material.MaterialService;
import com.learnloop.modelsettings.ModelCredentialService;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JournalService {
    private static final Set<String> COLORS = Set.of("LEMON", "MINT", "SKY", "LILAC", "PEACH");
    private static final Set<String> DEPTHS = Set.of("BRIEF", "DETAILED");
    private final JournalMapper journal;
    private final LearningMapper learning;
    private final MaterialService materials;
    private final MaterialParsingService parsing;
    private final GenerationClient model;
    private final GenerationProperties modelProperties;
    private final ModelCredentialService credentials;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecretKeySpec receiptKey;
    private final PdfAnnotationService pdfAnnotations;

    public JournalService(JournalMapper journal, LearningMapper learning, MaterialService materials,
                          MaterialParsingService parsing, GenerationClient model, GenerationProperties modelProperties,
                          ModelCredentialService credentials) {
        this(journal, learning, materials, parsing, model, modelProperties, credentials, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JournalService(JournalMapper journal, LearningMapper learning, MaterialService materials,
                          MaterialParsingService parsing, GenerationClient model, GenerationProperties modelProperties,
                          ModelCredentialService credentials, PdfAnnotationService pdfAnnotations) {
        this.journal = journal;
        this.learning = learning;
        this.materials = materials;
        this.parsing = parsing;
        this.model = model;
        this.modelProperties = modelProperties;
        this.credentials = credentials;
        this.pdfAnnotations = pdfAnnotations;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        receiptKey = new SecretKeySpec(secret, "HmacSHA256");
    }

    public Note note(long user, String version) {
        requireQuestion(user, version);
        return journal.note(user, version);
    }

    public Notes notes(long user, int page, String query, int size) {
        if (page < 1) throw invalid("页码必须大于零。");
        if (size < 1 || size > 20) throw invalid("每页笔记数必须在 1 到 20 之间。");
        // 多查一条判断还有没有下一页，省去单独的计数查询。
        String search = text(query, 100, true);
        long offset = ((long) page - 1) * size;
        // 旧数据库仍可查看题目笔记，完成增量迁移后才把阅读笔记放入同一分页。
        List<Note> rows;
        if (journal.readingNotesReady()) {
            rows = journal.pdfAnnotationsReady() ? journal.allNotesWithAnnotations(user, search, size + 1, offset)
                    : journal.allNotes(user, search, size + 1, offset);
        } else rows = journal.notes(user, search, size + 1, offset);
        return new Notes(rows.stream().limit(size).toList(), page, rows.size() > size);
    }

    @Transactional
    public Note saveNote(long user, String version, NoteEdit edit) {
        if (edit == null) throw invalid("请输入笔记内容。");
        String content = text(edit.content(), 8000, false);
        lockUser(user);
        requireQuestion(user, version);
        var existing = journal.note(user, version);
        if (existing != null && content.equals(existing.content())) return existing;
        // UUID 版本让两个标签页的编辑冲突可见，删除后重新创建也不会复用旧版本。
        if (!Objects.equals(edit.revision(), existing == null ? null : existing.revision())) {
            throw new JournalException(409, "NOTE_CHANGED", "笔记已在其他页面更新。请先保留草稿，再刷新后重试。");
        }
        journal.saveNote(user, version, content, UUID.randomUUID().toString(), now());
        return journal.note(user, version);
    }

    @Transactional
    public void deleteNote(long user, String version, String revision) {
        requireQuestion(user, version);
        if (journal.deleteNote(user, version, uuid(revision)) == 0 && journal.note(user, version) != null) {
            throw new JournalException(409, "NOTE_CHANGED", "笔记版本已变化，请刷新后重试。");
        }
    }

    @Transactional
    public Note updateReadingNote(long user, String id, NoteEdit edit) {
        validateReadingNoteId(id);
        if (edit == null) throw invalid("请输入笔记内容。");
        String content = text(edit.content(), 8000, false);
        lockUser(user);
        requireReadingNotes();
        Note existing = journal.pdfAnnotationsReady() ? journal.readingNoteWithAnnotation(user, id) : journal.readingNote(user, id);
        if (existing == null) throw new JournalException(404, "JOURNAL_NOT_FOUND", "找不到这条阅读笔记。");
        if (content.equals(existing.content())) return existing;
        if (!Objects.equals(edit.revision(), existing.revision())) {
            throw new JournalException(409, "NOTE_CHANGED", "笔记已在其他页面更新。请先保留草稿，再刷新后重试。");
        }
        journal.updateReadingNote(user, id, content, UUID.randomUUID().toString(), now());
        return journal.pdfAnnotationsReady() ? journal.readingNoteWithAnnotation(user, id) : journal.readingNote(user, id);
    }

    @Transactional
    public void deleteReadingNote(long user, String id, String revision) {
        validateReadingNoteId(id);
        lockUser(user);
        requireReadingNotes();
        if (journal.deleteReadingNote(user, id, uuid(revision)) == 0 && journal.readingNote(user, id) != null) {
            throw new JournalException(409, "NOTE_CHANGED", "笔记版本已变化，请刷新后重试。");
        }
    }

    private void requireReadingNotes() {
        if (!journal.readingNotesReady()) {
            throw new JournalException(503, "READING_NOTES_DATABASE_UNAVAILABLE", "阅读笔记尚未就绪，请先执行阅读笔记数据库迁移。");
        }
    }

    private static void validateReadingNoteId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw invalid("笔记编号无效。");
    }

    private static final int READER_PAGE_SIZE = 12;

    @Transactional(readOnly = true)
    public Reader reader(long user, long material, int page) {
        final int size = READER_PAGE_SIZE;
        var doc = materials.detail(user, material);
        var blocks = parsing.blocks(user, material, page, size);
        return new Reader(material, doc.originalName(), doc.parseAttempt(), blocks,
                journal.highlights(user, material, doc.parseAttempt(), ((long) page - 1) * size + 1, (long) page * size));
    }

    // 询问只读当前选区，模型调用在事务之外。未主动保存的问答仅停留在浏览器和本次响应。
    public Draft ask(long user, long material, Ask ask) {
        if (ask == null) throw invalid("请先选中一段正文。");
        String source = source(user, material, ask.anchor());
        String question = text(ask.question(), 500, true);
        String detail = depth(ask.detail());
        if (!modelProperties.isEnabled()) throw new JournalException(503, "MODEL_NOT_CONFIGURED", "请先启用模型服务。");
        String answer;
        try {
            answer = model.explain(source, ask.anchor().quote(), question, detail, credentials.requireKey(user));
        } catch (GenerationException ex) {
            throw new JournalException(ex.status(), ex.code(), "阅读问答暂未完成，请检查模型配置或稍后重试。");
        }
        answer = text(answer, 5000, false);
        long expires = Instant.now().plusSeconds(1800).getEpochSecond();
        var result = new Draft(ask.anchor(), question, detail, answer, expires, null);
        return new Draft(result.anchor(), question, detail, answer, expires, receipt(user, result));
    }

    @Transactional
    public Highlight saveHighlight(long user, long material, SaveHighlight request) {
        if (request == null || request.draft() == null || request.color() == null || !COLORS.contains(request.color())) {
            throw invalid("请选择有效的高亮颜色。");
        }
        var d = request.draft();
        validateAnchor(material, d.anchor());
        text(d.question(), 500, true);
        text(d.answer(), 5000, false);
        depth(d.detail());
        if (d.receipt() == null || !d.receipt().matches("[0-9a-f]{64}")) throw invalid("问答回执无效。");
        lockUser(user);
        source(user, material, d.anchor());
        if (pdfAnnotations != null) pdfAnnotations.requireNotDeleted(user, material, d.receipt());
        if (request.saveAsNote()) requireReadingNotes();
        if (request.pdfPosition() != null) {
            pdfAnnotations.requireReady();
            PdfAnnotationService.validatePosition(request.pdfPosition());
        }
        var existing = journal.highlight(user, d.receipt());
        if (existing != null) {
            // 同一回执的重试不应覆盖已编辑的笔记，也不应重建用户已删除的笔记。
            if (request.saveAsNote() && journal.readingNote(user, d.receipt()) == null) {
                throw new JournalException(409, "READING_NOTE_NOT_SAVED", "该高亮已保存，当前没有对应笔记。");
            }
            if (request.pdfPosition() != null) {
                pdfAnnotations.saveAi(user, material, d, request.pdfPosition(), request.color(), request.saveAsNote());
            }
            return existing;
        }
        if (d.expiresAt() <= Instant.now().getEpochSecond()
                || !MessageDigest.isEqual(HexFormat.of().parseHex(d.receipt()), HexFormat.of().parseHex(receipt(user, d)))) {
            throw new JournalException(409, "READING_DRAFT_EXPIRED", "临时回答已过期或内容已变化，请重新提问。");
        }
        if (journal.overlaps(user, d.anchor())) {
            throw new JournalException(409, "READING_HIGHLIGHT_OVERLAP", "选区与已保存的高亮重叠，请先查看或移除原高亮。");
        }
        LocalDateTime savedAt = now();
        journal.saveHighlight(user, d, request.color(), savedAt);
        // 高亮与笔记同事务提交，任一写入失败都不会留下部分结果。
        if (request.saveAsNote() && journal.copyHighlightToNote(user, d.receipt(), UUID.randomUUID().toString(), savedAt) != 1) {
            throw new IllegalStateException("Saved highlight was unavailable for reading note");
        }
        if (request.pdfPosition() != null) {
            pdfAnnotations.saveAi(user, material, d, request.pdfPosition(), request.color(), request.saveAsNote());
        }
        return journal.highlight(user, d.receipt());
    }

    @Transactional(readOnly = true)
    public StudyContext study(long user, long material, String id) {
        int block = requireCurrentMark(user, material, id);
        return new StudyContext((block - 1) / READER_PAGE_SIZE + 1, journal.studyTopics(user, material, id));
    }

    @Transactional
    public Highlight saveStudy(long user, long material, String id, StudyEdit edit) {
        if (edit == null || edit.intent() == null
                || !Set.of("HIGHLIGHT", "UNCLEAR", "CONFUSED", "REMEMBER").contains(edit.intent())) {
            throw invalid("请选择有效的标记意图。");
        }
        lockUser(user);
        requireCurrentMark(user, material, id);
        String topic = edit.topicId();
        // 文字匹配只排序，关联必须由用户保存。候选来自当前资料版本中的考点来源。
        if (topic != null && journal.studyTopics(user, material, id).stream().noneMatch(t -> t.id().equals(topic))) {
            throw invalid("请选择这份资料中可用的考点。");
        }
        journal.saveStudy(id, edit);
        return journal.highlight(user, id);
    }

    private int requireCurrentMark(long user, long material, String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw invalid("高亮编号无效。");
        Integer block = journal.studyBlock(user, material, id);
        if (block == null) throw missing();
        return block;
    }

    @Transactional
    public void deleteHighlight(long user, long material, String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw invalid("高亮编号无效。");
        lockUser(user);
        journal.deleteHighlight(user, material, id);
        if (pdfAnnotations != null) pdfAnnotations.deleteTextHighlightPosition(user, material, id);
    }

    private void requireQuestion(long user, String version) {
        if (learning.findQuestionVersion(user, uuid(version)) == null) throw missing();
    }

    private void lockUser(long user) {
        if (learning.lockUser(user) == null) throw missing();
    }

    private String source(long user, long material, Anchor anchor) {
        validateAnchor(material, anchor);
        var source = learning.findMaterialSource(user, material, anchor.blockId());
        if (source == null || source.parseAttempt() != anchor.parseAttempt()
                || !matchesSelection(source.textContent(), anchor)) throw stale();
        return source.textContent();
    }

    static boolean matchesSelection(String source, Anchor anchor) {
        int start = anchor.start(), end = anchor.end();
        return start >= 0 && end > start && end <= source.length()
                && !Character.isLowSurrogate(source.charAt(start))
                && !Character.isHighSurrogate(source.charAt(end - 1))
                && source.substring(start, end).equals(anchor.quote());
    }

    private static void validateAnchor(long material, Anchor anchor) {
        if (anchor == null || material <= 0 || anchor.materialId() != material || anchor.blockId() <= 0
                || anchor.parseAttempt() <= 0 || anchor.start() < 0 || anchor.end() <= anchor.start()
                || anchor.quote() == null || anchor.quote().length() != anchor.end() - anchor.start()) {
            throw invalid("请选择同一个正文块内的文字。");
        }
        text(anchor.quote(), 1200, false);
    }

    private static String text(String value, int max, boolean optional) {
        if (value == null) value = "";
        if (value.codePointCount(0, value.length()) > max || (!optional && value.isBlank())) {
            throw invalid("内容为空或超过 " + max + " 字的上限。");
        }
        return value.strip();
    }

    private static String depth(String detail) {
        if (detail == null || !DEPTHS.contains(detail)) throw invalid("请选择简短或详细回答。");
        return detail;
    }

    private static String uuid(String id) {
        try { if (id != null && UUID.fromString(id).toString().equals(id)) return id; }
        catch (IllegalArgumentException ignored) {}
        throw invalid("编号无效。");
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private record Proof(long user, Anchor anchor, String question, String detail, String answer, long expiresAt) {}

    private String receipt(long user, Draft draft) {
        // 回执绑定账号、来源、选区和完整回答。模型输出未经用户确认无需写库。
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(receiptKey);
            return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(
                    new Proof(user, draft.anchor(), draft.question(), draft.detail(), draft.answer(), draft.expiresAt()))));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign reading response", ex);
        }
    }
}
