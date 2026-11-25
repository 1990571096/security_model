<template>
  <div class="page" v-loading="loading">
    <section class="hero-band monitor-hero">
      <div>
        <span class="eyebrow">RUNTIME OBSERVABILITY</span>
        <h2>运行监控中心</h2>
        <p>先看本机 CPU、内存、JVM 堆和磁盘的真实采样，再按“执行资源、平台接入、消息派发”查看线程池、运行配额、GPU 租约、RabbitMQ Outbox、Worker 和消费积压。没有采集源的能力明确标记为不可用，不用 0 冒充健康。</p>
      </div>
      <div class="hero-summary monitor-summary">
        <span>调度状态</span>
        <strong>{{ dispatchReadyLabel }}</strong>
        <small>{{ dispatchModeLabel }} · 采样时间 {{ sampledAtLabel }}</small>
      </div>
    </section>

    <section class="host-panel">
      <div class="host-panel-head">
        <div>
          <span class="eyebrow">LOCAL HOST TELEMETRY</span>
          <h3>本机运行环境</h3>
          <p>来自当前 API 进程所在机器的实时采样；磁盘口径为后端工作目录所在卷。</p>
        </div>
        <div class="host-panel-actions">
          <span v-if="host.sampledAt" class="host-sampled">采样 {{ formatDateTime(host.sampledAt) }}</span>
          <el-tag :type="hostAvailable ? 'success' : 'info'" effect="light">{{ hostAvailable ? '实时采集' : '不可用' }}</el-tag>
        </div>
      </div>
      <div class="host-metric-grid">
        <div class="host-metric-card">
          <div class="host-metric-title"><span>CPU 使用率</span><small>{{ host.cpuLoadPercent != null && host.cpuLoadPercent >= 0 ? `${host.cpuLoadPercent}%` : '未采集' }}</small></div>
          <el-progress :percentage="safePercent(host.cpuLoadPercent)" :stroke-width="8" :show-text="false" :color="loadColor(host.cpuLoadPercent)" />
          <div class="host-metric-foot">
            <span>{{ host.availableProcessors || '—' }} 个逻辑处理器</span>
            <span>进程占用 {{ host.processCpuLoadPercent != null && host.processCpuLoadPercent >= 0 ? `${host.processCpuLoadPercent}%` : '—' }}</span>
          </div>
        </div>
        <div class="host-metric-card">
          <div class="host-metric-title"><span>物理内存</span><small>{{ host.memoryUsedPercent != null ? `${host.memoryUsedPercent}%` : '未采集' }}</small></div>
          <el-progress :percentage="safePercent(host.memoryUsedPercent)" :stroke-width="8" :show-text="false" :color="loadColor(host.memoryUsedPercent)" />
          <div class="host-metric-foot">
            <span>已用 {{ formatBytes(host.memoryUsedBytes) }}</span>
            <span>总量 {{ formatBytes(host.memoryTotalBytes) }}</span>
          </div>
        </div>
        <div class="host-metric-card">
          <div class="host-metric-title"><span>JVM 堆内存</span><small>{{ host.heapUsedPercent != null ? `${host.heapUsedPercent}%` : '未采集' }}</small></div>
          <el-progress :percentage="safePercent(host.heapUsedPercent)" :stroke-width="8" :show-text="false" color="#7c3aed" />
          <div class="host-metric-foot">
            <span>已用 {{ formatBytes(host.heapUsedBytes) }}</span>
            <span>上限 {{ formatBytes(host.heapMaxBytes) }}</span>
          </div>
        </div>
        <div class="host-metric-card">
          <div class="host-metric-title"><span>磁盘空间</span><small>{{ host.disk?.usedPercent != null ? `${host.disk.usedPercent}%` : '不可用' }}</small></div>
          <el-progress :percentage="safePercent(host.disk?.usedPercent)" :stroke-width="8" :show-text="false" :color="loadColor(host.disk?.usedPercent)" />
          <div class="host-metric-foot">
            <span>已用 {{ formatBytes(host.disk?.usedBytes) }}</span>
            <span>可用 {{ formatBytes(host.disk?.usableBytes) }}</span>
          </div>
        </div>
      </div>
      <div class="host-meta-line">
        <span>操作系统 {{ host.osName || '—' }} {{ host.osArch || '' }}</span>
        <span>Java {{ host.javaVersion || '—' }}</span>
        <span>JVM 已运行 {{ formatUptime(host.jvmUptimeSeconds) }}</span>
        <span v-if="host.disk?.path" class="host-path">磁盘卷 {{ host.disk.path }}</span>
      </div>
    </section>

    <div class="monitor-section-label">
      <div><strong>分布式运行与派发</strong><span>以下指标来自 MySQL 调度账本、线程池、Worker 和 GPU 租约，不代表本机硬件负载。</span></div>
      <el-tag type="info" effect="plain">分布式观测</el-tag>
    </div>

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

    <section class="monitor-category">
      <div class="monitor-category-head">
        <div><strong>执行资源</strong><span>线程池、运行配额与 GPU 租约；回答“现在能不能再跑一个任务”。</span></div>
        <el-tag type="info" effect="plain">EXECUTION</el-tag>
      </div>
      <div class="monitor-two-grid">
        <el-card class="resource-card" shadow="never">
          <div class="resource-card-head">
            <div><span>执行池与运行配额</span><strong>{{ executorStateLabel }}</strong></div>            <el-tag :type="executorStateTagType" effect="light">{{ executorAvailable ? '实时计数' : '不可用' }}</el-tag>
          </div>
          <el-progress :percentage="poolPercent" :stroke-width="10" color="#2f80ed" />
          <div class="resource-meta">
            <span>活跃线程 {{ metric(executor.activeCount, executorAvailable) }} / 最大 {{ metric(executor.maxPoolSize, executorAvailable) }}</span>
            <span>运行配额 {{ metric(executor.runningTaskCount, executorAvailable) }} / {{ metric(executor.maxRunningTasks, executorAvailable) }}</span>
            <span>排队 {{ metric(executor.queueSize, executorAvailable) }} / {{ metric(executor.queueCapacity, executorAvailable) }}</span>
            <span>已完成 {{ metric(executor.completedTaskCount, executorAvailable) }}</span>
            <span>{{ executor.acceptingTasks === false ? '已停止接收新任务' : '可接收新任务' }}</span>
          </div>
          <div class="quota-line">
            <span>配额占用 {{ quotaPercent }}%</span>
            <el-progress :percentage="quotaPercent" :stroke-width="6" :show-text="false" :status="quotaPercent >= 100 ? 'exception' : undefined" />
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
            <span>{{ gpuSlot.state === 'leased' ? `到期 ${formatDateTime(gpuSlot.expiresAt)}` : (gpuSlot.blockedReason || '无活动租约') }}</span>
            <span>fencing {{ gpuAvailable ? (gpuSlot.fencingToken ?? '无') : '—' }}</span>
          </div>
        </el-card>
      </div>
    </section>

    <section class="monitor-category">
      <div class="monitor-category-head">
        <div><strong>平台接入</strong><span>说明当前哪个防御阶段真正进入执行链路，哪些只是目录展示。</span></div>
        <el-tag type="success" effect="plain">CAPABILITY</el-tag>
      </div>
      <div class="access-panel">
        <div class="access-main">
          <div class="access-icon">LDD</div>
          <div>
            <div class="access-title"><strong>训练前数据清洗</strong><el-tag type="success" size="small">执行器已接入</el-tag></div>
            <p>LDD (VaB) · <code>vab://pre-defense/ldd</code></p>
          </div>
        </div>
        <div class="access-metrics">
          <div><span>可执行算法</span><strong>{{ runnableAlgorithmCount }}</strong><small>训练前 LDD</small></div>
          <div><span>训练前登记</span><strong>{{ preAlgorithmCount }}</strong><small>含仅目录展示</small></div>
          <div><span>仅目录展示</span><strong>{{ directoryOnlyCount }}</strong><small>训练中 / 训练后</small></div>
        </div>
        <div class="access-foot">
          <span>可执行阶段 1 / 3：训练中、训练后当前只展示算法目录，不会创建执行任务。</span>
          <el-tag v-if="algorithmError" type="warning" effect="light">算法目录读取失败</el-tag>
        </div>
      </div>
    </section>

    <section class="monitor-category">
      <div class="monitor-category-head">
        <div><strong>消息派发</strong><span>Outbox、Worker 与消费账本；回答“任务有没有被后台接手”。</span></div>
        <el-tag type="warning" effect="plain">DISPATCH</el-tag>
      </div>
      <div class="monitor-three-grid">
        <el-card class="resource-card" shadow="never">
          <div class="resource-card-head">
            <div><span>Outbox 积压</span><strong>{{ dispatch?.outboxBacklog ?? '—' }}</strong></div>
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
      </div>
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
import { getAlgorithms } from '../api/algorithm'
import { formatDateTime } from '../utils/time'

const loading = ref(false)
const executor = ref({})
const dispatch = ref(null)
const runningTasks = ref([])
const adminSummary = ref(null)
const algorithms = ref([])
const algorithmError = ref(false)
const refreshedAt = ref('')

const executorAvailable = computed(() => executor.value.acceptingTasks !== undefined)
const host = computed(() => executor.value.host || {})
const hostAvailable = computed(() => host.value.available === true)
const outboxAvailable = computed(() => dispatch.value?.outboxAvailable === true)
const workerApplicable = computed(() => dispatch.value?.workerConsumerExpected === true)
const gpuSlot = computed(() => dispatch.value?.gpuSlot || executor.value.gpuSlot || {})
const gpuAvailable = computed(() => gpuSlot.value.available !== false)

const poolPercent = computed(() => {
  const max = Number(executor.value.maxPoolSize || 0)
  return max > 0 ? Math.min(100, Math.round((Number(executor.value.activeCount || 0) / max) * 100)) : 0
})
const quotaPercent = computed(() => {
  const max = Number(executor.value.maxRunningTasks || 0)
  return max > 0 ? Math.min(100, Math.round((Number(executor.value.runningTaskCount || 0) / max) * 100)) : 0
})
const executorStateLabel = computed(() => {
  if (!executorAvailable.value) return '不可用'
  if (executor.value.acceptingTasks === false) return '已暂停'
  return executor.value.saturated ? '紧张' : '正常'
})
const executorStateTagType = computed(() => {
  if (!executorAvailable.value) return 'info'
  if (executor.value.acceptingTasks === false) return 'danger'
  return executor.value.saturated ? 'warning' : 'success'
})
const algorithmsLoaded = computed(() => algorithms.value.length > 0)
const runnableAlgorithms = computed(() => algorithms.value.filter(item =>
  item.enabled === true && item.defenseStage === 'pre' && item.implementationPath === 'vab://pre-defense/ldd'))
const runnableAlgorithmCount = computed(() => algorithmsLoaded.value ? runnableAlgorithms.value.length : '—')
const preAlgorithmCount = computed(() => algorithmsLoaded.value
  ? algorithms.value.filter(item => item.defenseStage === 'pre').length : '—')
const directoryOnlyCount = computed(() => algorithmsLoaded.value
  ? algorithms.value.filter(item => !(item.enabled === true && item.defenseStage === 'pre' && item.implementationPath === 'vab://pre-defense/ldd')).length
  : '—')

const dispatchModeLabel = computed(() => {
  if (!dispatch.value) return 'admin 接口不可用'
  const mode = dispatch.value.mode === 'rabbit' ? 'RabbitMQ' : '数据库直派'
  return `${mode} / ${dispatch.value.role || '—'}`
})

const dispatchReadyLabel = computed(() => {
  if (!dispatch.value) return '未知'
  return dispatch.value.dispatchReady ? '可接收任务' : '启动恢复未完成'
})

const sampledAtLabel = computed(() => formatDateTime(dispatch.value?.sampledAt || host.value.sampledAt || refreshedAt.value, '—'))

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
  return { idle: '空闲', leased: '占用中', blocked: '隔离中', unknown: '未知' }[gpuSlot.value.state] || gpuSlot.value.state || '未知'
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

function safePercent(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) return 0
  return Math.min(100, Math.round(number))
}

function loadColor(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) return '#9ca3af'
  if (number >= 85) return '#dc2626'
  if (number >= 70) return '#f59e0b'
  return '#2f80ed'
}

function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${Math.round(bytes)} B`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB']
  let amount = bytes / 1024
  let index = 0
  while (amount >= 1024 && index < units.length - 1) {
    amount /= 1024
    index += 1
  }
  return `${amount.toFixed(amount >= 100 ? 0 : 1)} ${units[index]}`
}

function formatUptime(seconds) {
  const value = Number(seconds)
  if (!Number.isFinite(value) || value < 0) return '—'
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  return days > 0 ? `${days} 天 ${hours} 小时` : `${hours} 小时 ${minutes} 分`
}

async function load() {
  loading.value = true
  try {
    const [statusResponse, runningResponse] = await Promise.all([getExecutorStatus(), getRunningTasks()])
    executor.value = statusResponse.data || {}
    runningTasks.value = runningResponse.data || []
    refreshedAt.value = formatDateTime(new Date())
    try {
      const { data } = await getAlgorithms()
      const result = data?.data ?? data
      algorithms.value = Array.isArray(result) ? result : (result?.records ?? [])
      algorithmError.value = false
    } catch (error) {
      algorithms.value = []
      algorithmError.value = true
    }
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
.host-panel {
  margin-bottom: 22px;
  padding: 20px 22px 16px;
  border: 1px solid #dce9f6;
  border-radius: 16px;
  background: linear-gradient(180deg, #fff, #f8fbff);
  box-shadow: 0 18px 34px -32px rgba(23, 58, 94, .45);
}

.host-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 16px;
}

.host-panel-head h3 {
  margin: 4px 0 3px;
  color: var(--ea-navy, #183a5a);
  font-size: 20px;
}

.host-panel-head p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.host-panel-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.host-sampled {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  white-space: nowrap;
}

.host-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.host-metric-card {
  padding: 12px 14px;
  border: 1px solid #e5eef8;
  border-radius: 12px;
  background: #fff;
}

.host-metric-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 9px;
}

.host-metric-title span {
  color: #36516d;
  font-size: 12px;
  font-weight: 600;
}

.host-metric-title small {
  color: #2f80ed;
  font-size: 15px;
  font-weight: 750;
}

.host-metric-card :deep(.el-progress) {
  margin-bottom: 9px;
}

.host-metric-foot {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 10.5px;
}

.host-meta-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #e2ecf7;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.host-path {
  max-width: 100%;
  overflow-wrap: anywhere;
}

.monitor-section-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 6px 0 12px;
}

.monitor-section-label strong {
  color: var(--ea-navy, #183a5a);
  font-size: 15px;
}

.monitor-section-label span {
  display: block;
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.monitor-category {
  margin-bottom: 22px;
}

.monitor-category-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  padding-left: 10px;
  border-left: 3px solid #2f80ed;
}

.monitor-category-head strong {
  display: block;
  color: var(--ea-navy, #183a5a);
  font-size: 15px;
}

.monitor-category-head span {
  display: block;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.monitor-two-grid,
.monitor-three-grid {
  display: grid;
  gap: 16px;
}

.monitor-two-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.monitor-three-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.monitor-category .resource-card,
.monitor-category .access-panel {
  height: 100%;
}

.quota-line {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #e2ecf7;
}

.quota-line > span {
  display: block;
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 10.5px;
}

.resource-meta {
  flex-wrap: wrap;
  gap: 8px 18px;
}

.access-panel {
  padding: 18px 20px;
  border: 1px solid #dce9f6;
  border-radius: 16px;
  background:
    radial-gradient(circle at 100% 0, rgba(47, 128, 237, .09), transparent 42%),
    linear-gradient(180deg, #fff, #f8fbff);
  box-shadow: 0 18px 34px -32px rgba(23, 58, 94, .45);
}

.access-main {
  display: flex;
  align-items: center;
  gap: 14px;
}

.access-icon {
  display: grid;
  place-items: center;
  flex: 0 0 58px;
  width: 58px;
  height: 58px;
  border-radius: 14px;
  background: #e8f1ff;
  color: #2f80ed;
  font-weight: 800;
  letter-spacing: .5px;
}

.access-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.access-title strong {
  color: var(--ea-navy, #183a5a);
  font-size: 17px;
}

.access-main p {
  margin: 5px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.access-main code {
  padding: 1px 6px;
  border-radius: 4px;
  background: #edf4fe;
  color: #31587d;
  font-size: 11px;
}

.access-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.access-metrics > div {
  padding: 11px 13px;
  border: 1px solid #e5eef8;
  border-radius: 11px;
  background: #fff;
}

.access-metrics span,
.access-metrics strong,
.access-metrics small {
  display: block;
}

.access-metrics span {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.access-metrics strong {
  margin: 3px 0;
  color: #17365d;
  font-size: 21px;
}

.access-metrics small {
  color: var(--el-text-color-placeholder);
  font-size: 10px;
}

.access-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 13px;
  padding-top: 11px;
  border-top: 1px dashed #e2ecf7;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

@media (max-width: 1100px) {
  .monitor-three-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .host-panel {
    padding: 16px;
  }
  .host-panel-head {
    flex-direction: column;
  }
  .host-panel-actions {
    justify-content: flex-start;
  }
  .monitor-section-label {
    align-items: flex-start;
  }
  .monitor-two-grid,
  .monitor-three-grid,
  .access-metrics {
    grid-template-columns: 1fr;
  }
  .access-foot,
  .monitor-category-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
