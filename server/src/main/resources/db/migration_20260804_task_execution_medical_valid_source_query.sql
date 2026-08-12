-- 2026-08-04
-- 保存医共体执行期问题行分流后的合规源查询快照，供 Validation 按实际写入范围校验。

ALTER TABLE task_execution
    ADD COLUMN IF NOT EXISTS medical_valid_source_query TEXT;

COMMENT ON COLUMN task_execution.medical_valid_source_query IS
    '医共体执行期分流后 SeaTunnel 实际读取的合规源查询快照，供 Validation 对齐执行范围';
