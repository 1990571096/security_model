-- R7: controlled experiment assistant. Only hashed confirmation tokens are stored; raw
-- tokens never leave the preview response. Sessions and tool audits are scoped by user id.
CREATE TABLE IF NOT EXISTS `sys_assistant_confirmation` (
  `confirmation_id` BIGINT NOT NULL AUTO_INCREMENT,
  `token_hash` CHAR(64) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `operation` VARCHAR(32) NOT NULL,
  `target_task_id` BIGINT NULL,
  `target_run_id` VARCHAR(64) NULL,
  `context_hash` CHAR(64) NOT NULL,
  `request_json` MEDIUMTEXT NOT NULL,
  `preview_json` MEDIUMTEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `result_code` INT NULL,
  `result_json` MEDIUMTEXT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  `consumed_at` DATETIME(3) NULL,
  PRIMARY KEY (`confirmation_id`),
  UNIQUE KEY `uk_assistant_confirmation_token` (`token_hash`),
  KEY `idx_assistant_confirmation_user` (`user_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_assistant_session` (
  `session_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(160) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `summary_json` MEDIUMTEXT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`session_id`),
  KEY `idx_assistant_session_user` (`user_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_assistant_tool_audit` (
  `audit_id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT NULL,
  `user_id` BIGINT NOT NULL,
  `tool_name` VARCHAR(64) NOT NULL,
  `operation` VARCHAR(32) NULL,
  `target_task_id` BIGINT NULL,
  `target_run_id` VARCHAR(64) NULL,
  `result_code` VARCHAR(64) NOT NULL,
  `duration_ms` INT NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`audit_id`),
  KEY `idx_assistant_audit_user_time` (`user_id`, `created_at`),
  KEY `idx_assistant_audit_target` (`target_task_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_assistant_usage_daily` (
  `user_id` BIGINT NOT NULL,
  `usage_date` DATE NOT NULL,
  `request_count` INT NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`user_id`, `usage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
