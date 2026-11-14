<template>
  <div class="page statistics-page" v-loading="loading">
    <div class="section-heading">
      <div><span class="eyebrow">TASK OBSERVABILITY</span><h2>任务统计</h2><p class="page-subtitle">数据来自当前账号的任务库，仅统计非演示任务；演示数据不计入任何指标。</p></div>
      <div class="heading-actions"><el-tag type="info">{{ scopeLabel }}</el-tag></div>
    </div>
    <div class="stat-grid">
      <el-card v-for="item in headlineStats" :key="item.label" shadow="never" class="stat-card"><span class="muted">{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small></el-card>
    </div>
    <section class="content-grid statistics-main-grid">
      <el-card shadow="never">
        <template #header><div class="card-heading"><div><strong>状态分布</strong><span class="table-caption">来自 task-progress/statistics 的实时计数</span></div></div></template>
        <el-table :data="statusRows" border size="small">
          <el-table-column prop="status" label="状态" width="140" />
          <el-table-column prop="count" label="任务数" width="120" />
          <el-table-column prop="meaning" label="含义" />
        </el-table>
      </el-card>
      <el-card shadow="never" v-if="adminSummary">
        <template #header><div class="card-heading"><div><strong>全局运行观测</strong><span class="table-caption">仅管理员可见，不含任务 ID 和用户 ID</span></div><el-tag type="warning">管理端</el-tag></div></template>
        <div class="definition-list">
          <div><span>GPU 槽位</span><p>{{ adminSummary.gpuSlot?.state || 'unknown' }} · {{ adminSummary.gpuSlot?.slotId || '未登记' }}</p></div>
          <div><span>执行池</span><p>活动 {{ adminSummary.executor?.active ?? 0 }} / 排队 {{ adminSummary.executor?.queued ?? 0 }} / 完成 {{ adminSummary.executor?.completed ?? 0 }}</p></div>
          <div><span>进度陈旧</span><p>{{ adminSummary.staleRunningTasks ?? 0 }} 个 running 任务超过阈值未更新</p></div>
          <div><span>缓存</span><p>命中 {{ adminSummary.cache?.hit ?? 0 }} / 未命中 {{ adminSummary.cache?.miss ?? 0 }} / 降级 {{ adminSummary.cache?.degraded ?? 0 }}</p></div>
        </div>
      </el-card>
      <el-card shadow="never">
        <template #header><strong>指标口径与边界</strong></template>
        <div class="definition-list">
          <div><span>统计范围</span><p>{{ scopeLabel }}；`system` 归属和历史未归属任务不在普通用户统计内。</p></div>
          <div><span>演示数据</span><p>`data_origin=demo` 的任务被排除，不能作为真实实验指标展示。</p></div>
          <div><span>未采集指标</span><p>CPU/GPU 利用率、攻击成功率 ASR、样本保留率当前没有真实采集源，页面不展示估算值。</p></div>
          <div><span>管理端</span><p>全局任务数、执行池、GPU 槽位和缓存命中由管理员观测接口提供；普通用户不读取全局机器指标。</p></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getObservabilitySummary, getTaskStatistics } from '../api/task'

const loading = ref(false)
const stats = ref({})
const adminSummary = ref(null)
const statusRows = ref([])

const scopeLabel = computed(() => stats.value.scope === 'all-users' ? '全部用户（仅管理员）' : '当前账号')

const headlineStats = computed(() => [
  { label: '任务总数', value: stats.value.totalCount ?? 0, note: '不含演示任务' },
  { label: '运行中', value: stats.value.runningCount ?? 0, note: '当前执行队列' },
  { label: '成功', value: stats.value.successCount ?? 0, note: '已完成并写入结果' },
  { label: '失败', value: stats.value.failedCount ?? 0, note: '含可重试失败' }
])

const meanings = {
  pending: '已创建，等待调度或执行',
  running: '执行器已接手，持续写入进度',
  success: '终态成功，结果和产物已落库',
  failed: '终态失败，可查看 error_code 后重试',
  canceled: '用户取消，迟到回调不覆盖'
}

async function loadStatistics() {
  loading.value = true
  try {
    const response = await getTaskStatistics()
    stats.value = response.data || {}
    statusRows.value = Object.entries(stats.value.statusStats || {}).map(([status, count]) => ({
      status, count, meaning: meanings[status] || '未定义状态'
    }))
    try {
      const adminResponse = await getObservabilitySummary()
      adminSummary.value = adminResponse.data || null
    } catch (error) {
      // Normal users receive 403 by design; the admin card stays hidden.
      adminSummary.value = null
    }
  } catch (error) {
    statusRows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadStatistics)
</script>
