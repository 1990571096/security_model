-- Private experiment notes. User-owned; no cross-user reads.
CREATE TABLE IF NOT EXISTS `sys_experiment_note` (
  `note_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(120) NOT NULL,
  `analysis_time` DATETIME NOT NULL,
  `run_ref_json` TEXT NOT NULL,
  `snapshot_json` MEDIUMTEXT NOT NULL,
  `next_step` VARCHAR(500) NULL,
  `request_key` VARCHAR(64) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'active',
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`note_id`),
  UNIQUE KEY `uk_experiment_note_request` (`user_id`, `request_key`),
  KEY `idx_experiment_note_user` (`user_id`, `status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
