package com.learnloop.tutor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.learnloop.tutor.TutorMapper.*;

@Service
public class TutorService {
    private static final Set<String> REASONS = Set.of("NOT_UNDERSTOOD","CONFUSED","FORGOT",
            "MISREAD","PROCEDURE","MISCLICK","QUESTION_ISSUE","UNSURE","OTHER");
    private final TutorMapper mapper;
    record Suggestion(String key, String message, List<Evidence> evidence) {}
    record Evidence(String label, Long materialId, String kind, String id) {}
    record Response(String key, String status) {}
    TutorService(TutorMapper mapper) { this.mapper = mapper; }

    public Feedback feedback(long user, String kind, String id) {
        requireWrong(user, kind, id);
        return mapper.feedback(user, kind, id);
    }

    public Feedback save(long user, String kind, String id, Feedback input) {
        if (input == null || input.reason() == null || !REASONS.contains(input.reason())) {
            throw invalid("请选择一个错因。");
        }
        String detail = input.detail() == null ? "" : input.detail().strip();
        if ("OTHER".equals(input.reason()) ? !valid(detail, 300) : !detail.isEmpty()) {
            throw invalid("请选择错因，或在「其他」中填写 1 到 300 字。");
        }
        requireWrong(user, kind, id);
        var feedback = new Feedback(input.reason(), detail);
        mapper.saveFeedback(user, kind, id, feedback);
        return feedback;
    }

    private void requireWrong(long user, String kind, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid("答题编号无效。");
        String ownedWrong = switch (kind) {
            case "PRACTICE" -> mapper.wrongPractice(user, id);
            case "REVIEW" -> mapper.wrongReview(user, id);
            default -> throw invalid("答题类型无效。");
        };
        if (ownedWrong == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可反馈的答错记录。");
    }

    public Suggestion suggestion(long user) {
        Set<String> suppressed = new HashSet<>(mapper.suppressed(user));
        return currentSuggestions(user).stream()
                .filter(candidate -> !suppressed.contains(candidate.key())).findFirst().orElse(null);
    }

    private List<Suggestion> currentSuggestions(long user) {
        var errors = mapper.recentErrors(user);
        var result = new ArrayList<>(readingSuggestions(errors, mapper.marks(user, null, "")));
        result.addAll(candidates(errors));
        return result;
    }

    static List<Suggestion> readingSuggestions(List<ErrorEvidence> errors, List<Mark> marks) {
        List<Suggestion> result = new ArrayList<>();
        for (var mark : marks) {
            if (mark.topicId() == null || !("UNCLEAR".equals(mark.intent()) || "CONFUSED".equals(mark.intent()))) continue;
            // 高亮需要明确自报困惑，再和同一考点的有效错答共同作为提示依据。
            errors.stream().filter(error -> error.materialId() == mark.materialId() && mark.topicId().equals(error.topicId())
                    && !"QUESTION_ISSUE".equals(error.reason()) && !"MISCLICK".equals(error.reason())).findFirst()
                    .ifPresent(error -> result.add(new Suggestion("mark:" + mark.id() + ":" + mark.topicId(),
                            "你标记过「" + mark.topicName() + "」有疑问，近期也在这里答错过。要一起看看吗？",
                            List.of(markEvidence(mark), new Evidence(error.materialName() + " · " + error.topicName(),
                                    error.materialId(), "ATTEMPT", error.attemptId())))));
        }
        return result;
    }

    private static Evidence markEvidence(Mark mark) {
        String topic = mark.topicName() == null ? "" : mark.topicName() + " · ";
        return new Evidence(mark.materialName() + " · " + topic + mark.quote(), mark.materialId(), "MARK", mark.id());
    }

    static List<Suggestion> candidates(List<ErrorEvidence> errors) {
        // 只对近期不同题族的重复信号发出建议，单次错选或高亮不足以归因。
        Map<String, Map<String, ErrorEvidence>> groups = new LinkedHashMap<>();
        for (var error : errors) {
            if ("MISCLICK".equals(error.reason()) || "QUESTION_ISSUE".equals(error.reason())) continue;
            if (error.groupName() != null) {
                // 同一小考点的变式共享题族；按组统计才能识别跨考点的重复困难。
                String identity = error.materialId() + "\n" + error.chapter() + "\n" + error.groupName();
                String key = "group:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
                groups.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(error.familyId(), error);
            }
            if ("MISREAD".equals(error.reason())) {
                groups.computeIfAbsent("reason:MISREAD", ignored -> new LinkedHashMap<>())
                        .putIfAbsent(error.familyId(), error);
            }
        }
        List<Suggestion> result = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var rows = entry.getValue().values().stream().limit(3).toList();
            if (rows.size() < 2) continue;
            String message = entry.getKey().startsWith("reason:")
                    ? "你在不同题目中提到过看漏条件。要一起看看怎么抓住关键条件吗？"
                    : "「" + rows.getFirst().groupName() + "」最近在不同题目中出现了错答。要一起看看吗？";
            result.add(new Suggestion(entry.getKey(), message, rows.stream().map(row ->
                    new Evidence(row.materialName() + " · " + (row.topicName() == null ? "错答" : row.topicName()),
                            row.materialId(), "ATTEMPT", row.attemptId())).toList()));
        }
        return result;
    }

    public void respond(long user, Response response) {
        if (response == null || response.key() == null || response.status() == null
                || !Set.of("LATER", "CORRECTED", "OPENED").contains(response.status())
                || currentSuggestions(user).stream().noneMatch(it -> it.key().equals(response.key()))) {
            throw invalid("这条建议已更新，请刷新后再试。");
        }
        mapper.respond(user, response.key(), response.status());
    }

    private static boolean valid(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit
                && value.codePoints().noneMatch(cp -> cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF));
    }
    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
