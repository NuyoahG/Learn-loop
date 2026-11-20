-- 错因是独立的自报反馈。同一条答题记录只保留当前反馈。
CREATE TABLE IF NOT EXISTS tutor_feedback (
    user_id BIGINT NOT NULL,
    attempt_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail VARCHAR(300) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, attempt_kind, attempt_id),
    KEY idx_tutor_feedback_recent (user_id, updated_at),
    CONSTRAINT fk_tutor_feedback_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_tutor_feedback_kind CHECK (attempt_kind IN ('PRACTICE','REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS tutor_suggestion_state (
    user_id BIGINT NOT NULL,
    suggestion_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, suggestion_key),
    CONSTRAINT fk_tutor_suggestion_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
SELECT 'TUTOR_SCHEMA_READY';
