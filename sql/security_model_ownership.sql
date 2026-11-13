-- R1: stable user ownership for training-before-defense tasks.
-- Run after sql/eladmin.sql, sql/security_model_execution_snapshot.sql,
-- sql/security_model_task_run_context.sql, sql/security_model_clean_subset_export.sql,
-- and sql/security_model_operation_audit.sql.
--
-- TWO-PASS USE (the script never guesses ownership on its own):
--
--   Pass 1 (default): adds the three marker columns and the index, then prints review
--           queries. No row is modified because @apply_backfill = 0.
--   Review: read the output. Decide which task ids are confirmed demo/seed rows, and
--           which matched rows must be rejected.
--   Pass 2: set @apply_backfill = 1, @demo_task_ids = '<ids you confirmed>',
--           @reject_task_ids = '<matched ids you reject>' and rerun the script. It is
--           idempotent: existing columns are kept, backfill only fills NULL markers.
--
-- Take a backup first, e.g.:
--   mysqldump -u root -p --single-transaction <db> sys_pre_defense > sys_pre_defense_before_ownership.sql
--
-- The script never rewrites status, cleaning_result, execution_snapshot, run id or
-- create_by; it only writes owner_user_id / data_origin / owner_backfill_status.
-- See docs/sdd/05-decisions.md ADR-013 and 18-multiuser-ownership-and-resources.md §4.

SET @schema_name = DATABASE();

-- ---------------------------------------------------------------------------
-- 0. Operator switches. Keep the defaults for the first (review-only) pass.
--    Pass 2: in the SAME mysql session run
--        SET @apply_backfill = 1, @demo_task_ids = '<ids>', @reject_task_ids = '<ids>';
--    then run this script again. IFNULL keeps values the operator already set and
--    only fills the unset ones, so re-running never silently resets the switch.
-- ---------------------------------------------------------------------------
SET @apply_backfill = IFNULL(@apply_backfill, 0);   -- 1 = write markers (after reviewing pass 1)
SET @demo_task_ids = IFNULL(@demo_task_ids, '');    -- comma separated task ids confirmed as demo/seed rows
SET @reject_task_ids = IFNULL(@reject_task_ids, '');-- comma separated task ids whose username match is wrong

SELECT @apply_backfill AS apply_backfill, @demo_task_ids AS demo_task_ids,
       @reject_task_ids AS reject_task_ids;

-- ---------------------------------------------------------------------------
-- 1. Capture the pre-migration state so column creation runs only once.
-- ---------------------------------------------------------------------------
SET @had_owner_column = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'owner_user_id'
);

-- ---------------------------------------------------------------------------
-- 2. Add the columns and the index (each guarded, so the script is re-runnable).
-- ---------------------------------------------------------------------------
SET @statement = IF(
  @had_owner_column = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN owner_user_id bigint NULL COMMENT ''任务归属用户ID(sys_user.user_id)，授权唯一依据'' AFTER current_attempt',
  'SELECT ''owner_user_id already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_origin = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'data_origin'
);
SET @statement = IF(
  @has_origin = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN data_origin varchar(16) NULL COMMENT ''数据来源: user-平台创建, legacy-历史真实任务, demo-演示数据'' AFTER owner_user_id',
  'SELECT ''data_origin already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_backfill = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND COLUMN_NAME = 'owner_backfill_status'
);
SET @statement = IF(
  @has_backfill = 0,
  'ALTER TABLE sys_pre_defense ADD COLUMN owner_backfill_status varchar(16) NULL COMMENT ''归属回填状态: matched-已匹配, unattributed-无法归属'' AFTER data_origin',
  'SELECT ''owner_backfill_status already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @has_index = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'sys_pre_defense'
    AND INDEX_NAME = 'idx_owner_status'
);
SET @statement = IF(
  @has_index = 0,
  'ALTER TABLE sys_pre_defense ADD INDEX idx_owner_status (owner_user_id, status)',
  'SELECT ''idx_owner_status already exists'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- ---------------------------------------------------------------------------
-- 3. Review queries (always printed). Use their output to fill the operator
--    switches above before running pass 2.
-- ---------------------------------------------------------------------------
-- 3a. Every historical row with its current owner guess, so ids can be judged one by one.
SELECT task_id, task_name, status, progress, create_by, create_time, update_time,
       current_run_id, execution_snapshot IS NULL AS snapshot_missing,
       cleaning_result IS NULL AS result_missing,
       LEFT(CAST(cleaning_result AS CHAR), 120) AS result_preview
FROM sys_pre_defense
ORDER BY task_id;

-- 3b. Rows that look like seeded demo data: never executed by the platform adapter,
--     no frozen snapshot, and either no result or a simulated result.
SELECT task_id, task_name, status, progress, create_by,
       (current_run_id IS NULL) AS run_id_missing,
       (execution_snapshot IS NULL) AS snapshot_missing,
       (cleaning_result IS NULL) AS result_missing
FROM sys_pre_defense
WHERE execution_snapshot IS NULL
ORDER BY task_id;

-- 3c. Rows still marked running but with no run id (not restartable, should not look alive).
SELECT task_id, task_name, create_by, progress, start_time, update_time
FROM sys_pre_defense
WHERE status = 'running' AND (current_run_id IS NULL OR current_run_id = '')
ORDER BY task_id;

-- 3d. How many distinct authors exist, so an unmapped author is visible before backfill.
SELECT create_by, COUNT(*) AS task_count
FROM sys_pre_defense
GROUP BY create_by
ORDER BY task_count DESC;

-- ---------------------------------------------------------------------------
-- 4. Backfill (only when @apply_backfill = 1).
--    Order matters: demo isolation first, then username match, then the rest.
-- ---------------------------------------------------------------------------
SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense SET owner_user_id = NULL, owner_backfill_status = ''unattributed'', data_origin = ''demo'' WHERE FIND_IN_SET(task_id, @demo_task_ids) > 0',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense t JOIN sys_user u ON u.username = t.create_by SET t.owner_user_id = u.user_id, t.owner_backfill_status = ''matched'' WHERE t.owner_backfill_status IS NULL AND (t.data_origin IS NULL OR t.data_origin <> ''demo'')',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense SET owner_user_id = NULL, owner_backfill_status = ''unattributed'' WHERE FIND_IN_SET(task_id, @reject_task_ids) > 0',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense SET owner_backfill_status = ''unattributed'' WHERE owner_user_id IS NULL AND owner_backfill_status IS NULL',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense SET data_origin = ''legacy'' WHERE data_origin IS NULL',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- ---------------------------------------------------------------------------
-- 5. Verification. Keep the output for the handover record.
-- ---------------------------------------------------------------------------
SELECT owner_backfill_status, data_origin, COUNT(*) AS task_count
FROM sys_pre_defense
GROUP BY owner_backfill_status, data_origin
ORDER BY owner_backfill_status, data_origin;

-- Rows still marked running without a run id remain "alive-looking" but not restartable.
SELECT COUNT(*) AS orphan_running_tasks
FROM sys_pre_defense
WHERE status = 'running' AND (current_run_id IS NULL OR current_run_id = '');

-- Demo rows that carry a terminal status. This counts rows, it does not test the
-- statistics endpoint.
SELECT COUNT(*) AS demo_rows_with_terminal_status
FROM sys_pre_defense
WHERE data_origin = 'demo' AND status IN ('success', 'failed');

-- Every ownership decision written by this script, for row-level review.
SELECT task_id, create_by, owner_user_id, owner_backfill_status, data_origin
FROM sys_pre_defense
ORDER BY task_id;
