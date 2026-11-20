package com.learnloop.modelsettings;

import com.learnloop.auth.LoginUser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller 直接边界测试不替代 Session/CSRF 的真实 HTTP 验收；所有输入均为虚构测试密钥。 */
class ModelSettingsControllerTest {
    private static final LoginUser USER = new LoginUser(7L, "test-user", "test-hash");
    private static final String KEY = "sk-fixture-key-000000000000000000000007";
    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-LearnLoop-Expected-User", "7");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test void acceptsOnlyApiKeyAndNeverEchoesIt() {
        ModelCredentialService service = mock(ModelCredentialService.class);
        when(service.save(7, KEY)).thenReturn(new ModelCredentialService.Settings("DeepSeek", "test-model", true));
        var result = new ModelSettingsController(service).save(USER, request("{\"apiKey\":\"" + KEY + "\"}"));
        assertTrue(result.getBody().configured());
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertFalse(result.getBody().toString().contains(KEY));
        verify(service).save(7, KEY);
    }
    @Test void rejectsDuplicateExtraMissingWrongTypeAndTrailingJsonBeforeService() {
        for (String body : new String[]{"{}", "null", "[]", "{\"apiKey\":null}", "{\"apiKey\":1}",
                "{\"apiKey\":\"x\",\"model\":\"other\"}", "{\"apiKey\":\"x\",\"apiKey\":\"y\"}", "{\"apiKey\":\"x\"} {}", "not-json"}) {
            ModelCredentialService service = mock(ModelCredentialService.class);
            var ex = assertThrows(ModelCredentialException.class, () -> new ModelSettingsController(service).save(USER, request(body)));
            assertEquals("INVALID_MODEL_KEY_REQUEST", ex.code());
            assertNull(ex.getCause());
            verifyNoInteractions(service);
        }
    }
    @Test void bodyOverOneKiBIsRejectedBeforeService() {
        ModelCredentialService service = mock(ModelCredentialService.class);
        var ex = assertThrows(ModelCredentialException.class, () -> new ModelSettingsController(service).save(USER, request(" ".repeat(1025))));
        assertEquals(413, ex.status());
        verifyNoInteractions(service);
    }
    @Test void getAndDeleteUseOnlyAuthenticatedUserIdentity() {
        ModelCredentialService service = mock(ModelCredentialService.class);
        when(service.settings(7)).thenReturn(new ModelCredentialService.Settings("DeepSeek", "test", true));
        when(service.delete(7)).thenReturn(new ModelCredentialService.Settings("DeepSeek", "test", false));
        var controller = new ModelSettingsController(service);
        assertTrue(controller.get(USER).getBody().configured());
        assertFalse(controller.delete(USER, request("")).getBody().configured());
        verify(service).settings(7); verify(service).delete(7);
    }

    @Test void missingMismatchedAndNoncanonicalExpectedOwnerAreRejectedBeforeReadingBody() {
        for (String header : new String[]{null, "8", "07", " 7", "7 ", "7,7", "-7", "7.0"}) {
            ModelCredentialService service = mock(ModelCredentialService.class);
            MockHttpServletRequest request = unreadableRequest();
            if (header != null) request.addHeader("X-LearnLoop-Expected-User", header);
            var controller = new ModelSettingsController(service);
            assertContextChanged(() -> controller.save(USER, request));
            assertContextChanged(() -> controller.delete(USER, request));
            verifyNoInteractions(service);
        }
    }

    @Test void repeatedExpectedOwnerHeadersAreRejectedEvenWhenBothValuesMatch() {
        ModelCredentialService service = mock(ModelCredentialService.class);
        MockHttpServletRequest request = unreadableRequest();
        request.addHeader("X-LearnLoop-Expected-User", "7");
        request.addHeader("X-LearnLoop-Expected-User", "7");
        var controller = new ModelSettingsController(service);
        assertContextChanged(() -> controller.save(USER, request));
        assertContextChanged(() -> controller.delete(USER, request));
        verifyNoInteractions(service);
    }

    private MockHttpServletRequest unreadableRequest() {
        // 若 Controller 在身份栅栏前读取正文，测试立刻失败；密钥内容不应进入错误账号上下文。
        return new MockHttpServletRequest() {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("必须先拒绝变化的账号上下文，再读取请求体");
            }
        };
    }
    private void assertContextChanged(org.junit.jupiter.api.function.Executable action) {
        ModelCredentialException ex = assertThrows(ModelCredentialException.class, action);
        assertEquals(409, ex.status()); assertEquals("SESSION_CONTEXT_CHANGED", ex.code());
        assertEquals("登录账号上下文已变化，请刷新页面后再操作模型密钥。", ex.getMessage());
        assertNull(ex.getCause());
    }
}
