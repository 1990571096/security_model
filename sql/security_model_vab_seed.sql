-- LDD real training-pre-defense algorithm registration.
-- Run after sql/eladmin.sql and sql/security_model_mvp_seed.sql.
--
-- The runtime dataset path is deployment-specific and is therefore NOT stored in this
-- file. The caller sets @vab_dataset_root before running the script, for example:
--   SET @vab_dataset_root = '<absolute path to VaB-main/dataset>';
--   SOURCE sql/security_model_vab_seed.sql;
-- scripts/dev/InitializeSecurityModelDatabase.ps1 does this from -VabDatasetRoot or
-- SECURITY_MODEL_VAB_DATASET_ROOT and verifies the directory exists first.
--
-- When @vab_dataset_root is NULL or empty the dataset row is skipped, while the LDD
-- algorithm and its default hyper-parameter template are still registered. A dataset is
-- never written with a guessed path or a fake "success" parse status.

SET @vab_root = NULLIF(TRIM(@vab_dataset_root), '');

INSERT INTO `sys_dataset` (
  `dataset_name`, `description`, `enabled`, `dataset_type`, `file_path`, `file_name`,
  `file_size`, `sample_count`, `feature_count`, `class_count`, `parse_status`,
  `parse_message`, `parse_time`, `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VaB CIFAR-10 Runtime Root',
  'Runtime VaB dataset root supplied by the deployment. The initializer verifies the directory exists before marking it parsed.',
  b'1', 'image',
  @vab_root,
  SUBSTRING_INDEX(@vab_root, '/', -1),
  NULL, 50000, 3072, 10, 'success',
  'Deployment-provided VaB runtime dataset root; the caller verified the path.',
  NOW(), 'system', 'system', NOW(), NOW()
FROM (SELECT 1) AS anchor
WHERE @vab_root IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_dataset` WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root'
  );

-- Only refresh the path when the caller supplied one. Running this script without a root
-- must not erase a dataset path that a previous deployment already configured.
-- CAST(... AS BINARY) keeps the comparison byte-wise so it does not depend on the
-- connection collation.
SET @vab_needs_update = (
  SELECT COUNT(*) FROM `sys_dataset`
  WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root'
    AND (
      `file_path` IS NULL
      OR CAST(`file_path` AS BINARY) <> CAST(@vab_root AS BINARY)
      OR `parse_status` <> 'success'
      OR `enabled` <> b'1'
    )
);
UPDATE `sys_dataset`
SET
  `description` = 'Runtime VaB dataset root supplied by the deployment. The initializer verifies the directory exists before marking it parsed.',
  `enabled` = b'1',
  `dataset_type` = 'image',
  `file_path` = @vab_root,
  `file_name` = SUBSTRING_INDEX(@vab_root, '/', -1),
  `sample_count` = 50000,
  `feature_count` = 3072,
  `class_count` = 10,
  `parse_status` = 'success',
  `parse_message` = 'Deployment-provided VaB runtime dataset root; the caller verified the path.',
  `parse_time` = NOW(),
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root'
  AND @vab_root IS NOT NULL
  AND @vab_needs_update > 0;

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'LDD (VaB)',
  'LDD training-pre-defense algorithm executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/ldd',
  '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"num_epochs":5,"lr_poison":0.001,"r":0.75,"alpha1":0.1,"alpha2":0.5,"seed":123}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"SIG"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.1},"batch_size":{"type":"int","min":16,"max":512,"default":128},"num_epochs":{"type":"int","min":1,"max":200,"default":5},"lr_poison":{"type":"float","min":0.00001,"max":1.0,"default":0.001},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"alpha1":{"type":"float","min":0.0,"max":10.0,"default":0.1},"alpha2":{"type":"float","min":0.0,"max":10.0,"default":0.5},"seed":{"type":"int","min":0,"max":999999,"default":123}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/ldd'
);

UPDATE `sys_algorithm`
SET
  `algorithm_name` = 'LDD (VaB)',
  `description` = 'LDD training-pre-defense algorithm executed by the external Python adapter.',
  `enabled` = b'1',
  `default_config` = '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"num_epochs":5,"lr_poison":0.001,"r":0.75,"alpha1":0.1,"alpha2":0.5,"seed":123}',
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `implementation_path` = 'vab://pre-defense/ldd';

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'LDD (VaB) - CIFAR10 SIG',
  'Five-epoch LDD configuration for the VaB CIFAR-10 SIG experiment.',
  b'1', a.`algorithm_id`,
  a.`default_config`, a.`config_schema`, b'1', '["image"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`implementation_path` = 'vab://pre-defense/ldd'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_hyper` WHERE `template_name` = 'LDD (VaB) - CIFAR10 SIG'
  );

UPDATE `sys_hyper` h
JOIN `sys_algorithm` a ON h.`algorithm_id` = a.`algorithm_id`
SET
  h.`description` = 'Five-epoch LDD configuration for the VaB CIFAR-10 SIG experiment.',
  h.`parameter_config` = a.`default_config`,
  h.`parameter_schema` = a.`config_schema`,
  h.`enabled` = b'1',
  h.`update_by` = 'system',
  h.`update_time` = NOW()
WHERE h.`template_name` = 'LDD (VaB) - CIFAR10 SIG'
  AND a.`implementation_path` = 'vab://pre-defense/ldd';

-- Older VS registrations remain for historical task records but are no longer selectable.
UPDATE `sys_algorithm`
SET `enabled` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `implementation_path` IN (
  'vab://pre-defense/vs-dcm',
  'vab://pre-defense/vs-abl',
  'vab://pre-defense/vs-meta-sift',
  'vab://pre-defense/vs-mi-dcm',
  'vab://pre-defense/vs-spectral-signature',
  'vab://pre-defense/vs-spectre',
  'vab://pre-defense/vs-telltale'
);
