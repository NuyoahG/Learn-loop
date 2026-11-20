-- 仅新增个人笔记与高亮表。请在已完成 stage3_2 的 learnloop 库执行。
CREATE TABLE IF NOT EXISTS question_notes (
    user_id BIGINT NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    revision CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, question_version_id),
    KEY idx_question_notes_updated (user_id, updated_at, question_version_id),
    CONSTRAINT fk_question_notes_question FOREIGN KEY (user_id, question_version_id)
        REFERENCES learning_questions (user_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reading_highlights (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    -- 重解析会清理旧正文块。保留锚点和原文快照，不对旧块设外键。
    block_id BIGINT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    quote TEXT NOT NULL,
    question VARCHAR(500) NOT NULL,
    answer TEXT NOT NULL,
    detail VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    color VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reading_highlights_anchor (user_id, material_id, parse_attempt, block_id, start_offset),
    CONSTRAINT fk_reading_highlights_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_highlights_material FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE,
    CONSTRAINT ck_reading_highlights_offsets CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_reading_highlights_detail CHECK (detail IN ('BRIEF','DETAILED')),
    CONSTRAINT ck_reading_highlights_color CHECK (color IN ('LEMON','MINT','SKY','LILAC','PEACH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 重试可跳过已建表；提前发现同名表存在但缺少业务字段的情况。
SELECT user_id, question_version_id, content, revision, updated_at FROM question_notes LIMIT 0;
SELECT id, user_id, material_id, parse_attempt, block_id, start_offset, end_offset,
       quote, question, answer, detail, color, created_at FROM reading_highlights LIMIT 0;
SELECT 'JOURNAL_SCHEMA_READY';
