-- VaB real training-pre-defense algorithm registrations.
-- Run after sql/eladmin.sql and sql/security_model_mvp_seed.sql.

-- This local runtime resource points to the external VaB dataset root. It is not
-- a repository asset and must be adjusted when the platform is deployed elsewhere.
INSERT INTO `sys_dataset` (
  `dataset_name`, `description`, `enabled`, `dataset_type`, `file_path`, `file_name`,
  `file_size`, `sample_count`, `feature_count`, `class_count`, `parse_status`,
  `parse_message`, `parse_time`, `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VaB CIFAR-10 Runtime Root',
  'Local VaB dataset root used by the LDD and VS-DCM research adapters. Configure a deployment-specific path outside the repository.',
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
  `description` = 'Local VaB dataset root used by the LDD and VS comparison-baseline research adapters. Configure a deployment-specific path outside the repository.',
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
  'Real VaB LDD training-pre-defense algorithm executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/ldd',
  '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"num_epochs":10,"lr_poison":0.001,"r":0.75,"alpha1":0.1,"alpha2":0.5,"seed":123}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"SIG"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.1},"batch_size":{"type":"int","min":16,"max":512,"default":128},"num_epochs":{"type":"int","min":1,"max":200,"default":10},"lr_poison":{"type":"float","min":0.00001,"max":1.0,"default":0.001},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"alpha1":{"type":"float","min":0.0,"max":10.0,"default":0.1},"alpha2":{"type":"float","min":0.0,"max":10.0,"default":0.5},"seed":{"type":"int","min":0,"max":999999,"default":123}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/ldd'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-DCM (VaB)',
  'VaB DCM comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-dcm',
  '{"dataset":"cifar10","trigger_type":"WaNet","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.4,"batch_size":128,"tuning_epochs":10,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"gamma":0.5,"seed":0}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"WaNet"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.4},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":10},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"gamma":{"type":"float","min":0.0,"max":1.0,"default":0.5},"seed":{"type":"int","min":0,"max":999999,"default":0}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-dcm'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-ABL (VaB)',
  'VaB anti-backdoor learning comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-abl',
  '{"dataset":"cifar10","trigger_type":"blended","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"tuning_epochs":10,"num_epochs":10,"lr":0.1,"lr_finetuning_init":0.1,"momentum":0.9,"weight_decay":0.0001,"gradient_ascent_type":"Flooding","gamma":0.5,"flooding":0.5,"threshold_clean":70.0,"threshold_bad":90.0,"r":0.75,"seed":888}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"blended"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.1},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":10},"num_epochs":{"type":"int","min":1,"max":200,"default":10},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"lr_finetuning_init":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"gradient_ascent_type":{"type":"select","options":["Flooding"],"default":"Flooding"},"gamma":{"type":"float","min":0.0,"max":10.0,"default":0.5},"flooding":{"type":"float","min":0.0,"max":10.0,"default":0.5},"threshold_clean":{"type":"float","min":0.0,"max":100.0,"default":70.0},"threshold_bad":{"type":"float","min":0.0,"max":100.0,"default":90.0},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"seed":{"type":"int","min":0,"max":999999,"default":888}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-abl'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-Meta-Sift (VaB)',
  'VaB Meta-Sift comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-meta-sift',
  '{"dataset":"cifar10","trigger_type":"WaNet","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.4,"batch_size":128,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"isolation_ratio":0.01,"seed":888,"num_sifter":5,"res_epochs":1,"warmup_epochs":1,"num_workers":0,"v_lr":0.0005,"meta_lr":0.1,"top_k":15,"go_lr":0.1,"num_act":4,"nesterov":true}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"WaNet"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.4},"batch_size":{"type":"int","min":16,"max":512,"default":128},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"isolation_ratio":{"type":"float","min":0.01,"max":0.95,"default":0.01},"seed":{"type":"int","min":0,"max":999999,"default":888},"num_sifter":{"type":"int","min":1,"max":20,"default":5},"res_epochs":{"type":"int","min":1,"max":200,"default":1},"warmup_epochs":{"type":"int","min":0,"max":200,"default":1},"num_workers":{"type":"int","min":0,"max":32,"default":0},"v_lr":{"type":"float","min":0.000001,"max":1.0,"default":0.0005},"meta_lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"top_k":{"type":"int","min":1,"max":100,"default":15},"go_lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"num_act":{"type":"int","min":1,"max":100,"default":4},"nesterov":{"type":"boolean","default":true}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-meta-sift'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-MI-DCM (VaB)',
  'VaB model-inversion DCM comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-mi-dcm',
  '{"dataset":"cifar10","trigger_type":"WaNet","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.4,"batch_size":128,"tuning_epochs":5,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"isolation_ratio":0.1,"r":0.75,"seed":888}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"WaNet"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.4},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":5},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"isolation_ratio":{"type":"float","min":0.01,"max":0.95,"default":0.1},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"seed":{"type":"int","min":0,"max":999999,"default":888}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-mi-dcm'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-Spectral Signature (VaB)',
  'VaB spectral-signature comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-spectral-signature',
  '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"tuning_epochs":10,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"isolation_ratio":0.1,"r":0.75,"seed":123}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"SIG"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.1},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":10},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"isolation_ratio":{"type":"float","min":0.01,"max":0.95,"default":0.1},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"seed":{"type":"int","min":0,"max":999999,"default":123}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-spectral-signature'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-Spectre (VaB)',
  'VaB SPECTRE comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-spectre',
  '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,"tuning_epochs":10,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"isolation_ratio":0.1,"r":0.75,"seed":123}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"SIG"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.1},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":10},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"isolation_ratio":{"type":"float","min":0.01,"max":0.95,"default":0.1},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"seed":{"type":"int","min":0,"max":999999,"default":123}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-spectre'
);

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-Telltale (VaB)',
  'VaB Telltale comparison baseline executed by the external Python adapter.',
  b'1', 'data_cleaning', 'pre', 'deep_learning',
  'vab://pre-defense/vs-telltale',
  '{"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,"poisoned_mode":"all2one","poisoned_portion":0.25,"batch_size":128,"tuning_epochs":20,"lr":0.1,"momentum":0.9,"weight_decay":0.0001,"isolation_ratio":0.1,"r":0.75,"seed":123}',
  '{"dataset":{"type":"select","options":["cifar10","cifar100","gtsrb"],"default":"cifar10"},"trigger_type":{"type":"select","options":["SIG","WaNet","badnet","blended","CL"],"default":"SIG"},"trigger_label":{"type":"int","min":0,"max":99,"default":0},"poisoned_mode":{"type":"select","options":["all2one","all2all"],"default":"all2one"},"poisoned_portion":{"type":"float","min":0.01,"max":0.6,"default":0.25},"batch_size":{"type":"int","min":16,"max":512,"default":128},"tuning_epochs":{"type":"int","min":1,"max":200,"default":20},"lr":{"type":"float","min":0.00001,"max":1.0,"default":0.1},"momentum":{"type":"float","min":0.0,"max":0.99,"default":0.9},"weight_decay":{"type":"float","min":0.0,"max":1.0,"default":0.0001},"isolation_ratio":{"type":"float","min":0.01,"max":0.95,"default":0.1},"r":{"type":"float","min":0.0,"max":1.0,"default":0.75},"seed":{"type":"int","min":0,"max":999999,"default":123}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_algorithm` WHERE `implementation_path` = 'vab://pre-defense/vs-telltale'
);

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'LDD (VaB) - CIFAR10 SIG',
  'Default reproducible LDD configuration for the VaB CIFAR-10 SIG experiment.',
  b'1', a.`algorithm_id`,
  a.`default_config`, a.`config_schema`, b'1', '["image"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`implementation_path` = 'vab://pre-defense/ldd'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_hyper` WHERE `template_name` = 'LDD (VaB) - CIFAR10 SIG'
  );

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  'VS-DCM (VaB) - CIFAR10 WaNet',
  'Default reproducible DCM baseline configuration for the VaB CIFAR-10 WaNet experiment.',
  b'1', a.`algorithm_id`,
  a.`default_config`, a.`config_schema`, b'1', '["image"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`implementation_path` = 'vab://pre-defense/vs-dcm'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_hyper` WHERE `template_name` = 'VS-DCM (VaB) - CIFAR10 WaNet'
  );

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  CONCAT(a.`algorithm_name`, ' - Default'),
  CONCAT('Default platform configuration for ', a.`algorithm_name`, '.'),
  b'1', a.`algorithm_id`,
  a.`default_config`, a.`config_schema`, b'1', '["image"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`implementation_path` IN (
  'vab://pre-defense/vs-abl',
  'vab://pre-defense/vs-meta-sift',
  'vab://pre-defense/vs-mi-dcm',
  'vab://pre-defense/vs-spectral-signature',
  'vab://pre-defense/vs-spectre',
  'vab://pre-defense/vs-telltale'
)
AND NOT EXISTS (
  SELECT 1
  FROM `sys_hyper` h
  WHERE h.`template_name` = CONCAT(a.`algorithm_name`, ' - Default')
);
