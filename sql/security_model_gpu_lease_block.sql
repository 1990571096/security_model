-- R2 hardening: block the single GPU slot while an orphaned executor process is unconfirmed.
-- Run once on environments that already created sys_gpu_lease. Idempotent guard is not provided;
-- re-running fails with "Duplicate column" and changes nothing.
ALTER TABLE `sys_gpu_lease`
  ADD COLUMN `blocked_at` DATETIME(3) NULL AFTER `released_at`,
  ADD COLUMN `blocked_reason` VARCHAR(64) NULL AFTER `blocked_at`;
