-- R6 rollback: drop only the knowledge tables. Task/report/audit data is untouched.
DROP TABLE IF EXISTS `sys_knowledge_chunk`;
DROP TABLE IF EXISTS `sys_knowledge_document`;
