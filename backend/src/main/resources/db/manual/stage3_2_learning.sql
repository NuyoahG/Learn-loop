-- LearnLoop M1：稳定概念、不可变题目版本、幂等答题和有序 BKT 事件（MySQL 8.4）。
-- 这是人工审核后执行的增量脚本，不会被 Spring Boot 自动执行。
-- 必须先选择正确数据库，并确认 users/questions/answer_records/materials/material_blocks 已存在。
-- 故意不写 USE、不用 IF NOT EXISTS、不修改旧表，便于在隔离验收库验证及识别结构冲突。
-- MySQL DDL 隐式提交：中途失败不能假定已创建的表会回滚；应用运行账号仍只需业务 DML 权限。
-- 所有 DATETIME(6) 由 Java 显式写入 UTC；evidence_date 取提交时刻的 UTC 自然日。

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
    -- MySQL UNIQUE 允许多个 NULL，因此只有 OPEN 才占用“同用户同版本一条有效尝试”的槽位。
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
