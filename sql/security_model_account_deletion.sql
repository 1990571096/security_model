-- Keep deleted account IDs and usernames reserved for historical ownership.
SET @account_deletion_sql := IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'deleted_at'
), 'DO 0', 'ALTER TABLE `sys_user` ADD COLUMN `deleted_at` DATETIME(3) NULL');
PREPARE account_deletion_stmt FROM @account_deletion_sql;
EXECUTE account_deletion_stmt;
DEALLOCATE PREPARE account_deletion_stmt;
