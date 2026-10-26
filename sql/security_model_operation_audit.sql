-- Independent operation audit table for the migrated security model platform.
-- Run after sql/eladmin.sql and the security-model task tables.

CREATE TABLE IF NOT EXISTS `security_operation_audit` (
  `audit_id` bigint NOT NULL AUTO_INCREMENT COMMENT '审计记录ID',
  `resource_type` varchar(64) NOT NULL COMMENT '资源类型',
  `resource_id` bigint NOT NULL COMMENT '资源ID',
  `action` varchar(64) NOT NULL COMMENT '业务动作',
  `operator` varchar(255) NOT NULL COMMENT '操作人',
  `before_status` varchar(32) DEFAULT NULL COMMENT '操作前状态',
  `after_status` varchar(32) DEFAULT NULL COMMENT '操作后状态',
  `request_snapshot` json DEFAULT NULL COMMENT '请求参数快照',
  `result_message` varchar(1000) DEFAULT NULL COMMENT '操作结果说明',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`audit_id`),
  KEY `idx_security_audit_resource` (`resource_type`, `resource_id`),
  KEY `idx_security_audit_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='安全模型平台业务操作审计记录';
