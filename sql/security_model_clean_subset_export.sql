-- Persisted asynchronous export tasks for LDD clean subsets.
-- Run after sql/eladmin.sql and the existing security-model task context migration.

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS `sys_pre_defense_export` (
  `export_id` bigint NOT NULL AUTO_INCREMENT COMMENT '导出任务ID',
  `task_id` bigint NOT NULL COMMENT '训练前防御任务ID',
  `isolation_ratio` int NOT NULL COMMENT '隔离比例',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT 'pending, running, success, failed',
  `progress` int NOT NULL DEFAULT 0 COMMENT '导出进度百分比',
  `file_path` varchar(1024) DEFAULT NULL COMMENT 'ZIP 文件路径',
  `metadata_path` varchar(1024) DEFAULT NULL COMMENT '导出元数据路径',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码',
  `failure_reason` varchar(1000) DEFAULT NULL COMMENT '失败原因',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `create_by` varchar(255) DEFAULT NULL COMMENT '创建者',
  `update_by` varchar(255) DEFAULT NULL COMMENT '更新者',
  `create_time` datetime DEFAULT NULL COMMENT '创建日期',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`export_id`),
  UNIQUE KEY `uk_pre_defense_export_task_ratio` (`task_id`, `isolation_ratio`),
  KEY `idx_pre_defense_export_status` (`status`),
  CONSTRAINT `fk_pre_defense_export_task`
    FOREIGN KEY (`task_id`) REFERENCES `sys_pre_defense` (`task_id`)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='训练前防御清洗子集导出任务';
