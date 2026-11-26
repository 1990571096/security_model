-- Rollback for sql/security_model_gpu_lease.sql.
-- DROP removes lease history; only run it after confirming no task is executing.
DROP TABLE IF EXISTS `sys_gpu_lease`;
