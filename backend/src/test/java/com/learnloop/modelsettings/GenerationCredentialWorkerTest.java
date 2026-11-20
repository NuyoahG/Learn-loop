package com.learnloop.modelsettings;

import com.learnloop.generation.*;
import com.learnloop.learning.LearningProperties;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** 后台任务必须选择当前用户的密钥，缺失时不能悄悄改用服务器共享密钥。 */
class GenerationCredentialWorkerTest {
    private GenerationWorker worker(GenerationService service, GenerationClient client, ModelCredentialService credentials) {
        var p = new GenerationProperties(); p.setEnabled(true); p.setWorkerEnabled(true);
        var learning = new LearningProperties(); learning.setEnabled(true);
        return new GenerationWorker(service, client, p, learning, credentials);
    }
    private GenerationService.Claimed claimed(GenerationService service) {
        var job = mock(GenerationRows.Job.class);
        when(job.userId()).thenReturn(9L);
        var claimed = new GenerationService.Claimed(job, null);
        when(service.claimNext()).thenReturn(claimed);
        return claimed;
    }

    @Test void enabledModePassesOnlyClaimedOwnersKeyToInternalClient() {
        var service = mock(GenerationService.class); var client = mock(GenerationClient.class);
        var credentials = mock(ModelCredentialService.class); var claimed = claimed(service);
        when(credentials.isEnabled()).thenReturn(true);
        when(credentials.requireKey(9)).thenReturn("sk-fixture-key-000000000000000000000003");
        GenerationWorker worker = worker(service, client, credentials);
        try {
            worker.tick();
            verify(service, timeout(5000)).publish(claimed, null);
            verify(client).generate(null, "sk-fixture-key-000000000000000000000003");
            verify(client, never()).generate(any());
        } finally { worker.close(); }
    }
    @Test void deletedKeyFailsQueuedWorkAndNeverFallsBackToSharedCredential() {
        var service = mock(GenerationService.class); var client = mock(GenerationClient.class);
        var credentials = mock(ModelCredentialService.class); var claimed = claimed(service);
        when(credentials.isEnabled()).thenReturn(true);
        when(credentials.requireKey(9)).thenThrow(new ModelCredentialException(409, "MODEL_KEY_REQUIRED", "请先配置模型密钥。"));
        GenerationWorker worker = worker(service, client, credentials);
        try {
            worker.tick();
            verify(service, timeout(5000)).fail(claimed, "PREPARE_MODEL_KEY_REQUIRED");
            verifyNoInteractions(client);
        } finally { worker.close(); }
    }
    @Test void disabledModePreservesPreviousExplicitServerConfiguration() {
        var service = mock(GenerationService.class); var client = mock(GenerationClient.class);
        var credentials = mock(ModelCredentialService.class); var claimed = claimed(service);
        when(credentials.isEnabled()).thenReturn(false);
        GenerationWorker worker = worker(service, client, credentials);
        try {
            worker.tick();
            verify(service, timeout(5000)).publish(claimed, null);
            verify(client).generate(null);
            verify(credentials, never()).requireKey(anyLong());
        } finally { worker.close(); }
    }
}
