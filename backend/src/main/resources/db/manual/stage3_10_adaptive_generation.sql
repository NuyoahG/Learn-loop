-- 保留旧题、旧考点和作答事件，只增加知识目录及归组关系。
CREATE TABLE IF NOT EXISTS adaptive_topics (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    topic_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chapter VARCHAR(120) NOT NULL,
    group_name VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    definition VARCHAR(300) NOT NULL,
    UNIQUE KEY uk_adaptive_topic (user_id, material_id, topic_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS adaptive_topic_sources (
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_id BIGINT NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quote VARCHAR(300) NOT NULL,
    PRIMARY KEY (user_id, material_id, parse_attempt, block_id, topic_id),
    CONSTRAINT fk_adaptive_source_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS adaptive_topic_links (
    user_id BIGINT NOT NULL,
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, concept_id),
    KEY ix_adaptive_link_topic (user_id, topic_id),
    CONSTRAINT fk_adaptive_link_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS adaptive_indexed_blocks (
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, material_id, parse_attempt, block_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS adaptive_generation_choices (
    user_id BIGINT NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, question_version_id),
    CONSTRAINT fk_adaptive_choice_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB;
