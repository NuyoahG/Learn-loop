package com.learnloop.auth;

import com.learnloop.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 原来的注册接口。
     *
     * 接入安全配置后，注册也需要先取得 CSRF 令牌，
     * 再通过请求头携带它。
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @RequestBody RegisterRequest request
    ) {
        RegisterResponse response = userService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 获取当前会话可使用的 CSRF 令牌。
     *
     * CsrfToken 由框架提供。
     * 调用 getToken 会取得实际可提交的令牌，
     * 并触发需要的令牌生成和保存。
     */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getToken()
        );
    }

    /**
     * 查询当前登录用户。
     *
     * @AuthenticationPrincipal 从认证上下文中取得身份，
     * 不接受客户端自行提交 userId。
     *
     * 未登录的请求会在进入本方法之前被安全配置拒绝。
     */
    @GetMapping("/me")
    public CurrentUserResponse me(
            @AuthenticationPrincipal LoginUser currentUser
    ) {
        return new CurrentUserResponse(
                currentUser.getUserId(),
                currentUser.getUsername()
        );
    }
}