package com.learnloop.journal;

import static com.learnloop.journal.PdfAnnotationApi.*;
import com.learnloop.auth.LoginUser;
import com.learnloop.material.MaterialException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reading/{material}/annotations")
public class PdfAnnotationController {
    private final PdfAnnotationService service;
    public PdfAnnotationController(PdfAnnotationService service) { this.service = service; }

    @GetMapping
    public Annotations list(@AuthenticationPrincipal LoginUser user, @PathVariable long material) {
        return service.list(user.getUserId(), material);
    }
    @PostMapping
    public Annotation create(@AuthenticationPrincipal LoginUser user, @PathVariable long material,
                             @RequestBody Create request) {
        return service.create(user.getUserId(), material, request);
    }
    @PutMapping("/{id}")
    public Annotation update(@AuthenticationPrincipal LoginUser user, @PathVariable long material,
                             @PathVariable String id, @RequestBody Edit request) {
        return service.update(user.getUserId(), material, id, request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal LoginUser user, @PathVariable long material,
                                       @PathVariable String id, @RequestParam String revision) {
        service.delete(user.getUserId(), material, id, revision);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(JournalException.class)
    ResponseEntity<Map<String, String>> failure(JournalException ex) { return error(ex.status, ex.code, ex.getMessage()); }
    @ExceptionHandler(MaterialException.class)
    ResponseEntity<Map<String, String>> material(MaterialException ex) { return error(ex.getStatus(), ex.getErrorCode(), ex.getMessage()); }
    @ExceptionHandler(BadSqlGrammarException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return error(503, "PDF_ANNOTATIONS_DATABASE_UNAVAILABLE", "PDF 标注尚未就绪，请执行标注数据库迁移。");
    }
    private ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("errorCode", code, "message", message));
    }
}
