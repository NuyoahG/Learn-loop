package com.learnloop.journal;

import com.learnloop.material.MaterialParsingService.BlockPage;
import java.time.LocalDateTime;
import java.util.List;

public final class JournalApi {
    private JournalApi() {}

    public record Note(String questionVersionId, String content, String revision, LocalDateTime updatedAt,
                       String question, long materialId, String materialName, String readingNoteId, String quote,
                       String annotationId) {
        public Note(String questionVersionId, String content, String revision, LocalDateTime updatedAt,
                    String question, long materialId, String materialName, String readingNoteId, String quote) {
            this(questionVersionId, content, revision, updatedAt, question, materialId, materialName, readingNoteId, quote, null);
        }
    }
    public record NoteEdit(String content, String revision) {}
    public record Notes(List<Note> records, int page, boolean hasMore) {}

    // 选区用 UTF-16 偏移与 DOM Range 对齐，原文用于防止偏移指向另一处文本。
    public record Anchor(long materialId, long parseAttempt, long blockId, int start, int end, String quote) {}
    public record Ask(Anchor anchor, String question, String detail) {}
    public record Draft(Anchor anchor, String question, String detail, String answer, long expiresAt, String receipt) {}
    public record SaveHighlight(Draft draft, String color, boolean saveAsNote, PdfAnnotationApi.Position pdfPosition) {
        public SaveHighlight(Draft draft, String color) { this(draft, color, false, null); }
        public SaveHighlight(Draft draft, String color, boolean saveAsNote) { this(draft, color, saveAsNote, null); }
    }
    public record Highlight(String id, long blockId, int start, int end, String quote, String question,
                            String answer, String detail, String color, String intent, String topicId, String topicName) {}
    public record StudyEdit(String intent, String topicId) {}
    public record StudyTopic(String id, String chapter, String groupName, String name, boolean suggested) {}
    public record StudyContext(int page, List<StudyTopic> topics) {}
    public record Reader(long materialId, String title, long parseAttempt, BlockPage blocks,
                         List<Highlight> highlights) {}
}
