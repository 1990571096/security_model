-- R2: single-GPU MySQL lease (one physical GPU per machine, no RabbitMQ / no distributed scheduler).
-- Run once per environment before enabling task start. Re-runnable.
CREATE TABLE IF NOT EXISTS `sys_gpu_lease` (
  `slot_id` VARCHAR(32) NOT NULL,
  `lease_token` CHAR(36) NULL,
  `task_id` BIGINT NULL,
  `run_id` VARCHAR(64) NULL,
  `attempt` INT NULL,
  `fencing_token` BIGINT NOT NULL DEFAULT 0,
  `acquired_at` DATETIME(3) NULL,
  `heartbeat_at` DATETIME(3) NULL,
  `expires_at` DATETIME(3) NULL,
  `released_at` DATETIME(3) NULL,
  PRIMARY KEY (`slot_id`),
  KEY `idx_gpu_lease_active` (`lease_token`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `sys_gpu_lease` (`slot_id`, `fencing_token`) VALUES ('default-gpu-0', 0);
