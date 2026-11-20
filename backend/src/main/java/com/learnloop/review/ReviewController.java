package com.learnloop.review;

import com.learnloop.auth.LoginUser;
import java.util.Map;
import java.time.LocalDateTime;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/review")
public class ReviewController {
    record Queue(ReviewMapper.Due card) {}
    record Selection(String topicId, String versionId, String selected, LocalDateTime dueAt) {}
    record Rating(int rating) {}

    private final ReviewService service;
    ReviewController(ReviewService service) { this.service = service; }

    @GetMapping("/today")
    public Queue today(@AuthenticationPrincipal LoginUser user) { return new Queue(service.today(user.getUserId())); }

    @PostMapping("/answer")
    public ReviewService.Answer answer(@AuthenticationPrincipal LoginUser user, @RequestBody Selection selection) {
        return service.answer(user.getUserId(), selection.topicId(), selection.versionId(), selection.selected(), selection.dueAt());
    }

    @PostMapping("/{attemptId}/rating")
    public ReviewService.Result rate(@AuthenticationPrincipal LoginUser user, @PathVariable String attemptId, @RequestBody Rating rating) {
        return service.rate(user.getUserId(), attemptId, rating.rating());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException problem) {
        return ResponseEntity.status(problem.getStatusCode()).body(Map.of("errorCode", "REVIEW_REQUEST_FAILED",
                "message", problem.getReason()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> database() {
        return ResponseEntity.status(503).body(Map.of("errorCode", "REVIEW_DATABASE_UNAVAILABLE", "message", "复习数据暂不可用。"));
    }
}
