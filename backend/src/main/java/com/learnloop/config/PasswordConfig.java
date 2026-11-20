package com.learnloop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码处理配置。
 *
 * 注册时使用 encode 生成密码散列。
 * 后续登录时使用 matches 校验密码。
 */
@Configuration
public class PasswordConfig {

    /**
     * @Bean 表示把返回的对象交给 Spring 管理。
     *
     * UserService 需要 PasswordEncoder 时，
     * Spring 会把这个对象注入进去。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {

        /*
         * 当前默认使用 bcrypt 生成密码散列。
         *
         * 保存的字符串包含算法标识，例如：
         * {bcrypt}$2a$...
         *
         * 必须完整保存这个字符串，后续校验密码时会使用它。
         * 随机盐由组件处理，不需要自己生成或单独建字段保存。
         */
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}