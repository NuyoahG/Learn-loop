package com.learnloop.auth;

import com.learnloop.user.UserAccount;
import com.learnloop.user.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 连接 Spring Security 与项目数据库。
 *
 * 框架需要按用户名查询账号时，会调用
 * loadUserByUsername 方法。
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public DatabaseUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        // 1. 从数据库读取用户和密码散列。
        UserAccount account = userMapper.findByUsername(username);

        // 2. 不存在时，通知认证框架登录失败。
        if (account == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        /*
         * 3. 转换成框架使用的登录用户对象。
         *
         * 这里不手动比较密码。
         * 框架会通过配置的 PasswordEncoder 进行比较。
         */
        return new LoginUser(
                account.getId(),
                account.getUsername(),
                account.getPasswordHash()
        );
    }
}