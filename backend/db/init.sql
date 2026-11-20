-- LearnLoop clean installation. Execute in batch mode without --force.
CREATE DATABASE learnloop CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE learnloop;
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE questions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    option_a TEXT NOT NULL,
    option_b TEXT NOT NULL,
    option_c TEXT NOT NULL,
    option_d TEXT NOT NULL,
    correct_answer CHAR(1) NOT NULL,
    explanation TEXT,
    FOREIGN KEY (user_id) REFERENCES users (id),
    CHECK (correct_answer IN ('A','B','C','D'))
) ENGINE=InnoDB;

CREATE TABLE answer_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option CHAR(1) NOT NULL,
    correct_option_snapshot CHAR(1) NOT NULL,
    is_correct BOOLEAN NOT NULL,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (question_id) REFERENCES questions (id),
    CHECK (selected_option IN ('A','B','C','D')),
    CHECK (correct_option_snapshot IN ('A','B','C','D'))
) ENGINE=InnoDB;

-- stage3_1_materials
CREATE TABLE materials (
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
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC，展示层负责转换时区',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近业务修改时间，UTC，由应用显式更新',
    parsed_at DATETIME(6) NULL COMMENT '成功解析发布时间，UTC，未成功时为空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_materials_user_request (user_id, request_id),
    UNIQUE KEY uk_materials_storage_key (storage_key),
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

-- stage3_1_materials
CREATE TABLE material_blocks (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '正文块内部编号',
    material_id BIGINT NOT NULL COMMENT '所属资料，查询必须联接资料并检查 user_id',
    block_no INT NOT NULL COMMENT '同份资料内从 1 开始连续编号；不是向量 chunk 编号',
    block_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'paragraph/table_row/text/pdf_text 等受控类型',
    text_content MEDIUMTEXT NOT NULL COMMENT '仅有效正文文本，后续发布前校验非空及单块上限',
    source_locator JSON NOT NULL COMMENT '受校验来源对象：PDF页码、DOCX正文/行序号或TXT行范围',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_blocks_material_no (material_id, block_no),
    CONSTRAINT fk_material_blocks_material FOREIGN KEY (material_id) REFERENCES materials (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_blocks_no CHECK (block_no >= 1),
    CONSTRAINT ck_material_blocks_type CHECK (block_type IN ('paragraph','table_row','text','pdf_text')),
    CONSTRAINT ck_material_blocks_text CHECK (CHAR_LENGTH(TRIM(text_content)) > 0),
    CONSTRAINT ck_material_blocks_locator CHECK (JSON_TYPE(source_locator) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='有序提取与预览正文单元；阶段3.1A建空表，3.1B才发布解析结果';

-- stage3_2_learning
CREATE TABLE learning_concepts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的稳定概念 UUID',
    user_id BIGINT NOT NULL COMMENT '所属用户，从已认证 Session 获取',
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建概念的幂等请求 UUID',
    material_id BIGINT NOT NULL COMMENT '来源资料编号，正文后续重解析也不变更此快照',
    block_id BIGINT NOT NULL COMMENT '绑定时正文块编号，仅作来源快照而不外键到可替换正文',
    material_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定时原件 SHA-256',
    parse_attempt BIGINT NOT NULL COMMENT '绑定时成功解析轮次，与 SHA-256 一起确定内容版本',
    name VARCHAR(120) NOT NULL COMMENT '简短概念名称；不能用同名当作同一概念',
    definition TEXT NOT NULL COMMENT '稳定概念定义，用于后续匹配与人工核对',
    granularity VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'COARSE/MEDIUM/FINE',
    source_text MEDIUMTEXT NOT NULL COMMENT '绑定时原文快照，避免资料重解析后来源丢失',
    source_locator JSON NOT NULL COMMENT '绑定时来源定位对象快照',
    created_at DATETIME(6) NOT NULL COMMENT '创建时刻 UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_concepts_user_id (user_id,id),
    UNIQUE KEY uk_learning_concepts_request (user_id,request_key),
    UNIQUE KEY uk_learning_concepts_anchor (user_id,material_id,block_id,parse_attempt,granularity,name),
    KEY idx_learning_concepts_material (user_id,material_id,created_at,id),
    CONSTRAINT fk_learning_concepts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_concepts_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_concepts_granularity CHECK (granularity IN ('COARSE','MEDIUM','FINE')),
    CONSTRAINT ck_learning_concepts_source CHECK (block_id>0 AND parse_attempt>0),
    CONSTRAINT ck_learning_concepts_sha CHECK (REGEXP_LIKE(material_sha256,'^[0-9a-f]{64}$','c')),
    CONSTRAINT ck_learning_concepts_text CHECK (CHAR_LENGTH(TRIM(name))>0 AND CHAR_LENGTH(TRIM(definition))>0 AND CHAR_LENGTH(TRIM(source_text))>0),
    CONSTRAINT ck_learning_concepts_locator CHECK (JSON_TYPE(source_locator)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='稳定概念及不可变资料来源；M1 先人工绑定，后续自动抽取复用同一契约';

-- stage3_2_learning
CREATE TABLE learning_questions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变题目版本 UUID',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    question_id BIGINT NOT NULL COMMENT '复用旧 questions 主键；M1 每原题只能绑定一次',
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定的一个主考点',
    family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端变式家族 UUID，客户端不能随意声明',
    content TEXT NOT NULL COMMENT '题干快照',
    option_a TEXT NOT NULL COMMENT '选项 A 快照',
    option_b TEXT NOT NULL COMMENT '选项 B 快照',
    option_c TEXT NOT NULL COMMENT '选项 C 快照',
    option_d TEXT NOT NULL COMMENT '选项 D 快照',
    correct_answer CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '正确答案快照，A/B/C/D',
    content_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范题干及选项、答案的 SHA-256',
    created_at DATETIME(6) NOT NULL COMMENT '绑定时刻 UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_questions_user_id (user_id,id),
    UNIQUE KEY uk_learning_questions_original (question_id),
    KEY idx_learning_questions_concept (user_id,concept_id,created_at,id),
    KEY idx_learning_questions_family (user_id,family_id),
    CONSTRAINT fk_learning_questions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_questions_original FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_questions_concept FOREIGN KEY (user_id,concept_id) REFERENCES learning_concepts(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_questions_answer CHECK (correct_answer IN ('A','B','C','D')),
    CONSTRAINT ck_learning_questions_digest CHECK (REGEXP_LIKE(content_digest,'^[0-9a-f]{64}$','c')),
    CONSTRAINT ck_learning_questions_text CHECK (CHAR_LENGTH(TRIM(content))>0 AND CHAR_LENGTH(TRIM(option_a))>0 AND CHAR_LENGTH(TRIM(option_b))>0 AND CHAR_LENGTH(TRIM(option_c))>0 AND CHAR_LENGTH(TRIM(option_d))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='旧题的不可变版本与主考点绑定；业务更新旧题不会修改历史判分依据';

-- stage3_2_learning
CREATE TABLE learning_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次答题尝试 UUID',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次冻结的题目版本',
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建尝试的幂等 UUID',
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建请求规范 payload 摘要，重复 key 必须一致',
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'OPEN/SUBMITTED/EXPIRED',
    expires_at DATETIME(6) NOT NULL COMMENT '尝试到期时刻 UTC，首版创建后两小时',
    assisted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '提交前是否通过本接口显式取得提示或答案',
    assisted_at DATETIME(6) NULL COMMENT '首次显式取得辅助的时刻 UTC',
    submit_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '提交答案的独立幂等 UUID',
    confidence VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'UNSURE/SOMEWHAT_SURE/VERY_SURE，仅用户主动填写',
    selected_option CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '提交选项快照',
    is_correct BOOLEAN NULL COMMENT 'Java 根据冻结版本判分',
    answer_record_id BIGINT NULL COMMENT '旧 answer_records 的真实写入编号',
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '合格证据对应的事件；不设反向外键以避免循环建表',
    ineligibility_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '未进入 BKT 的机器可读原因；答题记录照常保存',
    created_at DATETIME(6) NOT NULL COMMENT '创建时刻 UTC',
    submitted_at DATETIME(6) NULL COMMENT '提交成功时刻 UTC',
    open_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status='OPEN' THEN question_version_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_attempts_user_id (user_id,id),
    UNIQUE KEY uk_learning_attempts_request (user_id,request_key),
    UNIQUE KEY uk_learning_attempts_submit (user_id,submit_key),
    UNIQUE KEY uk_learning_attempts_open (user_id,open_version_id),
    UNIQUE KEY uk_learning_attempts_answer (answer_record_id),
    UNIQUE KEY uk_learning_attempts_event (event_id),
    KEY idx_learning_attempts_expiry (user_id,question_version_id,status,expires_at),
    CONSTRAINT fk_learning_attempts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_attempts_question FOREIGN KEY (user_id,question_version_id) REFERENCES learning_questions(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_attempts_answer FOREIGN KEY (answer_record_id) REFERENCES answer_records(id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_attempts_status CHECK (status IN ('OPEN','SUBMITTED','EXPIRED')),
    CONSTRAINT ck_learning_attempts_digest CHECK (REGEXP_LIKE(request_digest,'^[0-9a-f]{64}$','c')),
    CONSTRAINT ck_learning_attempts_confidence CHECK (confidence IS NULL OR confidence IN ('UNSURE','SOMEWHAT_SURE','VERY_SURE')),
    CONSTRAINT ck_learning_attempts_option CHECK (selected_option IS NULL OR selected_option IN ('A','B','C','D')),
    CONSTRAINT ck_learning_attempts_expiry CHECK (expires_at>created_at),
    CONSTRAINT ck_learning_attempts_assisted CHECK ((assisted=FALSE AND assisted_at IS NULL) OR (assisted=TRUE AND assisted_at IS NOT NULL)),
    CONSTRAINT ck_learning_attempts_submission CHECK (
        (status='SUBMITTED' AND submit_key IS NOT NULL AND selected_option IS NOT NULL AND is_correct IS NOT NULL AND answer_record_id IS NOT NULL AND submitted_at IS NOT NULL)
        OR (status IN ('OPEN','EXPIRED') AND submit_key IS NULL AND selected_option IS NULL AND is_correct IS NULL AND answer_record_id IS NULL AND submitted_at IS NULL)),
    CONSTRAINT ck_learning_attempts_evidence CHECK (status<>'SUBMITTED' OR ((event_id IS NULL)<>(ineligibility_reason IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='有期限、可标记辅助的幂等答题尝试；题目答案响应必须在标辅助后返回';

-- stage3_2_learning
CREATE TABLE learning_mastery (
    user_id BIGINT NOT NULL COMMENT '用户',
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定概念',
    model_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '算法参数和有效证据规则的版本',
    mastery_probability DOUBLE NOT NULL DEFAULT 0.2 COMMENT 'BKT 概念掌握概率，初始先验 0.2 不代表已测量',
    next_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已分配的最大事件号',
    applied_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已严格按序应用的最大事件号',
    state_version BIGINT NOT NULL DEFAULT 0 COMMENT '已应用状态乐观锁版本',
    evidence_count BIGINT NOT NULL DEFAULT 0 COMMENT '已实际应用的独立观测数',
    updated_at DATETIME(6) NOT NULL COMMENT '最近状态或队列更新时刻 UTC',
    PRIMARY KEY (user_id,concept_id,model_version),
    CONSTRAINT fk_learning_mastery_concept FOREIGN KEY (user_id,concept_id) REFERENCES learning_concepts(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_mastery_probability CHECK (mastery_probability>=0 AND mastery_probability<=1),
    CONSTRAINT ck_learning_mastery_sequence CHECK (next_sequence>=applied_sequence AND applied_sequence>=0 AND state_version>=0 AND evidence_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='每用户每概念每模型版本一份有序 BKT 状态';

-- stage3_2_learning
CREATE TABLE learning_evidence_claims (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立证据占用 UUID',
    user_id BIGINT NOT NULL COMMENT '用户',
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '只能观测一次的题目版本',
    family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端家族 UUID',
    evidence_date DATE NOT NULL COMMENT 'UTC 自然日，同家族同日只能贡献一次',
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '产生观测的尝试',
    created_at DATETIME(6) NOT NULL COMMENT '占用时刻 UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_claims_question (user_id,question_version_id),
    UNIQUE KEY uk_learning_claims_family_day (user_id,family_id,evidence_date),
    UNIQUE KEY uk_learning_claims_attempt (attempt_id),
    CONSTRAINT fk_learning_claims_question FOREIGN KEY (user_id,question_version_id) REFERENCES learning_questions(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_claims_attempt FOREIGN KEY (user_id,attempt_id) REFERENCES learning_attempts(user_id,id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='独立答题的数据库唯一占用；与旧答题写入、事件插入共同提交';

-- stage3_2_learning
CREATE TABLE learning_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '学习事件 UUID，也是算法调用幂等键',
    user_id BIGINT NOT NULL COMMENT '用户',
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '主考点',
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结版本',
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对应的已提交答题尝试',
    model_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '参数和证据规则版本',
    sequence_no BIGINT NOT NULL COMMENT '同用户同概念同模型版本单调分配，从 1 开始',
    is_correct BOOLEAN NOT NULL COMMENT 'Java 判分观测值，Python 不能重判',
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING/PROCESSING/APPLIED/FAILED',
    prior_probability DOUBLE NULL COMMENT '成功应用时保存的原掌握概率',
    applied_probability DOUBLE NULL COMMENT '成功应用后的掌握概率',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次认领 UUID，重领必须更换',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '本轮重试的认领次数',
    retry_at DATETIME(6) NOT NULL COMMENT '下一次可认领时刻 UTC',
    lease_until DATETIME(6) NULL COMMENT 'PROCESSING 租约截止 UTC',
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全机器错误码，不保存堆栈或凭据',
    created_at DATETIME(6) NOT NULL COMMENT '入队时刻 UTC',
    applied_at DATETIME(6) NULL COMMENT '结果成功应用时刻 UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_events_user_id (user_id,id),
    UNIQUE KEY uk_learning_events_sequence (user_id,concept_id,model_version,sequence_no),
    UNIQUE KEY uk_learning_events_attempt (attempt_id),
    KEY idx_learning_events_pending (status,retry_at,created_at,id),
    KEY idx_learning_events_lease (status,lease_until,created_at,id),
    CONSTRAINT fk_learning_events_mastery FOREIGN KEY (user_id,concept_id,model_version) REFERENCES learning_mastery(user_id,concept_id,model_version) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_events_question FOREIGN KEY (user_id,question_version_id) REFERENCES learning_questions(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_events_attempt FOREIGN KEY (user_id,attempt_id) REFERENCES learning_attempts(user_id,id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_events_status CHECK (status IN ('PENDING','PROCESSING','APPLIED','FAILED')),
    CONSTRAINT ck_learning_events_sequence CHECK (sequence_no>=1 AND attempt_count>=0),
    CONSTRAINT ck_learning_events_prior CHECK (prior_probability IS NULL OR (prior_probability>=0 AND prior_probability<=1)),
    CONSTRAINT ck_learning_events_applied CHECK (applied_probability IS NULL OR (applied_probability>=0 AND applied_probability<=1)),
    CONSTRAINT ck_learning_events_lease CHECK ((status='PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_learning_events_result CHECK ((status='APPLIED' AND prior_probability IS NOT NULL AND applied_probability IS NOT NULL AND applied_at IS NOT NULL) OR (status<>'APPLIED' AND prior_probability IS NULL AND applied_probability IS NULL AND applied_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='Java 事务 outbox；队首按序认领、事务外调用 Python、带 token 和版本栅栏写回';

-- stage3_3_generation
CREATE TABLE generation_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_count INT NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    granularity VARCHAR(8) NOT NULL,
    snapshot_json JSON NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    model VARCHAR(200) NULL,
    shortfall_reason VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    active_user BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING') THEN user_id ELSE NULL END) STORED,
    UNIQUE KEY uk_generation_request(user_id,request_key),
    UNIQUE KEY uk_generation_active(active_user),
    KEY ix_generation_pending(status,created_at),
    KEY ix_generation_lease(status,lease_until),
    CONSTRAINT fk_generation_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT fk_generation_material FOREIGN KEY(material_id) REFERENCES materials(id),
    CONSTRAINT ck_generation_status CHECK(status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_generation_count CHECK(requested_count IN (3,5,10)),
    CONSTRAINT ck_generation_difficulty CHECK(difficulty IN ('EASY','MEDIUM','HARD')),
    CONSTRAINT ck_generation_granularity CHECK(granularity IN ('COARSE','MEDIUM','FINE')),
    CONSTRAINT ck_generation_claim CHECK((status='PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- stage3_3_generation
CREATE TABLE generation_job_items (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_no INT NOT NULL,
    user_id BIGINT NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    block_id BIGINT NOT NULL,
    source_quote TEXT NOT NULL,
    source_locator JSON NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    PRIMARY KEY(job_id,item_no),
    UNIQUE KEY uk_generation_question(question_version_id),
    KEY ix_generation_items_owner(user_id,job_id),
    CONSTRAINT fk_generation_item_job FOREIGN KEY(job_id) REFERENCES generation_jobs(id),
    CONSTRAINT fk_generation_item_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT fk_generation_item_question FOREIGN KEY(question_version_id) REFERENCES learning_questions(id),
    CONSTRAINT ck_generation_item_no CHECK(item_no BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- stage3_4_model_credentials
CREATE TABLE model_credentials (
    user_id BIGINT NOT NULL PRIMARY KEY,
    cipher_format VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce BINARY(12) NOT NULL,
    ciphertext VARBINARY(528) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_model_credentials_user FOREIGN KEY(user_id) REFERENCES users(id),
    CONSTRAINT ck_model_credentials_format CHECK(cipher_format='AES256_GCM_V1'),
    CONSTRAINT ck_model_credentials_size CHECK(OCTET_LENGTH(ciphertext) BETWEEN 32 AND 528)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- stage3_9_journal
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

-- stage3_9_journal
CREATE TABLE IF NOT EXISTS reading_highlights (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
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

-- stage3_10_adaptive_generation
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

-- stage3_10_adaptive_generation
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

-- stage3_10_adaptive_generation
CREATE TABLE IF NOT EXISTS adaptive_topic_links (
    user_id BIGINT NOT NULL,
    concept_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, concept_id),
    KEY ix_adaptive_link_topic (user_id, topic_id),
    CONSTRAINT fk_adaptive_link_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- stage3_10_adaptive_generation
CREATE TABLE IF NOT EXISTS adaptive_indexed_blocks (
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, material_id, parse_attempt, block_id)
) ENGINE=InnoDB;

-- stage3_10_adaptive_generation
CREATE TABLE IF NOT EXISTS adaptive_generation_choices (
    user_id BIGINT NOT NULL,
    question_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, question_version_id),
    CONSTRAINT fk_adaptive_choice_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id)
) ENGINE=InnoDB;

-- stage3_11_topic_review_cards
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

-- stage3_12_fsrs_reviews
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

-- stage3_13_tutor
CREATE TABLE IF NOT EXISTS tutor_feedback (
    user_id BIGINT NOT NULL,
    attempt_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail VARCHAR(300) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, attempt_kind, attempt_id),
    KEY idx_tutor_feedback_recent (user_id, updated_at),
    CONSTRAINT fk_tutor_feedback_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_tutor_feedback_kind CHECK (attempt_kind IN ('PRACTICE','REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- stage3_13_tutor
CREATE TABLE IF NOT EXISTS tutor_suggestion_state (
    user_id BIGINT NOT NULL,
    suggestion_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, suggestion_key),
    CONSTRAINT fk_tutor_suggestion_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- stage3_14_tutor_plans
CREATE TABLE IF NOT EXISTS tutor_plans (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    minutes INT NOT NULL,
    reason VARCHAR(240) NOT NULL,
    planner VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trace JSON NOT NULL,
    items JSON NOT NULL,
    position INT NOT NULL DEFAULT 0,
    active_attempt CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    open_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('DRAFT','ACTIVE') THEN user_id END) STORED,
    UNIQUE KEY uk_tutor_open_plan (open_user_id),
    KEY idx_tutor_plans_owner (user_id, created_at, id),
    CONSTRAINT fk_tutor_plan_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tutor_plan_status CHECK (status IN ('DRAFT','ACTIVE','COMPLETED','CANCELLED')),
    CONSTRAINT ck_tutor_plan_progress CHECK (position BETWEEN 0 AND 5 AND minutes BETWEEN 4 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- stage3_15_reading_study
CREATE TABLE IF NOT EXISTS reading_highlight_study (
    highlight_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    intent VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT fk_reading_study_highlight FOREIGN KEY (highlight_id) REFERENCES reading_highlights(id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_study_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id) ON DELETE SET NULL,
    CONSTRAINT ck_reading_study_intent CHECK (intent IN ('HIGHLIGHT','UNCLEAR','CONFUSED','REMEMBER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- stage4_3_reading_notes
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

-- stage4_4_pdf_annotations
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

-- stage4_7_generation_quality
CREATE TABLE IF NOT EXISTS adaptive_topic_profiles (
    user_id BIGINT NOT NULL,
    topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aliases_json JSON NOT NULL,
    granularity VARCHAR(8) NOT NULL,
    PRIMARY KEY (user_id, topic_id),
    CONSTRAINT fk_topic_profile_topic FOREIGN KEY (topic_id) REFERENCES adaptive_topics(id) ON DELETE CASCADE,
    CONSTRAINT ck_topic_profile_granularity CHECK (granularity IN ('COARSE','MEDIUM','FINE'))
) ENGINE=InnoDB;

-- stage4_7_generation_quality
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

-- stage4_7_generation_quality
CREATE TABLE IF NOT EXISTS material_model_noise (
    material_id BIGINT NOT NULL,
    parse_attempt BIGINT NOT NULL,
    block_no INT NOT NULL,
    ranges_json JSON NOT NULL,
    PRIMARY KEY (material_id, parse_attempt, block_no),
    CONSTRAINT fk_model_noise_material FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- stage4_9_tutor_lessons
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
