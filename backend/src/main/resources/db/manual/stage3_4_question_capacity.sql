-- 阶段 3.4：适配已冻结的生成题长度上限（题干 2000 字符、每个选项 1000 字符）。
-- 原题干 VARCHAR(1000)、选项 VARCHAR(255) 无法容纳合法生成结果，因此只扩大这五列。
-- 执行前必须由 Enable-LearningDatabase.ps1 检查：结构、NOT NULL、无自定义索引及旧数据摘要。
-- 不修改正确答案、解析、用户归属或任何记录；explanation 已经为 TEXT，不在本次范围。
-- MySQL DDL 隐式提交，不能承诺跨本文件与其他建表文件全部回滚。
ALTER TABLE questions
    MODIFY COLUMN content TEXT NOT NULL,
    MODIFY COLUMN option_a TEXT NOT NULL,
    MODIFY COLUMN option_b TEXT NOT NULL,
    MODIFY COLUMN option_c TEXT NOT NULL,
    MODIFY COLUMN option_d TEXT NOT NULL;
