package com.learnloop.user;

/**
 * 用户数据对象。
 *
 * 本节映射注册需要使用的三个字段。
 * created_at 由数据库默认值生成，因此插入时不用设置。
 * 这与你之前 AnswerRecord 接收自增编号的做法相似。
 */
public class UserAccount {

    // 插入之前为空；插入成功后，由 MyBatis 回填数据库生成的编号。
    private Long id;

    // 已经通过格式校验的用户名。
    private String username;

    // 只保存编码后的完整散列字符串。
    private String passwordHash;

    public Long getId() {
        return id;
    }

    /**
     * MyBatis 使用这个方法回填自增编号。
     */
    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}