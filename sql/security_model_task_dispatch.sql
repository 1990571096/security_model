-- R3-A: persistent dispatch intent. Written in the same transaction as task claim + GPU lease.
-- Re-runnable. Apply to a fresh database or as an upgrade; no existing rows are modified.
CREATE TABLE IF NOT EXISTS `sys_task_dispatch_intent` (
  `intent_id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `run_id` VARCHAR(64) NOT NULL,
  `attempt` INT NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `schema_version` INT NOT NULL DEFAULT 1,
  `request_json` LONGTEXT NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `runtime_hash` CHAR(64) NULL,
  `error_code` VARCHAR(64) NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `submitted_at` DATETIME(3) NULL,
  PRIMARY KEY (`intent_id`),
  UNIQUE KEY `uk_dispatch_intent_run` (`task_id`, `run_id`, `attempt`),
  KEY `idx_dispatch_intent_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
