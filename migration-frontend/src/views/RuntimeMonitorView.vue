<template>
  <div class="page" v-loading="loading">
    <section class="hero-band monitor-hero">
      <div>
        <span class="eyebrow">RUNTIME OBSERVABILITY</span>
        <h2>运行监控中心</h2>
        <p>任务、执行池和 GPU 槽位来自当前后端；CPU/GPU 利用率、节点拓扑和事件流没有真实采集源时不展示估算值。</p>
      </div>
      <div class="hero-summary monitor-summary">
        <span>调度状态</span>
        <strong>{{ executor.acceptingTasks ? '可接受任务' : '已停止接收' }}</strong>
        <small>最近刷新：{{ refreshedAt }}</small>
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

    <section class="monitor-resource-grid">
      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>执行池占用</span><strong>{{ poolPercent }}%</strong></div>
          <el-tag type="success" effect="light">真实计数</el-tag>
        </div>
        <el-progress :percentage="poolPercent" :stroke-width="10" color="#2f80ed" />
        <div class="resource-meta">
          <span>活动 {{ executor.activeCount ?? 0 }} / 最大 {{ executor.maxPoolSize ?? 0 }}</span>
          <span>队列 {{ executor.queueSize ?? 0 }}</span>
        </div>
      </el-card>
      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>GPU 单槽</span><strong>{{ gpuSlot.state || 'unknown' }}</strong></div>
          <el-tag :type="gpuSlot.state === 'blocked' ? 'danger' : 'success'" effect="light">租约状态</el-tag>
        </div>
        <p class="table-caption">槽位 {{ gpuSlot.slotId || '未登记' }}；blocked 时不会因 TTL 过期自动释放。</p>
        <div class="resource-meta">
          <span>{{ gpuSlot.blockedReason || gpuSlot.leasedUntil || '无活动租约' }}</span>
        </div>
      </el-card>
      <el-card class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div><span>CPU / GPU 利用率</span><strong>未采集</strong></div>
          <el-tag type="info" effect="light">无采集源</el-tag>
        </div>
        <p class="table-caption">当前没有 CPU/GPU 采集器，页面不展示硬编码百分比；仅展示任务与槽位事实。</p>
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
        <template #header><div class="card-heading"><div><strong>全局观测</strong><span class="table-caption">仅管理员可见，不含 taskId/userId</span></div><el-tag type="warning">管理端</el-tag></div></template>
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
import { getExecutorStatus, getObservabilitySummary, getRunningTasks } from '../api/task'

const loading = ref(false)
const executor = ref({})
const gpuSlot = ref({})
const runningTasks = ref([])
const adminSummary = ref(null)
const refreshedAt = ref('')

const poolPercent = computed(() => {
  const max = Number(executor.value.maxPoolSize || 0)
  return max > 0 ? Math.min(100, Math.round((Number(executor.value.activeCount || 0) / max) * 100)) : 0
})

const overviewStats = computed(() => [
  { label: '运行中任务', value: executor.value.activeCount ?? 0, desc: '执行池 activityCount', badge: '实时', type: 'success' },
  { label: '等待队列', value: executor.value.queueSize ?? 0, desc: '尚未被线程接手的提交', badge: '队列', type: 'warning' },
  { label: 'GPU 槽位', value: gpuSlot.value.state || 'unknown', desc: '单槽租约，不显示虚构节点', badge: '租约', type: gpuSlot.value.state === 'blocked' ? 'danger' : 'success' },
  { label: '可接收任务', value: executor.value.acceptingTasks ? '是' : '否', desc: '执行池 shutdown/terminated 状态', badge: '调度', type: 'primary' }
])

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
    gpuSlot.value = executor.value.gpuSlot || {}
    runningTasks.value = runningResponse.data || []
    refreshedAt.value = new Date().toLocaleString()
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
