package com.learnloop.journal;

import static com.learnloop.journal.JournalApi.*;
import com.learnloop.auth.LoginUser;
import com.learnloop.material.MaterialException;
import com.learnloop.modelsettings.ModelCredentialException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class JournalController {
    private final JournalService service;

    public JournalController(JournalService service) { this.service = service; }

    @GetMapping("/api/notes")
    public Notes notes(@AuthenticationPrincipal LoginUser user, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "20") int size) {
        return service.notes(user.getUserId(), page, q, size);
    }

    @GetMapping("/api/notes/{version}")
    public Map<String, Note> note(@AuthenticationPrincipal LoginUser user, @PathVariable String version) {
        return java.util.Collections.singletonMap("note", service.note(user.getUserId(), version));
    }

    @PutMapping("/api/notes/{version}")
    public Note saveNote(@AuthenticationPrincipal LoginUser user, @PathVariable String version, @RequestBody NoteEdit edit) {
        return service.saveNote(user.getUserId(), version, edit);
    }

    @DeleteMapping("/api/notes/{version}")
    public ResponseEntity<Void> deleteNote(@AuthenticationPrincipal LoginUser user, @PathVariable String version,
                                           @RequestParam String revision) {
        service.deleteNote(user.getUserId(), version, revision);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/notes/reading/{id}")
    public Note updateReadingNote(@AuthenticationPrincipal LoginUser user, @PathVariable String id, @RequestBody NoteEdit edit) {
        return service.updateReadingNote(user.getUserId(), id, edit);
    }

    @DeleteMapping("/api/notes/reading/{id}")
    public ResponseEntity<Void> deleteReadingNote(@AuthenticationPrincipal LoginUser user, @PathVariable String id,
                                                  @RequestParam String revision) {
        service.deleteReadingNote(user.getUserId(), id, revision);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/reading/{material}")
    public Reader reader(@AuthenticationPrincipal LoginUser user, @PathVariable long material,
                         @RequestParam(defaultValue = "1") int page) {
        return service.reader(user.getUserId(), material, page);
    }

    @PostMapping("/api/reading/{material}/ask")
    public ResponseEntity<Draft> ask(@AuthenticationPrincipal LoginUser user, @PathVariable long material, @RequestBody Ask ask) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.ask(user.getUserId(), material, ask));
    }

    @PostMapping("/api/reading/{material}/highlights")
    public Highlight save(@AuthenticationPrincipal LoginUser user, @PathVariable long material, @RequestBody SaveHighlight save) {
        return service.saveHighlight(user.getUserId(), material, save);
    }

    @GetMapping("/api/reading/{material}/highlights/{id}/study")
    public StudyContext study(@AuthenticationPrincipal LoginUser user, @PathVariable long material, @PathVariable String id) {
        return service.study(user.getUserId(), material, id);
    }

    @PutMapping("/api/reading/{material}/highlights/{id}/study")
    public Highlight saveStudy(@AuthenticationPrincipal LoginUser user, @PathVariable long material,
                               @PathVariable String id, @RequestBody StudyEdit edit) {
        return service.saveStudy(user.getUserId(), material, id, edit);
    }

    @DeleteMapping("/api/reading/{material}/highlights/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal LoginUser user, @PathVariable long material, @PathVariable String id) {
        service.deleteHighlight(user.getUserId(), material, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(JournalException.class)
    ResponseEntity<Map<String, String>> failure(JournalException ex) { return error(ex.status, ex.code, ex.getMessage()); }

    @ExceptionHandler(ModelCredentialException.class)
    ResponseEntity<Map<String, String>> credentials(ModelCredentialException ex) { return error(ex.status(), ex.code(), ex.getMessage()); }

    @ExceptionHandler(MaterialException.class)
    ResponseEntity<Map<String, String>> material(MaterialException ex) { return error(ex.getStatus(), ex.getErrorCode(), ex.getMessage()); }

    @ExceptionHandler(BadSqlGrammarException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return error(503, "JOURNAL_DATABASE_UNAVAILABLE", "笔记与高亮数据库尚未就绪，请检查本机迁移。");
    }

    private ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("errorCode", code, "message", message));
    }
}
