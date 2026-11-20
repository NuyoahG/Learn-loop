package com.learnloop.tutor;

import com.learnloop.auth.LoginUser;
import com.learnloop.learning.LearningException;
import com.learnloop.tutor.TutorMapper.Feedback;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tutor")
public class TutorController {
    private final TutorService tutor;
    private final TutorPlanService plans;
    private final TutorCoachService coach;
    TutorController(TutorService tutor, TutorPlanService plans, TutorCoachService coach) {
        this.tutor = tutor;
        this.plans = plans;
        this.coach = coach;
    }

    @PostMapping("/plans")
    TutorPlanService.View draft(@AuthenticationPrincipal LoginUser user, @RequestBody TutorPlanService.Input input) {
        return plans.draft(user.getUserId(), input);
    }

    @GetMapping("/plans/latest")
    ResponseEntity<TutorPlanService.View> latest(@AuthenticationPrincipal LoginUser user) {
        var view = plans.latest(user.getUserId());
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @PostMapping("/plans/{id}/confirm")
    TutorPlanService.View confirm(@AuthenticationPrincipal LoginUser user, @PathVariable String id) {
        return plans.confirm(user.getUserId(), id);
    }

    @PostMapping("/plans/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@AuthenticationPrincipal LoginUser user, @PathVariable String id) {
        plans.cancel(user.getUserId(), id);
    }

    @PostMapping("/plans/{id}/open")
    TutorPlanService.Step open(@AuthenticationPrincipal LoginUser user, @PathVariable String id) {
        return plans.open(user.getUserId(), id);
    }

    @PostMapping("/plans/{id}/advance")
    TutorPlanService.View advance(@AuthenticationPrincipal LoginUser user, @PathVariable String id,
                                  @RequestBody TutorPlanService.Advance input) {
        return plans.advance(user.getUserId(), id, input);
    }

    @ExceptionHandler(LearningException.class)
    ResponseEntity<Map<String, String>> learning(LearningException problem) {
        return ResponseEntity.status(problem.status()).body(Map.of("errorCode", problem.code(), "message", problem.getMessage()));
    }

    @GetMapping("/feedback/{kind}/{id}")
    ResponseEntity<Feedback> feedback(@AuthenticationPrincipal LoginUser user, @PathVariable String kind,
                                      @PathVariable String id) {
        Feedback feedback = tutor.feedback(user.getUserId(), kind, id);
        return feedback == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(feedback);
    }

    @PutMapping("/feedback/{kind}/{id}")
    Feedback save(@AuthenticationPrincipal LoginUser user, @PathVariable String kind, @PathVariable String id,
                  @RequestBody Feedback feedback) {
        return tutor.save(user.getUserId(), kind, id, feedback);
    }

    @GetMapping("/suggestion")
    ResponseEntity<TutorService.Suggestion> suggestion(@AuthenticationPrincipal LoginUser user) {
        var suggestion = tutor.suggestion(user.getUserId());
        return suggestion == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(suggestion);
    }

    @PutMapping("/suggestion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void respond(@AuthenticationPrincipal LoginUser user, @RequestBody TutorService.Response response) {
        tutor.respond(user.getUserId(), response);
    }

    @PostMapping("/chat")
    TutorCoachService.Reply chat(@AuthenticationPrincipal LoginUser user, @RequestBody TutorCoachService.Chat chat) {
        return coach.chat(user.getUserId(), chat);
    }

    @GetMapping("/lessons")
    java.util.List<TutorCoachService.Reply> lessons(@AuthenticationPrincipal LoginUser user,
                                                  @RequestParam(required=false) Long materialId) {
        return coach.recent(user.getUserId(), materialId);
    }

    @PutMapping("/lessons/{id}/feedback")
    TutorCoachService.Reply lessonFeedback(@AuthenticationPrincipal LoginUser user,@PathVariable String id,
                                           @RequestBody TutorCoachService.Feedback input) {
        return coach.feedback(user.getUserId(),id,input);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> failure(ResponseStatusException problem) {
        return ResponseEntity.status(problem.getStatusCode())
                .body(Map.of("errorCode", "TUTOR_REQUEST_FAILED", "message", problem.getReason()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("errorCode", "TUTOR_DATA_UNAVAILABLE", "message", "辅导记录暂不可用。"));
    }
}
