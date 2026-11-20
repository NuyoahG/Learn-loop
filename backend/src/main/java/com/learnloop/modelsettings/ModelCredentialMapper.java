package com.learnloop.modelsettings;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** 所有查询都显式带当前登录用户 ID；表中只保存认证密文和 nonce。 */
@Mapper
public interface ModelCredentialMapper {
    /** 密文行也禁止自动打印字段；该类型不作为任何 Controller 返回值。 */
    record Stored(long userId, String cipherFormat, byte[] nonce, byte[] ciphertext,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        @Override public String toString() { return "Stored[userId=" + userId + ", credential=REDACTED]"; }
    }

    @Select("SELECT user_id,cipher_format,nonce,ciphertext,created_at,updated_at FROM model_credentials WHERE user_id=#{user}")
    Stored find(long user);

    @Insert("""
        INSERT INTO model_credentials(user_id,cipher_format,nonce,ciphertext,created_at,updated_at)
        VALUES(#{user},#{format},#{nonce},#{ciphertext},#{now},#{now})
        ON DUPLICATE KEY UPDATE cipher_format=#{format},nonce=#{nonce},ciphertext=#{ciphertext},updated_at=#{now}
        """)
    void save(long user, String format, byte[] nonce, byte[] ciphertext, LocalDateTime now);

    @Delete("DELETE FROM model_credentials WHERE user_id=#{user}")
    int delete(long user);
}
