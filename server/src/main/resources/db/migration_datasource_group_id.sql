-- 数据源分组功能：为 source_datasource 和 target_datasource 表新增 group_id 字段
-- 复用现有 task_group 表

ALTER TABLE source_datasource ADD COLUMN group_id BIGINT DEFAULT NULL;
ALTER TABLE target_datasource ADD COLUMN group_id BIGINT DEFAULT NULL;

-- 可选：添加索引加速按分组查询
CREATE INDEX idx_source_datasource_group_id ON source_datasource(group_id);
CREATE INDEX idx_target_datasource_group_id ON target_datasource(group_id);
