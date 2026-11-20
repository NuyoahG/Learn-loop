package com.learnloop.config;

import com.learnloop.auth.LoginUser;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** 直接验证安全链门禁：被拒绝请求既不能读取 body，也不能到达上传/退出/业务处理器。 */
class ExpectedUserContextFilterTest {
    private final ExpectedUserContextFilter filter = new ExpectedUserContextFilter();
    @BeforeEach void loggedInOwner() {
        var user = new LoginUser(7L, "synthetic-user", "synthetic-hash");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }
    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("过滤器不得读取请求正文"); }
            @Override public BufferedReader getReader() { throw new AssertionError("过滤器不得读取请求正文"); }
        };
        request.setMethod(method); request.setRequestURI(path); return request;
    }
    private MockHttpServletResponse run(MockHttpServletRequest request, boolean shouldContinue) throws Exception {
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> continued.set(true));
        assertEquals(shouldContinue, continued.get()); return response;
    }

    @Test void matchingOwnerContinuesAuthenticatedWrite() throws Exception {
        var request = request("POST", "/api/generation/jobs"); request.addHeader("X-LearnLoop-Expected-User", "7");
        assertEquals(200, run(request, true).getStatus());
    }
    @Test void changedOwnerBlocksUploadAndLogoutBeforeBodyOrChain() throws Exception {
        for (String path : List.of("/api/materials", "/api/auth/logout")) {
            var request = request("POST", path); request.addHeader("X-LearnLoop-Expected-User", "6");
            var response = run(request, false);
            assertEquals(409, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals("{\"errorCode\":\"SESSION_CONTEXT_CHANGED\",\"message\":\"登录账号上下文已变化，请刷新页面后再操作。\"}", response.getContentAsString());
        }
    }
    @Test void duplicateAndNoncanonicalOwnerHeadersFailClosed() throws Exception {
        var duplicate = request("DELETE", "/api/settings/model-key");
        duplicate.addHeader("X-LearnLoop-Expected-User", "7"); duplicate.addHeader("X-LearnLoop-Expected-User", "7");
        assertEquals(409, run(duplicate, false).getStatus());
        for (String value : List.of("", "07", " 7", "7 ", "7,7", "7.0", "+7")) {
            var request = request("PUT", "/api/settings/model-key"); request.addHeader("X-LearnLoop-Expected-User", value);
            assertEquals(409, run(request, false).getStatus());
        }
    }
    @Test void noHeaderPreservesLegacyHtmlWrites() throws Exception {
        assertEquals(200, run(request("POST", "/api/answers"), true).getStatus());
    }
    @Test void readsAndNonApiRequestsSkipOwnerFence() throws Exception {
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            var request = request(method, "/api/materials"); request.addHeader("X-LearnLoop-Expected-User", "6");
            assertEquals(200, run(request, true).getStatus());
        }
        var other = request("POST", "/other/path"); other.addHeader("X-LearnLoop-Expected-User", "6");
        assertEquals(200, run(other, true).getStatus());
    }
    @Test void anonymousRequestsRemainResponsibilityOfExistingAuthenticationChain() throws Exception {
        SecurityContextHolder.clearContext();
        var absent = request("POST", "/api/auth/login"); absent.addHeader("X-LearnLoop-Expected-User", "6");
        assertEquals(200, run(absent, true).getStatus());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test-only", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var anonymous = request("POST", "/api/materials"); anonymous.addHeader("X-LearnLoop-Expected-User", "6");
        assertEquals(200, run(anonymous, true).getStatus());
    }
    @Test void servletContextPathIsRemovedBeforeApiMatching() throws Exception {
        var request = request("POST", "/learnloop/api/materials"); request.setContextPath("/learnloop");
        request.addHeader("X-LearnLoop-Expected-User", "6");
        assertEquals(409, run(request, false).getStatus());
    }
}
