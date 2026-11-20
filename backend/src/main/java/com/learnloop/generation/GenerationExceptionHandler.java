package com.learnloop.generation;
//资料生成异常处理
import com.learnloop.modelsettings.ModelCredentialException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 仅拦截出题接口，旧业务错误契约保持独立；失败消息不得包含 SQL、第三方正文和密钥。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GenerationController.class)//限定作用范围
public class GenerationExceptionHandler {
    public record Error(String errorCode, String message) { }
    /**
     * 创建任务时也会检查个人密钥；保持与设置页相同的安全错误码，
     * 不能让凭据缺失退化成框架 500 或带出内部异常信息。
     */
    @ExceptionHandler(ModelCredentialException.class)
    public ResponseEntity<Error> credential(ModelCredentialException ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler(GenerationException.class)//只处理自定义业务异常
    public ResponseEntity<Error> business(GenerationException ex) { return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage())); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> invalid(Exception ex) { return ResponseEntity.badRequest().body(new Error("INVALID_GENERATION_REQUEST", "出题请求格式或参数无效。")); }
    @ExceptionHandler(DataAccessException.class)//数据库异常处理器
    public ResponseEntity<Error> database(DataAccessException ex) { return ResponseEntity.status(503).body(new Error("GENERATION_DATABASE_UNAVAILABLE", "出题数据暂时无法保存，请检查迁移或稍后重试。")); }
}
