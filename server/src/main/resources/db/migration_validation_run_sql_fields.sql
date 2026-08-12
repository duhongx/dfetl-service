-- validation-hardening-phase2 Task 9: ValidationRun 新增 SQL 记录字段
-- 用于持久化每次校验实际执行的 SQL 和 WHERE 条件

ALTER TABLE validation_run ADD COLUMN source_sql TEXT;
ALTER TABLE validation_run ADD COLUMN target_sql TEXT;
ALTER TABLE validation_run ADD COLUMN source_where TEXT;
ALTER TABLE validation_run ADD COLUMN target_where TEXT;
