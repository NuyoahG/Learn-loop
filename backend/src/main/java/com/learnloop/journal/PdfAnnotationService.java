package com.learnloop.journal;

import static com.learnloop.journal.JournalException.*;
import static com.learnloop.journal.PdfAnnotationApi.*;
import com.learnloop.learning.LearningMapper;
import com.learnloop.material.MaterialService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PdfAnnotationService {
    private static final Set<String> COLORS = Set.of("LEMON", "MINT", "SKY", "LILAC", "PEACH");
    private final PdfAnnotationMapper mapper;
    private final JournalMapper journal;
    private final LearningMapper learning;
    private final MaterialService materials;
    private final JsonMapper json = JsonMapper.builder().build();

    public PdfAnnotationService(PdfAnnotationMapper mapper, JournalMapper journal,
                                LearningMapper learning, MaterialService materials) {
        this.mapper = mapper;
        this.journal = journal;
        this.learning = learning;
        this.materials = materials;
    }

    public void requireReady() {
        if (!mapper.ready() || !journal.readingNotesReady()) {
            throw new JournalException(503, "PDF_ANNOTATIONS_DATABASE_UNAVAILABLE", "PDF 标注尚未就绪，请执行标注数据库迁移。");
        }
    }

    @Transactional(readOnly = true)
    public Annotations list(long user, long material) {
        requirePdf(user, material);
        requireReady();
        return new Annotations(mapper.list(user, material).stream().map(this::view).toList());
    }

    @Transactional
    public Annotation create(long user, long material, Create request) {
        if (request == null) throw invalid("请选择 PDF 原文。");
        String id = uuid(request.id());
        validatePosition(new Position(request.pageNumber(), request.rects()));
        String quote = text(request.quote(), 4000, false);
        String note = text(request.note(), 8000, true);
        color(request.color());
        lockUser(user);
        requirePdf(user, material);
        requireReady();
        String hash = digest(json.writeValueAsString(new Create(id, request.pageNumber(), quote,
                request.rects(), request.color(), note)));
        Row existing = mapper.find(user, material, id);
        if (existing != null) {
            if (existing.deleted()) throw new JournalException(409, "PDF_ANNOTATION_DELETED", "这条标注已经删除。");
            if (!hash.equals(existing.createHash())) {
                throw new JournalException(409, "PDF_ANNOTATION_REQUEST_CHANGED", "保存请求已变化，请重新选择原文。");
            }
            return view(existing);
        }
        requireCapacity(user, material);
        String noteId = digest("pdf-note:" + user + ":" + id);
        LocalDateTime now = now();
        mapper.insert(user, material, id, request.pageNumber(), quote, json.writeValueAsString(request.rects()),
                request.color(), UUID.randomUUID().toString(), hash, noteId, now);
        if (!note.isEmpty()) mapper.insertNote(user, material, noteId, quote, note, UUID.randomUUID().toString(), now);
        return view(mapper.find(user, material, id));
    }

    @Transactional
    public Annotation update(long user, long material, String id, Edit edit) {
        identifier(id);
        if (edit == null) throw invalid("请填写标注内容。");
        color(edit.color());
        String note = edit.note() == null ? null : text(edit.note(), 8000, true);
        lockUser(user);
        requirePdf(user, material);
        requireReady();
        Row row = requireRow(user, material, id);
        // 成功回执丢失后用旧版本重试，同样的目标状态直接返回，保留已保存的版本。
        if (edit.color().equals(row.color()) && (note == null || note.equals(Objects.toString(row.note(), "")))) {
            return view(row);
        }
        requireRevision(row, edit.revision());
        // 笔记可在独立笔记页编辑，原件页需同时核对它的版本，避免覆盖另一页的编辑。
        if (note != null && !Objects.equals(edit.noteRevision(), row.noteRevision())) {
            throw new JournalException(409, "NOTE_CHANGED", "笔记已在其他页面更新，请保留草稿并刷新。");
        }
        LocalDateTime now = now();
        if (note != null) {
            if (note.isEmpty()) mapper.deleteNote(user, row.noteId());
            else if (row.noteRevision() == null) {
                mapper.insertNote(user, material, row.noteId(), row.quote(), note, UUID.randomUUID().toString(), now);
            } else if (!note.equals(row.note())) {
                journal.updateReadingNote(user, row.noteId(), note, UUID.randomUUID().toString(), now);
            }
        }
        mapper.update(user, material, id, edit.color(), UUID.randomUUID().toString(), now);
        return view(mapper.find(user, material, id));
    }

    @Transactional
    public void delete(long user, long material, String id, String revision) {
        identifier(id);
        lockUser(user);
        requirePdf(user, material);
        requireReady();
        Row row = mapper.find(user, material, id);
        if (row == null || row.deleted()) return;
        requireRevision(row, revision);
        mapper.deleteNote(user, row.noteId());
        journal.deleteHighlight(user, material, id);
        // 墓碑防止迟到的原始创建请求把已删除的高亮重新插回。
        mapper.delete(user, material, id, UUID.randomUUID().toString(), now());
    }

    /** 调用方已在 JournalService 的事务中校验原文和回执，此处只补充原件坐标。 */
    public void saveAi(long user, long material, JournalApi.Draft draft, Position position,
                       String color, boolean saveAsNote) {
        requireReady();
        validatePosition(position);
        requirePdf(user, material);
        String id = draft.receipt();
        Row existing = mapper.find(user, material, id);
        if (existing != null) {
            if (existing.deleted()) throw new JournalException(409, "PDF_ANNOTATION_DELETED", "这条标注已经删除。");
            return;
        }
        requireCapacity(user, material);
        mapper.insert(user, material, id, position.pageNumber(), draft.anchor().quote(),
                json.writeValueAsString(position.rects()), color, UUID.randomUUID().toString(),
                digest("ai:" + id), id, now());
    }

    /** 文字版删除高亮时同步隐藏原件标记，保留既有独立笔记。调用方已持有用户锁。 */
    public void deleteTextHighlightPosition(long user, long material, String id) {
        if (mapper.ready()) mapper.delete(user, material, id, UUID.randomUUID().toString(), now());
    }

    public void requireNotDeleted(long user, long material, String id) {
        if (!mapper.ready()) return;
        Row existing = mapper.find(user, material, id);
        if (existing != null && existing.deleted()) {
            throw new JournalException(409, "PDF_ANNOTATION_DELETED", "这条标注已经删除。");
        }
    }

    public static void validatePosition(Position position) {
        if (position == null || position.pageNumber() < 1 || position.pageNumber() > 10000
                || position.rects() == null || position.rects().isEmpty() || position.rects().size() > 128) {
            throw invalid("请选择单页内的有效文字范围。");
        }
        for (Rect rect : position.rects()) {
            if (rect == null || !Double.isFinite(rect.x()) || !Double.isFinite(rect.y())
                    || !Double.isFinite(rect.width()) || !Double.isFinite(rect.height())
                    || rect.x() < 0 || rect.y() < 0 || rect.width() <= 0 || rect.height() <= 0
                    || rect.x() + rect.width() > 1.000001 || rect.y() + rect.height() > 1.000001) {
                throw invalid("高亮范围超出 PDF 页面。");
            }
        }
    }

    private Annotation view(Row row) {
        var rects = Arrays.asList(json.readValue(row.rects(), Rect[].class));
        return new Annotation(row.id(), row.pageNumber(), row.quote(), rects, row.color(),
                revision(row), row.noteRevision() == null ? null : row.noteId(),
                row.note(), row.noteRevision());
    }

    private String revision(Row row) { return digest(row.revision() + ":" + Objects.toString(row.noteRevision(), "")); }

    private void requireRevision(Row row, String revision) {
        if (!revision(row).equals(revision)) {
            throw new JournalException(409, "PDF_ANNOTATION_CHANGED", "标注已在其他页面更新，请保留草稿并刷新。");
        }
    }

    private Row requireRow(long user, long material, String id) {
        Row row = mapper.find(user, material, id);
        if (row == null || row.deleted()) throw missing();
        return row;
    }

    private void requirePdf(long user, long material) {
        if (!"pdf".equals(materials.detail(user, material).fileType())) throw invalid("该资料不是 PDF。");
    }

    private void requireCapacity(long user, long material) {
        if (mapper.count(user, material) >= 2000) throw invalid("每份 PDF 最多保存 2000 条标注。");
    }

    private void lockUser(long user) { if (learning.lockUser(user) == null) throw missing(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private static String uuid(String id) {
        try { if (id != null && UUID.fromString(id).toString().equals(id)) return id; }
        catch (IllegalArgumentException ignored) {}
        throw invalid("标注编号无效。");
    }

    private static void identifier(String id) {
        if (id != null && id.matches("[0-9a-f]{64}")) return;
        uuid(id);
    }

    private static void color(String value) {
        if (!COLORS.contains(Objects.toString(value, ""))) throw invalid("请选择有效的高亮颜色。");
    }

    private static String text(String value, int max, boolean optional) {
        String content = value == null ? "" : value.strip();
        if ((!optional && content.isEmpty()) || content.codePointCount(0, content.length()) > max) {
            throw invalid("内容为空或超过 " + max + " 字。");
        }
        return content;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
