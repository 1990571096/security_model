<template>
  <div class="page dashboard-page" v-loading="loading">
    <section class="hero-band dashboard-hero">
      <div>
        <span class="eyebrow">SECURITY EXPERIMENT OVERVIEW</span>
        <h2>安全模型训练工作台</h2>
        <p>围绕数据集、算法、任务和报告构建可追溯的模型安全实验闭环。页面数字均来自 MySQL 真实记录，按当前账号可见范围统计；未接入的指标显示“未采集”，不再使用演示常量。</p>
      </div>
      <div class="hero-summary">
        <span>统计范围</span>
        <strong>{{ scopeLabel }}</strong>
        <small>{{ sampledAtText }}</small>
        <el-button text type="primary" :loading="loading" @click="load">刷新</el-button>
      </div>
    </section>

    <el-alert v-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon class="page-alert" />

    <div class="stat-grid dashboard-stat-grid">
      <el-card v-for="item in stats" :key="item.label" shadow="never" class="stat-card">
        <div class="stat-card-top"><span class="muted">{{ item.label }}</span><el-tag :type="item.type" effect="plain">{{ item.badge }}</el-tag></div>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
      </el-card>
    </div>

    <section class="content-grid dashboard-main-grid">
      <el-card shadow="never" class="chart-card">
        <template #header>
          <div class="card-heading">
            <div><strong>实验任务趋势</strong><span class="table-caption">近 {{ trendDays }} 日按创建时间统计的真实任务</span></div>
            <el-tag type="info">MySQL</el-tag>
          </div>
        </template>
        <div v-if="hasTrend" class="bar-chart">
          <div v-for="item in taskTrend" :key="item.date" class="bar-group">
            <div class="bar-values"><span :style="{ height: `${barHeight(item.total)}px` }"></span><i :style="{ height: `${barHeight(item.success)}px` }"></i></div>
            <strong>{{ item.total }}</strong><small>{{ item.label }}</small>
          </div>
        </div>
        <el-empty v-else description="近 7 日没有真实任务记录" :image-size="64" />
        <div class="chart-legend">
          <span><i class="legend-total"></i>全部任务</span>
          <span><i class="legend-success"></i>成功任务</span>
          <em>近 {{ trendDays }} 日共 {{ trendTotal }} 个任务，成功 {{ trendSuccess }} 个</em>
        </div>
      </el-card>

      <el-card shadow="never" class="risk-card">
        <template #header><div class="card-heading"><strong>防御体系算法分布</strong><el-button text type="primary" @click="$router.push('/algorithms')">查看算法目录</el-button></div></template>
        <div v-for="item in defenseCoverage" :key="item.stage" class="coverage-row">
          <div class="coverage-icon">{{ item.code }}</div>
          <div class="coverage-content">
            <div><strong>{{ item.title }}</strong><span>{{ item.count }} 个已登记算法</span></div>
            <el-progress :percentage="item.percentage" :stroke-width="8" :show-text="false" :color="item.color" />
            <small>{{ item.detail }}</small>
          </div>
          <strong class="coverage-percent">{{ item.count }}</strong>
        </div>
      </el-card>
    </section>

    <section class="content-grid dashboard-secondary-grid">
      <el-card shadow="never">
        <template #header><div class="card-heading"><div><strong>近期实验任务</strong><span class="table-caption">按创建时间倒序的真实任务，最多 5 条</span></div><el-button text type="primary" @click="$router.push('/tasks')">全部任务</el-button></div></template>
        <el-table :data="recentTasks" size="small">
          <el-table-column prop="taskName" label="任务名称" min-width="190" show-overflow-tooltip />
          <el-table-column prop="datasetName" label="数据集" min-width="150" show-overflow-tooltip />
          <el-table-column label="进度" width="90"><template #default="{ row }">{{ row.progress ?? 0 }}%</template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        </el-table>
        <el-empty v-if="!loading && !recentTasks.length" description="当前范围还没有真实任务" :image-size="56" />
      </el-card>
      <el-card shadow="never">
        <template #header><div class="card-heading"><strong>执行池状态</strong><el-tag type="info">实时</el-tag></div></template>
        <div class="activity-list">
          <div v-for="item in executorRows" :key="item.label" class="activity-item">
            <span class="activity-time">{{ item.label }}</span><div><strong>{{ item.value }}</strong><p>{{ item.note }}</p></div>
          </div>
        </div>
        <el-alert v-if="executorError" :title="executorError" type="warning" :closable="false" show-icon />
      </el-card>
    </section>

    <section class="dashboard-footer-grid">
      <el-card shadow="never">
        <template #header><strong>实验指标</strong></template>
        <div class="metric-list">
          <div><span>平均攻击成功率</span><strong>未采集</strong><small>需要模型评估链路，当前未接入</small></div>
          <div><span>平均清洗保留率</span><strong>未采集</strong><small>清洗纯净度请在任务报告中查看</small></div>
          <div><span>清洗结果任务</span><strong>{{ reportRatioText }}</strong><small>{{ reportReadinessText }}</small></div>
        </div>
        <small class="muted">{{ metricsNote }}</small>
      </el-card>
      <el-card shadow="never">
        <template #header><strong>资源与报告</strong></template>
        <div class="baseline-grid">
          <div><span>数据集</span><strong>{{ resourceCount('datasetCount') }}</strong></div>
          <div><span>算法</span><strong>{{ resourceCount('algorithmCount') }}</strong></div>
          <div><span>成功任务</span><strong>{{ taskCount('success') }}</strong></div>
          <div><span>有清洗结果</span><strong>{{ readinessValue('tasksWithCleaningResult') }}</strong></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getDashboardOverview } from '../api/dashboard'
import { getExecutorStatus } from '../api/task'

const trendDays = 7
const loading = ref(false)
const errorMessage = ref('')
const overview = ref(null)
const executor = ref(null)
const executorError = ref('')

const statusLabels = { pending: '等待中', running: '运行中', success: '已完成', failed: '失败', canceled: '已取消' }
function statusLabel(status) { return statusLabels[status] || status || '未知' }
function statusType(status) { return { success: 'success', running: 'warning', failed: 'danger', canceled: 'info' }[status] || 'info' }

const scopeLabel = computed(() => overview.value?.scope === 'all-users' ? '全库（管理员）' : '仅本人')
const sampledAtText = computed(() => {
  const raw = overview.value?.sampledAt
  return raw ? `统计时间 ${raw.replace('T', ' ').slice(0, 19)}` : '尚未完成统计'
})

const stats = computed(() => {
  if (!overview.value?.tasks || !overview.value?.resources) {
    return ['累计实验任务', '成功 / 失败任务', '资源目录', '报告就绪'].map(label => ({
      label, value: '—', note: '数据未加载', badge: '待加载', type: 'info'
    }))
  }
  const tasks = overview.value.tasks
  const resources = overview.value.resources
  return [
    { label: '累计实验任务', value: valueOrDash(tasks.total), note: `等待中 ${valueOrZero(tasks.pending)} · 运行中 ${valueOrZero(tasks.running)}`, badge: '真实', type: 'success' },
    { label: '成功 / 失败任务', value: `${valueOrZero(tasks.success)} / ${valueOrZero(tasks.failed)}`, note: `已取消 ${valueOrZero(tasks.canceled)}`, badge: 'MySQL', type: 'info' },
    { label: '资源目录', value: `${valueOrZero(resources.datasetCount)} / ${valueOrZero(resources.algorithmCount)}`, note: '数据集 / 算法登记数', badge: '目录', type: 'warning' },
    { label: '报告就绪', value: `${valueOrZero(overview.value?.reportReadiness?.tasksWithCleaningResult)} / ${valueOrZero(overview.value?.reportReadiness?.successfulTasks)}`, note: '有清洗结果的成功任务 / 成功任务', badge: '可追溯', type: 'success' }
  ]
})

const taskTrend = computed(() => (overview.value?.taskTrend || []).map(item => ({
  ...item,
  label: (item.date || '').slice(5)
})))
const hasTrend = computed(() => taskTrend.value.some(item => Number(item.total) > 0))
const trendTotal = computed(() => taskTrend.value.reduce((sum, item) => sum + Number(item.total || 0), 0))
const trendSuccess = computed(() => taskTrend.value.reduce((sum, item) => sum + Number(item.success || 0), 0))
const trendMax = computed(() => Math.max(1, ...taskTrend.value.map(item => Number(item.total || 0))))
function barHeight(count) {
  const value = Number(count || 0)
  return value === 0 ? 0 : Math.max(4, Math.round(value / trendMax.value * 72))
}

const defenseCoverage = computed(() => {
  const byStage = overview.value?.resources?.algorithmsByStage || {}
  const resources = overview.value?.resources || {}
  const counts = [byStage.pre || 0, byStage.during || 0, byStage.post || 0]
  const total = counts.reduce((sum, count) => sum + Number(count), 0)
  const percent = count => total === 0 ? 0 : Math.round(Number(count) / total * 100)
  return [
    { stage: 'pre', code: '01', title: '训练前防御', count: counts[0], percentage: percent(counts[0]), detail: 'LDD 数据清洗已接入真实执行', color: '#2f80ed' },
    { stage: 'during', code: '02', title: '训练中防御', count: counts[1], percentage: percent(counts[1]), detail: '算法已登记，真实执行未接入', color: '#21a366' },
    { stage: 'post', code: '03', title: '训练后防御', count: counts[2], percentage: percent(counts[2]), detail: '算法已登记，真实执行未接入', color: '#e6a23c' }
  ]
})

const recentTasks = computed(() => overview.value?.recentTasks || [])

const executorRows = computed(() => {
  const data = executor.value
  if (!data) return []
  return [
    { label: '活动线程', value: `${data.activeCount ?? 0} / ${data.poolSize ?? 0}`, note: `最大线程 ${data.maxPoolSize ?? '—'}` },
    { label: '队列', value: `${data.queueSize ?? 0} / ${data.queueCapacity ?? 0}`, note: `已完成 ${data.completedTaskCount ?? 0}` },
    { label: '接收状态', value: data.acceptingTasks ? '可接收' : '已停止', note: data.saturated ? '执行池饱和' : '执行池未饱和' }
  ]
})

const metricsNote = computed(() => overview.value?.metrics?.note || '工作台不聚合正式实验指标。')
const reportRatioText = computed(() => {
  if (!overview.value?.reportReadiness) return '—'
  const readiness = overview.value.reportReadiness
  return readiness.ratio == null ? '暂无成功任务' : `${readiness.ratio}%`
})
const reportReadinessText = computed(() => {
  if (!overview.value?.reportReadiness) return '数据未加载'
  const readiness = overview.value.reportReadiness
  return `${valueOrZero(readiness.tasksWithCleaningResult)} / ${valueOrZero(readiness.successfulTasks)} 个成功任务有清洗结果`
})

function valueOrZero(value) { return value == null ? 0 : value }
function valueOrDash(value) { return value == null ? '—' : value }
function resourceCount(key) { return overview.value ? valueOrZero(overview.value?.resources?.[key]) : '—' }
function taskCount(status) { return overview.value ? valueOrZero(overview.value?.tasks?.[status]) : '—' }
function readinessValue(key) { return overview.value ? valueOrZero(overview.value?.reportReadiness?.[key]) : '—' }

function unwrap(response) {
  return response?.data?.data ?? response?.data ?? response ?? {}
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  executorError.value = ''
  try {
    const data = unwrap(await getDashboardOverview(trendDays))
    overview.value = data && typeof data === 'object' ? data : null
  } catch (error) {
    overview.value = null
    errorMessage.value = error.response?.data?.message || '工作台数据读取失败，请检查服务连接后重试。'
  } finally {
    loading.value = false
  }
  try {
    executor.value = unwrap(await getExecutorStatus())
  } catch (error) {
    executor.value = null
    executorError.value = '执行池状态读取失败'
  }
}

onMounted(load)
</script>
