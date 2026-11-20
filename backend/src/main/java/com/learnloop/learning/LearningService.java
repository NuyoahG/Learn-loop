package com.learnloop.learning;

import com.learnloop.answer.AnswerMapper;
import com.learnloop.answer.AnswerRecord;
import com.learnloop.learning.LearningRows.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.learnloop.learning.LearningApi.*;

/**
 * 第一条正式学习闭环：人工确认来源与考点 → 冻结题目 → 幂等作答 → 有序事件。
 *
 * 这一片先复用现有手工题；后续模型生成的题可以复用同样的版本与证据协议。
 * 写操作先锁当前用户，串行化同一用户的小事务，解决创建、题族日限额和重复提交竞态。
 * 持锁时只访问数据库；真正的 Python 调用由后台 worker 在事务外执行。
 */
@Service
public class LearningService {
    private final LearningMapper mapper;
    private final AnswerMapper answers;
    private final LearningProperties properties;

    public LearningService(LearningMapper mapper, AnswerMapper answers, LearningProperties properties) {
        this.mapper = mapper; this.answers = answers; this.properties = properties;
    }

    @Transactional
    public ConceptView createConcept(long user, CreateConcept request) {
        writeUser(user);
        if (request == null || request.materialId() <= 0 || request.blockId() <= 0) throw invalid("资料与正文块编号无效。");
        String key = uuid(request.requestKey());
        String name = text(request.name(), 120), definition = text(request.definition(), 2000);
        if (!Set.of("COARSE", "MEDIUM", "FINE").contains(String.valueOf(request.granularity()))) throw invalid("考点细致度无效。");
        Concept previous = mapper.findConceptByRequest(user, key);
        if (previous != null) {
            if (previous.materialId() != request.materialId() || previous.blockId() != request.blockId()
                    || !previous.name().equals(name) || !previous.definition().equals(definition)
                    || !previous.granularity().equals(request.granularity())) throw conflict("同一请求键不能创建不同考点。");
            return conceptView(previous);
        }
        Source source = mapper.findMaterialSource(user, request.materialId(), request.blockId());
        if (source == null) throw missing("资料正文不存在或尚未解析。");
        if (mapper.findConceptByAnchor(user, source.materialId(), source.blockId(), source.parseAttempt(), request.granularity(), name) != null) {
            throw conflict("该来源位置已有同名同粒度考点，请从考点列表复用稳定编号。");
        }
        // 当前人工确认目录最多 200 项，防止列表查询上限让旧考点悄悄不可见。
        if (mapper.listConcepts(user, request.materialId(), 201).size() >= 200) throw conflict("本资料的考点数量已达本阶段上限。");
        Concept concept = new Concept(id(), user, key, source.materialId(), source.blockId(), source.sha256(),
                source.parseAttempt(), name, definition, request.granularity(), source.textContent(), source.sourceLocator(), now());
        mapper.insertConcept(concept);
        mapper.ensureMastery(user, concept.id(), BktClient.MODEL_VERSION, now());
        return conceptView(concept);
    }

    @Transactional
    public QuestionView bindQuestion(long user, long questionId, BindQuestion request) {
        return bind(user, questionId, request, false);
    }

    /** 仅由自适应出题服务调用，允许同一稳定考点换来源后继续沿用题族。 */
    @Transactional
    public QuestionView bindGroupedQuestion(long user, long questionId, BindQuestion request) {
        return bind(user, questionId, request, true);
    }

    private QuestionView bind(long user, long questionId, BindQuestion request, boolean grouped) {
        writeUser(user);
        if (request == null || questionId <= 0) throw invalid("题目绑定参数无效。");
        Concept concept = requiredConcept(user, uuid(request.conceptId()));
        validateSource(user, concept);
        ExistingQuestion current = mapper.findCurrentQuestion(user, questionId);
        if (current == null) throw missing("题目不存在。");
        String digest = questionDigest(current);
        QuestionVersion old = mapper.findQuestionVersionByQuestion(user, questionId);
        if (old != null) {
            if (!old.conceptId().equals(concept.id()) || !old.contentDigest().equals(digest)) {
                throw conflict("题目已绑定不可变版本，不能直接改写考点或答案。");
            }
            return questionView(old);
        }
        if (mapper.listQuestionVersions(user, concept.materialId(), 501).size() >= 500) throw conflict("本资料的题目数量已达本阶段上限。");
        String family = id();
        if (request.familyReferenceVersionId() != null) {
            QuestionVersion reference = requiredQuestion(user, uuid(request.familyReferenceVersionId()));
            if (!reference.conceptId().equals(concept.id())
                    && (!grouped || !mapper.sameAdaptiveTopic(user, concept.id(), reference.conceptId()))) {
                throw conflict("变式家族必须属于同一个考点。");
            }
            family = reference.familyId();
        }
        // 完全重复题干/选项不能通过换旧题 ID 获得新的家族占用。
        QuestionVersion duplicate = mapper.findQuestionByDigest(user, concept.id(), digest);
        if (duplicate != null) family = duplicate.familyId();
        QuestionVersion version = new QuestionVersion(id(), user, questionId, concept.id(), family,
                current.content(), current.optionA(), current.optionB(), current.optionC(), current.optionD(),
                current.correctAnswer(), digest, now());
        mapper.insertQuestionVersion(version);
        return questionView(version);
    }

    @Transactional
    public AttemptView createAttempt(long user, CreateAttempt request) {
        writeUser(user);
        if (request == null) throw invalid("答题尝试参数无效。");
        String versionId = uuid(request.questionVersionId()), key = uuid(request.requestKey());
        String digest = hash(versionId);
        Attempt previous = mapper.findAttemptByRequest(user, key);
        if (previous != null) {
            if (!previous.requestDigest().equals(digest)) throw conflict("同一请求键不能用于不同题目。");
            return attemptView(previous, requiredQuestion(user, previous.questionVersionId()));
        }
        QuestionVersion version = requiredQuestion(user, versionId);
        validateQuestion(user, version);
        LocalDateTime now = now();
        mapper.expireOpenForQuestion(user, versionId, now);
        // 从题目中心深链回到同一题时，浏览器可能已丢失本地 attempt 票据。
        // 同一用户同一题只允许一条 OPEN 尝试，因此直接恢复它，而不是拒绝作答。
        Attempt open = mapper.findOpenAttempt(user, versionId);
        if (open != null) return attemptView(open, version);
        LocalDateTime firstReveal = mapper.firstRevealAt(user, versionId);
        Attempt attempt = new Attempt(id(), user, versionId, key, digest, "OPEN", now.plusHours(2),
                firstReveal != null, firstReveal, null, null, null, null, null, null, null, now, null);
        mapper.insertAttempt(attempt);
        return attemptView(attempt, version);
    }

    @Transactional
    public AttemptView getAttempt(long user, String attemptId) {
        writeUser(user);
        Attempt attempt = requiredAttempt(user, uuid(attemptId));
        // 查询时同样检查过期，但不把“关闭浏览器”解释为答错。
        if ("OPEN".equals(attempt.status()) && !attempt.expiresAt().isAfter(now())) {
            mapper.expireAttempt(user, attempt.id());
            attempt = requiredAttempt(user, attempt.id());
        }
        return attemptView(attempt, requiredQuestion(user, attempt.questionVersionId()));
    }

    @Transactional
    public Map<String, String> reveal(long user, String attemptId) {
        writeUser(user);
        Attempt attempt = requiredAttempt(user, uuid(attemptId));
        requireOpen(attempt);
        QuestionVersion question = requiredQuestion(user, attempt.questionVersionId());
        validateQuestion(user, question);
        // 必须先落辅助标志，再将正确答案返回；浏览器不能自己把该标志重置为 false。
        if (mapper.markAssisted(user, attempt.id(), now()) != 1) throw conflict("答题尝试已失效。");
        return Map.of("correctOption", question.correctAnswer(), "evidenceEffect", "ASSISTED");
    }

    @Transactional
    public SubmissionView submit(long user, String attemptId, Submit request) {
        writeUser(user);
        if (request == null || request.selectedOption() == null || !request.selectedOption().matches("[A-D]")) throw invalid("选项必须为 A/B/C/D。");
        String submitKey = uuid(request.submitKey());
        if (request.confidence() != null && !Set.of("UNSURE", "SOMEWHAT_SURE", "VERY_SURE").contains(request.confidence())) throw invalid("作答信心无效。");
        Attempt attempt = requiredAttempt(user, uuid(attemptId));
        QuestionVersion question = requiredQuestion(user, attempt.questionVersionId());
        Attempt keyOwner = mapper.findAttemptBySubmitKey(user, submitKey);
        if (keyOwner != null && !keyOwner.id().equals(attempt.id())) throw conflict("同一提交键不能用于不同答题尝试。");
        if ("SUBMITTED".equals(attempt.status())) {
            if (!submitKey.equals(attempt.submitKey()) || !request.selectedOption().equals(attempt.selectedOption())
                    || !Objects.equals(request.confidence(), attempt.confidence())) throw conflict("此尝试已经提交，重复请求必须保持原载荷。");
            return submissionView(attempt, question);
        }
        requireOpen(attempt);
        validateQuestion(user, question);
        LocalDateTime submittedAt = now();
        mapper.ensureMastery(user, question.conceptId(), BktClient.MODEL_VERSION, submittedAt);
        Mastery state = mapper.lockMastery(user, question.conceptId(), BktClient.MODEL_VERSION);

        // 独立证据判断发生在插入本次 answer_records 之前，旧接口做过的题也不能重新计入。
        String exclusion = attempt.assisted() ? "ASSISTED" :
                mapper.hasPreviousAnswer(user, question.questionId()) ? "PREVIOUSLY_ANSWERED" :
                mapper.findEvidenceClaimByQuestion(user, question.id()) != null ? "REPEATED_QUESTION" :
                mapper.findEvidenceClaimByFamilyDay(user, question.familyId(), submittedAt.toLocalDate()) != null ? "FAMILY_DAILY_CAP" : null;
        boolean correct = question.correctAnswer().equals(request.selectedOption());
        AnswerRecord record = new AnswerRecord(user, question.questionId(), request.selectedOption(), question.correctAnswer(), correct);
        answers.insert(record);
        String eventId = null;
        if (exclusion == null) {
            mapper.insertEvidenceClaim(new EvidenceClaim(id(), user, question.id(), question.familyId(), submittedAt.toLocalDate(), attempt.id(), submittedAt));
            if (mapper.reserveSequence(user, question.conceptId(), BktClient.MODEL_VERSION, state.nextSequence(), submittedAt) != 1) throw conflict("学习状态版本发生变化，请重试原请求。");
            eventId = id();
            mapper.insertEvent(new Event(eventId, user, question.conceptId(), question.id(), attempt.id(),
                    BktClient.MODEL_VERSION, state.nextSequence() + 1, correct, "PENDING", null, null,
                    null, 0, submittedAt, null, null, submittedAt, null));
        }
        // 答案、独立证据占用、事件和尝试完成处在同一个 MySQL 事务，任一失败全部回滚。
        if (mapper.completeAttempt(user, attempt.id(), submitKey, request.confidence(), request.selectedOption(),
                correct, record.getId(), eventId, exclusion, submittedAt) != 1) throw conflict("答题尝试已失效。");
        return submissionView(requiredAttempt(user, attempt.id()), question);
    }

    public List<ConceptView> concepts(long user, long materialId) {
        requireMaterial(user, materialId);
        return mapper.listConcepts(user, materialId, 200).stream().filter(c -> validSource(user, c))
                .map(LearningService::conceptView).toList();
    }

    public List<QuestionView> questions(long user, long materialId) {
        requireMaterial(user, materialId);
        return mapper.listQuestionVersions(user, materialId, 500).stream().filter(q -> validQuestion(user, q))
                .map(LearningService::questionView).toList();
    }

    /**
     * 题目中心按资料、作答状态和批次时间分页。这里只读学习题版本，
     * 不返回正确答案；旧题没有生成批次时 jobId 为空，仍会显示。
     * 分页在 SQL 层完成，避免把全部题目读入内存后再过滤。
     */
    public LibraryPage library(long user, Long materialId, String status, int page, int size) {
        requireEnabled();
        if (user <= 0) throw new LearningException(401, "LOGIN_REQUIRED", "请先登录。");
        if (page < 1 || size < 1 || size > 50) throw invalid("分页参数无效。");
        if (materialId != null) requireMaterial(user, materialId);
        String filter = status == null ? "ALL" : status;
        Boolean answered = switch (filter) {
            case "ALL" -> null;
            case "ANSWERED" -> true;
            case "UNANSWERED" -> false;
            default -> throw invalid("题目状态筛选无效。");
        };
        long total = mapper.countLibrary(user, materialId, answered);
        long offset = (long) (page - 1) * size;
        List<LearningApi.LibraryQuestion> records = mapper.library(user, materialId, answered, size, offset).stream()
                .map(r -> new LearningApi.LibraryQuestion(r.id(), r.questionId(), r.materialId(), r.materialName(),
                        r.conceptId(), r.conceptName(), r.content(), r.optionA(), r.optionB(), r.optionC(),
                        r.optionD(), r.jobId(),
                        r.jobCreatedAt() == null ? null : r.jobCreatedAt().toInstant(ZoneOffset.UTC),
                        r.difficulty(), r.sourceLocator(), r.answered(), r.latestCorrect(),
                        r.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new LibraryPage(page, size, total, totalPages, records);
    }

    public List<MasteryView> states(long user, long materialId) {
        requireMaterial(user, materialId);
        return mapper.listConcepts(user, materialId, 200).stream().map(c -> {
            Mastery m = mapper.findMastery(user, c.id(), BktClient.MODEL_VERSION);
            long count = m == null ? 0 : m.evidenceCount();
            boolean currentSource = validSource(user, c);
            return new MasteryView(c.id(), c.name(), BktClient.MODEL_VERSION, count == 0 || !currentSource ? null : m.masteryProbability(),
                    count, m == null ? 0 : m.stateVersion(), m != null && m.nextSequence() > m.appliedSequence(),
                    !currentSource ? "SOURCE_INVALID" : count == 0 ? "NO_EVIDENCE" : count < 3 ? "LOW_EVIDENCE" : "MODEL_ESTIMATE_UNCALIBRATED",
                    m == null ? null : m.updatedAt().toInstant(ZoneOffset.UTC));
        }).toList();
    }

    /**
     * M1 交付真实 BKT 补弱与覆盖选择。FSRS 尚未接入，因此此接口不伪造到期槽。
     * 后续正式复习计划将在这些候选上增加到期配额；本接口不会调用模型生成新题。
     */
    @Transactional
    public Selection select(long user, long materialId, int size) {
        requireMaterial(user, materialId);
        if (!Set.of(3, 5, 10).contains(size)) throw invalid("本阶段题量为 3/5/10。");
        Map<String, Mastery> state = new HashMap<>();
        Set<String> currentConcepts = new HashSet<>();
        for (Concept concept : mapper.listConcepts(user, materialId, 200)) {
            if (validSource(user, concept)) currentConcepts.add(concept.id());
        }
        for (Mastery m : mapper.listMasteryByMaterial(user, materialId, BktClient.MODEL_VERSION, 200)) {
            // 重解析后的旧概念即使有失效队首，也不能永久挡住新版本的练习入口。
            if (!currentConcepts.contains(m.conceptId())) continue;
            if (m.nextSequence() > m.appliedSequence()) throw new LearningException(409, "PROFILE_UPDATING", "学习状态正在更新，请稍后重试；已保存答案不会丢失。");
            state.put(m.conceptId(), m);
        }
        List<QuestionVersion> candidates = mapper.listQuestionVersions(user, materialId, 500).stream()
                .filter(q -> validQuestion(user, q)).filter(q -> !mapper.hasPreviousAnswer(user, q.questionId())).toList();
        Comparator<QuestionVersion> weakOrder = Comparator.comparingDouble((QuestionVersion q) -> state.get(q.conceptId()).masteryProbability())
                .thenComparingLong(q -> state.get(q.conceptId()).evidenceCount()).thenComparing(QuestionVersion::conceptId).thenComparing(QuestionVersion::id);
        List<QuestionVersion> weak = candidates.stream().filter(q -> state.containsKey(q.conceptId()) && state.get(q.conceptId()).evidenceCount() >= 3).sorted(weakOrder).toList();
        List<QuestionVersion> coverage = candidates.stream().sorted(Comparator.comparingLong((QuestionVersion q) -> state.containsKey(q.conceptId()) ? state.get(q.conceptId()).evidenceCount() : 0)
                .thenComparing(QuestionVersion::createdAt).thenComparing(QuestionVersion::conceptId).thenComparing(QuestionVersion::id)).toList();
        List<Recommendation> result = new ArrayList<>();
        Set<String> usedConcepts = new HashSet<>();
        int weakSlots = size == 3 ? 2 : size == 5 ? 3 : 6;
        append(weak, weakSlots, "BKT_WEAKNESS", state, usedConcepts, result);
        append(coverage, size, "COVERAGE", state, usedConcepts, result);
        return new Selection(materialId, "selection-v1-learning", BktClient.MODEL_VERSION, size, List.copyOf(result),
                "本阶段为已有题的补弱/覆盖候选；每考点一题，不足不凑；FSRS 到期计划和模型生成尚未接入。");
    }

    public EventView event(long user, String eventId) {
        requireEnabled();
        Event event = mapper.findEvent(user, uuid(eventId));
        if (event == null) throw missing("学习事件不存在。");
        return eventView(event);
    }

    @Transactional
    public EventView retry(long user, String eventId) {
        writeUser(user);
        Event event = mapper.lockEvent(user, uuid(eventId));
        if (event == null) throw missing("学习事件不存在。");
        if (!"FAILED".equals(event.status())) throw conflict("只有失败事件可以手动重试。");
        mapper.retryFailedEvent(user, event.id(), now());
        return event(user, event.id());
    }

    private static void append(List<QuestionVersion> candidates, int totalLimit, String reason, Map<String, Mastery> states,
                               Set<String> usedConcepts, List<Recommendation> result) {
        for (QuestionVersion q : candidates) {
            if (result.size() >= totalLimit) break;
            if (!usedConcepts.add(q.conceptId())) continue;
            Mastery state = states.get(q.conceptId());
            result.add(new Recommendation(questionView(q), reason, state == null || state.evidenceCount() == 0 ? null : state.masteryProbability(),
                    state == null ? 0 : state.evidenceCount(), state == null ? 0 : state.stateVersion()));
        }
    }

    /** 发布/作答前检查原资料版本与题目摘要，旧快照可追溯但不能生成新证据。 */
    void validateQuestion(long user, QuestionVersion question) {
        validateSource(user, requiredConcept(user, question.conceptId()));
        ExistingQuestion current = mapper.findCurrentQuestion(user, question.questionId());
        if (current == null || !question.contentDigest().equals(questionDigest(current))) throw conflict("题目内容发生变化，当前学习版本已失效。");
    }
    private boolean validQuestion(long user, QuestionVersion q) {
        try { validateQuestion(user, q); return true; } catch (LearningException ex) { return false; }
    }
    private void validateSource(long user, Concept concept) {
        Source source = mapper.findMaterialSource(user, concept.materialId(), concept.blockId());
        if (source == null || !source.sha256().equals(concept.materialSha256()) || source.parseAttempt() != concept.parseAttempt()) throw conflict("资料内容版本已变化，请重新确认考点来源。");
    }
    private boolean validSource(long user, Concept concept) {
        try { validateSource(user, concept); return true; } catch (LearningException ex) { return false; }
    }
    private void writeUser(long user) {
        requireEnabled();
        if (user <= 0 || mapper.lockUser(user) == null) throw new LearningException(401, "LOGIN_REQUIRED", "请先登录。");
    }
    private void requireEnabled() {
        if (!properties.isEnabled()) throw new LearningException(503, "LEARNING_NOT_ENABLED", "学习闭环尚未启用，请先完成数据库迁移与服务配置。");
    }
    private void requireMaterial(long user, long material) {
        requireEnabled();
        if (user <= 0 || material <= 0 || !mapper.ownsParsedMaterial(user, material)) throw missing("已解析资料不存在。");
    }
    private Concept requiredConcept(long user, String id) {
        Concept value = mapper.findConcept(user, id);
        if (value == null) throw missing("考点不存在。"); return value;
    }
    private QuestionVersion requiredQuestion(long user, String id) {
        QuestionVersion value = mapper.findQuestionVersion(user, id);
        if (value == null) throw missing("学习题目不存在。"); return value;
    }
    private Attempt requiredAttempt(long user, String id) {
        Attempt value = mapper.lockAttempt(user, id);
        if (value == null) throw missing("答题尝试不存在。"); return value;
    }
    private static void requireOpen(Attempt attempt) {
        if (!"OPEN".equals(attempt.status()) || !attempt.expiresAt().isAfter(now())) throw conflict("答题尝试已结束或过期。");
    }
    private SubmissionView submissionView(Attempt a, QuestionVersion q) {
        Event event = a.eventId() == null ? null : mapper.findEvent(a.userId(), a.eventId());
        return new SubmissionView(a.id(), a.answerRecordId(), Boolean.TRUE.equals(a.correct()), q.correctAnswer(),
                a.eventId() != null, a.ineligibilityReason(), a.eventId(), event == null ? "EXCLUDED" : event.status());
    }
    private static AttemptView attemptView(Attempt a, QuestionVersion q) {
        return new AttemptView(a.id(), a.status(), a.expiresAt().toInstant(ZoneOffset.UTC), a.assisted(), questionView(q));
    }
    private static ConceptView conceptView(Concept c) {
        return new ConceptView(c.id(), c.materialId(), c.blockId(), c.name(), c.definition(), c.granularity(), c.parseAttempt());
    }
    static QuestionView questionView(QuestionVersion q) {
        return new QuestionView(q.id(), q.questionId(), q.conceptId(), q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD());
    }
    private static EventView eventView(Event e) { return new EventView(e.id(), e.status(), e.sequenceNo(), e.errorCode(), e.priorProbability(), e.appliedProbability()); }
    static String questionDigest(ExistingQuestion q) {
        if (q.correctAnswer() == null || !q.correctAnswer().matches("[A-D]")) throw invalid("题目正确答案配置无效。");
        // 长度前缀消除字段边界歧义，例如 [ab,c] 与 [a,bc] 不应产生相同输入。
        StringBuilder canonical = new StringBuilder();
        for (String value : List.of(q.content(), q.optionA(), q.optionB(), q.optionC(), q.optionD(), q.correctAnswer())) {
            String normalized = text(value, 20000).replaceAll("\\s+", " ");
            canonical.append(normalized.length()).append(':').append(normalized);
        }
        return hash(canonical.toString());
    }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) throw invalid("文本为空或超过长度限制。");
        return value.strip();
    }
    static String uuid(String value) {
        try { String normalized = UUID.fromString(value).toString(); if (!normalized.equals(value)) throw invalid("请求编号必须是标准小写 UUID。"); return normalized; }
        catch (IllegalArgumentException | NullPointerException ex) { throw invalid("请求编号必须是标准小写 UUID。"); }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    static String id() { return UUID.randomUUID().toString(); }
    static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    static LearningException invalid(String message) { return new LearningException(400, "INVALID_LEARNING_REQUEST", message); }
    static LearningException conflict(String message) { return new LearningException(409, "LEARNING_CONFLICT", message); }
    static LearningException missing(String message) { return new LearningException(404, "LEARNING_NOT_FOUND", message); }
}
