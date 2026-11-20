package com.learnloop.generation;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 每次认领独立记账，重试不覆盖上一轮；日志只含受控标识，不含密钥、资料或模型正文。 */
final class GenerationDiagnostics implements AutoCloseable {
    enum Stage { PREPARE, CATALOG, SELECT, QUESTIONS, PUBLISH }
    private final String job;
    private final int attempt;
    private final long started=System.nanoTime();
    private Stage stage=Stage.PREPARE;
    GenerationDiagnostics(GenerationService.Claimed claimed) {
        job=claimed.job().id(); attempt=claimed.job().attemptCount();
        MDC.put("generationJob",job); MDC.put("generationAttempt",Integer.toString(attempt));
        event("STARTED",null);
    }
    void stage(Stage next) { stage=next; event("RUNNING",null); }
    void success() { event("SUCCEEDED",null); }
    void stale() { event("DISCARDED","STALE_ATTEMPT"); }
    String failure(String code) {
        String safe=code!=null && code.matches("[A-Z_]{1,48}")?code:"GENERATION_PROCESSING_FAILED";
        event("FAILED",safe);
        // 保留原错误在诊断日志中；任务保存的短码额外带阶段，让页面能区分提炼和出题失败。
        return stage.name()+"_"+safe;
    }
    private void event(String status,String code) {
        LoggerFactory.getLogger(GenerationDiagnostics.class).info(
            "job={} attempt={} stage={} status={} elapsedMs={} code={}",job,attempt,stage,status,
            (System.nanoTime()-started)/1_000_000,code==null?"NONE":code);
    }
    @Override public void close() { MDC.remove("generationJob"); MDC.remove("generationAttempt"); }
}
