-- R3-A rollback. Drops only the dispatch-intent table added by R3-A.
-- Run only after all tasks are terminal and the application is stopped.
DROP TABLE IF EXISTS `sys_task_dispatch_intent`;
