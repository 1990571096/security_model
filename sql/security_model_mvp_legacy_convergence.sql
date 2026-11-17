-- Optional one-time legacy convergence migration for the MVP seed.
--
-- THIS FILE IS NOT RUN BY scripts/dev/InitializeSecurityModelDatabase.ps1.
-- Apply it explicitly, once, when an operator wants the historical behavior:
--   * disable old builtin://baseline/% algorithms that have no executable adapter;
--   * disable/deprecate the old "321" demo rows;
--   * reduce legacy ELADMIN menu visibility.
--
-- Keeping these writes out of the normal seed means a rerun cannot overwrite an
-- operator's enabled flags, update_time or menu choices. New builtin rows remain
-- visible but cannot be created/started because the command service only accepts the
-- LDD implementation path.

UPDATE `sys_algorithm`
SET `enabled` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `implementation_path` LIKE 'builtin://baseline/%';

UPDATE `sys_algorithm`
SET `enabled` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `algorithm_name` IN ('321');

UPDATE `sys_model`
SET `status` = 'deprecated', `update_by` = 'system', `update_time` = NOW()
WHERE `model_name` IN ('321');

UPDATE `sys_menu`
SET `hidden` = b'1', `update_by` = 'system', `update_time` = NOW()
WHERE `menu_id` NOT IN (122, 117, 123, 124, 125, 146);

UPDATE `sys_menu`
SET `hidden` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `menu_id` IN (122, 117, 123, 124, 125, 146);
