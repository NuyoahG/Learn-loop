package com.learnloop.material;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 资料专用错误契约。全局 Advice 也能捕获 Controller 解析前的 multipart 超限；
 * 对非资料接口继续抛出原异常，保持现有登录/答题处理不变。
 */
@RestControllerAdvice
public class MaterialExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MaterialExceptionHandler.class);
    public record ErrorResponse(String errorCode, String message) {}
    private boolean isMaterial(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/materials") || path.startsWith("/api/materials/");
    }
    @ExceptionHandler(MaterialException.class)
    public ResponseEntity<ErrorResponse> business(MaterialException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getErrorCode(), ex.getMessage()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> size(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        if (!isMaterial(request)) throw ex;
        return ResponseEntity.status(413).body(new ErrorResponse("FILE_TOO_LARGE", "上传文件或请求超过大小上限。"));
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> multipart(MultipartException ex, HttpServletRequest request) {
        if (!isMaterial(request)) throw ex;
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_UPLOAD_FORM", "上传表单格式无效。"));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parameter(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if (!isMaterial(request)) throw ex;
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PARAMETER", "资料编号和分页参数必须是有效整数。"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> database(DataAccessException ex, HttpServletRequest request) {
        if (!isMaterial(request)) throw ex;
        log.error("Material database operation failed; category={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(503).body(new ErrorResponse("DATABASE_UNAVAILABLE", "资料暂时无法保存或查询，请稍后重试。"));
    }
}
