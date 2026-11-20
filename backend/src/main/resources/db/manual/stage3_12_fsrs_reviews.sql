-- 作答后先冻结题目和选项。只有反馈提交成功的行才是正式 FSRS 事件。
CREATE TABLE IF NOT EXISTS topic_review_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_due DATETIME(6) NOT NULL,
    selected CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rating TINYINT NULL,
    reviewed_at DATETIME(6) NULL,
    next_due DATETIME(6) NULL,
    fsrs_state VARCHAR(2000) NULL,
    UNIQUE KEY uk_review_cycle (user_id,material_id,topic_id,prior_due),
    KEY ix_review_previous (user_id,material_id,topic_id,next_due),
    CONSTRAINT fk_review_card FOREIGN KEY (user_id,material_id,topic_id)
        REFERENCES topic_review_cards(user_id,material_id,topic_id),
    CONSTRAINT ck_review_selected CHECK (selected IN ('A','B','C','D')),
    CONSTRAINT ck_review_feedback CHECK (
        (rating IS NULL AND reviewed_at IS NULL AND next_due IS NULL AND fsrs_state IS NULL)
        OR (rating BETWEEN 1 AND 4 AND rating IS NOT NULL AND reviewed_at IS NOT NULL
            AND next_due IS NOT NULL AND next_due > reviewed_at AND fsrs_state IS NOT NULL)
    )
) ENGINE=InnoDB;
