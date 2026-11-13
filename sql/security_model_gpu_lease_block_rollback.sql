-- Rollback for sql/security_model_gpu_lease_block.sql.
-- Clearing the block columns removes isolation evidence; only run when no task is executing
-- and no orphaned Python process may still hold the GPU.
ALTER TABLE `sys_gpu_lease`
  DROP COLUMN `blocked_reason`,
  DROP COLUMN `blocked_at`;
