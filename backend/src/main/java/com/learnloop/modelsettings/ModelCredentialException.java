package com.learnloop.modelsettings;

/** 仅保存预定义安全信息；异常原因链不携带密钥、密文或第三方响应。 */
public final class ModelCredentialException extends RuntimeException {
    private final int status;
    private final String code;
    public ModelCredentialException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
