-- 阶段 4.7：只新增元数据，不改原文、旧考点 ID、答题和 FSRS 历史。
USE learnloop;

CREATE TABLE IF NOT EXISTS adaptive_topic_profiles (
    user_id BIGINT NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aliases_json JSON NOT NULL,
    granularity VARCHAR(8) NOT NULL,
    PRIMARY KEY (user_id, topic_id),
    CONSTRAINT fk_topic_profile_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id) ON DELETE CASCADE,
    CONSTRAINT ck_topic_profile_granularity CHECK (granularity IN ('COARSE','MEDIUM','FINE'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS adaptive_catalog_results (
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_id BIGINT NOT NULL,
    version VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, material_id, parse_attempt, block_id),
    CONSTRAINT fk_catalog_result_block FOREIGN KEY (block_id) REFERENCES material_blocks(id) ON DELETE CASCADE,
    CONSTRAINT ck_catalog_result_status CHECK (status IN ('READY','NO_KNOWLEDGE','RETRYABLE_EMPTY'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS material_model_noise (
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_no INT NOT NULL,
    ranges_json JSON NOT NULL,
    PRIMARY KEY (material_id, parse_attempt, block_no),
    CONSTRAINT fk_model_noise_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE
) ENGINE=InnoDB;

SELECT 'GENERATION_QUALITY_SCHEMA_READY' AS result;
