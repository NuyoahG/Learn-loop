package com.learnloop.auth;

/**
 * 客户端发送写请求所需的 CSRF 信息。
 *
 * headerName：请求头名称。
 * token：对应的请求头值。
 */
public record CsrfResponse(
        String headerName,
        String token
) {
}