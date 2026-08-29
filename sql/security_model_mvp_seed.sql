-- Security Model Training Platform MVP seed patch.
-- Run this after sql/eladmin.sql on an existing development database.
-- The script avoids destructive deletes so legacy role/menu references remain valid.

-- The current thesis demonstration scope is LDD only.
-- Keep historical mock algorithms in the database for migration compatibility,
-- but disable them so they cannot be selected or used by the current platform.
UPDATE `sys_algorithm`
SET `enabled` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `implementation_path` LIKE 'builtin://baseline/%';

-- Disable obvious test rows from the original competition/demo data.
UPDATE `sys_algorithm`
SET `enabled` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `algorithm_name` IN ('321');

UPDATE `sys_model`
SET `status` = 'deprecated', `update_by` = 'system', `update_time` = NOW()
WHERE `model_name` IN ('321');

-- Baseline pre-training data cleaning algorithms.
INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-统计异常清洗',
  'MVP内置训练前清洗基线：根据统计异常分数过滤疑似污染样本。',
  b'1', 'data_cleaning', 'pre', 'statistical',
  'builtin://baseline/statistical-cleaning',
  '{"method":"zscore","threshold":2.5,"max_remove_ratio":0.1}',
  '{"method":{"type":"select","default":"zscore","options":["zscore","iqr"]},"threshold":{"type":"float","min":1.0,"max":5.0,"default":2.5},"max_remove_ratio":{"type":"float","min":0.0,"max":0.5,"default":0.1}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-统计异常清洗');

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-低置信样本过滤',
  'MVP内置训练前清洗基线：根据样本置信度或一致性分数剔除低质量样本。',
  b'1', 'data_cleaning', 'pre', 'ml_based',
  'builtin://baseline/low-confidence-filter',
  '{"confidence_threshold":0.65,"max_remove_ratio":0.15}',
  '{"confidence_threshold":{"type":"float","min":0.0,"max":1.0,"default":0.65},"max_remove_ratio":{"type":"float","min":0.0,"max":0.5,"default":0.15}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-低置信样本过滤');

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-IQR四分位清洗',
  'MVP内置训练前清洗基线：使用IQR箱线图规则过滤离群样本。',
  b'1', 'data_cleaning', 'pre', 'statistical',
  'builtin://baseline/iqr-cleaning',
  '{"iqr_multiplier":1.5,"max_remove_ratio":0.12}',
  '{"iqr_multiplier":{"type":"float","min":0.5,"max":5.0,"default":1.5},"max_remove_ratio":{"type":"float","min":0.0,"max":0.5,"default":0.12}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-IQR四分位清洗');

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-IsolationForest异常清洗',
  'MVP内置训练前清洗基线：模拟Isolation Forest异常检测，筛出疑似污染样本。',
  b'1', 'data_cleaning', 'pre', 'ml_based',
  'builtin://baseline/isolation-forest-cleaning',
  '{"estimators":100,"contamination":0.06,"max_remove_ratio":0.2}',
  '{"estimators":{"type":"int","min":10,"max":500,"default":100},"contamination":{"type":"float","min":0.0,"max":0.5,"default":0.06},"max_remove_ratio":{"type":"float","min":0.0,"max":0.5,"default":0.2}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-IsolationForest异常清洗');

-- Baseline in-training defense algorithm.
INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-鲁棒训练',
  'MVP内置训练中防御基线：通过稳健损失和轻量正则化模拟安全训练流程。',
  b'1', 'backdoor_detection', 'during', 'deep_learning',
  'builtin://baseline/robust-training',
  '{"epochs":10,"learning_rate":0.001,"weight_decay":0.0005,"defense_strength":0.3}',
  '{"epochs":{"type":"int","min":1,"max":200,"default":10},"learning_rate":{"type":"float","min":0.00001,"max":0.1,"default":0.001},"weight_decay":{"type":"float","min":0.0,"max":0.1,"default":0.0005},"defense_strength":{"type":"float","min":0.0,"max":1.0,"default":0.3}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-鲁棒训练');

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-标签平滑训练',
  'MVP内置训练中防御基线：通过标签平滑降低模型对异常标签的过拟合。',
  b'1', 'backdoor_detection', 'during', 'deep_learning',
  'builtin://baseline/label-smoothing-training',
  '{"epochs":10,"learning_rate":0.001,"label_smoothing":0.1}',
  '{"epochs":{"type":"int","min":1,"max":200,"default":10},"learning_rate":{"type":"float","min":0.00001,"max":0.1,"default":0.001},"label_smoothing":{"type":"float","min":0.0,"max":0.5,"default":0.1}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-标签平滑训练');

INSERT INTO `sys_algorithm` (
  `algorithm_name`, `description`, `enabled`, `algorithm_type`, `defense_stage`,
  `algorithm_category`, `implementation_path`, `default_config`, `config_schema`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-对抗训练占位',
  'MVP内置训练中防御基线：模拟对抗训练流程，为后续真实扰动训练算法预留接口。',
  b'1', 'backdoor_detection', 'during', 'deep_learning',
  'builtin://baseline/adversarial-training',
  '{"epochs":10,"learning_rate":0.001,"epsilon":0.03,"attack_steps":3}',
  '{"epochs":{"type":"int","min":1,"max":200,"default":10},"learning_rate":{"type":"float","min":0.00001,"max":0.1,"default":0.001},"epsilon":{"type":"float","min":0.0,"max":0.3,"default":0.03},"attack_steps":{"type":"int","min":1,"max":20,"default":3}}',
  'system', 'system', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `sys_algorithm` WHERE `algorithm_name` = '基线-对抗训练占位');

-- Default hyperparameter templates for the MVP baselines.
INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-统计异常清洗 - 默认配置',
  '适用于图像和表格数据的训练前统计清洗默认参数。',
  b'1', a.`algorithm_id`,
  '{"method":"zscore","threshold":2.5,"max_remove_ratio":0.1}',
  a.`config_schema`, b'1', '["image","tabular"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-统计异常清洗'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-统计异常清洗 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-低置信样本过滤 - 默认配置',
  '适用于图像、文本和表格数据的低置信样本过滤默认参数。',
  b'1', a.`algorithm_id`,
  '{"confidence_threshold":0.65,"max_remove_ratio":0.15}',
  a.`config_schema`, b'1', '["image","text","tabular"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-低置信样本过滤'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-低置信样本过滤 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-IQR四分位清洗 - 默认配置',
  '适用于表格特征、样本损失分布和异常分数序列的IQR默认参数。',
  b'1', a.`algorithm_id`,
  '{"iqr_multiplier":1.5,"max_remove_ratio":0.12}',
  a.`config_schema`, b'1', '["tabular","image"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-IQR四分位清洗'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-IQR四分位清洗 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-IsolationForest异常清洗 - 默认配置',
  '适用于训练前异常样本筛查的Isolation Forest占位默认参数。',
  b'1', a.`algorithm_id`,
  '{"estimators":100,"contamination":0.06,"max_remove_ratio":0.2}',
  a.`config_schema`, b'1', '["tabular","image","text"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-IsolationForest异常清洗'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-IsolationForest异常清洗 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-鲁棒训练 - 默认配置',
  '适用于MVP安全训练流程的鲁棒训练默认参数。',
  b'1', a.`algorithm_id`,
  '{"epochs":10,"learning_rate":0.001,"weight_decay":0.0005,"defense_strength":0.3}',
  a.`config_schema`, b'1', '["image","text","tabular"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-鲁棒训练'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-鲁棒训练 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-标签平滑训练 - 默认配置',
  '适用于训练中安全训练流程的标签平滑默认参数。',
  b'1', a.`algorithm_id`,
  '{"epochs":10,"learning_rate":0.001,"label_smoothing":0.1}',
  a.`config_schema`, b'1', '["image","text","tabular"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-标签平滑训练'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-标签平滑训练 - 默认配置');

INSERT INTO `sys_hyper` (
  `template_name`, `description`, `enabled`, `algorithm_id`, `parameter_config`,
  `parameter_schema`, `is_default`, `applicable_datasets`,
  `create_by`, `update_by`, `create_time`, `update_time`
)
SELECT
  '基线-对抗训练占位 - 默认配置',
  '适用于后续真实对抗训练算法集成前的占位默认参数。',
  b'1', a.`algorithm_id`,
  '{"epochs":10,"learning_rate":0.001,"epsilon":0.03,"attack_steps":3}',
  a.`config_schema`, b'1', '["image","tabular"]',
  'system', 'system', NOW(), NOW()
FROM `sys_algorithm` a
WHERE a.`algorithm_name` = '基线-对抗训练占位'
  AND NOT EXISTS (SELECT 1 FROM `sys_hyper` h WHERE h.`template_name` = '基线-对抗训练占位 - 默认配置');

-- Reduce the visible surface for the MVP without deleting legacy ELADMIN data.
-- Role/menu permissions are kept intact; this only controls sidebar visibility.
UPDATE `sys_menu`
SET `hidden` = b'1', `update_by` = 'system', `update_time` = NOW()
WHERE `menu_id` NOT IN (122, 117, 123, 124, 125, 146);

-- Keep the core security training modules visible in the sidebar.
UPDATE `sys_menu`
SET `hidden` = b'0', `update_by` = 'system', `update_time` = NOW()
WHERE `menu_id` IN (122, 117, 123, 124, 125, 146);
