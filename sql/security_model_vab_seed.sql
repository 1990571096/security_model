-- LDD real training-pre-defense algorithm registration.
-- Run after sql/eladmin.sql and sql/security_model_mvp_seed.sql.
--
-- This seed is ADDITIVE ONLY. It inserts missing rows and never updates a row that
-- already exists, so rerunning initialization cannot overwrite an operator's dataset
-- path, algorithm parameters, enabled flag or hyper-parameter template. Changing a
-- system default is an explicit versioned migration or an explicit reset, not this seed.
--
-- The runtime dataset path is deployment-specific and is therefore NOT stored in this
-- file. The caller sets @vab_dataset_root before running the script, for example:
--   SET @vab_dataset_root = '<absolute path to VaB-main/dataset>';
--   SOURCE sql/security_model_vab_seed.sql;
-- scripts/dev/InitializeSecurityModelDatabase.ps1 does this from -VabDatasetRoot or
-- SECURITY_MODEL_VAB_DATASET_ROOT, verifies the CIFAR-10 files, and sets
-- @vab_register_cifar100 to 1 when the optional CIFAR-100 files also pass.
--
-- When @vab_dataset_root is NULL or empty the dataset rows are skipped, while the LDD
-- algorithm and its default hyper-parameter template are still registered. A newly
-- registered dataset is always 'pending': the caller's directory check is not a sample
-- parse, so sample_count/feature_count/class_count stay NULL. An administrator promotes
-- it through the existing POST /api/dataset/{id}/validate endpoint. Until that succeeds
-- the dataset is not returned by the available-dataset query.

SET @vab_root = NULLIF(TRIM(@vab_dataset_root), '');

INSERT INTO `sys_dataset` (
  `dataset_name`, `description`, `enabled`, `dataset_type`, `file_path`, `file_name`,
  `file_size`, `sample_count`, `feature_count`, `class_count`, `parse_status`,
  `parse_message`, `parse_time`, `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VaB CIFAR-10 Runtime Root',
  'Official CIFAR-10 Python batches for the VaB runtime.',
  b'1', 'image',
  CONCAT(TRIM(TRAILING '/' FROM @vab_root), '/cifar-10-batches-py'),
  'cifar-10-batches-py',
  NULL, NULL, NULL, NULL, 'pending',
  '种子登记：目录结构与 CIFAR-10 批次文件已由调用方检查，但样本尚未解析；预期 50000 训练样本/3072 特征/10 类，均不是实测统计。请管理员执行 POST /api/dataset/{id}/validate 后再选用。',
  NULL, 'system', 'system', NOW(), NOW()
FROM (SELECT 1) AS anchor
WHERE @vab_root IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_dataset` WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root'
  );

INSERT INTO `sys_dataset` (
  `dataset_name`, `description`, `enabled`, `dataset_type`, `file_path`, `file_name`,
  `file_size`, `sample_count`, `feature_count`, `class_count`, `parse_status`,
  `parse_message`, `parse_time`, `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VaB CIFAR-100 Runtime Root',
  'Official CIFAR-100 Python batches for the VaB runtime.',
  b'1', 'image',
  CONCAT(TRIM(TRAILING '/' FROM @vab_root), '/cifar-100-python'),
  'cifar-100-python',
  NULL, NULL, NULL, NULL, 'pending',
  '种子登记：请管理员执行数据集验证后再选用。',
  NULL, 'system', 'system', NOW(), NOW()
FROM (SELECT 1) AS anchor
WHERE @vab_root IS NOT NULL
  AND @vab_register_cifar100 = 1
  AND NOT EXISTS (
    SELECT 1 FROM `sys_dataset` WHERE `dataset_name` = 'VaB CIFAR-100 Runtime Root'
  );

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

-- Older VS registrations are intentionally left untouched. This seed no longer disables
-- them: that was an initialization side effect on existing rows. Historical records keep
-- their algorithm rows, and an explicit migration/reset owns any enabled-state change.
