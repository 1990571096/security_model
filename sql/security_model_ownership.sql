-- R1: stable user ownership for training-before-defense tasks.
-- Run after sql/eladmin.sql, sql/security_model_execution_snapshot.sql,
-- sql/security_model_task_run_context.sql, sql/security_model_clean_subset_export.sql,
-- and sql/security_model_operation_audit.sql.
--
-- TWO-PASS USE (the script never guesses ownership on its own):
--
--   Pass 1 (default): adds the marker columns, the index and the decision table,
--           then prints review queries. No row is modified because @apply_backfill = 0.
--   Review: read the printed rows. Record every decision in
--           sys_pre_defense_ownership_decision (one row per task id):
--             decision='demo'   -> seed/demo row, never a real run
--             decision='assign' -> explicit owner (user_id required)
--             decision='reject' -> revoke an owner matched by an older script version
--           Each decision requires reason and decided_by. The primary key on task_id
--           makes duplicate or conflicting mappings impossible.
--   Pass 2: run
--             SET @apply_backfill = 1;
--           in the SAME session and run this script again. It is idempotent: columns
--           are kept and only NULL markers are filled. Invalid decisions abort the
--           whole backfill before any write: the script prints the offending rows and
--           then fails on an intentionally invalid column reference (MySQL does not
--           allow SIGNAL through PREPARE).
--
-- Take a backup first, e.g.:
--   mysqldump -u root -p --single-transaction <db> sys_pre_defense > sys_pre_defense_before_ownership.sql
--
-- The script never rewrites status, cleaning_result, execution_snapshot, run id or
-- create_by; it only writes owner_user_id / data_origin / owner_backfill_status.
-- See docs/sdd/05-decisions.md ADR-013 and 18-multiuser-ownership-and-resources.md §4.

SET @schema_name = DATABASE();

-- ---------------------------------------------------------------------------
-- 0. Operator switch. Keep the default for the first (review-only) pass.
--    IFNULL keeps a value the operator already set, so re-running never silently
--    resets the switch.
-- ---------------------------------------------------------------------------
SET @apply_backfill = IFNULL(@apply_backfill, 0);

SELECT @apply_backfill AS apply_backfill;

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
-- 2b. Decision table: the only place ownership of historical rows may be decided.
--     Structured and auditable instead of free-form id strings.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_pre_defense_ownership_decision (
  task_id BIGINT NOT NULL COMMENT 'sys_pre_defense.task_id',
  decision VARCHAR(16) NOT NULL COMMENT 'demo / assign / reject',
  user_id BIGINT NULL COMMENT 'assign 时必填，对应 sys_user.user_id',
  reason VARCHAR(255) NOT NULL COMMENT '人工判断依据',
  decided_by VARCHAR(64) NOT NULL COMMENT '审核人账号',
  decided_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R1 历史任务归属人工决策（审计依据）';

-- ---------------------------------------------------------------------------
-- 3. Review queries (always printed). Record decisions after reading them.
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

-- 3e. Decisions recorded so far, with the row each one targets.
SELECT d.task_id, d.decision, d.user_id, d.reason, d.decided_by, d.decided_at,
       t.task_name, t.status, t.create_by
FROM sys_pre_defense_ownership_decision d
LEFT JOIN sys_pre_defense t ON t.task_id = d.task_id
ORDER BY d.task_id;

-- ---------------------------------------------------------------------------
-- 4. Validation. Unknown task ids, unknown users, invalid decision values,
--    missing reason/decided_by and a non-NULL user_id on demo/reject are fatal
--    when backfill is applied: the script prints the offending rows and aborts
--    before writing anything.
-- ---------------------------------------------------------------------------
SELECT d.task_id, d.decision, d.user_id, d.reason, d.decided_by,
       (t.task_id IS NULL) AS unknown_task,
       (d.decision NOT IN ('demo', 'assign', 'reject')) AS invalid_decision,
       (d.decision = 'assign' AND (d.user_id IS NULL OR u.user_id IS NULL)) AS invalid_assign_user,
       (d.decision <> 'assign' AND d.user_id IS NOT NULL) AS unexpected_user_id,
       (d.reason IS NULL OR TRIM(d.reason) = ''
         OR d.decided_by IS NULL OR TRIM(d.decided_by) = '') AS missing_audit_fields
FROM sys_pre_defense_ownership_decision d
LEFT JOIN sys_pre_defense t ON t.task_id = d.task_id
LEFT JOIN sys_user u ON u.user_id = d.user_id
WHERE t.task_id IS NULL
   OR d.decision NOT IN ('demo', 'assign', 'reject')
   OR (d.decision = 'assign' AND (d.user_id IS NULL OR u.user_id IS NULL))
   OR (d.decision <> 'assign' AND d.user_id IS NOT NULL)
   OR d.reason IS NULL OR TRIM(d.reason) = ''
   OR d.decided_by IS NULL OR TRIM(d.decided_by) = ''
ORDER BY d.task_id;

SET @invalid_decisions = (
  SELECT COUNT(*)
  FROM sys_pre_defense_ownership_decision d
  LEFT JOIN sys_pre_defense t ON t.task_id = d.task_id
  LEFT JOIN sys_user u ON u.user_id = d.user_id
  WHERE t.task_id IS NULL
     OR d.decision NOT IN ('demo', 'assign', 'reject')
     OR (d.decision = 'assign' AND (d.user_id IS NULL OR u.user_id IS NULL))
     OR (d.decision <> 'assign' AND d.user_id IS NOT NULL)
     OR d.reason IS NULL OR TRIM(d.reason) = ''
     OR d.decided_by IS NULL OR TRIM(d.decided_by) = ''
);

SELECT @invalid_decisions AS invalid_decisions;

SET @statement = IF(
  @apply_backfill = 1 AND @invalid_decisions > 0,
  'SELECT ownership_decisions_invalid__fix_sys_pre_defense_ownership_decision FROM sys_pre_defense LIMIT 1',
  'SELECT ''decisions validated'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- ---------------------------------------------------------------------------
-- 5. Backfill (only when @apply_backfill = 1).
--    Order matters: demo isolation, explicit assignment, explicit revocation,
--    then everything that still has no decision becomes unattributed.
--    Unknown authors are never matched automatically.
-- ---------------------------------------------------------------------------
SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense t JOIN sys_pre_defense_ownership_decision d ON d.task_id = t.task_id AND d.decision = ''demo'' SET t.owner_user_id = NULL, t.owner_backfill_status = ''unattributed'', t.data_origin = ''demo''',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense t JOIN sys_pre_defense_ownership_decision d ON d.task_id = t.task_id AND d.decision = ''assign'' JOIN sys_user u ON u.user_id = d.user_id SET t.owner_user_id = u.user_id, t.owner_backfill_status = ''matched'', t.data_origin = ''legacy''',
  'SELECT ''backfill not applied: set @apply_backfill = 1 after review'''
);
PREPARE migration_statement FROM @statement;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @statement = IF(
  @apply_backfill = 1,
  'UPDATE sys_pre_defense t JOIN sys_pre_defense_ownership_decision d ON d.task_id = t.task_id AND d.decision = ''reject'' SET t.owner_user_id = NULL, t.owner_backfill_status = ''unattributed'', t.data_origin = ''legacy''',
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
-- 6. Verification. Keep the output for the handover record.
-- ---------------------------------------------------------------------------
-- 6a. Every row whose ownership came from a manual decision, with the decision itself.
SELECT t.task_id, d.decision, d.user_id AS decided_user_id,
       t.owner_user_id, t.data_origin, t.owner_backfill_status, d.reason, d.decided_by, d.decided_at
FROM sys_pre_defense_ownership_decision d
JOIN sys_pre_defense t ON t.task_id = d.task_id
ORDER BY t.task_id;

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
