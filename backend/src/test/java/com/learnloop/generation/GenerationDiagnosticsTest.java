package com.learnloop.generation;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationDiagnosticsTest {
    @Test void failureHasStageAndDoesNotLeakExceptionTextOrThreadContext() {
        var job=mock(GenerationRows.Job.class);
        when(job.id()).thenReturn("12345678-1234-1234-1234-123456789abc");
        when(job.attemptCount()).thenReturn(2);
        try(var log=new GenerationDiagnostics(new GenerationService.Claimed(job,null))) {
            log.stage(GenerationDiagnostics.Stage.CATALOG);
            assertThat(log.failure("MODEL_OUTPUT_INVALID")).isEqualTo("CATALOG_MODEL_OUTPUT_INVALID");
            assertThat(log.failure("Bearer private-key\nraw model body"))
                .isEqualTo("CATALOG_GENERATION_PROCESSING_FAILED");
            assertThat(MDC.get("generationAttempt")).isEqualTo("2");
        }
        assertThat(MDC.get("generationJob")).isNull();
        assertThat(MDC.get("generationAttempt")).isNull();
    }
}
