-- R3-B: request idempotency. Key scope is (owner_user_id, operation, idempotency_key).
-- Same key + same body reuses the stored response; same key + different body returns 409.
-- Business write and success record share one transaction; failed/rolled-back requests do
-- not permanently occupy the key.
CREATE TABLE IF NOT EXISTS `sys_request_idempotency` (
  `record_id` BIGINT NOT NULL AUTO_INCREMENT,
  `owner_user_id` BIGINT NOT NULL,
  `operation` VARCHAR(64) NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `resource_id` BIGINT NULL,
  `status` VARCHAR(20) NOT NULL,
  `response_code` INT NULL,
  `response_body` LONGTEXT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`record_id`),
  UNIQUE KEY `uk_request_idempotency` (`owner_user_id`, `operation`, `idempotency_key`),
  KEY `idx_request_idempotency_resource` (`resource_id`),
  KEY `idx_request_idempotency_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
