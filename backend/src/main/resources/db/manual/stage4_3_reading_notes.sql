-- 阅读问答在保留原始高亮的同时，保存一份可独立编辑的笔记。
CREATE TABLE IF NOT EXISTS reading_notes (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    quote TEXT NOT NULL,
    question VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    revision CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reading_notes_updated (user_id, updated_at, id),
    CONSTRAINT fk_reading_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_notes_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SELECT id, user_id, material_id, quote, question, content, revision, updated_at FROM reading_notes LIMIT 0;
