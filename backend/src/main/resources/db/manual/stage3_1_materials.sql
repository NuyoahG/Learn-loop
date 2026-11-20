-- LearnLoop 阶段 3.1A 增量建表脚本（MySQL 8.4）
-- 执行前：先记录 users/questions/answer_records 的结构、数量和内容指纹。
-- 只有 materials 和 material_blocks 均不存在时才执行；故意不用 IF NOT EXISTS，
-- 避免同名旧表结构有误时被悄悄跳过。运行账号需要 CREATE/REFERENCES 权限。
-- 线上应用账号继续只拥有业务 DML 权限，不为本功能增加建表权限。
-- MySQL DDL 会隐式提交；第二张表失败时第一张可能已经创建，不能认为事务已回滚。
-- 此文件不修改或删除已有 users/questions/answer_records，不含自动回退语句。

USE learnloop;
SET NAMES utf8mb4;
-- DATETIME 本身不携带时区。Java 显式写 UTC，本连接也固定 UTC，便于手工检查。
SET SESSION time_zone = '+00:00';

CREATE TABLE materials (
    -- Long 对应有符号 BIGINT；必须与既有 users.id 的类型一致。
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '资料编号',
    user_id BIGINT NOT NULL COMMENT '资料所有者，由登录 Session 确定',
    original_name VARCHAR(255) NOT NULL COMMENT '清理并校验后的原始展示名称，不能用作磁盘路径',
    file_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '小写 txt/md/docx/pdf',
    size_bytes BIGINT NOT NULL COMMENT '实际读取到的原件字节数，非客户端声明值',
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原件 SHA-256 小写十六进制，用于重传一致性判断',
    storage_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端随机生成的无路径存储键，禁止返回给网页',
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '小写标准 UUID 上传请求键，不是用户身份',
    parse_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/PARSED/FAILED；3.1A 只创建 UPLOADED',
    parse_attempt BIGINT NOT NULL DEFAULT 0 COMMENT '解析认领次数，用于后续排除迟到结果；3.1A 为 0',
    parser_version VARCHAR(64) NULL COMMENT '成功处理此原件的解析器版本，未解析时为空',
    error_code VARCHAR(64) NULL COMMENT '可稳定识别的失败码，未失败时为空',
    error_message VARCHAR(255) NULL COMMENT '可安全展示的失败说明，禁止堆栈、SQL、内部路径或凭据',
    warnings JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '结构化警告数组，不能把 NULL 当空数组',
    block_count INT NOT NULL DEFAULT 0 COMMENT '完整发布的正文块数量，未解析时为 0',
    text_char_count INT NOT NULL DEFAULT 0 COMMENT '统一正文文本的 Unicode 码点总数，未解析时为 0',
    -- 应用写入 UTC LocalDateTime。默认值只作手工 INSERT 的兜底，连接时区须为 UTC。
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC，展示层负责转换时区',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近业务修改时间，UTC，由应用显式更新',
    parsed_at DATETIME(6) NULL COMMENT '成功解析发布时间，UTC，未成功时为空',
    PRIMARY KEY (id),
    -- 必须由数据库解决并发重传；先查再插不能消除两个请求同时到达的竞态。
    UNIQUE KEY uk_materials_user_request (user_id, request_id),
    -- 随机键也由唯一约束兜底，不能让不同资料引用并覆盖同一个原件。
    UNIQUE KEY uk_materials_storage_key (storage_key),
    -- 所有列表先按 user_id 过滤，再按创建时间、ID 倒序；ID 保证同一时刻仍稳定排序。
    KEY idx_materials_user_created (user_id, created_at, id),
    CONSTRAINT fk_materials_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_materials_file_type CHECK (file_type IN ('txt','md','docx','pdf')),
    CONSTRAINT ck_materials_size CHECK (size_bytes > 0),
    CONSTRAINT ck_materials_sha256 CHECK (REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_materials_status CHECK (parse_status IN ('UPLOADED','PARSING','PARSED','FAILED')),
    CONSTRAINT ck_materials_attempt CHECK (parse_attempt >= 0),
    CONSTRAINT ck_materials_counts CHECK (block_count >= 0 AND text_char_count >= 0),
    CONSTRAINT ck_materials_warnings CHECK (JSON_TYPE(warnings) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='个人资料元信息和解析状态；原件保存在静态目录之外';

CREATE TABLE material_blocks (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '正文块内部编号',
    material_id BIGINT NOT NULL COMMENT '所属资料，查询必须联接资料并检查 user_id',
    block_no INT NOT NULL COMMENT '同份资料内从 1 开始连续编号；不是向量 chunk 编号',
    block_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'paragraph/table_row/text/pdf_text 等受控类型',
    text_content MEDIUMTEXT NOT NULL COMMENT '仅有效正文文本，后续发布前校验非空及单块上限',
    source_locator JSON NOT NULL COMMENT '受校验来源对象：PDF页码、DOCX正文/行序号或TXT行范围',
    PRIMARY KEY (id),
    -- 同份资料的稳定顺序也防止重试时插入相同编号的重复块；成功发布仍须短事务。
    UNIQUE KEY uk_material_blocks_material_no (material_id, block_no),
    CONSTRAINT fk_material_blocks_material FOREIGN KEY (material_id) REFERENCES materials (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_blocks_no CHECK (block_no >= 1),
    CONSTRAINT ck_material_blocks_type CHECK (block_type IN ('paragraph','table_row','text','pdf_text')),
    CONSTRAINT ck_material_blocks_text CHECK (CHAR_LENGTH(TRIM(text_content)) > 0),
    CONSTRAINT ck_material_blocks_locator CHECK (JSON_TYPE(source_locator) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='有序提取与预览正文单元；阶段3.1A建空表，3.1B才发布解析结果';

-- 执行完成后保留输出作为证据。此处只查询，不写入演示资料。
SHOW CREATE TABLE materials;
SHOW INDEX FROM materials;
SHOW CREATE TABLE material_blocks;
SHOW INDEX FROM material_blocks;
SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'questions', COUNT(*) FROM questions
UNION ALL SELECT 'answer_records', COUNT(*) FROM answer_records
UNION ALL SELECT 'materials', COUNT(*) FROM materials
UNION ALL SELECT 'material_blocks', COUNT(*) FROM material_blocks;
