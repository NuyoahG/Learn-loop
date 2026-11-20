package com.learnloop.config;

import com.learnloop.auth.LoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 新工作台写请求携带“页面加载时的用户 ID”，防止其他标签页切换账号后旧页面写入新账号。
 * 此头不是认证凭据：当前所有者始终由 Session 决定，匿名认证仍交给原安全链处理。
 * 不标注 Component/Bean，只加入 SecurityFilterChain 一次，避免同时作为 Servlet Filter 注册。
 */
public final class ExpectedUserContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (!context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        boolean privateReading = path.equals("/api/answers") || path.startsWith("/api/answers/")
                || path.equals("/api/notes") || path.startsWith("/api/notes/") || path.startsWith("/api/reading/")
                || path.startsWith("/api/review/") || path.startsWith("/api/tutor/") || (path.startsWith("/api/materials/") && path.endsWith("/original"));
        if (!path.startsWith("/api/") || (!privateReading && Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()))) {
            chain.doFilter(request, response); return;
        }
        var headers = Collections.list(request.getHeaders("X-LearnLoop-Expected-User"));
        // 旧 HTML 尚未发送此头，继续沿用已有行为；模型密钥 Controller 另行强制要求此头。
        if (headers.isEmpty()) { chain.doFilter(request, response); return; }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser user)) {
            chain.doFilter(request, response); return;
        }
        // 必须早于 CSRF/body 读取/上传解析/退出逻辑，不能先消耗敏感输入或使 B 的 Session 退出。
        if (headers.size() != 1 || user.getUserId() == null
                || !Long.toString(user.getUserId()).equals(headers.getFirst())) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setHeader("Cache-Control", "no-store");
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"errorCode\":\"SESSION_CONTEXT_CHANGED\",\"message\":\"登录账号上下文已变化，请刷新页面后再操作。\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
