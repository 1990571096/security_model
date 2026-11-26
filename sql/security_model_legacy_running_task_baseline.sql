-- Converge legacy demo rows that were shipped as 'running' without any execution context.
--
-- sql/eladmin.sql contains two 2025 seed demo tasks (task_id 3 and 4) with status='running'
-- but no current_run_id, current_attempt, execution snapshot, dispatch intent or process
-- record. A fresh schema therefore cannot prove whether a process exists; startup recovery
-- quarantines the GPU slot and keeps the scheduling gate closed.
--
-- Only those exact seed rows are converged here. An arbitrary old row with the same
-- "running but no run context" shape is not evidence that it is a demo row, so it is
-- deliberately left untouched and reported for review.
--
-- This is a one-time baseline, not an execution shortcut:
--   * it matches only the two seed demo rows by task_id, exact task_name and create_by,
--     in addition to the missing-run-context predicate;
--   * it records an explicit failure with EXECUTOR_RESTARTED, but does not claim that an
--     unknown historical row is safe to retry;
--   * it never marks any task successful and never touches a task that already has a run id;
--   * any other running row without run context is left untouched and is printed by the
--     initializer as an audit list for an operator.

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
    'baseline', 'legacy-seed-running-3-4',
    'failure_reason', '历史种子任务（ID 3/4，create_by=test）缺少迁移版执行上下文；基线只收敛状态，是否重试需人工审核',
    'artifacts', JSON_OBJECT()
  ),
  `end_time` = COALESCE(`end_time`, NOW()),
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `status` = 'running'
  AND `current_run_id` IS NULL
  AND `current_attempt` IS NULL
  AND `create_by` = 'test'
  AND (
    (`task_id` = 3 AND `task_name` = 'MNIST数据预处理')
    OR (`task_id` = 4 AND `task_name` = '文本数据清洗任务')
  );
