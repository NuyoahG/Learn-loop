package com.learnloop.tutor;

import org.springframework.web.bind.annotation.*;

@RestController
public class TutorRetrievalController {
    private final TutorRetrievalService retrieval;
    TutorRetrievalController(TutorRetrievalService retrieval) { this.retrieval=retrieval; }

    @PostMapping("/internal/tutor/retrieve")
    TutorRetrievalService.Result read(@RequestHeader(value="X-LearnLoop-Tutor-Token",required=false) String token,
                                      @RequestBody TutorRetrievalService.Query query) {
        return retrieval.retrieve(token,query);
    }
}
