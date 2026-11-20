package com.learnloop.config;

import com.learnloop.auth.DatabaseUserDetailsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Web 安全配置。
 *
 * 统一规定：
 * 哪些请求可以匿名访问；
 * 如何登录；
 * 如何保存登录状态；
 * 如何退出。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 仅回调端点使用短期能力令牌；在读取请求体前认证，不放宽任何浏览器 API 的 CSRF。 */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain tutorRetrievalChain(HttpSecurity http,
            com.learnloop.tutor.TutorRetrievalService retrieval) throws Exception {
        return http.securityMatcher("/internal/tutor/retrieve")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        "POST".equals(context.getRequest().getMethod())
                        && retrieval.authorized(context.getRequest().getHeader("X-LearnLoop-Tutor-Token")))))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req,res,error) -> res.setStatus(401)))
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DatabaseUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) throws Exception {

        /*
         * 认证提供者：
         * 使用我们编写的查询服务读取用户，
         * 使用原来的编码器校验 {bcrypt} 密码。
         */
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);

        http.authenticationProvider(provider);

        /*
         * 阶段 3.4：Session 安全上下文载入后立即检查新工作台的账号栅栏。
         * 该检查只对携带 X-LearnLoop-Expected-User 的写请求生效，
         * 旧网页不发这个头，行为保持不变。
         * 只作为安全链中的过滤器实例加入，避免被 Servlet 层重复注册。
         */
        http.addFilterAfter(new ExpectedUserContextFilter(), SecurityContextHolderFilter.class);

        http.authorizeHttpRequests(auth -> auth

                /*
                 * 8080 只保留前端迁移后的静态提示页；主界面由 Next.js 提供。
                 * 页面里的业务 API 仍然需要登录。
                 */
                .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/favicon.ico",
                        "/actuator/health",
                        "/api/auth/csrf"
                ).permitAll()

                /*
                 * 注册和登录允许匿名发起。
                 * permitAll 只表示不要求登录，
                 * 不表示可以跳过 CSRF 校验。
                 */
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/register",
                        "/api/auth/login"
                ).permitAll()

                // 允许错误处理，避免原本的错误被登录检查覆盖。
                .requestMatchers("/error").permitAll()

                /*
                 * 其他请求必须登录。
                 * /api/auth/me 也在这一规则之内。
                 */
                .anyRequest().authenticated()
        );

        /*
         * 保留默认 CSRF 防护。
         * POST 等写请求需要有效令牌。
         */
        http.csrf(Customizer.withDefaults());

        /*
         * 按需要创建会话。
         * 登录成功后，框架通过 HttpSession 保存认证状态。
         */
        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
        );

        /*
         * API 请求直接返回状态码。
         * 不保存一个等待登录后自动跳转的请求地址。
         */
        http.requestCache(cache -> cache.disable());

        /*
         * 配置表单登录过滤器。
         *
         * POST /api/auth/login 由 Spring Security 处理，
         * 不需要再写一个同路径的 Controller 方法。
         */
        http.formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")

                // 登录成功：204，响应没有 JSON 正文。
                .successHandler((request, response, authentication) ->
                        response.setStatus(
                                HttpServletResponse.SC_NO_CONTENT
                        )
                )

                // 用户名或密码错误：统一返回 401。
                .failureHandler((request, response, exception) ->
                        response.setStatus(
                                HttpServletResponse.SC_UNAUTHORIZED
                        )
                )

                .permitAll()
        );

        // 未登录访问受保护接口时返回 401。
        http.exceptionHandling(exception -> exception
                .authenticationEntryPoint(
                        (request, response, authException) ->
                                response.setStatus(
                                        HttpServletResponse.SC_UNAUTHORIZED
                                )
                )
        );

        http.logout(logout -> logout
                .logoutUrl("/api/auth/logout")

                // 退出时使服务端会话失效并清除认证状态。
                .invalidateHttpSession(true)
                .clearAuthentication(true)

                // 同时要求客户端删除会话 Cookie。
                .deleteCookies("JSESSIONID")

                // 退出成功同样返回 204，没有 JSON 正文。
                .logoutSuccessHandler(
                        (request, response, authentication) ->
                                response.setStatus(
                                        HttpServletResponse.SC_NO_CONTENT
                                )
                )

                .permitAll()
        );

        return http.build();
    }
}
