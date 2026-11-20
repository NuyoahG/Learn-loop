-- 草案、确认后的练习和进度共用同一主键，确认重试不会创建第二份练习。
CREATE TABLE IF NOT EXISTS tutor_plans (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    minutes INT NOT NULL,
    reason VARCHAR(240) NOT NULL,
    planner VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace JSON NOT NULL,
    items JSON NOT NULL,
    position INT NOT NULL DEFAULT 0,
    active_attempt CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    open_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('DRAFT','ACTIVE') THEN user_id END) STORED,
    UNIQUE KEY uk_tutor_open_plan (open_user_id),
    KEY idx_tutor_plans_owner (user_id, created_at, id),
    CONSTRAINT fk_tutor_plan_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tutor_plan_status CHECK (status IN ('DRAFT','ACTIVE','COMPLETED','CANCELLED')),
    CONSTRAINT ck_tutor_plan_progress CHECK (position BETWEEN 0 AND 5 AND minutes BETWEEN 4 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
SELECT 'TUTOR_PLANS_SCHEMA_READY';
