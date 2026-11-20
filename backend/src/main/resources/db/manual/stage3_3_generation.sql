-- 阶段 3.3：资料出题持久任务。先完成 stage3_2_learning.sql，再人工选择 LearnLoop 数据库执行。
-- 不覆盖旧表、不自动迁移；来源快照含正文，只允许 Java 内部访问，公开 DTO 不回传 snapshot_json。
CREATE TABLE generation_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_count INT NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    granularity VARCHAR(8) NOT NULL,
    snapshot_json JSON NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    model VARCHAR(200) NULL,
    shortfall_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    -- NULL 不参与唯一冲突；同一用户最多一个 PENDING/PROCESSING，数据库兜住并发创建。
    active_user BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING') THEN user_id ELSE NULL END) STORED,
    UNIQUE KEY uk_generation_request(user_id,request_key),
    UNIQUE KEY uk_generation_active(active_user),
    KEY ix_generation_pending(status,created_at),
    KEY ix_generation_lease(status,lease_until),
    CONSTRAINT fk_generation_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT fk_generation_material FOREIGN KEY(material_id) REFERENCES materials(id),
    CONSTRAINT ck_generation_status CHECK(status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_generation_count CHECK(requested_count IN (3,5,10)),
    CONSTRAINT ck_generation_difficulty CHECK(difficulty IN ('EASY','MEDIUM','HARD')),
    CONSTRAINT ck_generation_granularity CHECK(granularity IN ('COARSE','MEDIUM','FINE')),
    CONSTRAINT ck_generation_claim CHECK((status='PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE generation_job_items (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_no INT NOT NULL,
    user_id BIGINT NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_id BIGINT NOT NULL,
    source_quote TEXT NOT NULL,
    source_locator JSON NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    PRIMARY KEY(job_id,item_no),
    UNIQUE KEY uk_generation_question(question_version_id),
    KEY ix_generation_items_owner(user_id,job_id),
    CONSTRAINT fk_generation_item_job FOREIGN KEY(job_id) REFERENCES generation_jobs(id),
    CONSTRAINT fk_generation_item_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT fk_generation_item_question FOREIGN KEY(question_version_id) REFERENCES learning_questions(id),
    CONSTRAINT ck_generation_item_no CHECK(item_no BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
