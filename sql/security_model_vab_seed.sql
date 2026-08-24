-- LDD real training-pre-defense algorithm registration.
-- Run after sql/eladmin.sql and sql/security_model_mvp_seed.sql.

INSERT INTO `sys_dataset` (
  `dataset_name`, `description`, `enabled`, `dataset_type`, `file_path`, `file_name`,
  `file_size`, `sample_count`, `feature_count`, `class_count`, `parse_status`,
  `parse_message`, `parse_time`, `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VaB CIFAR-10 Runtime Root',
  'Local VaB dataset root used by the LDD research adapter. Configure a deployment-specific path outside the repository.',
  b'1', 'image',
  CONCAT('F:/Study/postgraduate/1', CONVERT(0xE4B88B USING utf8mb4),
    '/VB backdoor defence/VaB-main/dataset'),
  'VaB-main/dataset',
  NULL, 50000, 3072, 10, 'success',
  'External VaB runtime dataset root is available.',
  NOW(), 'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_dataset` WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root'
);

UPDATE `sys_dataset`
SET
  `description` = 'Local VaB dataset root used by the LDD research adapter. Configure a deployment-specific path outside the repository.',
  `enabled` = b'1',
  `dataset_type` = 'image',
  `file_path` = CONCAT('F:/Study/postgraduate/1', CONVERT(0xE4B88B USING utf8mb4),
    '/VB backdoor defence/VaB-main/dataset'),
  `file_name` = 'VaB-main/dataset',
  `sample_count` = 50000,
  `feature_count` = 3072,
  `class_count` = 10,
  `parse_status` = 'success',
  `parse_message` = 'External VaB runtime dataset root is available.',
  `parse_time` = NOW(),
  `update_by` = 'system',
  `update_time` = NOW()
WHERE `dataset_name` = 'VaB CIFAR-10 Runtime Root';

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
