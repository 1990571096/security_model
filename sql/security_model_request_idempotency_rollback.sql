-- R3-B rollback. Drops only the request-idempotency table added by R3-B.
DROP TABLE IF EXISTS `sys_request_idempotency`;
