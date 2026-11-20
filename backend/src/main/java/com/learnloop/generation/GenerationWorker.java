package com.learnloop.generation;

import com.learnloop.learning.LearningException;
import com.learnloop.learning.LearningProperties;
import com.learnloop.modelsettings.ModelCredentialException;
import com.learnloop.modelsettings.ModelCredentialService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单调用后台 worker；全局队列在 MySQL 中，进程只保留一个正在工作的线程，不堆积内存任务。 */
@Component
public class GenerationWorker {
    private final GenerationService service;
    private final GenerationClient client;
    private final GenerationProperties properties;
    private final LearningProperties learningProperties;
    private final ModelCredentialService credentials;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "learning-generation-worker"); thread.setDaemon(true); return thread;
    });
    public GenerationWorker(GenerationService service, GenerationClient client, GenerationProperties properties,
                            LearningProperties learningProperties, ModelCredentialService credentials) {
        this.service = service; this.client = client; this.properties = properties; this.learningProperties = learningProperties;
        this.credentials = credentials;
    }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!properties.isEnabled() || !properties.isWorkerEnabled() || !learningProperties.isEnabled()
                || !running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                // 租约恢复独立提交，只锁任务；随后认领统一使用 user→job→source 的锁顺序。
                service.expireInterrupted();
                GenerationService.Claimed claimed = service.claimNext();
                if (claimed == null) return;
                try (var diagnosis = new GenerationDiagnostics(claimed)) {
                  try {
                    // 该位置没有数据库事务；HTTP 超时不会长期占用用户行锁或数据库连接。
                    // 密钥不进入任务快照、数据库或状态 DTO。调用前才读取当前配置：
                    // 排队期间删除密钥会让任务安全失败；已发出的供应商请求无法被本地删除撤销。
                    String key = credentials.isEnabled() ? credentials.requireKey(claimed.job().userId()) : null;
                    if (service.isAdaptive(claimed)) {
                        diagnosis.stage(GenerationDiagnostics.Stage.CATALOG);
                        var request = service.catalogRequest(claimed);
                        var catalog = request == null ? null : client.catalog(request, key);
                        diagnosis.stage(GenerationDiagnostics.Stage.SELECT);
                        var selected = service.selectAdaptive(claimed, request, catalog);
                        if (selected == null) { diagnosis.success(); return; }
                        claimed = selected;
                        if (request != null && credentials.isEnabled()) key = credentials.requireKey(claimed.job().userId());
                    }
                    diagnosis.stage(GenerationDiagnostics.Stage.QUESTIONS);
                    GenerationProtocol.Result result = key == null ? client.generate(claimed.request())
                            : client.generate(claimed.request(), key);
                    diagnosis.stage(GenerationDiagnostics.Stage.PUBLISH);
                    if(service.publish(claimed, result)) diagnosis.success(); else diagnosis.stale();
                } catch (ModelCredentialException ex) { service.fail(claimed, diagnosis.failure(ex.code())); }
                catch (GenerationException ex) { service.fail(claimed, diagnosis.failure(ex.code())); }
                catch (LearningException ex) { service.fail(claimed, diagnosis.failure("GENERATION_PUBLICATION_REJECTED")); }
                catch (RuntimeException ex) { service.fail(claimed, diagnosis.failure("GENERATION_PROCESSING_FAILED")); }
                }
            } catch (RuntimeException ignored) {
                // 数据库暂时不可用时留给租约恢复；不打印资料、模型返回或身份凭据。
            } finally { running.set(false); }
        });
    }
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
