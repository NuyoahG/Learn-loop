package com.learnloop.modelsettings;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 设置接口只返回固定安全错误；不回显原始请求、SQL、认证密文或底层异常。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ModelSettingsController.class)
public class ModelSettingsExceptionHandler {
    public record Error(String errorCode, String message) { }
    @ExceptionHandler(ModelCredentialException.class)
    public ResponseEntity<Error> business(ModelCredentialException ex) {
        return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore()).body(new Error(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Error> database(DataAccessException ex) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Error("MODEL_SETTINGS_DATABASE_UNAVAILABLE", "模型设置暂时不可用，请检查数据库迁移或稍后重试。"));
    }
}
