-- 只增加辅导对话与反馈；不修改掌握度、复习卡和已有学习记录。
USE learnloop;
CREATE TABLE IF NOT EXISTS tutor_lessons (
  user_id BIGINT NOT NULL,
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  material_id BIGINT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question VARCHAR(500) NOT NULL,
  answer TEXT NOT NULL,
  teaching VARCHAR(16) NOT NULL,
  evidence_json JSON NOT NULL,
  trace_json JSON NOT NULL,
  feedback VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  detail VARCHAR(300) NOT NULL DEFAULT '',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(user_id,id),
  KEY idx_tutor_lessons_recent(user_id,material_id,created_at),
  KEY idx_tutor_lessons_feedback(user_id,updated_at),
  CONSTRAINT fk_tutor_lessons_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_tutor_lessons_material FOREIGN KEY(material_id) REFERENCES materials(id) ON DELETE CASCADE,
  CONSTRAINT ck_tutor_lessons_teaching CHECK(teaching IN ('EXPLAIN','COMPARE','HINT','REVIEW')),
  CONSTRAINT ck_tutor_lessons_feedback CHECK(feedback IN ('PENDING','HELPFUL','UNRESOLVED','INCORRECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
SELECT 'TUTOR_LESSONS_SCHEMA_READY';
