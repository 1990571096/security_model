-- R4: transactional outbox + worker consumption ledger. Re-runnable.
-- The outbox row is written in the same MySQL transaction as task claim + dispatch intent.
-- RabbitMQ only carries the committed event; MySQL remains the task fact source.

-- Status values: new, publishing, published, failed, dead.
-- publishing is a lease: claim_owner/claim_token/claim_until; an expired lease is reclaimed to failed.
CREATE TABLE IF NOT EXISTS `sys_dispatch_outbox` (
  `outbox_id` BIGINT NOT NULL AUTO_INCREMENT,
  `message_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `task_id` BIGINT NOT NULL,
  `run_id` VARCHAR(64) NOT NULL,
  `attempt` INT NOT NULL,
  `intent_id` BIGINT NOT NULL,
  `payload_hash` CHAR(64) NOT NULL,
  `payload_json` LONGTEXT NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `publish_attempts` INT NOT NULL DEFAULT 0,
  `claim_owner` VARCHAR(128) NULL,
  `claim_until` DATETIME(3) NULL,
  `claim_token` CHAR(36) NULL,
  `next_at` DATETIME(3) NOT NULL,
  `last_error` VARCHAR(255) NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `published_at` DATETIME(3) NULL,
  PRIMARY KEY (`outbox_id`),
  UNIQUE KEY `uk_dispatch_outbox_message` (`message_id`),
  UNIQUE KEY `uk_dispatch_outbox_run` (`task_id`, `run_id`, `attempt`, `event_type`),
  KEY `idx_dispatch_outbox_claim` (`status`, `next_at`),
  KEY `idx_dispatch_outbox_expiry` (`status`, `claim_until`),
  KEY `idx_dispatch_outbox_task` (`task_id`, `run_id`, `attempt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Consumption status values: processing, retry, terminal. A terminal row is the durable
-- idempotency ledger; outcome carries success/failed/canceled/duplicate/stale/uncertain.
CREATE TABLE IF NOT EXISTS `sys_dispatch_consumption` (
  `consumption_id` BIGINT NOT NULL AUTO_INCREMENT,
  `message_id` VARCHAR(64) NOT NULL,
  `task_id` BIGINT NOT NULL,
  `run_id` VARCHAR(64) NOT NULL,
  `attempt` INT NOT NULL,
  `worker_id` VARCHAR(128) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `outcome` VARCHAR(32) NULL,
  `error_code` VARCHAR(64) NULL,
  `claim_owner` VARCHAR(128) NULL,
  `claim_until` DATETIME(3) NULL,
  `claim_token` CHAR(36) NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `terminal_at` DATETIME(3) NULL,
  PRIMARY KEY (`consumption_id`),
  UNIQUE KEY `uk_dispatch_consumption_message` (`message_id`),
  KEY `idx_dispatch_consumption_claim` (`status`, `claim_until`),
  KEY `idx_dispatch_consumption_run` (`task_id`, `run_id`, `attempt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
