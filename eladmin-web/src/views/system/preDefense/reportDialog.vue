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
          <el-table-column prop="launchedAt" label="启动时间" width="180" />
          <el-table-column prop="dataset" label="数据集" min-width="130" />
          <el-table-column prop="algorithm" label="算法" min-width="130" />
          <el-table-column prop="template" label="参数模板" min-width="130" />
          <el-table-column label="冻结参数" width="110">
            <template slot-scope="{ row }"><el-popover placement="top" width="430" trigger="click"><pre class="json-preview">{{ pretty(row.config) }}</pre><el-button slot="reference" type="text" size="mini">查看</el-button></el-popover></template>
          </el-table-column>
        </el-table>

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

        <el-divider content-position="left">清洗数据集导出</el-divider>
        <el-alert :title="downloadInfo.available ? '可按 LDD 保留索引重建并下载 CIFAR-10 干净子集。' : '当前任务没有可下载的 LDD 干净子集。'" :description="downloadInfo.description || '导出文件包含样本图像、标签、索引和重建说明。'" :type="downloadInfo.available ? 'success' : 'warning'" :closable="false" show-icon />
        <div v-if="downloadInfo.available" class="download-actions">
          <el-button v-for="ratio in downloadInfo.isolationRatios" :key="ratio" size="small" type="primary" icon="el-icon-download" :loading="exportingRatio === ratio" @click="downloadSubset(ratio)">下载 {{ ratio }}% 子集</el-button>
        </div>
      </template>
    </div>
    <div slot="footer">
      <el-button icon="el-icon-download" :disabled="!report" @click="downloadReportJson">导出报告 JSON</el-button>
      <el-button @click="close">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { downloadCleanedDataset, getCleaningReport } from '@/api/system/preDefense'
export default {
  props: { visible: { type: Boolean, default: false }, taskId: { type: Number, default: null } },
  data() { return { loading: false, report: null, exportingRatio: null } },
  computed: {
    task() { return (this.report && this.report.taskInfo) || {} },
    dataset() { return (this.report && this.report.dataset) || {} },
    algorithm() { return (this.report && this.report.algorithm) || {} },
    hyper() { return (this.report && this.report.hyperParameter) || {} },
    result() { return (this.report && this.report.cleaningResult) || {} },
    metrics() { return this.result.metrics || {} },
    downloadInfo() { return (this.report && this.report.cleanDatasetDownload) || {} },
    isLdd() { return this.result.algorithm === 'vab://pre-defense/ldd' },
    snapshotRows() {
      return ((this.report.executionSnapshot || {}).attempts || []).map(item => {
        const request = item.request || {}
        return { attempt: item.attempt, runId: item.runId || '-', launchedAt: item.launchedAt, dataset: (request.dataset || {}).name || '-', algorithm: (request.algorithm || {}).name || '-', template: (request.hyperParameter || {}).name || '未使用', config: { algorithmDefault: (request.algorithm || {}).configJson || null, hyperParameter: (request.hyperParameter || {}).configJson || null, taskCustomParams: request.customParams || null } }
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
    load() { this.loading = true; getCleaningReport(this.taskId).then(response => { this.report = response.data || response }).catch(() => this.$message.error('获取实验报告失败')).finally(() => { this.loading = false }) },
    downloadSubset(ratio) { this.exportingRatio = ratio; downloadCleanedDataset(this.taskId, ratio).then(blob => this.saveBlob(blob, `task-${this.taskId}-ldd-clean-subset-${ratio}percent.zip`)).catch(() => this.$message.error('清洗数据集导出失败')).finally(() => { this.exportingRatio = null }) },
    downloadReportJson() { this.saveBlob(new Blob([JSON.stringify(this.report, null, 2)], { type: 'application/json' }), `task-${this.taskId}-report.json`) },
    saveBlob(blob, filename) { const url = window.URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); window.URL.revokeObjectURL(url) },
    pretty(value) { return JSON.stringify(value, null, 2) },
    statusType(status) { return { success: 'success', failed: 'danger', running: 'warning', pending: 'info' }[status] || 'info' },
    statusText(status) { return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[status] || status || '-' },
    close() { this.$emit('update:visible', false) }
  }
}
</script>

<style scoped>
.json-preview { max-height: 240px; margin: 0; overflow: auto; white-space: pre-wrap; }
.report-note { color: #909399; font-size: 12px; }
.download-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.demo-notice, .failure-notice { margin-top: 10px; }
</style>
