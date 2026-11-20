package com.learnloop.learning;

import com.learnloop.learning.LearningRows.*;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL 事件表的最小可靠消费者：认领短事务 → Python HTTP → 带栅栏的发布短事务。
 *
 * 单个 worker 顺序处理小批事件，独立线程避免阻塞资料解析的调度器。
 * 数据库序号/租约/token 才是防重复依据，不能只依赖本进程 AtomicBoolean。
 */
@Component
public class LearningWorker {
    private final LearningMapper mapper;
    private final LearningService service;
    private final BktClient client;
    private final LearningProperties properties;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "learning-bkt-worker"); thread.setDaemon(true); return thread;
    });
    private record Claimed(Event event, Mastery state, String token) { }

    public LearningWorker(LearningMapper mapper, LearningService service, BktClient client,
                          LearningProperties properties, TransactionTemplate tx) {
        this.mapper = mapper; this.service = service; this.client = client; this.properties = properties; this.tx = tx;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled() || !running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                // 每轮重新扫描，前序刚应用的下一个事件才能进入可认领集合。
                for (int i = 0; i < 5; i++) {
                    var candidates = mapper.listClaimableEvents(LearningService.now(), 10);
                    if (candidates.isEmpty()) break;
                    boolean progressed = false;
                    for (Event candidate : candidates) {
                        Claimed claimed = tx.execute(status -> claim(candidate));
                        if (claimed == null) continue;
                        progressed = true;
                        process(claimed);
                        break;
                    }
                    if (!progressed) break;
                }
            } catch (RuntimeException ignored) {
                // 数据库短暂不可用时下轮重新扫描；租约会恢复已认领但未发布的任务。
                // 不打印输入正文、SQL、身份凭据或任意第三方响应。
            } finally { running.set(false); }
        });
    }

    private Claimed claim(Event candidate) {
        Mastery state = mapper.lockMastery(candidate.userId(), candidate.conceptId(), candidate.modelVersion());
        Event event = mapper.lockEvent(candidate.userId(), candidate.id());
        LocalDateTime now = LearningService.now();
        if (state == null || event == null || !BktClient.MODEL_VERSION.equals(event.modelVersion())
                || event.sequenceNo() != state.appliedSequence() + 1) return null;
        String token = LearningService.id();
        if (mapper.claimEvent(event.userId(), event.id(), token, now, now.plusSeconds(30)) != 1) return null;
        return new Claimed(mapper.findEvent(event.userId(), event.id()), state, token);
    }

    private void process(Claimed claimed) {
        try {
            if (claimed.event().attemptCount() > 5) { fail(claimed, "BKT_RETRY_LIMIT", true); return; }
            // 每次使用冻结的前状态；不能先更新本地概率再等待 HTTP 成功。
            BktClient.Result result = client.step(claimed.event().id(), claimed.state().masteryProbability(), claimed.event().correct());
            tx.executeWithoutResult(status -> publish(claimed, result));
        } catch (LearningException ex) {
            fail(claimed, ex.code(), "LEARNING_CONFLICT".equals(ex.code()) || claimed.event().attemptCount() >= 5);
        } catch (RuntimeException ex) { fail(claimed, "BKT_PROCESSING_FAILED", claimed.event().attemptCount() >= 5); }
    }

    private void publish(Claimed claimed, BktClient.Result result) {
        Event expected = claimed.event();
        // 所有事务采用先 mastery 后 event 的锁顺序，减少并发消费者之间的死锁。
        Mastery current = mapper.lockMastery(expected.userId(), expected.conceptId(), expected.modelVersion());
        Event actual = mapper.lockEvent(expected.userId(), expected.id());
        LocalDateTime now = LearningService.now();
        if (current == null || actual == null || !"PROCESSING".equals(actual.status())
                || !Objects.equals(claimed.token(), actual.claimToken()) || !actual.leaseUntil().isAfter(now)) return;
        // 资料重解析、原题更改后迟到的计算只能失败，不能发布为新有效证据。
        QuestionVersion question = mapper.findQuestionVersion(expected.userId(), expected.questionVersionId());
        if (question == null) throw LearningService.conflict("题目版本失效。");
        service.validateQuestion(expected.userId(), question);
        if (mapper.applyMastery(expected.userId(), expected.conceptId(), expected.modelVersion(),
                claimed.state().stateVersion(), expected.sequenceNo(), result.masteryProbability(),
                expected.id(), claimed.token(), now) != 1) throw LearningService.conflict("学习结果版本冲突。");
        if (mapper.markEventApplied(expected.userId(), expected.id(), claimed.token(),
                result.priorMastery(), result.masteryProbability(), now) != 1) throw LearningService.conflict("事件认领已过期。");
    }

    private void fail(Claimed claimed, String code, boolean terminal) {
        tx.executeWithoutResult(status -> {
            LocalDateTime now = LearningService.now();
            // token 条件保护已经被别人重新认领的任务；失败处理也不能撤销别人发布的结果。
            mapper.failEvent(claimed.event().userId(), claimed.event().id(), claimed.token(), terminal,
                    code, now.plusSeconds(Math.min(60, 1L << Math.min(6, claimed.event().attemptCount()))), now);
        });
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
