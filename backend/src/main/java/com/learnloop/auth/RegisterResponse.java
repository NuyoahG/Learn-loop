package com.learnloop.auth;

/**
 * 注册成功后返回的数据。
 *
 * 只返回用户编号和用户名。
 * 不包含原始密码，也不包含 passwordHash。
 */
public record RegisterResponse(
        Long userId,
        String username
) {
}