-- 在已执行 stage4_3 的 learnloop 库执行。标注绑定只读原件，不依赖正文解析。
CREATE TABLE IF NOT EXISTS pdf_annotations (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    quote TEXT NOT NULL,
    rects JSON NOT NULL,
    color VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pdf_annotation_note (note_id),
    KEY idx_pdf_annotations_material (user_id,material_id,deleted,page_number),
    CONSTRAINT fk_pdf_annotation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pdf_annotation_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    CONSTRAINT ck_pdf_annotation_page CHECK (page_number BETWEEN 1 AND 10000),
    CONSTRAINT ck_pdf_annotation_color CHECK (color IN ('LEMON','MINT','SKY','LILAC','PEACH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
SELECT id,user_id,material_id,page_number,quote,rects,color,revision,create_hash,note_id,deleted,created_at,updated_at
FROM pdf_annotations LIMIT 0;
