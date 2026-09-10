<template>
  <el-dialog title="实验清洗报告" :visible="visible" width="980px" append-to-body :before-close="close">
    <div v-loading="loading">
      <el-empty v-if="!loading && !report" description="暂无报告数据" />
      <template v-else-if="report">
        <el-alert :title="`${task.taskName || 'LDD 数据清洗任务'} 的实验报告`" :description="task.description || '汇总任务资源、启动参数快照、LDD 清洗结果与产物导出。'" type="info" :closable="false" show-icon />
        <el-alert class="demo-notice" title="当前平台展示范围：LDD 训练前数据清洗。报告指标用于工程联调与论文实验记录，不代表正式实验结论。" type="warning" :closable="false" show-icon />
        <el-divider content-position="left">任务概览</el-divider>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="任务状态"><el-tag size="mini" :type="statusType(task.status)">{{ statusText(task.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="数据集">{{ dataset.datasetName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="算法">{{ algorithm.algorithmName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="参数模板">{{ hyper.templateName || '未使用' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ task.startTime || '未开始' }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ task.endTime || '未结束' }}</el-descriptions-item>
          <el-descriptions-item label="执行耗时">{{ executionDuration }}</el-descriptions-item>
          <el-descriptions-item label="总样本数">{{ sampleStats.original_count || '-' }}</el-descriptions-item>
          <el-descriptions-item label="保留样本数">{{ sampleStats.cleaned_count || '-' }}</el-descriptions-item>
          <el-descriptions-item label="移除样本数">{{ sampleStats.removed_count || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">实验参数快照</el-divider>
        <el-table :data="snapshotRows" border size="small" empty-text="该任务没有启动快照">
          <el-table-column prop="attempt" label="次数" width="70" align="center" />
          <el-table-column prop="runId" label="运行标识" width="220" show-overflow-tooltip />
          <el-table-column label="快照摘要" width="130" show-overflow-tooltip>
            <template slot-scope="{ row }">{{ shortHash(row.snapshotHash) }}</template>
          </el-table-column>
          <el-table-column prop="launchedAt" label="启动时间" width="180" />
          <el-table-column prop="dataset" label="数据集" min-width="130" />
          <el-table-column prop="algorithm" label="算法" min-width="130" />
          <el-table-column prop="template" label="参数模板" min-width="130" />
          <el-table-column label="运行环境" width="100">
            <template slot-scope="{ row }">
              <el-popover placement="top" width="430" trigger="click">
                <pre class="json-preview">{{ pretty(row.runtime) }}</pre>
                <el-button slot="reference" type="text" size="mini">查看</el-button>
              </el-popover>
            </template>
          </el-table-column>
          <el-table-column label="冻结参数" width="110">
            <template slot-scope="{ row }"><el-popover placement="top" width="430" trigger="click"><pre class="json-preview">{{ pretty(row.config) }}</pre><el-button slot="reference" type="text" size="mini">查看</el-button></el-popover></template>
          </el-table-column>
        </el-table>

        <template v-if="runtimeAvailable">
          <el-divider content-position="left">实际算法运行环境</el-divider>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="适配器版本">{{ runtime.adapterVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Python">{{ runtime.pythonVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="解释器">{{ runtime.pythonExecutable || '-' }}</el-descriptions-item>
            <el-descriptions-item label="PyTorch">{{ runtime.torchVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="NumPy">{{ runtime.numpyVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="CUDA">{{ runtime.cudaVersion || (runtime.cudaAvailable ? '可用' : '不可用') }}</el-descriptions-item>
            <el-descriptions-item label="GPU 数量">{{ runtime.cudaDeviceCount === undefined ? '-' : runtime.cudaDeviceCount }}</el-descriptions-item>
            <el-descriptions-item label="GPU 型号" :span="2">{{ gpuNames }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <template v-if="isLdd">
          <el-divider content-position="left">LDD 清洗结果</el-divider>
          <el-table :data="metricRows" border size="small" empty-text="算法未返回隔离比例指标">
            <el-table-column prop="ratio" label="隔离比例" width="120"><template slot-scope="{ row }">{{ row.ratio }}%</template></el-table-column>
            <el-table-column prop="selectedCount" label="保留样本数" width="160" />
            <el-table-column prop="purity" label="干净子集纯度"><template slot-scope="{ row }"><el-progress :percentage="Number(row.purity) || 0" :stroke-width="10" /></template></el-table-column>
          </el-table>
          <p class="report-note">{{ metrics.metric_note || '纯度指标依赖受控实验中的已知中毒标签，仅用于工程和论文实验评估。' }}</p>
        </template>
        <el-alert v-if="task.status === 'failed'" class="failure-notice" title="本次 LDD 执行失败" :description="failureReason" type="error" :closable="false" show-icon />
        <el-alert v-if="task.status === 'canceled'" class="failure-notice" title="本次 LDD 执行已取消" :description="failureReason" type="warning" :closable="false" show-icon />

        <el-divider content-position="left">清洗数据集导出</el-divider>
        <el-alert :title="downloadInfo.available ? '可按 LDD 保留索引重建并下载 CIFAR-10 干净子集。' : '当前任务没有可下载的 LDD 干净子集。'" :description="downloadInfo.description || '导出文件包含样本图像、标签、索引和重建说明。'" :type="downloadInfo.available ? 'success' : 'warning'" :closable="false" show-icon />
        <div v-if="downloadInfo.available" class="download-actions">
          <el-button v-for="ratio in downloadInfo.isolationRatios" :key="ratio" size="small" type="primary" icon="el-icon-download" :loading="exportingRatio === ratio" :disabled="isExporting(ratio)" @click="downloadSubset(ratio)">{{ exportButtonText(ratio) }}</el-button>
        </div>
        <el-table v-if="exportTasks.length" class="export-status" :data="exportTasks" border size="small">
          <el-table-column prop="isolationRatio" label="隔离比例" width="110">
            <template slot-scope="{ row }">{{ row.isolationRatio }}%</template>
          </el-table-column>
          <el-table-column label="导出状态" width="120">
            <template slot-scope="{ row }"><el-tag size="mini" :type="exportStatusType(row.status)">{{ exportStatusText(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="进度" width="180">
            <template slot-scope="{ row }"><el-progress :percentage="Number(row.progress) || 0" :stroke-width="10" /></template>
          </el-table-column>
          <el-table-column prop="failureReason" label="失败原因" show-overflow-tooltip />
        </el-table>
      </template>
    </div>
    <div slot="footer">
      <el-button icon="el-icon-download" :disabled="!report" @click="downloadReportJson">导出报告 JSON</el-button>
      <el-button @click="close">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { downloadCleanedDatasetExport, getCleanedDatasetExport, getCleaningReport, submitCleanedDatasetExport } from '@/api/system/preDefense'
export default {
  props: { visible: { type: Boolean, default: false }, taskId: { type: Number, default: null }},
  data() { return { loading: false, report: null, exportingRatio: null, exportTasks: [], exportPollTimers: {} } },
  computed: {
    task() { return (this.report && this.report.taskInfo) || {} },
    dataset() { return (this.report && this.report.dataset) || {} },
    algorithm() { return (this.report && this.report.algorithm) || {} },
    hyper() { return (this.report && this.report.hyperParameter) || {} },
    result() { return (this.report && this.report.cleaningResult) || {} },
    metrics() { return this.result.metrics || {} },
    runtime() { return this.result.runtime || {} },
    runtimeAvailable() { return Object.keys(this.runtime).length > 0 },
    gpuNames() {
      const devices = this.runtime.cudaDevices
      return Array.isArray(devices) && devices.length ? devices.join(', ') : '未记录'
    },
    downloadInfo() { return (this.report && this.report.cleanDatasetDownload) || {} },
    isLdd() { return this.result.algorithm === 'vab://pre-defense/ldd' },
    snapshotRows() {
      return ((this.report.executionSnapshot || {}).attempts || []).map(item => {
        const request = item.request || {}
        return { attempt: item.attempt, runId: item.runId || '-', snapshotHash: item.snapshotHash || '-', runtime: item.runtime || {}, launchedAt: item.launchedAt, dataset: (request.dataset || {}).name || '-', algorithm: (request.algorithm || {}).name || '-', template: (request.hyperParameter || {}).name || '未使用', config: { algorithmDefault: (request.algorithm || {}).configJson || null, hyperParameter: (request.hyperParameter || {}).configJson || null, taskCustomParams: request.customParams || null }}
      })
    },
    metricRows() {
      const purity = this.metrics.clean_purity_percent_by_isolation_ratio || {}
      const selected = this.metrics.selected_sample_count_by_isolation_ratio || {}
      return Object.keys(selected).sort((a, b) => Number(a) - Number(b)).map(ratio => ({ ratio, selectedCount: selected[ratio] === null || selected[ratio] === undefined ? '-' : selected[ratio], purity: purity[ratio] === undefined ? 0 : purity[ratio] }))
    },
    sampleStats() {
      return this.result.sample_stats || {}
    },
    executionDuration() {
      const started = this.result.started_at || this.task.startTime
      const finished = this.result.finished_at || this.task.endTime
      if (!started || !finished) return '-'
      const seconds = Math.round((new Date(finished).getTime() - new Date(started).getTime()) / 1000)
      if (Number.isNaN(seconds) || seconds < 0) return '-'
      return Math.floor(seconds / 60) + ' 分 ' + (seconds % 60) + ' 秒'
    },
    failureReason() {
      return this.result.failure_reason || this.result.error_message || '未记录具体失败原因，请查看执行日志。'
    }
  },
  watch: { visible(value) { if (value && this.taskId) this.load() } },
  methods: {
    load() {
      this.loading = true
      getCleaningReport(this.taskId).then(response => {
        this.report = response.data || response
        this.exportTasks = this.report.cleanDatasetExports || []
        this.resumeExportPolling()
      }).catch(() => this.$message.error('获取实验报告失败')).finally(() => { this.loading = false })
    },
    downloadSubset(ratio) {
      this.exportingRatio = ratio
      submitCleanedDatasetExport(this.taskId, ratio)
        .then(response => this.waitForExport((response.data || response).exportId, ratio))
        .catch(error => this.$message.error(error.message || '清洗数据集导出失败'))
        .finally(() => { this.exportingRatio = null })
    },
    waitForExport(exportId, ratio, attempt = 0) {
      return getCleanedDatasetExport(exportId).then(response => {
        const exportTask = response.data || response
        this.upsertExportTask(exportTask)
        if (exportTask.status === 'success') {
          return downloadCleanedDatasetExport(exportId).then(blob => {
            this.saveBlob(blob, `task-${this.taskId}-ldd-clean-subset-${ratio}percent.zip`)
          })
        }
        if (exportTask.status === 'failed') {
          throw new Error(exportTask.failureReason || '清洗数据集导出失败')
        }
        if (attempt >= 180) {
          throw new Error('导出任务等待超时，请稍后在报告中查看状态')
        }
        return new Promise(resolve => setTimeout(resolve, 1000))
          .then(() => this.waitForExport(exportId, ratio, attempt + 1))
      })
    },
    upsertExportTask(exportTask) {
      const index = this.exportTasks.findIndex(item => item.exportId === exportTask.exportId)
      if (index < 0) this.exportTasks.push(exportTask)
      else this.$set(this.exportTasks, index, exportTask)
    },
    resumeExportPolling() {
      this.exportTasks
        .filter(item => item.status === 'pending' || item.status === 'running')
        .forEach(item => this.pollExistingExport(item.exportId))
    },
    pollExistingExport(exportId) {
      if (this.exportPollTimers[exportId]) return
      const poll = () => {
        getCleanedDatasetExport(exportId).then(response => {
          const exportTask = response.data || response
          this.upsertExportTask(exportTask)
          if (exportTask.status === 'pending' || exportTask.status === 'running') {
            const timer = setTimeout(poll, 1000)
            this.$set(this.exportPollTimers, exportId, timer)
          } else {
            this.$delete(this.exportPollTimers, exportId)
          }
        }).catch(() => {
          const timer = setTimeout(poll, 2000)
          this.$set(this.exportPollTimers, exportId, timer)
        })
      }
      poll()
    },
    stopExportPolling() {
      Object.keys(this.exportPollTimers).forEach(exportId => {
        clearTimeout(this.exportPollTimers[exportId])
      })
      this.exportPollTimers = {}
    },
    findExportTask(ratio) {
      return this.exportTasks.find(item => item.isolationRatio === ratio)
    },
    isExporting(ratio) {
      const task = this.findExportTask(ratio)
      return !!task && (task.status === 'pending' || task.status === 'running')
    },
    exportButtonText(ratio) {
      const task = this.findExportTask(ratio)
      if (task && task.status === 'success') return `再次下载 ${ratio}% 子集`
      if (task && (task.status === 'pending' || task.status === 'running')) return `导出中 ${task.progress || 0}%`
      return `导出 ${ratio}% 子集`
    },
    exportStatusType(status) { return { success: 'success', failed: 'danger', running: 'warning', pending: 'info' }[status] || 'info' },
    exportStatusText(status) { return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[status] || status || '-' },
    downloadReportJson() { this.saveBlob(new Blob([JSON.stringify(this.report, null, 2)], { type: 'application/json' }), `task-${this.taskId}-report.json`) },
    saveBlob(blob, filename) { const url = window.URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); window.URL.revokeObjectURL(url) },
    pretty(value) { return JSON.stringify(value, null, 2) },
    shortHash(value) { return value && value.length > 14 ? value.slice(0, 14) + '...' : value || '-' },
    statusType(status) { return { success: 'success', failed: 'danger', canceled: 'info', running: 'warning', pending: 'info' }[status] || 'info' },
    statusText(status) { return { success: '成功', failed: '失败', canceled: '已取消', running: '运行中', pending: '等待中' }[status] || status || '-' },
    close() {
      this.stopExportPolling()
      this.$emit('update:visible', false)
    }
  },
  beforeDestroy() {
    this.stopExportPolling()
  }
}
</script>

<style scoped>
.json-preview { max-height: 240px; margin: 0; overflow: auto; white-space: pre-wrap; }
.report-note { color: #909399; font-size: 12px; }
.download-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.demo-notice, .failure-notice { margin-top: 10px; }
</style>
