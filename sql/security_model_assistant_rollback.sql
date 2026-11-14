-- R7 rollback. Drop assistant-only tables; no core task/knowledge table is touched.
DROP TABLE IF EXISTS `sys_assistant_tool_audit`;
DROP TABLE IF EXISTS `sys_assistant_usage_daily`;
DROP TABLE IF EXISTS `sys_assistant_session`;
DROP TABLE IF EXISTS `sys_assistant_confirmation`;
