<template>
  <div class="page detail-page">
    <div class="detail-topbar">
      <el-button text :icon="ArrowLeft" @click="$router.push('/algorithms')">返回算法资源</el-button>
      <span class="muted">算法详情 / {{ algorithm?.algorithmName || '未找到' }}</span>
    </div>

    <el-alert v-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon class="page-alert" />

    <template v-if="algorithm">
      <section class="detail-hero">
        <div class="algorithm-icon large-icon">{{ shortName }}</div>
        <div class="detail-hero-copy">
          <div class="detail-title-row">
            <div><span class="eyebrow">{{ stageLabel }} · {{ algorithm.algorithmCategory || '未分类' }}</span><h2>{{ algorithm.algorithmName }}</h2></div>
            <div>
              <el-tag :type="algorithm.enabled ? 'success' : 'info'" size="large">{{ algorithm.enabled ? '已登记' : '已停用' }}</el-tag>
              <el-tag :type="isRunnable ? 'success' : 'warning'" size="large">{{ isRunnable ? '已接入运行' : '展示占位' }}</el-tag>
            </div>
          </div>
          <p>{{ algorithm.description || '暂无算法描述。' }}</p>
          <div class="detail-meta">
            <span>算法 ID {{ algorithm.algorithmId }}</span>
            <span>类型 {{ typeLabel }}</span>
            <span>实现路径 {{ algorithm.implementationPath || '未登记' }}</span>
          </div>
        </div>
      </section>

      <div class="detail-stat-grid">
        <el-card v-for="item in metricCards" :key="item.label" shadow="never">
          <span class="muted">{{ item.label }}</span><strong :class="item.className">{{ item.value }}</strong><small>{{ item.note }}</small>
        </el-card>
      </div>

      <el-tabs v-model="activeTab" class="detail-tabs">
        <el-tab-pane label="算法说明" name="overview">
          <section class="content-grid detail-content-grid">
            <el-card shadow="never">
              <template #header><strong>算法元数据</strong></template>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="算法名称">{{ algorithm.algorithmName }}</el-descriptions-item>
                <el-descriptions-item label="算法分类">{{ algorithm.algorithmCategory || '未分类' }}</el-descriptions-item>
                <el-descriptions-item label="防御阶段">{{ stageLabel }}</el-descriptions-item>
                <el-descriptions-item label="算法类型">{{ typeLabel }}</el-descriptions-item>
                <el-descriptions-item label="目录状态">{{ algorithm.enabled ? '启用' : '停用' }}</el-descriptions-item>
                <el-descriptions-item label="实现路径">{{ algorithm.implementationPath || '未登记' }}</el-descriptions-item>
                <el-descriptions-item label="描述" :span="2">{{ algorithm.description || '暂无描述' }}</el-descriptions-item>
              </el-descriptions>
            </el-card>
            <el-card shadow="never">
              <template #header><strong>接入状态</strong></template>
              <div class="scene-list">
                <div><span>本平台执行</span><strong>{{ isRunnable ? '已接入 VaB LDD 真实执行' : '未接入真实执行' }}</strong></div>
                <div><span>结果形态</span><strong>{{ isRunnable ? '清洗结果 JSON + 产物目录' : '仅算法元数据' }}</strong></div>
                <div><span>清洗指标</span><strong>{{ isRunnable ? '见任务报告' : '未接入' }}</strong></div>
                <div><span>展示口径</span><strong>{{ isRunnable ? '真实运行' : '展示占位' }}</strong></div>
              </div>
              <el-alert
                :title="isRunnable ? '该算法可创建训练前 LDD 清洗任务，结果写入任务报告。' : '该算法当前只做目录登记与展示，不会创建真实任务，也不产生实验指标。'"
                :type="isRunnable ? 'success' : 'warning'" :closable="false" show-icon />
            </el-card>
          </section>
        </el-tab-pane>

        <el-tab-pane label="参数配置" name="parameters">
          <el-card shadow="never" class="config-center-card">
            <template #header>
              <div class="card-heading">
                <div>
                  <strong>参数配置中心</strong>
                  <span class="table-caption">算法默认超参数是任务创建的唯一默认来源，不再依赖独立超参模板。</span>
                </div>
                <div class="config-head-actions">
                  <el-tag type="success" effect="light">单一配置来源</el-tag>
                  <el-button v-if="isAdmin && !configEditing" type="primary" plain :icon="EditPen" @click="startConfigEdit">编辑配置</el-button>
                </div>
              </div>
            </template>

            <el-alert title="这里保存的是服务端算法默认配置。任务表单中的“自定义参数”只覆盖本次实验需要的字段。"
              type="info" :closable="false" show-icon />

            <template v-if="configEditing">
              <el-form v-if="configFields.length" class="algorithm-config-form" label-position="top">
                <div class="config-field-grid">
                  <el-form-item v-for="field in configFields" :key="field.key">
                    <template #label>
                      <span class="config-field-label">{{ field.label }}</span>
                      <small v-if="field.key !== field.label">{{ field.key }}</small>
                    </template>
                    <el-select v-if="field.type === 'select'" v-model="configDraft[field.key]" class="full-width">
                      <el-option v-for="option in field.options" :key="String(option)" :label="String(option)" :value="option" />
                    </el-select>
                    <el-switch v-else-if="field.type === 'boolean'" v-model="configDraft[field.key]" />
                    <el-input-number v-else-if="field.type === 'number'" v-model="configDraft[field.key]"
                      class="full-width" :min="field.min" :max="field.max" :step="field.step" :precision="field.precision" controls-position="right" />
                    <el-input v-else v-model="configDraft[field.key]" />
                    <span class="config-field-hint">{{ field.hint }}</span>
                  </el-form-item>
                </div>
              </el-form>
              <el-form v-else label-position="top">
                <el-form-item label="默认参数 JSON">
                  <el-input v-model="configJsonText" type="textarea" :rows="12" />
                </el-form-item>
              </el-form>
              <div class="config-edit-actions">
                <span class="muted">保存后新创建任务将使用这份默认配置。</span>
                <div>
                  <el-button :disabled="configSaving" @click="cancelConfigEdit">取消</el-button>
                  <el-button type="primary" :loading="configSaving" @click="saveConfig">保存配置</el-button>
                </div>
              </div>
            </template>

            <template v-else>
              <el-table v-if="parameterRows.length" :data="parameterRows" border>
                <el-table-column prop="label" label="参数" min-width="180">
                  <template #default="{ row }"><strong>{{ row.label }}</strong><small v-if="row.label !== row.name" class="config-key">{{ row.name }}</small></template>
                </el-table-column>
                <el-table-column prop="value" label="默认值" min-width="160" />
                <el-table-column prop="hint" label="说明" min-width="240" show-overflow-tooltip />
              </el-table>
              <el-empty v-else description="该算法没有登记默认参数" :image-size="56" />
            </template>
          </el-card>

          <el-card shadow="never" class="detail-card-gap">
            <template #header>
              <div class="card-heading">
                <strong>配置说明</strong>
                <el-tag type="info" effect="plain">sys_algorithm.default_config</el-tag>
              </div>
            </template>
            <div class="environment-grid">
              <div><span>执行方式</span><strong>{{ isRunnable ? '外部 Python 进程 / VaB' : '未接入' }}</strong></div>
              <div><span>配置来源</span><strong>算法配置中心</strong></div>
              <div><span>配置版本</span><strong>{{ formatDateTime(algorithm.updateTime) }}</strong></div>
            </div>
            <el-alert
              :title="isRunnable ? 'LDD 参数由服务端 schema 与参数策略共同校验，非法配置不会进入任务。' : '该算法仅登记目录；配置保存后供后续真实执行接入复用。'"
              :type="isRunnable ? 'success' : 'info'" :closable="false" show-icon />
          </el-card>
        </el-tab-pane>

        <el-tab-pane label="实验结果" name="results">
          <el-card shadow="never">
            <template #header><strong>实验结果</strong></template>
            <el-empty :description="isRunnable ? '清洗指标和导出文件保存在各次任务报告中。' : '该算法仅登记目录，暂无任务结果。'" :image-size="72">
              <el-button v-if="isRunnable" type="primary" @click="$router.push('/tasks')">查看任务报告</el-button>
            </el-empty>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, EditPen } from '@element-plus/icons-vue'
import { getAlgorithm, updateAlgorithmConfig } from '../api/algorithm'
import { getUserInfo } from '../api/auth'
import { formatDateTime } from '../utils/time'

const route = useRoute()
const activeTab = ref('overview')
const algorithm = ref(null)
const errorMessage = ref('')
const isAdmin = ref(false)
const configEditing = ref(false)
const configSaving = ref(false)
const configDraft = reactive({})
const configJsonText = ref('')

const CONFIG_LABELS = {
  r: '隔离比例',
  seed: '随机种子',
  alpha1: 'α1',
  alpha2: 'α2',
  lr_poison: '毒化学习率',
  batch_size: '批次大小',
  num_epochs: '训练轮数',
  dataset: '数据集标识',
  trigger_type: '触发器类型',
  poisoned_mode: '毒化模式',
  trigger_label: '目标标签',
  poisoned_portion: '毒化比例',
  data_path: '数据目录',
  method: '检测方法',
  threshold: '检测阈值',
  contamination: '污染比例',
  max_remove_ratio: '最大移除比例',
  confidence_threshold: '置信度阈值',
  iqr_multiplier: 'IQR 倍数',
  estimators: '估计器数量',
  epochs: '训练轮数',
  weight_decay: '权重衰减',
  learning_rate: '学习率',
  defense_strength: '防御强度',
  label_smoothing: '标签平滑',
  epsilon: '扰动上限',
  attack_steps: '攻击步数',
  pruning_ratio: '剪枝比例',
  fine_tune_epochs: '微调轮数',
  isolation_ratio: '隔离比例',
  unlearning_epochs: '反学习轮数',
  gradient_ascent_step: '梯度上升步长',
  alpha: '蒸馏权重',
  temperature: '温度系数',
  distill_epochs: '蒸馏轮数'
}

const CONFIG_HINTS = {
  r: 'LDD 隔离样本的比例，取值 0–1',
  seed: '训练与采样使用的固定随机种子',
  alpha1: 'LDD 损失函数权重 α1',
  alpha2: 'LDD 损失函数权重 α2',
  lr_poison: '触发器优化学习率',
  batch_size: '训练批次大小',
  num_epochs: '训练总轮数，是下一轮复现的关键变量',
  dataset: '必须与任务选择的数据集一致',
  trigger_type: '后门触发器类型',
  poisoned_mode: '毒化标签映射方式',
  trigger_label: '目标标签，0–99',
  poisoned_portion: '训练集毒化样本比例，取值 0.01–0.6',
  data_path: '可选数据根目录，由任务数据集自动校验'
}

const STAGE_LABELS = { pre: '训练前防御', during: '训练中防御', post: '训练后防御' }
const TYPE_LABELS = {
  data_cleaning: '数据清洗',
  backdoor_detection: '后门检测',
  model_training: '模型训练',
  model_evaluation: '模型评估',
  model_purification: '模型修复'
}
const stageLabel = computed(() => STAGE_LABELS[algorithm.value?.defenseStage] || '未标注阶段')
const typeLabel = computed(() => TYPE_LABELS[algorithm.value?.algorithmType] || algorithm.value?.algorithmType || '未标注类型')
const shortName = computed(() => (algorithm.value?.algorithmName || 'NA').slice(0, 3).toUpperCase())
const isRunnable = computed(() => algorithm.value?.enabled && algorithm.value.defenseStage === 'pre'
  && algorithm.value.implementationPath === 'vab://pre-defense/ldd')

const metricCards = computed(() => [
  { label: '干净准确率', value: '未采集', note: '需要模型评估链路', className: '' },
  { label: '攻击成功率', value: '未采集', note: '需要模型评估链路', className: '' },
  { label: '样本保留率', value: '未采集', note: '请在任务报告查看清洗纯净度', className: '' },
  { label: '目录状态', value: algorithm.value?.enabled ? '已启用' : '已停用', note: '来自 MySQL 算法目录', className: 'success-text' }
])

function parseConfigObject(raw) {
  if (!raw) return {}
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

const configSchema = computed(() => parseConfigObject(algorithm.value?.configSchema))

const configFields = computed(() => {
  const schema = configSchema.value
  const draft = configDraft
  const keys = [...new Set([...Object.keys(schema), ...Object.keys(draft)])]
  return keys.map(key => {
    const meta = schema[key] || {}
    const value = draft[key]
    const type = meta.type === 'select' ? 'select'
      : meta.type === 'boolean' ? 'boolean'
        : meta.type === 'int' || meta.type === 'float' ? 'number'
          : typeof value === 'number' ? 'number'
            : typeof value === 'boolean' ? 'boolean' : 'text'
    return {
      key,
      label: CONFIG_LABELS[key] || key,
      hint: meta.description || CONFIG_HINTS[key] || '算法默认参数',
      type,
      options: Array.isArray(meta.options) ? meta.options : [],
      min: typeof meta.min === 'number' ? meta.min : undefined,
      max: typeof meta.max === 'number' ? meta.max : undefined,
      step: meta.type === 'int' ? 1 : meta.type === 'float' ? 0.001 : undefined,
      precision: meta.type === 'int' ? 0 : undefined
    }
  })
})

const parameterRows = computed(() => {
  const parsed = parseConfigObject(algorithm.value?.defaultConfig)
  const schema = configSchema.value
  return Object.entries(parsed).map(([name, value]) => ({
    name,
    label: CONFIG_LABELS[name] || name,
    hint: schema[name]?.description || CONFIG_HINTS[name] || '算法默认参数',
    value: typeof value === 'string' ? value : JSON.stringify(value)
  }))
})

function resetConfigDraft() {
  const config = parseConfigObject(algorithm.value?.defaultConfig)
  const schema = configSchema.value
  Object.entries(schema).forEach(([key, meta]) => {
    if (!(key in config) && meta && meta.default !== undefined) config[key] = meta.default
  })
  Object.keys(configDraft).forEach(key => delete configDraft[key])
  Object.assign(configDraft, config)
  configJsonText.value = JSON.stringify(config, null, 2)
}

function startConfigEdit() {
  resetConfigDraft()
  configEditing.value = true
}

function cancelConfigEdit() {
  configEditing.value = false
  resetConfigDraft()
}

async function saveConfig() {
  if (configSaving.value || !algorithm.value?.algorithmId) return
  let config
  if (configFields.value.length) {
    config = { ...configDraft }
  } else {
    try {
      config = JSON.parse(configJsonText.value || '{}')
    } catch {
      ElMessage.error('默认参数必须是合法 JSON 对象')
      return
    }
  }
  if (!config || typeof config !== 'object' || Array.isArray(config) || !Object.keys(config).length) {
    ElMessage.error('默认参数不能为空，且必须是 JSON 对象')
    return
  }
  configSaving.value = true
  try {
    const response = await updateAlgorithmConfig(algorithm.value.algorithmId, JSON.stringify(config))
    const updated = unwrap(response)
    if (updated && typeof updated === 'object') algorithm.value = { ...algorithm.value, ...updated }
    resetConfigDraft()
    configEditing.value = false
    ElMessage.success('算法默认参数已保存')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '算法默认参数保存失败')
  } finally {
    configSaving.value = false
  }
}

function unwrap(response) {
  return response?.data?.data ?? response?.data ?? response ?? null
}

async function load() {
  errorMessage.value = ''
  algorithm.value = null
  configEditing.value = false
  const algorithmId = route.params.algorithmKey
  if (typeof algorithmId !== 'string' || !/^[1-9]\d*$/.test(algorithmId)) {
    errorMessage.value = '算法标识无效：请从算法资源列表进入详情页。'
    return
  }
  try {
    const data = unwrap(await getAlgorithm(algorithmId))
    algorithm.value = data && typeof data === 'object' ? data : null
    if (!algorithm.value) errorMessage.value = '算法不存在或已被删除。'
    resetConfigDraft()
  } catch (error) {
    errorMessage.value = error.response?.status === 404
      ? '算法不存在或已被删除。'
      : (error.response?.data?.message || '算法详情读取失败，请检查服务连接后重试。')
  }
}

onMounted(async () => {
  await load()
  try {
    const { data } = await getUserInfo()
    isAdmin.value = data?.admin === true
  } catch {
    isAdmin.value = false
  }
})
</script>

<style scoped>
.config-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.algorithm-config-form {
  margin-top: 16px;
}
.config-field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 2px 16px;
}
.config-field-label {
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.config-field-label + small {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}
.config-field-hint {
  display: block;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.5;
}
.config-key {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}
.config-edit-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.config-edit-actions > div {
  display: flex;
  gap: 8px;
}
@media (max-width: 680px) {
  .config-edit-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
