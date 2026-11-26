<template>
  <div class="page statistics-page" v-loading="loading">
    <div class="section-heading analytics-heading">
      <div>
        <span class="eyebrow">EXPERIMENT ANALYTICS</span>
        <h2>数据统计</h2>
        <p class="page-subtitle">用状态环图、任务趋势、完成质量和防御阶段构成观察实验数据。只统计当前账号可见的非演示任务，图表悬停可查看明细。</p>
      </div>
      <div class="heading-actions">
        <el-select v-model="trendDays" aria-label="趋势时间范围" style="width: 104px">
          <el-option :value="7" label="近 7 天" />
          <el-option :value="14" label="近 14 天" />
          <el-option :value="30" label="近 30 天" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon class="page-alert" />

    <section class="analytics-kpi-grid">
      <el-card v-for="item in kpiCards" :key="item.label" shadow="never" class="analytics-kpi-card">
        <div class="analytics-kpi-top">
          <span>{{ item.label }}</span>
          <el-tag :type="item.type" effect="plain">{{ item.badge }}</el-tag>
        </div>
        <strong class="kpi-value">{{ item.value }}</strong>
        <small class="kpi-note">{{ item.note }}</small>
        <el-progress v-if="item.percent !== undefined" :percentage="item.percent" :stroke-width="4" :show-text="false" :color="item.color" />
      </el-card>
    </section>

    <section class="analytics-split">
      <el-card shadow="never" class="chart-panel status-panel">
        <template #header>
          <div class="card-heading">
            <div><strong>任务状态分布</strong><span class="table-caption">当前统计范围内的真实任务状态构成</span></div>
            <el-tag type="info" effect="plain">{{ scopeLabel }}</el-tag>
          </div>
        </template>
        <div v-if="totalTasks > 0" class="status-chart">
          <div class="donut-wrap">
            <div class="status-donut" :style="{ background: statusDonutGradient }">
              <div class="donut-hole">
                <strong>{{ totalTasks }}</strong>
                <small>任务总数</small>
              </div>
            </div>
            <span class="donut-caption">成功率 {{ successRate }}%</span>
          </div>
          <div class="status-legend">
            <div v-for="item in statusSegments" :key="item.key" class="legend-row">
              <span class="legend-dot" :style="{ background: item.color }"></span>
              <div><strong>{{ item.label }}</strong><small>{{ item.meaning }}</small></div>
              <span class="legend-value">{{ item.count }}<em>{{ item.percent }}%</em></span>
            </div>
          </div>
        </div>
        <el-empty v-else description="当前范围还没有任务" :image-size="76" />
      </el-card>

      <el-card shadow="never" class="chart-panel trend-panel">
        <template #header>
          <div class="card-heading">
            <div><strong>近 {{ trendDays }} 日任务趋势</strong><span class="table-caption">按创建时间统计，堆叠显示每日状态构成</span></div>
            <el-tag effect="plain">MySQL</el-tag>
          </div>
        </template>
        <div v-if="hasTrend" class="trend-chart">
          <div class="trend-grid-lines"><span v-for="n in 4" :key="n"></span></div>
          <div class="trend-columns">
            <div v-for="item in trendBars" :key="item.date" class="trend-column" :class="{ dense: trendDays > 14 }">
              <div class="trend-count">{{ item.total || '' }}</div>
              <el-tooltip placement="top" :content="item.tooltip">
                <div class="trend-bar-shell">
                  <div class="trend-stack" :style="{ height: `${item.height}px` }">
                    <span v-for="segment in item.segments" :key="segment.key"
                          :style="{ height: `${segment.height}px`, background: segment.color }"></span>
                  </div>
                </div>
              </el-tooltip>
              <small>{{ item.label }}</small>
            </div>
          </div>
        </div>
        <el-empty v-else description="这个时间范围内没有任务记录" :image-size="76" />
        <div class="chart-footer">
          <div class="chart-legend">
            <span v-for="item in statusSegments" :key="item.key"><i :style="{ background: item.color }"></i>{{ item.label }}</span>
          </div>
          <em>区间内共 {{ trendTotal }} 个任务，成功 {{ trendSuccess }} 个</em>
        </div>
      </el-card>
    </section>

    <section class="analytics-split analytics-quality-grid">
      <el-card shadow="never" class="chart-panel">
        <template #header>
          <div class="card-heading">
            <div><strong>完成质量</strong><span class="table-caption">任务成功、报告产物和资源启用三个维度</span></div>
            <el-tag type="success" effect="plain">QUALITY</el-tag>
          </div>
        </template>
        <div class="quality-rings">
          <div v-for="item in qualityMetrics" :key="item.label" class="quality-item">
            <div class="quality-ring" :style="{ background: ringGradient(item.percent, item.color) }">
              <div class="quality-hole"><strong>{{ item.percent }}%</strong></div>
            </div>
            <span>{{ item.label }}</span>
            <small>{{ item.note }}</small>
          </div>
        </div>
        <div class="quality-footer">
          <span>成功任务 {{ successCount }} / {{ totalTasks }}</span>
          <span>报告就绪 {{ reportReadyCount }} / {{ successCount }}</span>
          <span>启用资源 {{ enabledResourceCount }} / {{ totalResourceCount }}</span>
        </div>
      </el-card>

      <el-card shadow="never" class="chart-panel">
        <template #header>
          <div class="card-heading">
            <div><strong>防御阶段构成</strong><span class="table-caption">算法目录按训练前、训练中、训练后分布</span></div>
            <el-button text type="primary" @click="$router.push('/algorithms')">算法目录</el-button>
          </div>
        </template>
        <div class="stage-bars">
          <div v-for="item in stageBars" :key="item.key" class="stage-row">
            <div class="stage-row-head">
              <span><i :style="{ background: item.color }"></i>{{ item.label }}</span>
              <strong>{{ item.count }}<small>{{ item.percent }}%</small></strong>
            </div>
            <div class="stage-track"><span :style="{ width: `${item.percent}%`, background: item.color }"></span></div>
            <small>{{ item.note }}</small>
          </div>
        </div>
        <div class="resource-snapshot">
          <div><span>数据集启用</span><strong>{{ resources.enabledDatasetCount ?? 0 }} / {{ resources.datasetCount ?? 0 }}</strong></div>
          <div><span>算法启用</span><strong>{{ resources.enabledAlgorithmCount ?? 0 }} / {{ resources.algorithmCount ?? 0 }}</strong></div>
          <div><span>总算法</span><strong>{{ resources.algorithmCount ?? 0 }}</strong></div>
        </div>
      </el-card>
    </section>

    <section class="content-grid analytics-detail-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>最近任务明细</strong><span class="table-caption">图表背后的原始记录，最多 8 条</span></div>
            <el-button text type="primary" @click="$router.push('/tasks')">全部任务</el-button>
          </div>
        </template>
        <el-table :data="detailTasks" size="small" empty-text="当前范围没有任务">
          <el-table-column prop="taskName" label="任务名称" min-width="190" show-overflow-tooltip />
          <el-table-column prop="datasetName" label="数据集" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="88">
            <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="进度" width="120">
            <template #default="{ row }"><el-progress :percentage="safePercent(row.progress)" :stroke-width="6" /></template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.createTime, '—') }}</template>
          </el-table-column>
          <el-table-column label="结束时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.endTime, '—') }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="scope-card">
        <template #header><strong>统计口径</strong></template>
        <div class="definition-list">
          <div><span>统计范围</span><p>{{ scopeLabel }}；`demo` 来源任务和未归属任务不计入。</p></div>
          <div><span>趋势窗口</span><p>近 {{ trendDays }} 日按任务创建时间落桶，窗口最长 30 日。</p></div>
          <div><span>完成质量</span><p>报告就绪只统计“成功且已写入清洗结果”的任务；资源启用率取数据集与算法启用比例的平均。</p></div>
          <div><span>未采集指标</span><p>攻击成功率 ASR、GPU 利用率、样本保留率没有真实采集源，不展示估算值。</p></div>
          <div><span>采样时间</span><p>{{ sampledAtLabel }}</p></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getDashboardOverview } from '../api/dashboard'
import { getTaskStatistics } from '../api/task'
import { formatDateTime } from '../utils/time'

const loading = ref(false)
const errorMessage = ref('')
const stats = ref({})
const overview = ref(null)
const trendDays = ref(7)

const STATUS_META = [
  { key: 'success', label: '成功', meaning: '结果已落库', color: '#16a34a' },
  { key: 'failed', label: '失败', meaning: '可重试或排查', color: '#dc2626' },
  { key: 'pending', label: '等待中', meaning: '尚未启动', color: '#f59e0b' },
  { key: 'running', label: '运行中', meaning: '执行器已接手', color: '#2f80ed' },
  { key: 'canceled', label: '已取消', meaning: '用户终止', color: '#8b95a7' }
]

const scopeLabel = computed(() => (overview.value?.scope || stats.value.scope) === 'all-users' ? '全部用户（管理员）' : '当前账号')
const totalTasks = computed(() => Number(stats.value.totalCount ?? overview.value?.tasks?.total ?? 0))
const successCount = computed(() => Number(stats.value.successCount ?? overview.value?.tasks?.success ?? 0))
const pendingCount = computed(() => Number(stats.value.pendingCount ?? overview.value?.tasks?.pending ?? 0))
const runningCount = computed(() => Number(stats.value.runningCount ?? overview.value?.tasks?.running ?? 0))
const failedCount = computed(() => Number(stats.value.failedCount ?? overview.value?.tasks?.failed ?? 0))
const canceledCount = computed(() => Number(stats.value.canceledCount ?? overview.value?.tasks?.canceled ?? 0))
const successRate = computed(() => totalTasks.value ? Math.round(successCount.value / totalTasks.value * 100) : 0)
const pendingWork = computed(() => pendingCount.value + runningCount.value)
const reportReadyCount = computed(() => Number(overview.value?.reportReadiness?.tasksWithCleaningResult ?? 0))
const reportRate = computed(() => successCount.value
  ? Math.round(reportReadyCount.value / successCount.value * 100)
  : 0)
const resources = computed(() => overview.value?.resources || {})
const datasetEnableRate = computed(() => {
  const total = Number(resources.value.datasetCount ?? 0)
  return total ? Number(resources.value.enabledDatasetCount ?? 0) / total * 100 : 0
})
const algorithmEnableRate = computed(() => {
  const total = Number(resources.value.algorithmCount ?? 0)
  return total ? Number(resources.value.enabledAlgorithmCount ?? 0) / total * 100 : 0
})
const resourceRate = computed(() => Math.round((datasetEnableRate.value + algorithmEnableRate.value) / 2))
const totalResourceCount = computed(() => Number(resources.value.datasetCount ?? 0) + Number(resources.value.algorithmCount ?? 0))
const enabledResourceCount = computed(() => Number(resources.value.enabledDatasetCount ?? 0) + Number(resources.value.enabledAlgorithmCount ?? 0))
const sampledAtLabel = computed(() => overview.value?.sampledAt ? formatDateTime(overview.value.sampledAt, '—') : '尚未完成采样')

const kpiCards = computed(() => [
  { label: '任务总数', value: totalTasks.value, note: `等待 ${pendingCount.value} · 运行 ${runningCount.value}`, badge: '全量', type: 'info' },
  { label: '任务成功率', value: `${successRate.value}%`, note: `成功 ${successCount.value} · 失败 ${failedCount.value}`, badge: '质量', type: 'success', percent: successRate.value, color: '#16a34a' },
  { label: '待处理任务', value: pendingWork.value, note: '等待启动 + 正在运行', badge: '队列', type: 'warning', percent: totalTasks.value ? Math.round(pendingWork.value / totalTasks.value * 100) : 0, color: '#f59e0b' },
  { label: '报告就绪率', value: `${reportRate.value}%`, note: `${reportReadyCount.value} 份清洗结果可追溯`, badge: '产物', type: 'success', percent: reportRate.value, color: '#2f80ed' }
])

const statusSegments = computed(() => {
  const counts = stats.value.statusStats || {}
  const fallback = {
    success: successCount.value,
    failed: failedCount.value,
    pending: pendingCount.value,
    running: runningCount.value,
    canceled: canceledCount.value
  }
  const total = totalTasks.value
  return STATUS_META.map(item => {
    const count = Number(counts[item.key] ?? fallback[item.key] ?? 0)
    return { ...item, count, percent: total ? Math.round(count / total * 1000) / 10 : 0 }
  })
})
const statusDonutGradient = computed(() => {
  const total = totalTasks.value
  if (!total) return '#e8edf4'
  let cursor = 0
  const parts = statusSegments.value
    .filter(item => item.count > 0)
    .map(item => {
      const start = cursor
      cursor += item.count / total * 100
      return `${item.color} ${start}% ${cursor}%`
    })
  return `conic-gradient(${parts.join(', ')})`
})

const trendRows = computed(() => overview.value?.taskTrend || [])
const hasTrend = computed(() => trendRows.value.some(item => Number(item.total) > 0))
const trendTotal = computed(() => trendRows.value.reduce((sum, item) => sum + Number(item.total || 0), 0))
const trendSuccess = computed(() => trendRows.value.reduce((sum, item) => sum + Number(item.success || 0), 0))
const trendMax = computed(() => Math.max(1, ...trendRows.value.map(item => Number(item.total || 0))))
const trendBars = computed(() => trendRows.value.map(item => {
  const total = Number(item.total || 0)
  const segments = STATUS_META.map(meta => ({
    key: meta.key,
    color: meta.color,
    height: Math.max(0, Math.round(Number(item[meta.key] || 0) / trendMax.value * 112))
  })).filter(segment => segment.height > 0)
  return {
    date: item.date,
    label: (item.date || '').slice(5),
    total,
    height: total ? Math.max(6, Math.round(total / trendMax.value * 112)) : 0,
    segments,
    tooltip: `${item.date}：总 ${total} · 成功 ${item.success || 0} · 失败 ${item.failed || 0} · 等待 ${item.pending || 0} · 运行 ${item.running || 0}`
  }
}))

const qualityMetrics = computed(() => [
  { label: '任务成功率', percent: successRate.value, color: '#16a34a', note: `${successCount.value} / ${totalTasks.value}` },
  { label: '报告就绪率', percent: reportRate.value, color: '#2f80ed', note: `${reportReadyCount.value} / ${successCount.value}` },
  { label: '资源启用率', percent: resourceRate.value, color: '#f59e0b', note: `${enabledResourceCount.value} / ${totalResourceCount.value}` }
])
function ringGradient(percent, color) {
  return `conic-gradient(${color} 0 ${Math.max(0, Math.min(100, percent))}%, #e8edf4 ${Math.max(0, Math.min(100, percent))}% 100%)`
}

const stageBars = computed(() => {
  const byStage = resources.value.algorithmsByStage || {}
  const total = Number(resources.value.algorithmCount ?? 0) || 1
  return [
    { key: 'pre', label: '训练前防御', count: Number(byStage.pre ?? 0), color: '#2f80ed', note: '数据清洗与 LDD 执行入口' },
    { key: 'during', label: '训练中防御', count: Number(byStage.during ?? 0), color: '#7c3aed', note: '鲁棒训练与监测，仅目录展示' },
    { key: 'post', label: '训练后防御', count: Number(byStage.post ?? 0), color: '#0f9f8f', note: '模型评估与修复，仅目录展示' }
  ].map(item => ({ ...item, percent: Math.round(item.count / total * 100) }))
})

const detailTasks = computed(() => (overview.value?.recentTasks || []).slice(0, 8))
const statusLabels = { pending: '等待中', running: '运行中', success: '成功', failed: '失败', canceled: '已取消' }
function statusLabel(status) { return statusLabels[status] || status || '未知' }
function statusType(status) { return { success: 'success', running: 'warning', failed: 'danger', canceled: 'info' }[status] || 'info' }
function safePercent(value) { const number = Number(value); return Number.isFinite(number) ? Math.max(0, Math.min(100, number)) : 0 }

async function load() {
  loading.value = true
  errorMessage.value = ''
  const [statsResult, overviewResult] = await Promise.allSettled([
    getTaskStatistics(),
    getDashboardOverview(trendDays.value)
  ])
  if (statsResult.status === 'fulfilled') {
    stats.value = statsResult.value.data || {}
  }
  if (overviewResult.status === 'fulfilled') {
    overview.value = overviewResult.value.data || null
  }
  if (statsResult.status === 'rejected' && overviewResult.status === 'rejected') {
    stats.value = {}
    overview.value = null
    errorMessage.value = '统计数据读取失败，请确认后端服务和登录状态。'
  } else if (statsResult.status === 'rejected' || overviewResult.status === 'rejected') {
    errorMessage.value = '部分统计数据读取失败，已展示可用的真实记录。'
  }
  loading.value = false
}

watch(trendDays, load)
onMounted(load)
</script>

<style scoped>
.analytics-heading {
  align-items: flex-end;
}

.analytics-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.analytics-kpi-card {
  position: relative;
  overflow: hidden;
  border: 1px solid #e3ebf4;
}

.analytics-kpi-card::after {
  content: '';
  position: absolute;
  right: -26px;
  bottom: -38px;
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(47, 128, 237, .12), transparent 70%);
  pointer-events: none;
}

.analytics-kpi-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.analytics-kpi-top > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.kpi-value {
  display: block;
  margin: 10px 0 4px;
  color: #17365d;
  font-size: 27px;
  line-height: 1;
  letter-spacing: -.4px;
}

.kpi-note {
  display: block;
  min-height: 18px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.analytics-kpi-card .el-progress {
  margin-top: 10px;
}

.analytics-split {
  display: grid;
  grid-template-columns: minmax(0, .86fr) minmax(0, 1.14fr);
  gap: 18px;
  margin-bottom: 18px;
}

.chart-panel {
  min-width: 0;
  border: 1px solid #e3ebf4;
}

.status-chart {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  align-items: center;
  gap: 18px;
  min-height: 260px;
}

.donut-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.status-donut {
  display: grid;
  place-items: center;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .7);
}

.donut-hole {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 102px;
  height: 102px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 8px 24px -18px rgba(23, 58, 94, .55);
}

.donut-hole strong {
  color: #17365d;
  font-size: 26px;
  line-height: 1;
}

.donut-hole small,
.donut-caption {
  margin-top: 5px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.status-legend {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.legend-row {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid #eff3f8;
  border-radius: 9px;
  background: #fbfdff;
}

.legend-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.legend-row strong,
.legend-row small,
.legend-value,
.legend-value em {
  display: block;
}

.legend-row strong {
  color: #36516d;
  font-size: 12px;
}

.legend-row small {
  margin-top: 2px;
  color: var(--el-text-color-placeholder);
  font-size: 10px;
}

.legend-value {
  color: #17365d;
  font-size: 16px;
  font-weight: 700;
  text-align: right;
}

.legend-value em {
  margin-top: 1px;
  color: var(--el-text-color-secondary);
  font-size: 10px;
  font-style: normal;
  font-weight: 400;
}

.trend-chart {
  position: relative;
  min-height: 260px;
  padding: 12px 6px 0;
}

.trend-grid-lines {
  position: absolute;
  inset: 18px 0 38px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  pointer-events: none;
}

.trend-grid-lines span {
  height: 1px;
  background: linear-gradient(90deg, #edf2f8, rgba(237, 242, 248, .25));
}

.trend-columns {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 236px;
  padding: 0 4px;
}

.trend-column {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.trend-column.dense > small {
  font-size: 8px;
  transform: rotate(-28deg);
  transform-origin: center;
}

.trend-count {
  height: 18px;
  color: #36516d;
  font-size: 10px;
  font-weight: 600;
}

.trend-bar-shell {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  width: min(34px, 76%);
  height: 112px;
}

.trend-stack {
  display: flex;
  flex-direction: column-reverse;
  width: 100%;
  overflow: hidden;
  border-radius: 5px 5px 2px 2px;
  background: #eef3f9;
  transition: height .25s ease;
}

.trend-stack span {
  display: block;
  width: 100%;
  min-height: 0;
}

.trend-column > small {
  margin-top: 7px;
  color: var(--el-text-color-secondary);
  font-size: 9.5px;
  white-space: nowrap;
}

.trend-column.dense > small {
  font-size: 8px;
  transform: rotate(-28deg);
  transform-origin: center;
}

.chart-footer {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e7eef7;
}

.chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.chart-legend i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.chart-footer em {
  color: var(--el-text-color-secondary);
  font-size: 10.5px;
  font-style: normal;
  text-align: right;
}

.quality-rings {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 8px 0 14px;
}

.quality-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}

.quality-ring {
  display: grid;
  place-items: center;
  width: 108px;
  height: 108px;
  border-radius: 50%;
}

.quality-hole {
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: #fff;
}

.quality-hole strong {
  color: #17365d;
  font-size: 17px;
}

.quality-item > span {
  color: #36516d;
  font-size: 12px;
  font-weight: 600;
}

.quality-item > small {
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.quality-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 12px;
  padding-top: 11px;
  border-top: 1px dashed #e7eef7;
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.stage-bars {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px 0 16px;
}

.stage-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.stage-row-head > span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #36516d;
  font-size: 12px;
  font-weight: 600;
}

.stage-row-head i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.stage-row-head strong {
  color: #17365d;
  font-size: 17px;
}

.stage-row-head strong small {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 10px;
  font-weight: 400;
}

.stage-track {
  height: 8px;
  margin: 6px 0 4px;
  overflow: hidden;
  border-radius: 99px;
  background: #edf2f8;
}

.stage-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width .3s ease;
}

.stage-row > small {
  color: var(--el-text-color-placeholder);
  font-size: 10px;
}

.resource-snapshot {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding-top: 12px;
  border-top: 1px dashed #e7eef7;
}

.resource-snapshot > div {
  padding: 9px 10px;
  border-radius: 8px;
  background: #f6f9fd;
}

.resource-snapshot span,
.resource-snapshot strong {
  display: block;
}

.resource-snapshot span {
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.resource-snapshot strong {
  margin-top: 3px;
  color: #17365d;
  font-size: 14px;
}

.analytics-detail-grid {
  grid-template-columns: minmax(0, 1.55fr) minmax(280px, .45fr);
}

.scope-card {
  border: 1px solid #e3ebf4;
}

@media (max-width: 1120px) {
  .analytics-kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .analytics-split,
  .analytics-detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .analytics-heading {
    align-items: flex-start;
  }
  .analytics-kpi-grid,
  .status-chart,
  .quality-rings,
  .resource-snapshot {
    grid-template-columns: 1fr;
  }
  .status-chart {
    justify-items: center;
  }
  .status-legend {
    width: 100%;
  }
  .chart-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
