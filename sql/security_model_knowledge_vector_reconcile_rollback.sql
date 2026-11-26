-- R6 vector cleanup rollback. Stop the reconcile job first.
-- Drops only the reconciliation bookkeeping; document and chunk rows are untouched.
-- Safe to re-run: each object is checked in information_schema before it is dropped.

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND index_name = 'idx_knowledge_document_vector_cleanup');
SET @sql := IF(@idx > 0,
  'ALTER TABLE `sys_knowledge_document` DROP INDEX `idx_knowledge_document_vector_cleanup`',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_updated_at');
SET @sql := IF(@col = 1,
  'ALTER TABLE `sys_knowledge_document` DROP COLUMN `vector_cleanup_updated_at`',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_next_at');
SET @sql := IF(@col = 1,
  'ALTER TABLE `sys_knowledge_document` DROP COLUMN `vector_cleanup_next_at`',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_attempts');
SET @sql := IF(@col = 1,
  'ALTER TABLE `sys_knowledge_document` DROP COLUMN `vector_cleanup_attempts`',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sys_knowledge_document'
    AND column_name = 'vector_cleanup_status');
SET @sql := IF(@col = 1,
  'ALTER TABLE `sys_knowledge_document` DROP COLUMN `vector_cleanup_status`',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
