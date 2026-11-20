package com.learnloop.learning;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于学习 Controller，避免改变旧账号、资料和历史接口的错误契约。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = LearningController.class)
public class LearningExceptionHandler {
    public record Error(String errorCode, String message) { }
    @ExceptionHandler(LearningException.class)
    public ResponseEntity<Error> business(LearningException ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new Error("INVALID_LEARNING_REQUEST", "学习请求格式或参数无效。"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Error> database(DataAccessException ex) {
        // SQL、表结构与异常堆栈不对外显示，排查依赖稳定错误码与受控测试日志。
        return ResponseEntity.status(503).body(new Error("LEARNING_DATABASE_UNAVAILABLE", "学习数据暂时无法保存，请检查迁移或稍后重试。"));
    }
}
