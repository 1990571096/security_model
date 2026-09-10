-- Add current execution context for idempotent task callbacks.
-- Run once after sql/eladmin.sql and sql/security_model_execution_snapshot.sql.

SET @schema_name = DATABASE();

SET @has_run_id = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'current_run_id'
);
SET @statement = IF(
  @has_run_id = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN current_run_id varchar(64) NULL COMMENT ''当前执行运行标识'' AFTER execution_snapshot',
  'SELECT ''current_run_id already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_attempt = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'current_attempt'
);
SET @statement = IF(
  @has_attempt = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN current_attempt int NULL COMMENT ''当前执行尝试次数'' AFTER current_run_id',
  'SELECT ''current_attempt already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_index = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND INDEX_NAME = 'idx_current_run_id'
);
SET @statement = IF(
  @has_index = 0,
  'ALTER TABLE sys_pre_defense ADD INDEX idx_current_run_id (current_run_id)',
  'SELECT ''idx_current_run_id already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
