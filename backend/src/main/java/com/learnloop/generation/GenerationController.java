package com.learnloop.generation;

import com.learnloop.auth.LoginUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.learnloop.generation.GenerationApi.*;

/** 上传和阅读不会触发出题；只有用户明确 POST 此入口才创建收费模型任务。认证与 CSRF 沿用 Java 主站。 */
@RestController
@RequestMapping("/api/generation/jobs")
public class GenerationController {
    private final GenerationService service;
    public GenerationController(GenerationService service) { this.service = service; }
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView create(@AuthenticationPrincipal LoginUser user, @RequestBody CreateJob request) { return service.create(user.getUserId(), request); }
    /** 用户对一道已有题明确点击“同考点再出一题”时才进入此收费入口。 */
    @PostMapping("/variants")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView createVariant(@AuthenticationPrincipal LoginUser user, @RequestBody CreateVariant request) {
        return service.createVariant(user.getUserId(), request);
    }
    @GetMapping("/{jobId}")
    public JobView get(@AuthenticationPrincipal LoginUser user, @PathVariable String jobId) { return service.get(user.getUserId(), jobId); }
    @GetMapping("/knowledge-groups")
    public List<AdaptiveCatalog.GroupView> groups(@AuthenticationPrincipal LoginUser user, @RequestParam long materialId) {
        return service.groups(user.getUserId(), materialId);
    }
    @PostMapping("/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobView retry(@AuthenticationPrincipal LoginUser user, @PathVariable String jobId) { return service.retry(user.getUserId(), jobId); }
}
