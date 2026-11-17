-- Converge legacy demo rows that were shipped as 'running' without any execution context.
--
-- sql/eladmin.sql contains two 2025 demo tasks with status='running' but no current_run_id,
-- current_attempt, execution snapshot, dispatch intent or process record. A fresh schema
-- therefore cannot prove whether a process exists; startup recovery quarantines the GPU slot
-- and keeps the scheduling gate closed. Such a row cannot represent a real executable attempt
-- because every started attempt receives a run id and attempt number.
--
-- This is a one-time baseline, not an execution shortcut:
--   * it only matches rows that are still 'running' and have no run context;
--   * it records an explicit failure with EXECUTOR_RESTARTED, so operators can retry;
--   * it never marks any task successful and never touches a task that already has a run id.

UPDATE `sys_pre_defense`
SET
  `status` = 'failed',
  `progress` = 0,
  `cleaning_result` = JSON_OBJECT(
    'executor', 'security-model-platform',
    'stage', 'recovery',
    'status', 'failed',
    'message', '应用重启导致任务中断',
    'error_code', 'EXECUTOR_RESTARTED',
    'failure_reason', '历史演示任务没有执行上下文，迁移版将其收敛为失败；可安全重试',
    'artifacts', JSON_OBJECT()
  ),
  `end_time` = COALESCE(`end_time`, NOW()),
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `status` = 'running'
  AND `current_run_id` IS NULL
  AND `current_attempt` IS NULL;
