<template>
  <div class="page task-page">
    <div class="section-heading">
      <div>
        <span class="eyebrow">TASK ORCHESTRATION</span>
        <h2>安全防御任务</h2>
        <p class="page-subtitle">从任务创建、执行监控到报告导出，形成覆盖三大防御阶段的完整实验闭环。</p>
      </div>
      <div class="heading-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建防御任务</el-button>
      </div>
    </div>

    <el-alert v-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon class="page-alert" />
    <div class="stat-grid task-stat-grid">
      <el-card v-for="item in statItems" :key="item.key" shadow="never" class="stat-card">
        <span class="muted">{{ item.label }}</span><strong>{{ statistics[item.key] ?? 0 }}</strong><small>{{ item.note }}</small>
      </el-card>
    </div>
    <section class="content-grid task-overview-grid">
      <el-card shadow="never">
        <template #header><div class="card-heading"><strong>执行池状态</strong><el-tag :type="executorTagType">{{ executorLabel }}</el-tag></div></template>
        <div class="executor-metrics">
          <div><span>活跃线程</span><strong>{{ executor.activeCount ?? 0 }}</strong></div>
          <div><span>队列占用</span><strong>{{ executor.queueSize ?? 0 }} / {{ executor.queueCapacity ?? '-' }}</strong></div>
          <div><span>已完成</span><strong>{{ executor.completedTaskCount ?? 0 }}</strong></div>
          <div><span>运行配额</span><strong>{{ executor.runningTaskCount ?? 0 }} / {{ executor.maxRunningTasks ?? '-' }}</strong></div>
          <div><span>GPU 槽位</span><strong>{{ gpuSlotLabel }}</strong></div>
        </div>
        <el-alert v-if="gpuSlot.state === 'blocked'" type="warning" :closable="false" show-icon
          :title="`GPU 槽位已隔离：${gpuSlot.blockedReason || '原因未记录'}`"
          :description="`隔离开始：${formatDate(gpuSlot.blockedAt)}；最近探测：${formatDate(gpuSlot.lastProbeAt)}。${gpuSlot.guidance || 'TTL 过期不会自动解除。'}`" />
      </el-card>
      <el-card shadow="never">
        <template #header><strong>当前接入范围</strong></template>
        <div class="scope-row"><span class="scope-icon">SM</span><div><strong>统一安全防御任务</strong><p>平台支持训练前数据清洗、训练中过程防护和训练后模型审计的统一任务管理。</p></div></div>
        <el-progress :percentage="completionProgress" :format="formatProgress" :stroke-width="8" />
      </el-card>
    </section>

    <el-card shadow="never" class="task-table-card">
      <template #header><div class="card-heading"><div><strong>任务列表</strong><span class="table-caption">任务参数、运行日志和清洗索引均按任务隔离</span></div><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px" @change="loadData"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></div></template>
      <el-table v-loading="loading" :data="tasks" row-key="taskId" empty-text="暂无实验任务">
        <el-table-column prop="taskName" label="任务名称" min-width="190" show-overflow-tooltip />
        <el-table-column label="算法" width="150"><template #default="{ row }"><el-tag effect="plain">{{ row.algorithmName || '-' }}</el-tag></template></el-table-column>
        <el-table-column label="数据集" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.datasetName || `数据集 #${row.datasetId ?? '-'}` }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="进度" width="150"><template #default="{ row }"><el-progress :percentage="safeProgress(row.progress)" :status="row.status === 'failed' ? 'exception' : undefined" /></template></el-table-column>
        <el-table-column label="操作" fixed="right" width="250"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">详情</el-button><el-button v-if="row.status === 'pending'" link type="success" @click="start(row)">启动</el-button><el-button v-if="row.status === 'failed'" link type="warning" @click="retry(row)">重试</el-button><el-button v-if="row.status === 'running'" link type="danger" @click="cancel(row)">取消</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="task-pagination" background layout="total, prev, pager, next, sizes" :total="totalTasks" v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" @change="loadData" />
    </el-card>

    <el-drawer v-model="createVisible" title="创建安全防御任务" size="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="任务名称" prop="taskName"><el-input v-model="createForm.taskName" placeholder="例如：CIFAR-10 安全防御实验" /></el-form-item>
        <el-form-item label="任务描述"><el-input v-model="createForm.description" type="textarea" :rows="3" placeholder="说明数据集、实验用途或论文章节" /></el-form-item>
        <el-form-item label="数据集" prop="datasetId"><el-select v-model="createForm.datasetId" class="full-width" placeholder="请选择数据集" :loading="resourceLoading"><el-option v-for="item in datasets" :key="item.datasetId || item.id" :label="datasetName(item)" :value="item.datasetId || item.id" /></el-select></el-form-item>
        <el-form-item label="算法" prop="algorithmId"><el-select v-model="createForm.algorithmId" class="full-width" placeholder="请选择算法" :loading="resourceLoading"><el-option v-for="item in algorithms" :key="item.algorithmId || item.id" :label="item.algorithmName || item.name || 'LDD'" :value="item.algorithmId || item.id" /></el-select></el-form-item>
        <el-alert title="实验基线配置" description="批次大小 128，训练轮数 5。参数随任务创建写入快照。" type="info" :closable="false" show-icon />
        <el-form-item label="自定义参数 JSON"><el-input v-model="createForm.customParams" type="textarea" :rows="7" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" @click="submitCreate">创建并启动</el-button></template>
    </el-drawer>

    <el-drawer v-model="detailVisible" title="任务详情与实验报告" size="760px" @close="stopDetailPolling">
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <el-empty v-else-if="!selectedTask" description="任务详情不可用" />
      <template v-else>
        <div class="detail-status"><div><span class="muted">任务状态</span><el-tag :type="statusType(selectedTask.status)">{{ statusLabel(selectedTask.status) }}</el-tag></div><div><span class="muted">当前进度</span><strong>{{ detailProgressValue }}%</strong></div></div>
        <div v-if="selectedTask.status === 'running' || detailProgress" class="runtime-progress-panel">
          <div class="runtime-progress-header">
            <strong>{{ detailProgress?.phase_label || '任务执行中' }}</strong>
            <el-tag size="small" :type="detailHeartbeatStale ? 'warning' : 'success'">
              {{ detailHeartbeatStale ? '较久未更新' : detailProgress?.heartbeat_at ? '进度正常更新' : '等待进度' }}
            </el-tag>
          </div>
          <el-progress :percentage="detailProgressValue" :status="selectedTask.status === 'failed' ? 'exception' : undefined" />
          <div class="runtime-progress-message">{{ detailProgress?.message || '正在等待训练进程上报进度。' }}</div>
          <div class="runtime-progress-meta">
            <span v-if="detailProgress?.epoch">当前轮次：第 {{ detailProgress.epoch }} / {{ detailProgress.total_epochs || '-' }} 轮</span>
            <span v-if="detailProgress?.heartbeat_at">最近更新：{{ formatDate(detailProgress.heartbeat_at) }}</span>
            <span v-else>尚未收到训练阶段快照</span>
          </div>
          <div v-if="detailHeartbeatStale" class="runtime-progress-hint">最近一次进度更新时间较久，任务状态仍以后台执行结果为准。</div>
        </div>
        <el-descriptions :column="2" border size="small"><el-descriptions-item label="任务名称">{{ selectedTask.taskName || '-' }}</el-descriptions-item><el-descriptions-item label="算法">{{ selectedTask.algorithmName || '-' }}</el-descriptions-item><el-descriptions-item label="数据集">{{ selectedTask.datasetName || selectedTask.datasetId || '-' }}</el-descriptions-item><el-descriptions-item label="当前尝试">{{ selectedTask.currentAttempt ?? '-' }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ formatDate(selectedTask.createTime) }}</el-descriptions-item><el-descriptions-item label="结束时间">{{ formatDate(selectedTask.endTime) }}</el-descriptions-item></el-descriptions>
        <div class="drawer-actions"><el-button v-if="selectedTask.status === 'pending'" type="success" @click="start(selectedTask)">启动任务</el-button><el-button v-if="selectedTask.status === 'failed'" type="warning" :icon="Refresh" @click="retry(selectedTask)">重试任务</el-button><el-button v-if="selectedTask.status === 'running'" type="danger" @click="cancel(selectedTask)">取消任务</el-button><el-button type="primary" :loading="reportLoading" @click="loadReport">刷新报告</el-button></div>
        <el-tabs v-model="detailTab">
          <el-tab-pane label="实验报告" name="report">
            <el-alert v-if="!report" title="尚未读取报告" description="任务成功后可查看清洗指标、参数快照和索引导出。" type="info" :closable="false" />
            <template v-else>
              <el-alert v-if="report.cleaningResult?.failure_reason" title="执行失败" :description="failureDescription(report.cleaningResult)" type="error" :closable="false" />
              <el-divider content-position="left">样本统计</el-divider>
              <div class="report-metrics"><div><span>原始样本</span><strong>{{ sampleStats.original_count ?? '-' }}</strong></div><div><span>保留样本</span><strong>{{ sampleStats.cleaned_count ?? '-' }}</strong></div><div><span>移除样本</span><strong>{{ sampleStats.removed_count ?? '-' }}</strong></div></div>
              <el-divider content-position="left">参数快照</el-divider>
              <el-table :data="snapshotRows" border size="small" empty-text="暂无启动快照"><el-table-column prop="attempt" label="次数" width="65" /><el-table-column prop="runId" label="运行标识" min-width="150" show-overflow-tooltip /><el-table-column prop="snapshotHash" label="快照摘要" min-width="120" show-overflow-tooltip /><el-table-column prop="launchedAt" label="启动时间" min-width="150" /></el-table>
              <el-divider content-position="left">清洗数据集导出</el-divider>
              <div class="download-actions"><el-button v-for="ratio in isolationRatios" :key="ratio" size="small" type="primary" :loading="exportingRatio === ratio" :disabled="isExporting(ratio)" @click="exportSubset(ratio)">{{ exportButtonText(ratio) }}</el-button><span v-if="!isolationRatios.length" class="muted">当前报告没有可用的保留比例。</span></div>
              <el-table v-if="exportTasks.length" class="export-status" :data="exportTasks" border size="small"><el-table-column prop="isolationRatio" label="隔离比例" width="100" /><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="exportStatusType(row.status)">{{ exportStatusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column label="进度" width="150"><template #default="{ row }"><el-progress :percentage="Number(row.progress) || 0" /></template></el-table-column><el-table-column prop="failureReason" label="说明" show-overflow-tooltip /></el-table>
              <el-button class="report-download" :icon="Download" @click="downloadReportJson">导出报告 JSON</el-button>
            </template>
          </el-tab-pane>
          <el-tab-pane label="执行日志" name="logs"><div class="log-toolbar"><span class="muted">日志按文件偏移增量读取，运行中的任务每 3 秒刷新。</span><el-button size="small" :icon="Refresh" @click="loadLogs">刷新日志</el-button></div><el-empty v-if="!logs.length" description="暂无可读取的执行日志" /><div v-for="item in logs" :key="item.path || item.name" class="log-block"><div class="log-title">{{ item.name || item.path || '执行日志' }}</div><pre>{{ item.content || item.text || '' }}</pre></div></el-tab-pane>
          <el-tab-pane label="操作记录" name="audits">
            <div class="log-toolbar">
              <span class="muted">记录任务状态变更、重试和清洗子集导出动作。</span>
              <el-button size="small" :icon="Refresh" @click="loadAudits">刷新记录</el-button>
            </div>
            <el-table :data="audits" border size="small" empty-text="暂无操作记录">
              <el-table-column label="时间" min-width="155">
                <template #default="{ row }">{{ formatDate(row.createdTime) }}</template>
              </el-table-column>
              <el-table-column label="动作" width="130">
                <template #default="{ row }"><el-tag effect="plain">{{ auditActionLabel(row.action) }}</el-tag></template>
              </el-table-column>
              <el-table-column label="状态变化" width="145">
                <template #default="{ row }">{{ row.beforeStatus || '-' }} → {{ row.afterStatus || '-' }}</template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="110" />
              <el-table-column prop="resultMessage" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Refresh } from '@element-plus/icons-vue'
import { getAvailableDatasets } from '../api/dataset'
import { getAvailableAlgorithms } from '../api/algorithm'
import { createTask, getExecutionLogs, submitCleanedDatasetExport, getCleanedDatasetExport, downloadCleanedDatasetExport, startTask } from '../api/preDefense'
import { cancelTask, getCleaningReport, getExecutorStatus, getTaskStatistics, getTaskDetail, getTaskPage, retryTask } from '../api/task'
import { getTaskAudits } from '../api/audit'

const loading = ref(false); const errorMessage = ref(''); const tasks = ref([]); const statistics = ref({}); const executor = ref({})
const datasets = ref([]); const algorithms = ref([]); const resourceLoading = ref(false); const statusFilter = ref('')
const currentPage = ref(1); const pageSize = ref(20); const totalTasks = ref(0)
const createVisible = ref(false); const creating = ref(false); const createFormRef = ref(); const detailVisible = ref(false); const detailLoading = ref(false); const reportLoading = ref(false); const selectedTask = ref(null); const report = ref(null); const detailTab = ref('report'); const logs = ref([]); const logOffsets = ref({}); const audits = ref([]); const exportTasks = ref([]); const exportingRatio = ref(null)
let timer
let detailTimer
const createForm = reactive({ taskName: '', description: '', datasetId: null, algorithmId: null, customParams: JSON.stringify({ batch_size: 128, epochs: 5 }, null, 2) })
const createRules = { taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }], datasetId: [{ required: true, message: '请选择数据集', trigger: 'change' }], algorithmId: [{ required: true, message: '请选择防御算法', trigger: 'change' }] }
const statusOptions = [{ label: '等待中', value: 'pending' }, { label: '运行中', value: 'running' }, { label: '成功', value: 'success' }, { label: '失败', value: 'failed' }, { label: '已取消', value: 'canceled' }]
const statItems = [{ key: 'totalCount', label: '全部任务', note: '当前安全防御任务总量' }, { key: 'runningCount', label: '运行中', note: '正在占用执行资源' }, { key: 'successCount', label: '已完成', note: '已产生实验结果' }, { key: 'failedCount', label: '失败', note: '支持原参数重试' }]
const completionProgress = computed(() => { const total = Number(statistics.value.totalCount || 0); return total ? Math.round((Number(statistics.value.successCount || 0) / total) * 100) : 0 })
const executorLabel = computed(() => executor.value.saturated ? '资源紧张' : executor.value.acceptingTasks === false ? '暂停接收' : '运行正常'); const executorTagType = computed(() => executor.value.saturated ? 'warning' : executor.value.acceptingTasks === false ? 'danger' : 'success')
const gpuSlot = computed(() => executor.value.gpuSlot || {}); const gpuSlotLabel = computed(() => ({ blocked: '隔离中', leased: '占用中', idle: '空闲', unknown: '未知' })[gpuSlot.value.state] || '未知')
const sampleStats = computed(() => report.value?.cleaningResult?.sample_stats || {}); const snapshotRows = computed(() => report.value?.executionSnapshot?.attempts || []); const isolationRatios = computed(() => report.value?.cleanDatasetDownload?.isolationRatios || [])
const detailProgress = ref(null)
const detailProgressValue = computed(() => safeProgress(detailProgress.value?.progress ?? selectedTask.value?.progress))
const detailHeartbeatStale = computed(() => {
  const value = detailProgress.value?.heartbeat_at
  if (!value || selectedTask.value?.status !== 'running') return false
  const timestamp = new Date(value).getTime()
  return Number.isFinite(timestamp) && Date.now() - timestamp > 60000
})
function unwrap(data) { return data?.data ?? data ?? {} }; function arrayData(response) { const data = unwrap(response); return Array.isArray(data) ? data : data.records || data.content || [] }; function safeProgress(value) { const progress = Number(value); return Number.isFinite(progress) ? Math.max(0, Math.min(100, progress)) : 0 }; function formatProgress(value) { return `${value}%` }; function statusLabel(status) { return statusOptions.find(item => item.value === status)?.label || status || '未知' }; function statusType(status) { return { pending: 'info', running: '', success: 'success', failed: 'danger', canceled: 'warning' }[status] || 'info' }; function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }; function datasetName(item) { return item.datasetName || item.name || `数据集 #${item.datasetId || item.id}` }; function auditActionLabel(action) { return { CREATE: '创建任务', UPDATE: '编辑任务', START: '启动任务', RETRY: '重试任务', CANCEL: '取消任务', DELETE: '删除任务', EXPORT_SUBMIT: '提交子集导出', EXPORT_RETRY: '重试子集导出' }[action] || action || '-' }
async function loadData() { loading.value = true; errorMessage.value = ''; try { const params = { page: currentPage.value, size: pageSize.value, ...(statusFilter.value ? { status: statusFilter.value } : {}) }; const [a, b, c] = await Promise.all([getTaskPage(params), getTaskStatistics(), getExecutorStatus()]); const page = unwrap(a); tasks.value = page.records || []; totalTasks.value = page.total || 0; statistics.value = unwrap(b); executor.value = unwrap(c) } catch (error) { tasks.value = []; totalTasks.value = 0; statistics.value = {}; executor.value = {}; errorMessage.value = error.response?.data?.message || '任务读取失败，请检查服务连接后重试。' } finally { loading.value = false } }
async function loadResources() { resourceLoading.value = true; try { const [a, b] = await Promise.all([getAvailableDatasets(), getAvailableAlgorithms()]); datasets.value = arrayData(a); algorithms.value = arrayData(b).filter(item => item.implementationPath === 'vab://pre-defense/ldd') } catch { datasets.value = []; algorithms.value = []; ElMessage.error('资源列表读取失败') } finally { resourceLoading.value = false } }
async function openCreate() { createVisible.value = true; if (!datasets.value.length || !algorithms.value.length) await loadResources() }
async function submitCreate() { const valid = await createFormRef.value?.validate().catch(() => false); if (!valid) return; let customParams; try { customParams = JSON.stringify(JSON.parse(createForm.customParams || '{}')) } catch { ElMessage.error('自定义参数必须是合法 JSON'); return }; creating.value = true; try { const createKey = idempotencyKey(); const startKey = idempotencyKey(); const created = unwrap(await createTask({ ...createForm, customParams, status: 'pending' }, createKey)); const taskId = created.taskId || created.id; if (taskId) await startTask(taskId, startKey); ElMessage.success('安全防御任务已创建并提交执行'); createVisible.value = false; await loadData() } catch (error) { ElMessage.error(error.response?.data?.message || '任务创建失败') } finally { creating.value = false } }
async function openDetail(row) { detailVisible.value = true; detailLoading.value = true; selectedTask.value = null; report.value = null; logs.value = []; logOffsets.value = {}; detailProgress.value = null; audits.value = []; exportTasks.value = []; detailTab.value = 'report'; try { selectedTask.value = unwrap(await getTaskDetail(row.taskId)); await Promise.all([loadReport(), loadAudits(), loadLogs()]); startDetailPolling() } catch (error) { ElMessage.error(error.response?.data?.message || '任务详情读取失败') } finally { detailLoading.value = false } }
async function loadReport() { if (!selectedTask.value?.taskId) return; reportLoading.value = true; try { report.value = unwrap(await getCleaningReport(selectedTask.value.taskId)); exportTasks.value = report.value?.cleanDatasetExports || [] } catch (error) { report.value = null; ElMessage.error(error.response?.data?.message || '报告读取失败') } finally { reportLoading.value = false } }
async function loadLogs() { if (!selectedTask.value?.taskId) return; try { const result = unwrap(await getExecutionLogs(selectedTask.value.taskId, logOffsets.value)); if (result.progress) detailProgress.value = result.progress; for (const item of result.logs || []) { const key = item.relativePath || item.name || item.path; const index = logs.value.findIndex(log => (log.relativePath || log.name || log.path) === key); if (index < 0) logs.value.push({ ...item }); else if (item.reset || Number(item.startOffset) === 0) logs.value[index] = { ...item }; else logs.value[index] = { ...logs.value[index], ...item, content: `${logs.value[index].content || ''}${item.content || ''}` }; if (item.offset !== undefined) logOffsets.value[key] = Number(item.offset) } } catch (error) { ElMessage.error(error.response?.data?.message || '日志读取失败') } }
async function loadAudits() { if (!selectedTask.value?.taskId) return; try { audits.value = arrayData(await getTaskAudits(selectedTask.value.taskId)) } catch (error) { audits.value = []; ElMessage.error(error.response?.data?.message || '审计记录读取失败') } }
async function start(row) { try { await startTask(row.taskId, idempotencyKey()); ElMessage.success('任务已提交执行'); await loadData(); if (selectedTask.value?.taskId === row.taskId) { selectedTask.value = { ...selectedTask.value, status: 'running', progress: 0 }; startDetailPolling() } } catch (error) { ElMessage.error(error.response?.data?.message || '任务启动失败') } }
async function retry(row) { try { await ElMessageBox.confirm(`确认重试任务“${row.taskName || row.taskId}”吗？`, '重试任务', { type: 'warning' }); await retryTask(row.taskId, idempotencyKey()); ElMessage.success('任务已重新提交'); await loadData(); if (selectedTask.value?.taskId === row.taskId) { selectedTask.value = { ...selectedTask.value, status: 'running', progress: 0 }; detailProgress.value = null; logOffsets.value = {}; logs.value = []; startDetailPolling() } } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '任务重试失败') } }
async function cancel(row) { try { await ElMessageBox.confirm(`确认取消任务“${row.taskName || row.taskId}”吗？`, '取消任务', { type: 'warning' }); await cancelTask(row.taskId); ElMessage.success('任务已取消'); stopDetailPolling(); await loadData(); if (selectedTask.value?.taskId === row.taskId) selectedTask.value = { ...selectedTask.value, status: 'canceled', progress: 0 } } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '任务取消失败') } }
async function refreshDetail() { if (!detailVisible.value || !selectedTask.value?.taskId) return; try { const latest = unwrap(await getTaskDetail(selectedTask.value.taskId)); selectedTask.value = { ...selectedTask.value, ...latest }; await loadLogs(); if (latest.status !== 'running') stopDetailPolling() } catch { /* The next poll retries while the drawer remains open. */ } }
function startDetailPolling() { stopDetailPolling(); if (selectedTask.value?.status === 'running') detailTimer = window.setInterval(refreshDetail, 3000) }
function stopDetailPolling() { if (detailTimer) { window.clearInterval(detailTimer); detailTimer = undefined } }
function findExport(ratio) { return exportTasks.value.find(item => Number(item.isolationRatio) === Number(ratio)) }; function isExporting(ratio) { const item = findExport(ratio); return item && ['pending', 'running'].includes(item.status) }; function exportButtonText(ratio) { const item = findExport(ratio); return item?.status === 'success' ? `再次下载 ${ratio}% 子集` : item ? `导出中 ${item.progress || 0}%` : `导出 ${ratio}% 子集` }; function exportStatusType(status) { return { success: 'success', failed: 'danger', running: 'warning', pending: 'info' }[status] || 'info' }; function exportStatusLabel(status) { return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[status] || status || '-' }
function upsertExport(item) { const index = exportTasks.value.findIndex(row => row.exportId === item.exportId); if (index < 0) exportTasks.value.push(item); else exportTasks.value[index] = item }
async function exportSubset(ratio) { exportingRatio.value = ratio; try { const created = unwrap(await submitCleanedDatasetExport(selectedTask.value.taskId, ratio)); const exportId = created.exportId; for (let attempt = 0; attempt < 180; attempt += 1) { const item = unwrap(await getCleanedDatasetExport(exportId)); upsertExport(item); if (item.status === 'success') { const blob = await downloadCleanedDatasetExport(exportId); saveBlob(blob, `task-${selectedTask.value.taskId}-ldd-${ratio}percent.zip`); return }; if (item.status === 'failed') throw new Error(item.failureReason || '清洗数据集导出失败'); await new Promise(resolve => window.setTimeout(resolve, 1000)) } throw new Error('导出等待超时，请稍后刷新报告查看状态') } catch (error) { ElMessage.error(error.response?.data?.message || error.message || '清洗数据集导出失败') } finally { exportingRatio.value = null } }
function idempotencyKey() { return (window.crypto?.randomUUID?.() || `req-${Date.now()}-${Math.random().toString(16).slice(2)}`) }; function failureDescription(result) { const code = result?.error_code ? `（${result.error_code}）` : ''; return `${result?.failure_reason || '任务执行失败'}${code}` }
function downloadReportJson() { saveBlob(new Blob([JSON.stringify(report.value, null, 2)], { type: 'application/json' }), `task-${selectedTask.value.taskId}-report.json`) }; function saveBlob(responseOrBlob, filename) { const blob = responseOrBlob?.data instanceof Blob ? responseOrBlob.data : responseOrBlob; const url = window.URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; document.body.appendChild(anchor); anchor.click(); anchor.remove(); window.setTimeout(() => window.URL.revokeObjectURL(url), 1000) }
onMounted(() => { loadData(); timer = window.setInterval(loadData, 15000) }); onUnmounted(() => { window.clearInterval(timer); stopDetailPolling() })
</script>

<style scoped>
.runtime-progress-panel {
  margin: 14px 0 18px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
}

.runtime-progress-header,
.runtime-progress-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.runtime-progress-header {
  justify-content: space-between;
  color: var(--el-text-color-primary);
}

.runtime-progress-message {
  margin-top: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.runtime-progress-meta {
  flex-wrap: wrap;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.runtime-progress-hint {
  margin-top: 8px;
  color: var(--el-color-warning);
  font-size: 12px;
}
</style>
