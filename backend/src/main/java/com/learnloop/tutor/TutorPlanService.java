package com.learnloop.tutor;

import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationService;
import com.learnloop.learning.LearningApi.CreateAttempt;
import com.learnloop.learning.LearningMapper;
import com.learnloop.learning.LearningService;
import com.learnloop.modelsettings.ModelCredentialException;
import com.learnloop.modelsettings.ModelCredentialService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.tutor.TutorPlanMapper.*;

@Service
public class TutorPlanService {
    private final TutorPlanMapper plans;
    private final LearningMapper questions;
    private final LearningService learning;
    private final GenerationService generation;
    private final GenerationClient model;
    private final ModelCredentialService credentials;
    private final LearningSummaryService summaries;
    private final JsonMapper json = JsonMapper.builder().build();

    public record Input(int minutes, Long materialId) {}
    public record Item(String versionId, String topicId, String topic, long materialId, String material,
                LocalDateTime dueAt, int recentErrors) {
        String kind() { return dueAt == null ? "PRACTICE" : "REVIEW"; }
    }
    public record View(String id, String status, int minutes, String reason, String planner, List<String> trace,
                List<Item> items, int position) {}
    public record Step(int position, String kind, String attemptId, Item item, String content,
                String optionA, String optionB, String optionC, String optionD, boolean finished) {}
    public record Advance(int position) {}

    TutorPlanService(TutorPlanMapper plans, LearningMapper questions, LearningService learning,
                     GenerationService generation, GenerationClient model, ModelCredentialService credentials, LearningSummaryService summaries) {
        this.summaries = summaries;
        this.plans = plans;
        this.questions = questions;
        this.learning = learning;
        this.generation = generation;
        this.model = model;
        this.credentials = credentials;
    }

    public View draft(long user, Input input) {
        if (input == null || input.minutes() < 4 || input.minutes() > 20 || (input.materialId() != null && input.materialId() <= 0)) {
            throw error(HttpStatus.BAD_REQUEST, "请选择 4 到 20 分钟。");
        }
        var existing = latest(user);
        if (existing != null) return existing;
        generation.restoreReviewCards(user);
        LocalDateTime now = now();
        var candidates = plans.candidates(user, input.materialId()).stream().map(row -> new Item(
                row.versionId(), row.topicId(), row.topic(), row.materialId(), row.material(),
                row.dueAt().isAfter(now) ? null : row.dueAt(), row.recentErrors())).toList();
        if (candidates.isEmpty()) throw error(HttpStatus.CONFLICT, "这个范围还没有可用题目。请先在工作台出题。");
        int limit = Math.min(5, input.minutes() / 2);
        List<Item> picked = candidates.stream().limit(limit).toList();
        String reason = "优先安排到期考点，再选近期答错较多的考点。";
        String planner = "RULES";
        List<String> trace = List.of();
        try {
            var offered = candidates.stream().map(item -> Map.of("id", item.versionId(), "kind", item.kind(),
                    "topic", clip(item.topic(), 200), "material", clip(item.material(), 255), "recentErrors", item.recentErrors())).toList();
            // 模型调用放在事务之外。只给去除答案的候选摘要，选题 ID 再由 Java 验证。
            var summary = summaries.load(user, input.materialId());
            var reply = model.tutorPlan(Map.of("minutes", input.minutes(), "candidates", offered,
                    "mastery", summary.groupMastery(), "learningSummary", summaries.context(summary, true)), credentials.requireKey(user));
            Map<String, Item> byId = new HashMap<>();
            candidates.forEach(item -> byId.put(item.versionId(), item));
            if (reply.ids().size() <= limit && byId.keySet().containsAll(reply.ids())) {
                picked = reply.ids().stream().map(byId::get).toList();
                reason = reply.reason();
                trace = reply.trace();
                planner = "AGENT";
            }
        } catch (GenerationException | ModelCredentialException unavailable) {
            // 规则草案带明确来源，模型失败时仍可使用已存在的题和复习卡。
        }
        var row = new Row(id(), "DRAFT", input.minutes(), reason, planner, json.writeValueAsString(trace),
                json.writeValueAsString(picked), 0, null, now);
        try {
            plans.insert(user, row);
            return view(row);
        } catch (DuplicateKeyException concurrentDraft) {
            // 两个页面同时安排时，以先落库的草案为准，用户只有一份待处理计划。
            var current = latest(user);
            if (current != null) return current;
            throw concurrentDraft;
        }
    }

    public View latest(long user) {
        var row = plans.latest(user);
        return row == null ? null : view(row);
    }

    @Transactional
    public View confirm(long user, String id) {
        var row = required(user, id);
        if ("ACTIVE".equals(row.status()) || "COMPLETED".equals(row.status())) return view(row);
        if (!"DRAFT".equals(row.status()) || row.createdAt().isBefore(now().minusHours(24))
                || items(row).stream().anyMatch(item -> !plans.available(user, item))) throw stale();
        plans.update(user, id, "ACTIVE", 0, null);
        return view(required(user, id));
    }

    @Transactional
    public void cancel(long user, String id) {
        var row = required(user, id);
        if (!"COMPLETED".equals(row.status())) plans.update(user, id, "CANCELLED", row.position(), null);
    }

    @Transactional
    public Step open(long user, String id) {
        // 与 LearningService 使用相同的锁顺序，防止双击开题造成重复绑定。
        questions.lockUser(user);
        var row = required(user, id);
        var items = items(row);
        var item = current(row, items);
        boolean done = completed(user, row, item);
        if (!done && !plans.available(user, item)) throw stale();
        String attempt = row.activeAttempt();
        if (!done && item.dueAt() == null) {
            if (attempt == null || "EXPIRED".equals(learning.getAttempt(user, attempt).status())) {
                attempt = learning.createAttempt(user, new CreateAttempt(item.versionId(), id())).attemptId();
                plans.update(user, id, "ACTIVE", row.position(), attempt);
            }
        }
        var question = questions.findQuestionVersion(user, item.versionId());
        if (question == null) throw stale();
        return new Step(row.position(), item.kind(), attempt, item, question.content(),
                question.optionA(), question.optionB(), question.optionC(), question.optionD(), done);
    }

    @Transactional
    public View advance(long user, String id, Advance input) {
        var row = required(user, id);
        if (input == null || input.position() < 0) throw error(HttpStatus.BAD_REQUEST, "练习进度无效。");
        if (input.position() < row.position() && Set.of("ACTIVE", "COMPLETED").contains(row.status())) return view(row);
        var items = items(row);
        var item = current(row, items);
        if (input.position() != row.position() || !completed(user, row, item)) {
            throw error(HttpStatus.CONFLICT, "请先完成本题。");
        }
        int next = row.position() + 1;
        plans.update(user, id, next == items.size() ? "COMPLETED" : "ACTIVE", next, null);
        return view(required(user, id));
    }

    private boolean completed(long user, Row row, Item item) {
        return item.dueAt() != null ? plans.reviewDone(user, item) : row.activeAttempt() != null
                && plans.practiceDone(user, row.activeAttempt(), item.versionId());
    }

    private static Item current(Row row, List<Item> items) {
        if (!"ACTIVE".equals(row.status()) || row.position() >= items.size()) throw stale();
        return items.get(row.position());
    }

    private Row required(long user, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw stale();
        var row = plans.lock(user, id);
        if (row == null) throw error(HttpStatus.NOT_FOUND, "没有找到这份练习计划。");
        return row;
    }

    private View view(Row row) {
        return new View(row.id(), row.status(), row.minutes(), row.reason(), row.planner(),
                json.readValue(row.trace(), new TypeReference<List<String>>() {}), items(row), row.position());
    }

    private List<Item> items(Row row) {
        return json.readValue(row.items(), new TypeReference<List<Item>>() {});
    }

    private static String clip(String value, int max) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(max, value.codePointCount(0, value.length()))));
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static ResponseStatusException stale() { return error(HttpStatus.CONFLICT, "练习或资料已更新，请重新安排。"); }
    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
