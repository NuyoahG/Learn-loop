package com.learnloop.learning;

/**
 * 学习闭环的稳定业务错误：网页只得到可理解的错误码和说明。
 * 不把数据库异常、内部地址、服务密钥或第三方响应正文返回给浏览器。
 */
public final class LearningException extends RuntimeException {
    private final int status;
    private final String code;

    public LearningException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
