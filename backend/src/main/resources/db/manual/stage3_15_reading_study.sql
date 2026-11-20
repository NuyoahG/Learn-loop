-- 旧高亮没有附加记录时仍视为普通高亮。
CREATE TABLE IF NOT EXISTS reading_highlight_study (
    highlight_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    intent VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT fk_reading_study_highlight FOREIGN KEY (highlight_id) REFERENCES reading_highlights(id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_study_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id) ON DELETE SET NULL,
    CONSTRAINT ck_reading_study_intent CHECK (intent IN ('HIGHLIGHT','UNCLEAR','CONFUSED','REMEMBER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SELECT 'READING_STUDY_SCHEMA_READY';
