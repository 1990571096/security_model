-- R6 vector cleanup upgrade: persistent cleanup state and retry time.
-- Run after security_model_knowledge.sql exists. Each statement is guarded by
-- information_schema, so re-running the script is safe (no duplicate column/index).
-- Stop the reconcile job before applying; it can resume from the persisted state.

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_status');
SET @sql := IF(@col = 0,
  'ALTER TABLE `sys_knowledge_document` ADD COLUMN `vector_cleanup_status` VARCHAR(16) NOT NULL DEFAULT ''pending''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_attempts');
SET @sql := IF(@col = 0,
  'ALTER TABLE `sys_knowledge_document` ADD COLUMN `vector_cleanup_attempts` INT NOT NULL DEFAULT 0',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_next_at');
SET @sql := IF(@col = 0,
  'ALTER TABLE `sys_knowledge_document` ADD COLUMN `vector_cleanup_next_at` DATETIME(3) NULL',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_updated_at');
SET @sql := IF(@col = 0,
  'ALTER TABLE `sys_knowledge_document` ADD COLUMN `vector_cleanup_updated_at` DATETIME(3) NULL',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND index_name = 'idx_knowledge_document_vector_cleanup');
SET @sql := IF(@idx = 0,
  'ALTER TABLE `sys_knowledge_document` ADD KEY `idx_knowledge_document_vector_cleanup` (`status`, `vector_cleanup_status`, `vector_cleanup_next_at`, `document_id`)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Existing retired rows must be visible to the reconciler after the upgrade.
UPDATE `sys_knowledge_document`
SET `vector_cleanup_status` = 'pending',
    `vector_cleanup_next_at` = COALESCE(`vector_cleanup_next_at`, `updated_at`),
    `vector_cleanup_updated_at` = NOW(3)
WHERE `status` = 'retired'
  AND `vector_cleanup_status` = 'pending'
  AND `vector_cleanup_next_at` IS NULL;
