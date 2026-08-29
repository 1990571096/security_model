-- Add immutable-at-launch experiment snapshots for existing deployments.
-- Run this once after sql/eladmin.sql.

SET @schema_name = DATABASE();
SET @has_column = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'execution_snapshot'
);
SET @statement = IF(
  @has_column = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN execution_snapshot JSON NULL COMMENT ''执行参数快照(JSON格式): 每次启动时冻结的资源与参数'' AFTER cleaning_result',
  'SELECT ''execution_snapshot already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
