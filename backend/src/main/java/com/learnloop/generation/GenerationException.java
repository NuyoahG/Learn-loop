package com.learnloop.generation;

/** 仅暴露稳定错误码和面向用户的信息；禁止包含第三方原始响应、SQL 或密钥。 */
public class GenerationException extends RuntimeException {
    private final int status;
    private final String code;
    public GenerationException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
