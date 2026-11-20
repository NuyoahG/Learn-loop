package com.learnloop.generation;

import com.learnloop.learning.LearningApi;
import com.learnloop.learning.LearningException;
import com.learnloop.learning.LearningMapper;
import com.learnloop.learning.LearningProperties;
import com.learnloop.learning.LearningRows;
import com.learnloop.learning.LearningService;
import com.learnloop.modelsettings.ModelCredentialException;
import com.learnloop.modelsettings.ModelCredentialService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.generation.GenerationApi.*;
import static com.learnloop.generation.GenerationRows.*;

/**
 * 收费模型调用与数据库事务分离：请求只冻结资料并入队，worker 在事务外调用 Python。
 * 发布时在同一事务创建旧题、稳定考点、不可变学习题与任务明细；任一校验失败全部回滚。
 * 这里调用注入的 LearningService 代理，复用其数量限制、题族、来源版本与 BKT 初始状态规则。
 */
@Service
public class GenerationService {
    private final GenerationMapper mapper;
    private final LearningMapper learningMapper;
    private final LearningService learning;
    private final GenerationProperties properties;
    private final LearningProperties learningProperties;
    private final ModelCredentialService credentials;
    private final AdaptiveCatalog adaptive;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 认领结果仅在进程内部使用；token 不是用户可提交的业务参数。 */
    public record Claimed(Job job, GenerationProtocol.Request request, AdaptivePolicy.Plan plan) {
        public Claimed(Job job, GenerationProtocol.Request request) { this(job, request, null); }
    }

    public GenerationService(GenerationMapper mapper, LearningMapper learningMapper, LearningService learning,
                             GenerationProperties properties, LearningProperties learningProperties,
                             ModelCredentialService credentials, AdaptiveCatalog adaptive) {
        this.mapper = mapper; this.learningMapper = learningMapper; this.learning = learning;
        this.properties = properties; this.learningProperties = learningProperties;// 初始化属性
        this.credentials = credentials;
        this.adaptive = adaptive;
    }

    @Transactional// 开启事务
    public JobView create(long user, CreateJob request) {//
        // 1. 锁用户防止并发创建任务
        lockUser(user);
        // 2. 验证请求参数
        validateCreate(request);
        // 3. 生成请求键与摘要
        String key = uuid(request.requestKey());
        // 来源范围的顺序具有统一含义：调用者必须提交严格递增、无重复的正文块 ID。
        // 摘要覆盖业务参数，不覆盖会随重解析变化的正文；同一请求键始终返回首次冻结的任务。
        List<Object> digestFields = new ArrayList<>(List.of(request.materialId(), request.blockIds(),
                request.count(), request.difficulty(), request.granularity()));
        if (request.mode() != null) digestFields.add(request.mode());
        if (request.refreshCatalog()) digestFields.add("REFRESH_CATALOG_V2");
        String digest = hash(json.writeValueAsString(digestFields));
        Job old = mapper.byRequest(user, key);
        if (old != null) {
            if (!old.requestDigest().equals(digest)) throw conflict("同一请求键不能用于不同出题参数。");
            return view(old);
        }
        // 启用个人密钥模式后，未保存 Key 的账号在建任务时就被拒绝，避免先排队再失败。
        requireUserKey(user);
        if (mapper.hasActive(user)) throw conflict("已有出题任务正在进行，请等待结束。");
        Snapshot snapshot = freeze(user, request.materialId(), request.blockIds());
        if (request.mode() != null) snapshot = new Snapshot(snapshot.materialSha256(), snapshot.parseAttempt(),
                snapshot.sources(), request.mode(), null,request.refreshCatalog());
        LocalDateTime now = now();
        Job job = new Job(uuid(), user, request.materialId(), key, digest, "PENDING", request.count(),
                request.difficulty(), request.granularity(), json.writeValueAsString(snapshot),
                0, null, null, null, null, null, now, now);
        mapper.insert(job);
        return view(job);
    }

    /**
     * 同考点再出一题：由用户对一道已有题明确触发，只冻结该考点的来源块。
     * 生成结果必须复用 targetConceptId，并复用该考点已有题目的 family，
     * 因此同一天的变式不会再次计入独立证据。requested_count 仍写 3，
     * 是为兼容 stage3_3 的数据库检查约束；实际请求数与任务展示以快照为准。
     */
    @Transactional
    public JobView createVariant(long user, CreateVariant request) {
        lockUser(user);
        if (request == null || request.questionVersionId() == null || request.requestKey() == null) {
            throw invalid("同考点变式参数无效。");
        }
        String versionId = uuid(request.questionVersionId());
        String key = uuid(request.requestKey());
        LearningRows.QuestionVersion version = learningMapper.findQuestionVersion(user, versionId);
        if (version == null) throw new GenerationException(404, "GENERATION_SOURCE_NOT_FOUND", "题目不存在或不属于当前账号。");
        LearningRows.Concept concept = learningMapper.findConcept(user, version.conceptId());
        if (concept == null) throw new GenerationException(404, "GENERATION_SOURCE_NOT_FOUND", "题目所属考点不存在。");
        String digest = hash(json.writeValueAsString(List.of("VARIANT", versionId)));
        Job old = mapper.byRequest(user, key);
        if (old != null) {
            if (!old.requestDigest().equals(digest)) throw conflict("同一请求键不能用于不同出题参数。");
            return view(old);
        }
        requireUserKey(user);
        if (mapper.hasActive(user)) throw conflict("已有出题任务正在进行，请等待结束。");
        LearningRows.Source source = learningMapper.findMaterialSource(user, concept.materialId(), concept.blockId());
        if (source == null || !source.sha256().equals(concept.materialSha256())
                || source.parseAttempt() != concept.parseAttempt()) {
            throw new GenerationException(409, "GENERATION_SOURCE_CHANGED", "资料版本已变化，请重新选择正文并创建出题任务。");
        }
        Published previous = mapper.itemForQuestion(user, versionId);
        String difficulty = previous == null ? "MEDIUM" : previous.difficulty();
        Snapshot snapshot = new Snapshot(source.sha256(), source.parseAttempt(),
                List.of(new FrozenSource(concept.blockId(), source.textContent(), source.sourceLocator(),
                        adaptive.noise(user,concept.materialId(),source.parseAttempt(),concept.blockId()))),
                "VARIANT", concept.id());
        LocalDateTime now = now();
        Job job = new Job(uuid(), user, concept.materialId(), key, digest, "PENDING", 3,
                difficulty, concept.granularity(), json.writeValueAsString(snapshot),
                0, null, null, null, null, null, now, now);
        mapper.insert(job);
        return view(job);
    }

    /**
     * 查询单个出题任务的完整状态与已发布题目列表（只读，供前端轮询进度）。
     * 入参 user 为当前登录用户 ID，jobId 为标准小写 UUID 字符串；
     * 状态含义：PENDING 待 worker 认领、PROCESSING 已发出模型请求、SUCCEEDED 已发布、FAILED 需手动重试。
     * 不开事务、不加用户锁：单条 SELECT 自身即快照，避免轮询接口与写入接口争锁拖慢出题流程。
     * 三重失败分支：功能未启用 -> 503 GENERATION_NOT_ENABLED；jobId 格式非法 -> 400 INVALID_GENERATION_REQUEST；
     * 任务不存在或不属于该用户 -> 404 GENERATION_NOT_FOUND（mapper.find 带 user_id 条件，越权与不存在同样响应，
     * 不泄露他人任务是否存在）。任何分支都不修改数据，也不触发重试或调用模型。
     */
    public JobView get(long user, String jobId) {
        enabled();
        Job job = mapper.find(user, uuid(jobId));
        if (job == null) throw missing();
        return view(job);
    }

    @Transactional
    public JobView retry(long user, String jobId) {
        lockUser(user);
        Job job = mapper.lock(user, uuid(jobId));
        if (job == null) throw missing();
        if (!"FAILED".equals(job.status())) throw conflict("只有失败任务可以手动重试。");
        // 重试会再次产生费用，必须先确认当前账号仍保存着可读取的个人密钥。
        requireUserKey(user);
        if (mapper.hasActive(user)) throw conflict("已有出题任务正在进行，请等待结束。");
        // 重试仍使用原始快照；资料已重解析时必须重新选择范围并创建新任务。
        validateCurrentSource(job, snapshot(job));
        if (mapper.retry(user, job.id(), now()) != 1) throw conflict("出题任务状态已变化。");
        return view(mapper.find(user, job.id()));
    }

    /**
     * 重新启动只恢复尚未开始的 PENDING；已发出模型请求但失联的任务转 FAILED。
     * 不自动重放收费请求，也不把超时当成模型一定没有执行。
     */
    @Transactional
    public void expireInterrupted() {
        enabled();
        mapper.expireLeases(now());
    }

    @Transactional
    public Claimed claimNext() {
        enabled();
        for (Job candidate : mapper.pending()) {
            // 与用户创建/重试/发布保持同一锁顺序，跨进程仍由 MySQL 条件更新完成认领。
            if (learningMapper.lockUser(candidate.userId()) == null) continue;
            Job job = mapper.lock(candidate.userId(), candidate.id());
            if (job == null || !"PENDING".equals(job.status())) continue;
            LocalDateTime now = now();
            String token = uuid();
            // 新资料先归组再出题，为两次外部调用预留同一租约。
            if (mapper.claim(job.userId(), job.id(), token, now.plusSeconds(isAdaptive(snapshot(job).mode()) ? 210 : 120), now) != 1) continue;
            Job claimed = mapper.find(job.userId(), job.id());
            Snapshot snapshot = snapshot(claimed);
            try {
                // 认领后、付费调用之前重新检查；上传资料已被重解析时直接落失败而不调用模型。
                validateCurrentSource(claimed, snapshot);
            } catch (GenerationException ex) {
                mapper.fail(claimed.userId(), claimed.id(), token, ex.code(), now());
                continue;
            }
            boolean variant = "VARIANT".equals(snapshot.mode());
            Set<Long> blocks = new HashSet<>();
            snapshot.sources().forEach(s -> blocks.add(s.blockId()));
            List<GenerationProtocol.KnownConcept> known = new ArrayList<>();
            List<GenerationProtocol.KnownQuestion> knownQuestions;
            String targetConceptId = null;
            if (variant) {
                LearningRows.Concept target = learningMapper.findConcept(job.userId(), snapshot.targetConceptId());
                if (target == null || target.parseAttempt() != snapshot.parseAttempt()
                        || !target.materialSha256().equals(snapshot.materialSha256())
                        || target.materialId() != job.materialId() || !blocks.contains(target.blockId())) {
                    mapper.fail(job.userId(), job.id(), token, "GENERATION_SOURCE_CHANGED", now());
                    continue;
                }
                targetConceptId = target.id();
                known.add(new GenerationProtocol.KnownConcept(target.id(), target.blockId(), target.name(),
                        cap(target.definition(), 300)));
                knownQuestions = mapper.knownQuestionsForConcept(job.userId(), target.id()).stream()
                        .map(q -> new GenerationProtocol.KnownQuestion(q.blockId(), cap(q.sourceQuote(), 200),
                                cap(q.content(), 500)))
                        .toList();
            } else {
                // 普通出题不再按粒度过滤：同一事实换粒度也必须被视为已覆盖。
                for (LearningRows.Concept concept : learningMapper.listConceptsForBlocks(job.userId(),
                        job.materialId(), snapshot.parseAttempt(), snapshot.materialSha256(), List.copyOf(blocks))) {
                    known.add(new GenerationProtocol.KnownConcept(concept.id(), concept.blockId(), concept.name(),
                            cap(concept.definition(), 300)));
                }
                knownQuestions = mapper.knownQuestionsForBlocks(job.userId(), job.materialId(),
                        snapshot.parseAttempt(), snapshot.materialSha256(), List.copyOf(blocks)).stream()
                        .map(q -> new GenerationProtocol.KnownQuestion(q.blockId(), cap(q.sourceQuote(), 200),
                                cap(q.content(), 500)))
                        .toList();
            }
            GenerationProtocol.Request request = new GenerationProtocol.Request("1", job.id(), token,
                    variant ? 1 : job.requestedCount(), job.difficulty(), job.granularity(), snapshot.sources().stream()
                    .map(s -> new GenerationProtocol.Source(s.blockId(), s.text(),s.noiseRanges())).toList(), List.copyOf(known),
                    variant ? "VARIANT" : "NEW", targetConceptId, knownQuestions);
            return new Claimed(claimed, fitPrompt(request));
        }
        return null;
    }

    boolean isAdaptive(Claimed claimed) { return isAdaptive(snapshot(claimed.job()).mode()); }
    private static boolean isAdaptive(String mode) { return "COVERAGE".equals(mode) || "WEAKNESS".equals(mode); }

    AdaptiveCatalog.CatalogRequest catalogRequest(Claimed claimed) {
        Snapshot snapshot = snapshot(claimed.job());
        return adaptive.request(claimed.job().userId(), claimed.job().materialId(), snapshot.parseAttempt(),
                claimed.request().sources(), claimed.request().knownConcepts(),snapshot.refreshCatalog());
    }

    @Transactional
    public Claimed selectAdaptive(Claimed claimed, AdaptiveCatalog.CatalogRequest request, AdaptiveCatalog.CatalogResult result) {
        long user = claimed.job().userId(), material = claimed.job().materialId();
        lockUser(user);
        Job job = mapper.lock(user, claimed.job().id());
        var base = claimed.request();
        if (!active(job, base.attemptToken())) return null;
        Snapshot snapshot = snapshot(job);
        validateCurrentSource(job, snapshot);
        adaptive.save(user, material, snapshot.parseAttempt(), request, result);
        adaptive.linkExactLegacy(user, material, snapshot.parseAttempt(), base.knownConcepts());
        Set<Long> blocks = new HashSet<>();
        base.sources().forEach(source -> blocks.add(source.blockId()));
        var data = adaptive.data(user, material);
        var candidates = adaptive.candidates(user, material, snapshot.parseAttempt(), blocks, data.topics(),job.granularity());
        var plan = AdaptivePolicy.select(candidates, data.mastery(), data.observations(),
                "WEAKNESS".equals(snapshot.mode()), job.requestedCount(), now());
        if (plan.targets().isEmpty()) {
            String detail=adaptive.emptyNotice(user,material,snapshot.parseAttempt(),blocks);
            String notice=detail==null?plan.notice():detail+" · "+plan.notice();
            if (mapper.succeed(user, job.id(), base.attemptToken(), null, notice, now()) != 1) {
                throw conflict("出题任务认领已过期。");
            }
            return null;
        }
        List<GenerationProtocol.KnownQuestion> history = new ArrayList<>(base.knownQuestions());
        List<GenerationProtocol.KnownConcept> targets = new ArrayList<>();
        List<String> reinforcement = new ArrayList<>();
        for (var target : plan.targets()) {
            var selected = target.candidate();
            var topic = selected.topic();
            targets.add(new GenerationProtocol.KnownConcept(topic.id(), selected.block(), topic.name(), topic.definition()));
            if (selected.previous() != null) {
                reinforcement.add(topic.id());
                var old = selected.previous();
                if (old.sourceQuote() != null) history.addFirst(new GenerationProtocol.KnownQuestion(selected.block(),
                        cap(old.sourceQuote(), 200), cap(old.content(), 500)));
            }
        }
        var targeted = new GenerationProtocol.Request(base.schemaVersion(), base.jobId(), base.attemptToken(),
                targets.size(), base.difficulty(), base.granularity(), base.sources(), targets, "TARGETED", null,
                history.stream().limit(200).toList(), reinforcement);
        return new Claimed(job, fitPrompt(targeted), plan);
    }

    public List<AdaptiveCatalog.GroupView> groups(long user, long material) {
        enabled();
        if (material <= 0) throw invalid("资料编号无效。");
        return adaptive.groups(user, material);
    }

    public record TutorMastery(String name, Double probability, int evidenceCount) {}

    /** 复用正式知识组 BKT；缺少独立证据时仍返回空概率。 */
    public List<TutorMastery> tutorMastery(long user, long material) {
        if (!properties.isEnabled()) return List.of();
        return groups(user, material).stream()
                .sorted(java.util.Comparator.comparing(AdaptiveCatalog.GroupView::probability,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .limit(5).map(group -> new TutorMastery(
                        group.name(), group.probability(), group.evidenceCount())).toList();
    }

    @Transactional
    public void restoreReviewCards(long user) {
        lockUser(user);
        adaptive.restoreReviewCards(user);
    }

    @Transactional
    public boolean publish(Claimed claimed, GenerationProtocol.Result result) {
        lockUser(claimed.job().userId());
        Job job = mapper.lock(claimed.job().userId(), claimed.job().id());
        if (!active(job, claimed.request().attemptToken())) return false;
        // 即使测试替身或未来其它适配器绕过 HTTP 客户端，也不能绕过契约与来源验证。
        GenerationClient.validateResult(claimed.request(), result);
        // 重新检查来源以防止模型调用时资料被重解析；上传资料已被重解析时直接落失败而不调用模型。
        Snapshot snapshot = snapshot(job);
        validateCurrentSource(job, snapshot);
        if (isAdaptive(snapshot.mode())) return publishAdaptive(claimed, result, job, snapshot);
        Map<Long, FrozenSource> byBlock = new HashMap<>();
        snapshot.sources().forEach(s -> byBlock.put(s.blockId(), s));
        boolean variant = "VARIANT".equals(snapshot.mode());
        Set<String> knownAnchors = new HashSet<>();
        for (GenerationProtocol.KnownConcept concept : claimed.request().knownConcepts()) {
            knownAnchors.add(concept.blockId() + ":" + normalized(concept.name()));
        }
        Set<String> acceptedQuotes = new HashSet<>();
        List<GenerationProtocol.Item> accepted = new ArrayList<>();
        int skipped = 0;
        for (GenerationProtocol.Item item : result.items()) {
            FrozenSource source = byBlock.get(item.blockId());
            if (source == null || item.sourceQuote() == null || item.sourceQuote().isBlank()
                    || !source.text().contains(item.sourceQuote())) throw invalidResult();
            if (variant) {
                // 提示词要求换一种考法；确定性检查防止模型只改写题干就当作变式。
                if (claimed.request().knownQuestions().stream()
                        .anyMatch(previous -> tooSimilar(item.content(), previous.content()))) {
                    throw new GenerationException(503, "GENERATION_VARIANT_TOO_SIMILAR",
                            "生成的变式与已有题目过于相似，请重试。");
                }
            } else {
                // 普通出题只接受新考点：已有 conceptId、同块同名考点、重复引用或重复题干都过滤。
                if (item.conceptId() != null
                        || knownAnchors.contains(item.blockId() + ":" + normalized(item.conceptName()))
                        || duplicateQuote(item.sourceQuote(), claimed.request().knownQuestions(), acceptedQuotes)
                        || duplicateContent(item.content(), claimed.request().knownQuestions())
                        || learningMapper.findConceptByAnchorAnyGranularity(job.userId(), job.materialId(),
                                item.blockId(), snapshot.parseAttempt(), item.conceptName().strip()) != null) {
                    skipped++;
                    continue;
                }
            }
            accepted.add(item);
            acceptedQuotes.add(normalizedQuote(item.sourceQuote()));
        }
        if (accepted.isEmpty()) {
            String reason = variant
                    ? "没有再生成同考点题目，请重试或换一道题。"
                    : "未生成新题：候选题目与已有考点或事实重复，可对已覆盖考点使用“同考点再出一题”。";
            if (mapper.succeed(job.userId(), job.id(), claimed.request().attemptToken(), result.model(),
                    shortfall(reason, skipped), now()) != 1) throw conflict("出题任务认领已过期。");
            return true;
        }
        Set<String> usedConcepts = new HashSet<>();
        int itemNo = 0;
        for (GenerationProtocol.Item item : accepted) {
            FrozenSource source = byBlock.get(item.blockId());
            LearningRows.Concept concept;
            String familyReference = null;
            if (variant) {
                concept = learningMapper.findConcept(job.userId(), snapshot.targetConceptId());
                if (concept == null || concept.materialId() != job.materialId() || concept.blockId() != item.blockId()
                        || concept.parseAttempt() != snapshot.parseAttempt()
                        || !concept.materialSha256().equals(snapshot.materialSha256())
                        || !concept.name().equals(item.conceptName().strip())) throw invalidResult();
                LearningRows.QuestionVersion previous = learningMapper.firstQuestionVersionForConcept(
                        job.userId(), concept.id());
                familyReference = previous == null ? null : previous.id();
            } else {
                // 普通出题已排除同块同名的任何粒度考点，这里只会为新事实建新概念。
                LearningApi.ConceptView created = learning.createConcept(job.userId(), new LearningApi.CreateConcept(
                        job.materialId(), item.blockId(), item.conceptName(), item.conceptDefinition(),
                        job.granularity(), uuid()));
                concept = learningMapper.findConcept(job.userId(), created.id());
            }
            if (!usedConcepts.add(concept.id())) throw new GenerationException(503, "GENERATION_DUPLICATE_CONCEPT", "同一批次每个考点只能生成一道题。");
            NewQuestion question = new NewQuestion(job.userId(), item);
            mapper.insertQuestion(question);
            LearningApi.QuestionView version = learning.bindQuestion(job.userId(), question.getId(),
                    new LearningApi.BindQuestion(concept.id(), familyReference));
            mapper.insertItem(new Published(job.id(), ++itemNo, job.userId(), version.versionId(),
                    item.blockId(), item.sourceQuote(), source.locator(), job.difficulty()));
        }
        // 最后再检查租约，防止耗时发布跨过租约后仍提交部分题目。
        if (mapper.succeed(job.userId(), job.id(), claimed.request().attemptToken(), result.model(),
                shortfall(result.shortfallReason(), skipped), now()) != 1) throw conflict("出题任务认领已过期。");
        return true;
    }

    private boolean publishAdaptive(Claimed claimed, GenerationProtocol.Result result, Job job, Snapshot snapshot) {
        if (claimed.plan() == null) throw invalidResult();
        long user = job.userId();
        Map<String, AdaptivePolicy.Target> targets = new LinkedHashMap<>();
        claimed.plan().targets().forEach(target -> targets.put(target.candidate().topic().id(), target));
        List<AdaptiveMapper.Previous> history = adaptive.history(user, job.materialId());
        Map<Long, FrozenSource> sources = new HashMap<>();
        snapshot.sources().forEach(source -> sources.put(source.blockId(), source));
        Set<String> acceptedQuotes = new HashSet<>();
        List<String> acceptedContents = new ArrayList<>();
        int itemNo = 0;
        for (GenerationProtocol.Item item : result.items()) {
            var target = targets.remove(item.conceptId());
            if (target == null) throw invalidResult();
            var candidate = target.candidate();
            var previous = history.stream().filter(q -> q.topicId().equals(item.conceptId())).findFirst().orElse(null);
            boolean variant = previous != null && "RECENT_ERROR_VARIANT".equals(target.reason());
            // 发布时以稳定小考点 ID 再查历史，保护覆盖模式的跨批次约束。
            if (!variant && previous != null) continue;
            if (history.stream().anyMatch(q -> tooSimilar(item.content(), q.content()))
                    || acceptedContents.stream().anyMatch(content -> tooSimilar(item.content(), content))
                    || (!variant && (duplicateQuote(item.sourceQuote(), claimed.request().knownQuestions(), acceptedQuotes)
                        || history.stream().anyMatch(q -> q.sourceQuote() != null
                            && similarQuote(normalizedQuote(item.sourceQuote()), normalizedQuote(q.sourceQuote())))))) continue;
            FrozenSource source = sources.get(candidate.block());
            if (source == null || candidate.block() != item.blockId() || !source.text().contains(item.sourceQuote())) throw invalidResult();
            var topic = candidate.topic();
            String familyReference = variant && previous != null ? previous.versionId() : null;
            var concept = learningMapper.findConceptByAnchorAnyGranularity(user, job.materialId(), item.blockId(),
                    snapshot.parseAttempt(), topic.name());
            String conceptId = concept != null ? concept.id() : learning.createConcept(user, new LearningApi.CreateConcept(
                    job.materialId(), item.blockId(), topic.name(), topic.definition(), job.granularity(), uuid())).id();
            adaptive.bind(user, conceptId, topic.id());
            NewQuestion question = new NewQuestion(user, item);
            mapper.insertQuestion(question);
            var version = learning.bindGroupedQuestion(user, question.getId(), new LearningApi.BindQuestion(conceptId, familyReference));
            mapper.insertItem(new Published(job.id(), ++itemNo, user, version.versionId(), item.blockId(),
                    item.sourceQuote(), source.locator(), job.difficulty()));
            adaptive.recordChoice(user, version.versionId(), target);
            acceptedQuotes.add(normalizedQuote(item.sourceQuote()));
            acceptedContents.add(item.content());
        }
        String notice = claimed.plan().notice();
        if (itemNo < claimed.plan().targets().size()) notice = notice == null ? "有效题目不足" : notice + " · 有效题目不足";
        if (mapper.succeed(user, job.id(), claimed.request().attemptToken(), result.model(), notice, now()) != 1) {
            throw conflict("出题任务认领已过期。");
        }
        // 每批新考点随成功题目一起建卡。复习卡只承担复习排期，不是资料级出题白名单。
        if (itemNo > 0) adaptive.ensurePublishedReviewCards(user, job.id());
        return true;
    }

    @Transactional
    public void fail(Claimed claimed, String code) {
        // 白名单形态防止将任意异常消息、第三方正文误塞进公开错误字段。
        String safeCode = code != null && code.matches("[A-Z_]{1,64}") ? code : "GENERATION_PROCESSING_FAILED";
        mapper.fail(claimed.job().userId(), claimed.job().id(), claimed.request().attemptToken(), safeCode, now());
    }

    private Snapshot freeze(long user, long material, List<Long> blockIds) {
        List<FrozenSource> sources = new ArrayList<>();
        String sha = null;
        long attempt = -1;
        int chars = 0;
        for (long block : blockIds) {
            LearningRows.Source source = learningMapper.findMaterialSource(user, material, block);
            if (source == null) throw new GenerationException(404, "GENERATION_SOURCE_NOT_FOUND", "资料正文不存在或尚未解析。");
            if (sha != null && (!sha.equals(source.sha256()) || attempt != source.parseAttempt())) throw conflict("资料正在变化，请重新选择。");
            sha = source.sha256(); attempt = source.parseAttempt();
            chars += source.textContent().codePointCount(0, source.textContent().length());
            if (chars > 12000) throw invalid("所选正文超过 12000 字符，请缩小范围；系统不会悄悄截断来源。");
            sources.add(new FrozenSource(block, source.textContent(), source.sourceLocator(),adaptive.noise(user,material,attempt,block)));
        }
        return new Snapshot(sha, attempt, List.copyOf(sources));
    }

    private void validateCurrentSource(Job job, Snapshot snapshot) {
        for (FrozenSource frozen : snapshot.sources()) {
            LearningRows.Source current = learningMapper.findMaterialSource(job.userId(), job.materialId(), frozen.blockId());
            if (current == null || !snapshot.materialSha256().equals(current.sha256())
                    || snapshot.parseAttempt() != current.parseAttempt() || !frozen.text().equals(current.textContent())
                    || !frozen.locator().equals(current.sourceLocator())) {
                throw new GenerationException(409, "GENERATION_SOURCE_CHANGED", "资料版本已变化，请重新选择正文并创建出题任务。");
            }
        }
    }

    private JobView view(Job job) {
        List<Question> questions = mapper.items(job.userId(), job.id()).stream().map(item -> {
            LearningRows.QuestionVersion q = learningMapper.findQuestionVersion(job.userId(), item.questionVersionId());
            return new Question(new LearningApi.QuestionView(q.id(), q.questionId(), q.conceptId(), q.content(),
                    q.optionA(), q.optionB(), q.optionC(), q.optionD()), item.blockId(), item.sourceLocator(), item.difficulty());
        }).toList();
        Snapshot snapshot = snapshot(job);
        boolean variant = "VARIANT".equals(snapshot.mode());
        return new JobView(job.id(), job.materialId(), job.status(), variant ? 1 : job.requestedCount(), questions.size(),
                job.difficulty(), job.granularity(), job.attemptCount(), job.errorCode(), job.shortfallReason(),
                questions, job.createdAt().toInstant(ZoneOffset.UTC), job.updatedAt().toInstant(ZoneOffset.UTC),
                variant || isAdaptive(snapshot.mode()) ? snapshot.mode() : "NEW", variant ? snapshot.targetConceptId() : null);
    }
    private Snapshot snapshot(Job job) { return json.readValue(job.snapshotJson(), Snapshot.class); }
    private boolean active(Job job, String token) { return job != null && "PROCESSING".equals(job.status())
            && Objects.equals(job.claimToken(), token) && job.leaseUntil() != null && job.leaseUntil().isAfter(now()); }

    /** 目录中的旧定义可能长于模型协议上限；只截断送给模型的提示，不改数据库原文。 */
    private static String cap(String value, int maximum) {
        if (value == null) return "";
        int length = value.codePointCount(0, value.length());
        return length <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    /**
     * 内部协议与 Python 请求体上限均为 128 KiB。考点和已出题目录只服务提示与近似去重，
     * 不能让历史数据把合法请求撑爆；从最旧条目开始裁掉，直到完整请求留有余量。
     * 变式必须保留唯一目标考点，因此绝不裁剪 knownConcepts。
     */
    private GenerationProtocol.Request fitPrompt(GenerationProtocol.Request request) {
        List<GenerationProtocol.KnownConcept> concepts = new ArrayList<>(request.knownConcepts());
        List<GenerationProtocol.KnownQuestion> questions = new ArrayList<>(request.knownQuestions());
        boolean fixedTargets = !"NEW".equals(request.mode());
        while (json.writeValueAsBytes(new GenerationProtocol.Request(request.schemaVersion(), request.jobId(),
                request.attemptToken(), request.count(), request.difficulty(), request.granularity(),
                request.sources(), concepts, request.mode(), request.targetConceptId(), questions, request.reinforcementIds())).length
                > 110 * 1024) {
            if (questions.size() >= concepts.size() && !questions.isEmpty()) {
                questions.removeLast();
            } else if (!fixedTargets && !concepts.isEmpty()) {
                concepts.removeLast();
            } else if (!questions.isEmpty()) {
                questions.removeLast();
            } else {
                break;
            }
        }
        return new GenerationProtocol.Request(request.schemaVersion(), request.jobId(), request.attemptToken(),
                request.count(), request.difficulty(), request.granularity(), request.sources(), concepts,
                request.mode(), request.targetConceptId(), questions, request.reinforcementIds());
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String normalizedQuote(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /** 同一引用或长引用互相包含，视为同一事实；短引用只做完全相等判断，减少误杀。 */
    private static boolean similarQuote(String left, String right) {
        if (left.equals(right)) return true;
        if (left.length() < 24 || right.length() < 24) return false;
        return left.contains(right) || right.contains(left);
    }

    private static boolean duplicateQuote(String quote, List<GenerationProtocol.KnownQuestion> known,
                                          Set<String> accepted) {
        String value = normalizedQuote(quote);
        if (accepted.stream().anyMatch(previous -> similarQuote(value, previous))) return true;
        return known.stream().map(previous -> normalizedQuote(previous.sourceQuote()))
                .anyMatch(previous -> similarQuote(value, previous));
    }

    private static boolean duplicateContent(String content, List<GenerationProtocol.KnownQuestion> known) {
        String value = normalized(content);
        return known.stream().map(previous -> normalized(previous.content()))
                .anyMatch(previous -> previous.equals(value)
                        || (value.length() >= 20 && (previous.contains(value) || value.contains(previous))));
    }

    /** 变式至少要在题干措辞上明显不同，不能只改几个词或选项顺序。 */
    private static boolean tooSimilar(String left, String right) {
        String a = normalized(left).replaceAll("\\s+", "");
        String b = normalized(right).replaceAll("\\s+", "");
        if (a.equals(b)) return true;
        if (a.length() < 2 || b.length() < 2) return false;
        Set<String> first = new HashSet<>(), second = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) first.add(a.substring(i, i + 2));
        for (int i = 0; i < b.length() - 1; i++) second.add(b.substring(i, i + 2));
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        Set<String> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        return union.isEmpty() || (double) intersection.size() / union.size() >= 0.8;
    }

    private static String shortfall(String original, int skipped) {
        if (skipped <= 0) return original;
        String extra = "已过滤 " + skipped + " 道与已有考点或事实重复的题。";
        if (original == null || original.isBlank()) return extra;
        String combined = original.strip() + " " + extra;
        if (combined.codePointCount(0, combined.length()) <= 300) return combined;
        return combined.substring(0, combined.offsetByCodePoints(0, 300));
    }
    private void lockUser(long user) { enabled(); if (user <= 0 || learningMapper.lockUser(user) == null)
        throw new GenerationException(401, "LOGIN_REQUIRED", "请先登录。"); }
    private void enabled() { if (!properties.isEnabled() || !learningProperties.isEnabled())
        throw new GenerationException(503, "GENERATION_NOT_ENABLED", "出题功能尚未启用，请先完成数据库迁移与服务配置。"); }

    /**
     * 未开启网页密钥模式时保留此前的服务器环境配置；
     * 一旦开启，就必须使用当前账号自己的密钥，不得回落到共享密钥。
     */
    private void requireUserKey(long user) {
        if (!credentials.isEnabled()) return;
        try { credentials.requireConfigured(user); }
        catch (ModelCredentialException ex) { throw new GenerationException(ex.status(), ex.code(), ex.getMessage()); }
    }
    private void validateCreate(CreateJob request) {
        if (request == null || request.materialId() <= 0 || request.blockIds() == null
                || request.blockIds().isEmpty() || request.blockIds().size() > 10
                || !Set.of(3, 5, 10).contains(request.count())
                || !Set.of("EASY", "MEDIUM", "HARD").contains(String.valueOf(request.difficulty()))
                || !Set.of("COARSE", "MEDIUM", "FINE").contains(String.valueOf(request.granularity()))
                || (request.mode() != null && !isAdaptive(request.mode()))
                || (request.refreshCatalog() && !isAdaptive(request.mode()))) throw invalid("出题参数无效。");
        long previous = 0;
        for (Long block : request.blockIds()) {
            if (block == null || block <= previous) throw invalid("正文块编号须为正数、升序且不重复。");
            previous = block;
        }
    }
    static String uuid() { return UUID.randomUUID().toString(); }
    static String uuid(String value) {
        try { String normalized = UUID.fromString(value).toString(); if (!normalized.equals(value)) throw invalid("编号必须是标准小写 UUID。"); return normalized; }
        catch (IllegalArgumentException | NullPointerException ex) { throw invalid("编号必须是标准小写 UUID。"); }
    }
    static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    static GenerationException invalid(String message) { return new GenerationException(400, "INVALID_GENERATION_REQUEST", message); }
    static GenerationException conflict(String message) { return new GenerationException(409, "GENERATION_CONFLICT", message); }
    static GenerationException missing() { return new GenerationException(404, "GENERATION_NOT_FOUND", "出题任务不存在。"); }
    static GenerationException invalidResult() { return new GenerationException(503, "GENERATION_INVALID_RESULT", "模型返回的题目或来源无效，请手动重试。"); }
}
