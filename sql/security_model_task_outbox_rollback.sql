-- R4 rollback. Run only after dispatch.mode=db and Worker/Consumer are stopped.
-- Published rows and audit retention are intentionally not deleted by application code.
DROP TABLE IF EXISTS `sys_dispatch_consumption`;
DROP TABLE IF EXISTS `sys_dispatch_outbox`;
