package com.learnloop.auth;

/**
 * 返回当前登录用户的公开信息。
 * 不返回完整的 LoginUser，避免带出内部认证信息。
 */
public record CurrentUserResponse(
        Long userId,
        String username
) {
}