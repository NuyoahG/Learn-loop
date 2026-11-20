package com.learnloop.learning;

import com.learnloop.auth.LoginUser;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.learnloop.learning.LearningApi.*;

/**
 * 正式学习业务只经 Java Session 入口访问；现有 SecurityConfig 统一要求登录和 CSRF。
 * 与 /demo/answers 实验接口不同，这里绝不接受浏览器声明 userId 或 correct。
 */
@RestController
@RequestMapping("/api/learning")
public class LearningController {
    private final LearningService service;
    public LearningController(LearningService service) { this.service = service; }

    @PostMapping("/concepts")
    public ConceptView createConcept(@AuthenticationPrincipal LoginUser user, @RequestBody CreateConcept request) {
        return service.createConcept(user.getUserId(), request);
    }
    @GetMapping("/concepts")
    public List<ConceptView> concepts(@AuthenticationPrincipal LoginUser user, @RequestParam long materialId) {
        return service.concepts(user.getUserId(), materialId);
    }
    @PostMapping("/questions/{questionId}/binding")
    public QuestionView bind(@AuthenticationPrincipal LoginUser user, @PathVariable long questionId, @RequestBody BindQuestion request) {
        return service.bindQuestion(user.getUserId(), questionId, request);
    }
    @GetMapping("/questions")
    public List<QuestionView> questions(@AuthenticationPrincipal LoginUser user, @RequestParam long materialId) {
        return service.questions(user.getUserId(), materialId);
    }
    @GetMapping("/question-library")
    public LibraryPage questionLibrary(@AuthenticationPrincipal LoginUser user,
            @RequestParam(name = "materialId", required = false) Long materialId,
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.library(user.getUserId(), materialId, status, page, size);
    }
    @PostMapping("/attempts")
    public AttemptView createAttempt(@AuthenticationPrincipal LoginUser user, @RequestBody CreateAttempt request) {
        return service.createAttempt(user.getUserId(), request);
    }
    @GetMapping("/attempts/{attemptId}")
    public AttemptView attempt(@AuthenticationPrincipal LoginUser user, @PathVariable String attemptId) {
        return service.getAttempt(user.getUserId(), attemptId);
    }
    @PostMapping("/attempts/{attemptId}/reveal")
    public Map<String, String> reveal(@AuthenticationPrincipal LoginUser user, @PathVariable String attemptId) {
        return service.reveal(user.getUserId(), attemptId);
    }
    @PostMapping("/attempts/{attemptId}/answers")
    public SubmissionView submit(@AuthenticationPrincipal LoginUser user, @PathVariable String attemptId, @RequestBody Submit request) {
        return service.submit(user.getUserId(), attemptId, request);
    }
    @GetMapping("/mastery")
    public List<MasteryView> mastery(@AuthenticationPrincipal LoginUser user, @RequestParam long materialId) {
        return service.states(user.getUserId(), materialId);
    }
    @GetMapping("/recommendations")
    public Selection recommendations(@AuthenticationPrincipal LoginUser user, @RequestParam long materialId,
                                     @RequestParam(defaultValue = "3") int size) {
        return service.select(user.getUserId(), materialId, size);
    }
    @GetMapping("/events/{eventId}")
    public EventView event(@AuthenticationPrincipal LoginUser user, @PathVariable String eventId) {
        return service.event(user.getUserId(), eventId);
    }
    @PostMapping("/events/{eventId}/retry")
    public EventView retry(@AuthenticationPrincipal LoginUser user, @PathVariable String eventId) {
        return service.retry(user.getUserId(), eventId);
    }
}
