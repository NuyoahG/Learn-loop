package com.learnloop.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * Spring Security 使用的登录用户对象。
 *
 * 继承框架的 User，复用用户名、密码校验信息、
 * 权限和认证后凭据清理等能力。
 *
 * 另外保留数据库中的 userId，
 * 后续可以用它查询“当前用户自己的数据”。
 */
public class LoginUser extends User {

    private static final long serialVersionUID = 1L;

    private final Long userId;

    public LoginUser(
            Long userId,
            String username,
            String passwordHash
    ) {
        /*
         * 将账号信息交给父类。
         *
         * 当前项目只有普通用户这一种角色。
         * 这里不扩展管理员或复杂权限体系。
         */
        super(
                username,
                passwordHash,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}