package com.learnloop.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表的数据访问接口。
 *
 * Mapper 负责执行 SQL。
 * 用户名格式、密码规则等业务检查放在 Service 中。
 */
@Mapper
public interface UserMapper {

    /**
     * 查询指定用户名已经存在的数量。
     *
     * 由于 username 有唯一约束，正常情况下结果只有 0 或 1。
     * #{username} 使用参数绑定，不把输入直接拼进 SQL。
     */
    @Select("""
            SELECT COUNT(*)
            FROM users
            WHERE username = #{username}
            """)
    long countByUsername(@Param("username") String username);

    /**
     * 新增用户。
     *
     * id 由 AUTO_INCREMENT 生成。
     * created_at 由数据库默认值生成。
     * 因此 INSERT 只填写 username 和 password_hash。
     */
    @Insert("""
            INSERT INTO users (
                username,
                password_hash
            )
            VALUES (
                #{username},
                #{passwordHash}
            )
            """)
    @Options(
            useGeneratedKeys = true,
            keyProperty = "id"
    )
    int insert(UserAccount user);
    /**
     * 按用户名查询登录需要的用户信息。
     *
     * 密码散列只在服务端参与校验，不直接返回给浏览器。
     * 用户不存在时，MyBatis 返回 null。
     */
    @Select("""
        SELECT
            id,
            username,
            password_hash AS passwordHash
        FROM users
        WHERE username = #{username}
        """)
    UserAccount findByUsername(@Param("username") String username);
}