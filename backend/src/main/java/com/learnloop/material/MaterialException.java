package com.learnloop.material;

/** 可以安全返回给用户的业务错误；message 不含磁盘路径、SQL 或原始异常。 */
public class MaterialException extends RuntimeException {
    private final int status;
    private final String errorCode;
    public MaterialException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
