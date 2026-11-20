package com.learnloop.learning;

import com.learnloop.auth.LoginUser;
import com.learnloop.generation.GenerationException;
import com.learnloop.modelsettings.ModelCredentialException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 由现有 Session/CSRF 安全链保护；GET 只读解析，POST 才触发用户自己的模型凭据。 */
@RestController
@RequestMapping("/api/learning/explanations")
public class AnswerExplanationController {
    private final AnswerExplanationService service;
    public record Error(String errorCode,String message) {}
    public AnswerExplanationController(AnswerExplanationService service) {this.service=service;}

    @GetMapping("/{kind}/{id}")
    public ResponseEntity<AnswerExplanationService.Brief> brief(@AuthenticationPrincipal LoginUser user,
            @PathVariable String kind,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.brief(user.getUserId(),kind,id));
    }
    @PostMapping("/{kind}/{id}")
    public ResponseEntity<AnswerExplanationService.Detailed> detailed(@AuthenticationPrincipal LoginUser user,
            @PathVariable String kind,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detailed(user.getUserId(),kind,id));
    }
    @ExceptionHandler(LearningException.class)
    ResponseEntity<Error> learning(LearningException ex) {return failure(ex.status(),ex.code(),ex.getMessage());}
    @ExceptionHandler(ModelCredentialException.class)
    ResponseEntity<Error> credential(ModelCredentialException ex) {return failure(ex.status(),ex.code(),ex.getMessage());}
    @ExceptionHandler(GenerationException.class)
    ResponseEntity<Error> model(GenerationException ex) {
        return failure(ex.status(),ex.code(),"详解暂未完成，请检查模型配置或稍后重试。");
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> database() {return failure(503,"EXPLANATION_DATA_UNAVAILABLE","讲解暂时不可用，请稍后重试。");}
    private static ResponseEntity<Error> failure(int status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code,message));
    }
}
