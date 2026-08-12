-- task_execution.triggered_by 原 VARCHAR(20) 无法保存
-- RECOLLECT_DROP_RECREATE（23 字符），导致重采在创建 execution 前失败。
-- 扩容为 VARCHAR(50)，兼容现有数据与后续新增触发来源。

ALTER TABLE task_execution
    ALTER COLUMN triggered_by TYPE VARCHAR(50);

COMMENT ON COLUMN task_execution.triggered_by IS
    'SCHEDULER=定时触发 | MANUAL=手动触发 | RECOLLECT_TRUNCATE / RECOLLECT_DROP_RECREATE=重采';
