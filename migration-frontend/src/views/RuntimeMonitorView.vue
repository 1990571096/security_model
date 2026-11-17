<template>
  <div class="page" v-loading="loading">
    <section class="hero-band monitor-hero">
      <div>
        <span class="eyebrow">RUNTIME OBSERVABILITY</span>
        <h2>运行监控中心</h2>
        <p>按 API 线程池、RabbitMQ Outbox、Worker 状态、消费积压、GPU 租约五组展示真实计数；没有采集源的能力明确标记为不可用，不用 0 冒充健康。</p>
      </div>
      <div class="hero-summary monitor-summary">
        <span>调度状态</span>
        <strong>{{ dispatchReadyLabel }}</strong>
        <small>{{ dispatchModeLabel }} · 采样时间 {{ sampledAtLabel }}</small>
      </div>
    </section>

    <section class="stat-grid monitor-stat-grid">
      <el-card v-for="item in overviewStats" :key="item.label" class="stat-card" shadow="never">
        <div class="stat-card-top">
          <span class="muted">{{ item.label }}</span>
          <el-tag :type="item.type" effect="light">{{ item.badge }}</el-tag>
        </div>
        <strong>{{ item.value }}</strong>
        <small>{{ item.desc }}</small>
      </el-card>
    </section>

    <section class="monitor-five-grid">
      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>API 线程池</span><strong>{{ poolPercent }}%</strong></div>
          <el-tag :type="executorAvailable ? 'success' : 'info'" effect="light">{{ executorAvailable ? '真实计数' : '不可用' }}</el-tag>
        </div>
        <el-progress :percentage="poolPercent" :stroke-width="10" color="#2f80ed" />
        <div class="resource-meta">
          <span>活动 {{ metric(executor.activeCount, executorAvailable) }} / 最大 {{ metric(executor.maxPoolSize, executorAvailable) }}</span>
          <span>排队 {{ metric(executor.queueSize, executorAvailable) }}</span>
          <span>已完成 {{ metric(executor.completedTaskCount, executorAvailable) }}</span>
          <span>{{ executor.acceptingTasks ? '可接收新任务' : '已停止接收' }}</span>
        </div>
      </el-card>

      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>RabbitMQ Outbox</span><strong>{{ dispatch?.outboxBacklog ?? '—' }}</strong></div>
          <el-tag :type="outboxTagType" effect="light">{{ outboxTagLabel }}</el-tag>
        </div>
        <p class="table-caption">Outbox 是派发事实账本；Broker 未启用时仍展示表内积压，不伪造队列深度。</p>
        <div class="resource-meta">
          <span>new {{ metric(dispatch?.outboxNew, outboxAvailable) }}</span>
          <span>publishing {{ metric(dispatch?.outboxPublishing, outboxAvailable) }}</span>
          <span>failed {{ metric(dispatch?.outboxFailed, outboxAvailable) }}</span>
          <span>dead {{ metric(dispatch?.outboxDead, outboxAvailable) }}</span>
          <span>最久等待 {{ formatSeconds(dispatch?.oldestWaitSeconds, outboxAvailable) }}</span>
        </div>
      </el-card>

      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>Worker 状态</span><strong>{{ workerStateLabel }}</strong></div>
          <el-tag :type="workerTagType" effect="light">{{ workerTagLabel }}</el-tag>
        </div>
        <p class="table-caption">db 模式由 API 直接派发，独立 Worker 不适用；rabbit 模式才判断消费者与归属锁。</p>
        <div class="resource-meta">
          <span>模式 {{ dispatch?.mode || '—' }} / 角色 {{ dispatch?.role || '—' }}</span>
          <span>消费者启动 {{ boolLabel(dispatch?.workerConsumerStarted, workerApplicable) }}</span>
          <span>恢复中 {{ boolLabel(dispatch?.workerRecovering, workerApplicable) }}</span>
          <span>持有归属锁 {{ boolLabel(dispatch?.workerOwned, workerApplicable) }}</span>
          <span>本进程已接受 {{ metric(dispatch?.workerLocalAccepted, !!dispatch) }}</span>
        </div>
      </el-card>

      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>消费积压</span><strong>{{ metric(dispatch?.consumptionProcessing, outboxAvailable) }}</strong></div>
          <el-tag :type="outboxAvailable ? 'warning' : 'info'" effect="light">{{ outboxAvailable ? '消费账本' : '不可用' }}</el-tag>
        </div>
        <p class="table-caption">processing 为已领取未终态，retry 为等待重投，terminal 为已收敛；过期 claim 单独计数。</p>
        <div class="resource-meta">
          <span>processing {{ metric(dispatch?.consumptionProcessing, outboxAvailable) }}</span>
          <span>retry {{ metric(dispatch?.consumptionRetry, outboxAvailable) }}</span>
          <span>terminal {{ metric(dispatch?.consumptionTerminal, outboxAvailable) }}</span>
          <span>过期未释放 {{ metric(dispatch?.expiredConsumptionClaims, outboxAvailable) }}</span>
          <span>最久 processing {{ formatSeconds(dispatch?.oldestProcessingSeconds, outboxAvailable) }}</span>
        </div>
      </el-card>

      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>GPU 租约</span><strong>{{ gpuStateLabel }}</strong></div>
          <el-tag :type="gpuTagType" effect="light">{{ gpuAvailable ? '租约事实' : '不可用' }}</el-tag>
        </div>
        <p class="table-caption">槽位 {{ gpuSlot.slotId || '未登记' }}；blocked 时不会因 TTL 过期自动释放，必须确认进程退出。</p>
        <div class="resource-meta">
          <span>持有任务 {{ gpuAvailable ? (gpuSlot.taskId ?? '无') : '—' }}</span>
          <span>run {{ gpuAvailable ? (gpuSlot.runId || '无') : '—' }}</span>
          <span>attempt {{ gpuAvailable ? (gpuSlot.attempt ?? '无') : '—' }}</span>
          <span>{{ gpuSlot.state === 'leased' ? `到期 ${gpuSlot.expiresAt || '—'}` : (gpuSlot.blockedReason || '无活动租约') }}</span>
          <span>fencing {{ gpuAvailable ? (gpuSlot.fencingToken ?? '无') : '—' }}</span>
        </div>
      </el-card>
    </section>

    <section class="content-grid monitor-main-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>当前运行任务</strong><span class="table-caption">当前账号 owner 范围内的 running 任务；不展示其他用户任务</span></div>
            <el-button type="primary" plain @click="load">刷新状态</el-button>
          </div>
        </template>
        <el-table :data="runningTasks" class="monitor-table">
          <el-table-column prop="taskName" label="任务名称" min-width="180" />
          <el-table-column prop="algorithmName" label="算法" width="130" />
          <el-table-column prop="datasetName" label="数据集" min-width="160" />
          <el-table-column label="进度" width="180">
            <template #default="{ row }"><el-progress :percentage="row.progress || 0" :stroke-width="8" /></template>
          </el-table-column>
          <el-table-column label="已运行" width="120">
            <template #default="{ row }">{{ formatDuration(row.runningDuration) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!runningTasks.length" description="当前没有运行中的任务" />
      </el-card>

      <el-card shadow="never" v-if="adminSummary">
        <template #header><div class="card-heading"><div><strong>全局观测</strong><span class="table-caption">仅管理员可见，聚合层不含 taskId/userId</span></div><el-tag type="warning">管理端</el-tag></div></template>
        <div class="definition-list">
          <div><span>进度陈旧</span><p>{{ adminSummary.staleRunningTasks ?? 0 }} 个 running 任务超过阈值未更新</p></div>
          <div><span>缓存</span><p>命中 {{ adminSummary.cache?.hit ?? 0 }} / 未命中 {{ adminSummary.cache?.miss ?? 0 }} / 降级 {{ adminSummary.cache?.degraded ?? 0 }}</p></div>
          <div><span>槽位</span><p>{{ adminSummary.gpuSlot?.state || 'unknown' }} · {{ adminSummary.gpuSlot?.slotId || '未登记' }}</p></div>
        </div>
      </el-card>
      <el-card shadow="never" v-else>
        <template #header><strong>告警与运行事件</strong></template>
        <el-alert title="事件流未接入" type="info" :closable="false" show-icon description="当前没有持久化事件流，页面不展示虚构告警或伪造吞吐趋势。" />
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getDispatchObservability, getExecutorStatus, getObservabilitySummary, getRunningTasks } from '../api/task'

const loading = ref(false)
const executor = ref({})
const dispatch = ref(null)
const runningTasks = ref([])
const adminSummary = ref(null)
const refreshedAt = ref('')

const executorAvailable = computed(() => executor.value.acceptingTasks !== undefined)
const outboxAvailable = computed(() => dispatch.value?.outboxAvailable === true)
const workerApplicable = computed(() => dispatch.value?.workerConsumerExpected === true)
const gpuSlot = computed(() => dispatch.value?.gpuSlot || executor.value.gpuSlot || {})
const gpuAvailable = computed(() => gpuSlot.value.available !== false)

const poolPercent = computed(() => {
  const max = Number(executor.value.maxPoolSize || 0)
  return max > 0 ? Math.min(100, Math.round((Number(executor.value.activeCount || 0) / max) * 100)) : 0
})

const dispatchModeLabel = computed(() => {
  if (!dispatch.value) return 'admin 接口不可用'
  const mode = dispatch.value.mode === 'rabbit' ? 'RabbitMQ' : '数据库直派'
  return `${mode} / ${dispatch.value.role || '—'}`
})

const dispatchReadyLabel = computed(() => {
  if (!dispatch.value) return '未知'
  return dispatch.value.dispatchReady ? '可接收任务' : '启动恢复未完成'
})

const sampledAtLabel = computed(() => dispatch.value?.sampledAt || refreshedAt.value || '—')

const overviewStats = computed(() => [
  { label: '运行中任务', value: executor.value.runningTaskCount ?? 0, desc: 'MySQL status=running', badge: '实时', type: 'success' },
  { label: '等待队列', value: metric(executor.value.queueSize, executorAvailable.value), desc: '尚未被线程接手的提交', badge: '队列', type: 'warning' },
  { label: 'GPU 槽位', value: gpuStateLabel.value, desc: '单槽租约，不显示虚构节点', badge: '租约', type: gpuTagType.value },
  { label: 'Outbox 积压', value: metric(dispatch.value?.outboxBacklog, outboxAvailable.value), desc: 'new/failed 中尚未发布的账本', badge: '派发', type: outboxTagType.value }
])

const outboxTagLabel = computed(() => {
  if (!dispatch.value) return '需管理员'
  if (!outboxAvailable.value) return '不可用'
  return dispatch.value.mode === 'rabbit' ? 'Broker 模式' : '数据库账本'
})
const outboxTagType = computed(() => {
  if (!dispatch.value || !outboxAvailable.value) return 'info'
  return (dispatch.value.outboxDead || 0) > 0 ? 'danger' : 'success'
})

const workerStateLabel = computed(() => {
  if (!dispatch.value) return '需管理员'
  if (!workerApplicable.value) return '不适用'
  if (dispatch.value.workerRecovering) return '恢复中'
  return dispatch.value.workerConsumerStarted ? '消费中' : '未启动'
})
const workerTagLabel = computed(() => {
  if (!dispatch.value) return '需管理员'
  return workerApplicable.value ? '独立 Worker' : 'db 模式'
})
const workerTagType = computed(() => {
  if (!dispatch.value || !workerApplicable.value) return 'info'
  return dispatch.value.workerConsumerStarted ? 'success' : 'warning'
})

const gpuStateLabel = computed(() => {
  if (!gpuAvailable.value) return '不可用'
  return gpuSlot.value.state || 'unknown'
})
const gpuTagType = computed(() => {
  if (!gpuAvailable.value) return 'info'
  if (gpuSlot.value.state === 'blocked') return 'danger'
  if (gpuSlot.value.state === 'leased') return 'warning'
  return 'success'
})

function metric(value, available) {
  if (!available) return '—'
  return value ?? '—'
}

function boolLabel(value, applicable) {
  if (!applicable) return '不适用'
  if (value === undefined || value === null) return '未知'
  return value ? '是' : '否'
}

function formatSeconds(seconds, available) {
  if (!available) return '—'
  const value = Number(seconds || 0)
  if (value < 60) return `${value}s`
  if (value < 3600) return `${Math.floor(value / 60)}m ${value % 60}s`
  return `${Math.floor(value / 3600)}h ${Math.floor((value % 3600) / 60)}m`
}

function formatDuration(millis) {
  const value = Number(millis || 0)
  const totalSeconds = Math.floor(value / 1000)
  const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, '0')
  const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0')
  const seconds = String(totalSeconds % 60).padStart(2, '0')
  return `${hours}:${minutes}:${seconds}`
}

async function load() {
  loading.value = true
  try {
    const [statusResponse, runningResponse] = await Promise.all([getExecutorStatus(), getRunningTasks()])
    executor.value = statusResponse.data || {}
    runningTasks.value = runningResponse.data || []
    refreshedAt.value = new Date().toLocaleString()
    try {
      const dispatchResponse = await getDispatchObservability()
      dispatch.value = dispatchResponse.data || null
    } catch (error) {
      dispatch.value = null
    }
    try {
      const adminResponse = await getObservabilitySummary()
      adminSummary.value = adminResponse.data || null
    } catch (error) {
      adminSummary.value = null
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.monitor-five-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.monitor-five-grid .resource-meta {
  flex-wrap: wrap;
  gap: 8px 18px;
}
</style>
