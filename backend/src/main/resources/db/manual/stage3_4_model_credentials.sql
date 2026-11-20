-- 阶段 3.4：网页按用户配置 DeepSeek API Key。人工确认数据库后仅执行一次。
-- 先完成基础 users 表；不覆盖旧数据、不删除表、不自动生成服务器主密钥。
-- 主密钥只由 Java 进程环境 LEARNLOOP_MODEL_MASTER_KEY 注入，绝不保存于本表。
CREATE TABLE model_credentials (
    user_id BIGINT NOT NULL PRIMARY KEY,
    cipher_format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- GCM 每次加密新建 12 字节随机 nonce；密文含 16 字节认证标签。
    nonce BINARY(12) NOT NULL,
    ciphertext VARBINARY(528) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_model_credentials_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT ck_model_credentials_format CHECK(cipher_format='AES256_GCM_V1'),
    CONSTRAINT ck_model_credentials_size CHECK(OCTET_LENGTH(ciphertext) BETWEEN 32 AND 528)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
