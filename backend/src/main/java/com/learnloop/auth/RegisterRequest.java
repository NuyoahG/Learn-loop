package com.learnloop.auth;

/**
 * 注册请求。
 *
 * Spring 会把请求 JSON 中的 username、password
 * 转换成这个对象。
 *
 * 这个对象包含原始密码，只用于接收和处理请求，
 * 不直接返回给浏览器，也不打印到日志。
 */
public record RegisterRequest(
        String username,
        String password
) {
}