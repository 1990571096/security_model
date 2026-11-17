-- Historical sample datasets shipped by sql/eladmin.sql carry hard-coded paths under
-- /uploads/datasets and a fabricated "success" status. The phase-5 VaB seed also wrote
-- "success" for a directory-only check. Both are registration fixtures, not proof that the
-- migration backend can parse those files. Keep the rows for history, but remove the fake
-- verification so they do not appear in the available-dataset query.
--
-- Safe to rerun: only rows still carrying the original seed message are matched. Once an
-- administrator runs the real verification endpoint, parse_message changes and this script
-- leaves the row alone.

UPDATE `sys_dataset`
SET
  `parse_status` = 'pending',
  `sample_count` = NULL,
  `feature_count` = NULL,
  `class_count` = NULL,
  `parse_message` = '历史样例数据：迁移版尚未验证，不作为真实可用资源；请管理员执行数据集验证',
  `parse_time` = NULL,
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `parse_status` = 'success'
  AND (
    (`file_path` LIKE '/uploads/datasets/%' AND `parse_message` LIKE '解析成功%')
    OR `parse_message` = 'Deployment-provided VaB runtime dataset root; the caller verified the path.'
  );
