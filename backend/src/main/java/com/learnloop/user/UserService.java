package com.learnloop.user;

import com.learnloop.auth.RegisterRequest;
import com.learnloop.auth.RegisterResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * 用户业务服务。
 *
 * 注册流程：
 * 校验输入 → 查重 → 编码密码 → 保存用户 → 返回公开信息。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 构造器注入。
     *
     * UserMapper 由 MyBatis 创建。
     * PasswordEncoder 来自 PasswordConfig 中的 @Bean。
     */
    public UserService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisterResponse register(RegisterRequest request) {

        // 1. 先判空，避免后面访问 request 时出现空指针异常。
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "注册信息不能为空"
            );
        }

        String username = request.username();
        String password = request.password();

        /*
         * 2. 校验用户名。
         *
         * 本版规则：
         * - 长度 3～32。
         * - 只允许英文字母、数字和下划线。
         * - 保留大小写，与当前数据库列规则一致。
         *
         * 先判断 null，再调用 matches。
         * || 会短路：前面成立时，不会执行后面的 matches。
         */
        if (username == null
                || !username.matches("[A-Za-z0-9_]{3,32}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "用户名应为3到32位英文字母、数字或下划线"
            );
        }

        // 3. 检查密码是否为空或全为空白。
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "密码不能为空"
            );
        }

        /*
         * 按 Unicode 码点计算长度。
         * 相比直接使用 length，对部分特殊字符的计数更合适。
         *
         * 密码不调用 trim 或 strip，
         * 避免悄悄改变用户真正输入的密码。
         */
        int passwordLength =
                password.codePointCount(0, password.length());

        if (passwordLength < 8 || passwordLength > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "密码长度应为8到64个字符"
            );
        }

        /*
         * 当前使用的 bcrypt 对输入有 72 字节限制。
         * 一个中文字符在 UTF-8 中通常占多个字节，
         * 因此字符长度通过后，还要单独检查字节长度。
         */
        int passwordBytes =
                password.getBytes(StandardCharsets.UTF_8).length;

        if (passwordBytes > 72) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "密码内容过长，请缩短后重试"
            );
        }

        // 4. 先查重，为普通的重复注册提供明确反馈。
        if (userMapper.countByUsername(username) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "用户名已存在"
            );
        }

        // 5. 创建用户对象，只放入编码后的密码。
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));

        /*
         * 6. 写入数据库。
         *
         * 先查重仍然不能解决并发问题：
         * 两个请求可能同时查到“用户名不存在”。
         *
         * 数据库唯一约束负责最终拦截重复，
         * DuplicateKeyException 用于处理这次拦截。
         */
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "用户名已存在"
            );
        }

        /*
         * 其他数据库错误不在这里伪装成“用户名重复”。
         * 例如连接失败，应作为服务端故障处理。
         *
         * insert 成功后，MyBatis 已经回填 user.id。
         * 只返回公开信息，不返回整个 UserAccount。
         */
        return new RegisterResponse(
                user.getId(),
                user.getUsername()
        );
    }
}