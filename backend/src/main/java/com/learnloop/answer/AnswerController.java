package com.learnloop.answer;

import com.learnloop.auth.LoginUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/answers")
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping
    public ResponseEntity<SubmitAnswerResponse> submit(
            @AuthenticationPrincipal LoginUser currentUser,
            @RequestBody SubmitAnswerRequest request
    ) {
        // 身份由认证上下文提供，题号和选项由 JSON 提供。
        SubmitAnswerResponse response = answerService.submit(
                currentUser.getUserId(),
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnswerRecordDetail> getById(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable("id") Long recordId
    ) {
        AnswerRecordDetail result = answerService.getById(
                currentUser.getUserId(),
                recordId
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<AnswerPageResponse> getPage(
            @AuthenticationPrincipal LoginUser currentUser,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "correct", required = false) Boolean correct,
            @RequestParam(name = "sort", defaultValue = "desc") String sort
    ) {
        AnswerPageResponse result = answerService.getPage(
                currentUser.getUserId(),
                page,
                size,
                correct,
                sort
        );

        return ResponseEntity.ok(result);
    }
}
