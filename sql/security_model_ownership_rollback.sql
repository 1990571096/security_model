-- Rollback for sql/security_model_ownership.sql (R1 ownership markers).
-- This removes the three marker columns and the owner index. It does NOT touch
-- status, cleaning_result, execution_snapshot or create_by.
--
-- Take a backup first:
--   mysqldump -u root -p --single-transaction <db> sys_pre_defense > sys_pre_defense_before_ownership_rollback.sql
--
-- After rollback the current backend jar will fail on task queries until the
-- previous jar is restored: the new code reads owner_user_id.

SET @schema_name = DATABASE();

SET @has_index = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND INDEX_NAME = 'idx_owner_status'
);
SET @statement = IF(
  @has_index > 0,
  'ALTER TABLE sys_pre_defense DROP INDEX idx_owner_status',
  'SELECT ''idx_owner_status already absent'''
);
PREPARE rollback_statement FROM @statement;
EXECUTE rollback_statement;
DEALLOCATE PREPARE rollback_statement;

SET @has_backfill = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'owner_backfill_status'
);
SET @statement = IF(
  @has_backfill > 0,
  'ALTER TABLE sys_pre_defense DROP COLUMN owner_backfill_status',
  'SELECT ''owner_backfill_status already absent'''
);
PREPARE rollback_statement FROM @statement;
EXECUTE rollback_statement;
DEALLOCATE PREPARE rollback_statement;

SET @has_origin = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'data_origin'
);
SET @statement = IF(
  @has_origin > 0,
  'ALTER TABLE sys_pre_defense DROP COLUMN data_origin',
  'SELECT ''data_origin already absent'''
);
PREPARE rollback_statement FROM @statement;
EXECUTE rollback_statement;
DEALLOCATE PREPARE rollback_statement;

SET @has_owner = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'owner_user_id'
);
SET @statement = IF(
  @has_owner > 0,
  'ALTER TABLE sys_pre_defense DROP COLUMN owner_user_id',
  'SELECT ''owner_user_id already absent'''
);
PREPARE rollback_statement FROM @statement;
EXECUTE rollback_statement;
DEALLOCATE PREPARE rollback_statement;

SELECT COUNT(*) AS remaining_ownership_columns
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = @schema_name
  AND TABLE_NAME = 'sys_pre_defense'
  AND COLUMN_NAME IN ('owner_user_id', 'data_origin', 'owner_backfill_status');
