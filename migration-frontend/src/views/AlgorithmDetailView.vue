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
                <div><span>指标采集</span><strong>未接入</strong></div>
                <div><span>展示口径</span><strong>{{ isRunnable ? '真实运行' : '展示占位' }}</strong></div>
              </div>
              <el-alert
                :title="isRunnable ? '该算法可创建训练前 LDD 清洗任务，结果写入任务报告。' : '该算法当前只做目录登记与展示，不会创建真实任务，也不产生实验指标。'"
                :type="isRunnable ? 'success' : 'warning'" :closable="false" show-icon />
            </el-card>
          </section>
        </el-tab-pane>

        <el-tab-pane label="参数配置" name="parameters">
          <el-card shadow="never">
            <template #header><div class="card-heading"><strong>默认参数配置</strong><el-tag type="info">来自 sys_algorithm.default_config</el-tag></div></template>
            <el-table v-if="parameterRows.length" :data="parameterRows" border>
              <el-table-column prop="name" label="参数名称" width="240" />
              <el-table-column prop="value" label="默认值" />
            </el-table>
            <el-empty v-else description="该算法没有登记默认参数" :image-size="56" />
          </el-card>
          <el-card shadow="never" class="detail-card-gap">
            <template #header><strong>运行环境</strong></template>
            <div class="environment-grid">
              <div><span>执行方式</span><strong>{{ isRunnable ? '外部 Python 进程 / VaB' : '未接入' }}</strong></div>
              <div><span>任务队列</span><strong>Spring 有界执行池</strong></div>
              <div><span>数据来源</span><strong>MySQL 资源目录</strong></div>
              <div><span>环境指标</span><strong>未采集</strong></div>
            </div>
            <el-alert title="运行环境版本（Python / PyTorch / GPU 型号）由执行机决定，工作台不做展示，避免把演示常量当成真实环境。" type="info" :closable="false" show-icon />
          </el-card>
        </el-tab-pane>

        <el-tab-pane label="实验结果" name="results">
          <el-card shadow="never">
            <template #header><div class="card-heading"><strong>实验结果</strong><el-tag type="info">未采集</el-tag></div></template>
            <el-empty description="该算法尚未产生平台侧真实实验结果；攻击成功率、干净准确率等指标需要模型评估链路。" :image-size="72">
              <el-button v-if="isRunnable" type="primary" @click="$router.push('/tasks')">去任务中心创建真实任务</el-button>
            </el-empty>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getAlgorithm } from '../api/algorithm'

const route = useRoute()
const activeTab = ref('overview')
const algorithm = ref(null)
const errorMessage = ref('')

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
const isRunnable = computed(() => algorithm.value?.implementationPath === 'vab://pre-defense/ldd')

const metricCards = computed(() => [
  { label: '干净准确率', value: '未采集', note: '需要模型评估链路', className: '' },
  { label: '攻击成功率', value: '未采集', note: '需要模型评估链路', className: '' },
  { label: '样本保留率', value: '未采集', note: '请在任务报告查看清洗纯净度', className: '' },
  { label: '目录状态', value: algorithm.value?.enabled ? '已启用' : '已停用', note: '来自 MySQL 算法目录', className: 'success-text' }
])

const parameterRows = computed(() => {
  const raw = algorithm.value?.defaultConfig
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    return Object.entries(parsed).map(([name, value]) => ({
      name,
      value: typeof value === 'string' ? value : JSON.stringify(value)
    }))
  } catch (error) {
    return []
  }
})

function unwrap(response) {
  return response?.data?.data ?? response?.data ?? response ?? null
}

async function load() {
  errorMessage.value = ''
  algorithm.value = null
  const algorithmId = Number(route.params.algorithmKey)
  if (!Number.isFinite(algorithmId) || algorithmId <= 0) {
    errorMessage.value = '算法标识无效：请从算法资源列表进入详情页。'
    return
  }
  try {
    const data = unwrap(await getAlgorithm(algorithmId))
    algorithm.value = data && typeof data === 'object' ? data : null
    if (!algorithm.value) errorMessage.value = '算法不存在或已被删除。'
  } catch (error) {
    errorMessage.value = error.response?.status === 404
      ? '算法不存在或已被删除。'
      : (error.response?.data?.message || '算法详情读取失败，请检查服务连接后重试。')
  }
}

onMounted(load)
</script>
