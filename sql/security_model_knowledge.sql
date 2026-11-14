-- R6: versioned knowledge documents and chunks.
-- ACL: `visibility=shared` is readable by every authenticated user; `visibility=private`
-- is readable only by `owner_user_id` (and an admin only when cross-user read is enabled).
-- Retrieval always joins document.status='active', so retired/deleted versions cannot be
-- recalled even though their chunk rows are kept for audit/version history.
CREATE TABLE IF NOT EXISTS `sys_knowledge_document` (
  `document_id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_type` VARCHAR(32) NOT NULL,
  `source_key` VARCHAR(256) NOT NULL,
  `version` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `visibility` VARCHAR(16) NOT NULL,
  `owner_user_id` BIGINT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`document_id`),
  UNIQUE KEY `uk_knowledge_document_source_version` (`source_key`, `version`),
  KEY `idx_knowledge_document_active` (`source_key`, `status`),
  KEY `idx_knowledge_document_acl` (`visibility`, `owner_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_knowledge_chunk` (
  `chunk_id` BIGINT NOT NULL AUTO_INCREMENT,
  `document_id` BIGINT NOT NULL,
  `chunk_index` INT NOT NULL,
  `section_path` VARCHAR(512) NULL,
  `content` MEDIUMTEXT NOT NULL,
  `start_line` INT NOT NULL,
  `end_line` INT NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`chunk_id`),
  UNIQUE KEY `uk_knowledge_chunk_document_index` (`document_id`, `chunk_index`),
  KEY `idx_knowledge_chunk_document` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
