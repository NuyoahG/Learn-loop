-- 每张卡对应资料的一个已发布小考点。各批发布与补卡在同一事务完成；卡不限制后续出题范围。
-- locked_job_id 为历史列名，记录首次建卡的发布批次，不再表示资料考点锁定。
CREATE TABLE IF NOT EXISTS topic_review_cards (
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    locked_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, material_id, topic_id),
    KEY ix_topic_review_due (user_id, due_at),
    CONSTRAINT fk_topic_review_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB;
