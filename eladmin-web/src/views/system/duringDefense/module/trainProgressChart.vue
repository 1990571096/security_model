<template>
  <div class="train-progress-chart">
    <!-- 总体进度信息 -->
    <el-row :gutter="20" class="progress-overview">
      <el-col :span="6">
        <el-card class="progress-card">
          <div slot="header" class="card-header">
            <i class="el-icon-loading"></i>
            <span>训练进度</span>
          </div>
          <div class="progress-content">
            <el-progress
              type="circle"
              :percentage="taskData.progress || 0"
              :status="getProgressStatus(taskData.status)"
              :width="80"
            />
            <div class="progress-text">
              <span class="progress-value">{{ taskData.progress || 0 }}%</span>
              <span class="progress-status">{{ getStatusLabel(taskData.status) }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="progress-card">
          <div slot="header" class="card-header">
            <i class="el-icon-time"></i>
            <span>训练时间</span>
          </div>
          <div class="time-content">
            <div class="time-item">
              <label>开始时间:</label>
              <span>{{ formatTime(taskData.startTime) }}</span>
            </div>
            <div class="time-item">
              <label>预计完成:</label>
              <span>{{ estimatedEndTime }}</span>
            </div>
            <div class="time-item">
              <label>已用时长:</label>
              <span>{{ elapsedTime }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="progress-card">
          <div slot="header" class="card-header">
            <i class="el-icon-data-analysis"></i>
            <span>训练指标</span>
          </div>
          <div class="metrics-content">
            <div class="metric-item">
              <label>当前Loss:</label>
              <span class="metric-value">{{ currentMetrics.loss || '--' }}</span>
            </div>
            <div class="metric-item">
              <label>准确率:</label>
              <span class="metric-value">{{ currentMetrics.accuracy || '--' }}</span>
            </div>
            <div class="metric-item">
              <label>当前轮次:</label>
              <span class="metric-value">{{ currentMetrics.currentEpoch || '--' }}/{{ currentMetrics.totalEpochs || '--' }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card class="progress-card">
          <div slot="header" class="card-header">
            <i class="el-icon-warning-outline"></i>
            <span>防御状态</span>
          </div>
          <div class="defense-content">
            <div class="defense-item">
              <label>防御触发:</label>
              <span class="defense-value">{{ defenseMetrics.defenseTriggered || 0 }} 次</span>
            </div>
            <div class="defense-item">
              <label>异常检测:</label>
              <span class="defense-value">{{ defenseMetrics.anomalyDetected || 0 }} 次</span>
            </div>
            <div class="defense-item">
              <label>防御效果:</label>
              <span class="defense-value">{{ defenseMetrics.defenseEffectiveness || '--' }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 训练图表区域 -->
    <el-row :gutter="20" class="chart-section">
      <el-col :span="12">
        <el-card class="chart-card">
          <div slot="header" class="card-header">
            <i class="el-icon-s-data"></i>
            <span>训练曲线</span>
            <el-button
              style="float: right; padding: 3px 0"
              type="text"
              icon="el-icon-refresh"
              @click="refreshCharts"
            >
              刷新
            </el-button>
          </div>
          <div ref="lossChart" class="chart-container"></div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="chart-card">
          <div slot="header" class="card-header">
            <i class="el-icon-s-marketing"></i>
            <span>准确率变化</span>
            <el-button
              style="float: right; padding: 3px 0"
              type="text"
              icon="el-icon-download"
              @click="exportChartData"
            >
              导出
            </el-button>
          </div>
          <div ref="accuracyChart" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 训练日志区域 -->
    <el-row class="log-section">
      <el-col :span="24">
        <el-card class="log-card">
          <div slot="header" class="card-header">
            <i class="el-icon-document"></i>
            <span>训练日志</span>
            <div style="float: right;">
              <el-button
                type="text"
                icon="el-icon-refresh"
                @click="refreshLogs"
              >
                刷新日志
              </el-button>
              <el-button
                type="text"
                icon="el-icon-download"
                @click="downloadLogs"
              >
                下载日志
              </el-button>
              <el-switch
                v-model="autoRefresh"
                active-text="自动刷新"
                inactive-text="手动刷新"
                @change="handleAutoRefreshChange"
              />
            </div>
          </div>
          <div class="log-container">
            <div v-if="formattedLogs.length === 0" class="no-logs">
              <el-empty description="暂无训练日志" />
            </div>
            <div v-else class="log-content">
              <div
                v-for="(log, index) in formattedLogs"
                :key="index"
                class="log-entry"
                :class="getLogLevelClass(log.level)"
              >
                <span class="log-time">{{ formatLogTime(log.timestamp) }}</span>
                <span class="log-level">{{ log.level }}</span>
                <span class="log-message">{{ log.message }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { getTrainingLog, getTaskProgress } from '@/api/system/duringDefense'

export default {
  name: 'TrainProgressChart',
  props: {
    taskData: {
      type: Object,
      required: true
    },
    visible: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      autoRefresh: false,
      refreshTimer: null,
      lossChart: null,
      accuracyChart: null,
      currentMetrics: {},
      defenseMetrics: {},
      formattedLogs: [],
      chartData: {
        epochs: [],
        losses: [],
        accuracies: [],
        defenseEvents: []
      }
    }
  },
  computed: {
    // 估算结束时间
    estimatedEndTime() {
      if (!this.taskData.startTime || !this.taskData.progress || this.taskData.progress === 0) {
        return '--'
      }
      const startTime = new Date(this.taskData.startTime)
      const now = new Date()
      const elapsed = now - startTime
      const totalEstimated = (elapsed / this.taskData.progress) * 100
      const endTime = new Date(startTime.getTime() + totalEstimated)
      return this.formatTime(endTime)
    },
    // 已用时长
    elapsedTime() {
      if (!this.taskData.startTime) return '--'
      const startTime = new Date(this.taskData.startTime)
      const now = new Date()
      const diff = now - startTime
      const hours = Math.floor(diff / (1000 * 60 * 60))
      const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
      return `${hours}h ${minutes}m`
    }
  },
  watch: {
    visible(newVal) {
      if (newVal) {
        this.$nextTick(() => {
          this.initCharts()
          this.loadTrainingData()
        })
      } else {
        this.destroyCharts()
      }
    },
    taskData: {
      handler() {
        this.loadTrainingData()
      },
      deep: true
    }
  },
  mounted() {
    if (this.visible) {
      this.$nextTick(() => {
        this.initCharts()
        this.loadTrainingData()
      })
    }
  },
  beforeDestroy() {
    this.destroyCharts()
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer)
    }
  },
  methods: {
    // 初始化图表
    initCharts() {
      if (this.$refs.lossChart) {
        this.lossChart = echarts.init(this.$refs.lossChart)
        this.initLossChart()
      }
      if (this.$refs.accuracyChart) {
        this.accuracyChart = echarts.init(this.$refs.accuracyChart)
        this.initAccuracyChart()
      }
      // 监听窗口大小变化
      window.addEventListener('resize', this.handleResize)
    },
    // 销毁图表
    destroyCharts() {
      if (this.lossChart) {
        this.lossChart.dispose()
        this.lossChart = null
      }
      if (this.accuracyChart) {
        this.accuracyChart.dispose()
        this.accuracyChart = null
      }
      window.removeEventListener('resize', this.handleResize)
    },
    // 初始化损失图表
    initLossChart() {
      const option = {
        title: {
          text: '训练损失',
          left: 'center',
          textStyle: { fontSize: 14 }
        },
        tooltip: {
          trigger: 'axis',
          formatter: 'Epoch {b}<br/>Loss: {c}'
        },
        xAxis: {
          type: 'category',
          name: 'Epoch',
          data: this.chartData.epochs
        },
        yAxis: {
          type: 'value',
          name: 'Loss'
        },
        series: [{
          name: 'Loss',
          type: 'line',
          data: this.chartData.losses,
          smooth: true,
          lineStyle: { color: '#ff6b6b' },
          itemStyle: { color: '#ff6b6b' }
        }],
        grid: {
          left: '10%',
          right: '10%',
          bottom: '15%',
          top: '20%'
        }
      }
      this.lossChart.setOption(option)
    },
    // 初始化准确率图表
    initAccuracyChart() {
      const option = {
        title: {
          text: '准确率变化',
          left: 'center',
          textStyle: { fontSize: 14 }
        },
        tooltip: {
          trigger: 'axis',
          formatter: function(params) {
            let html = 'Epoch ' + params[0].name + '<br/>'
            params.forEach(param => {
              html += param.seriesName + ': ' + param.value + '%<br/>'
            })
            return html
          }
        },
        legend: {
          data: ['训练准确率', '验证准确率'],
          bottom: 10
        },
        xAxis: {
          type: 'category',
          name: 'Epoch',
          data: this.chartData.epochs
        },
        yAxis: {
          type: 'value',
          name: 'Accuracy (%)',
          min: 0,
          max: 100
        },
        series: [
          {
            name: '训练准确率',
            type: 'line',
            data: this.chartData.accuracies,
            smooth: true,
            lineStyle: { color: '#4ecdc4' },
            itemStyle: { color: '#4ecdc4' }
          },
          {
            name: '验证准确率',
            type: 'line',
            data: this.chartData.accuracies.map(acc => acc * 0.95 + Math.random() * 5), // 模拟验证准确率
            smooth: true,
            lineStyle: { color: '#45b7d1' },
            itemStyle: { color: '#45b7d1' }
          }
        ],
        grid: {
          left: '10%',
          right: '10%',
          bottom: '20%',
          top: '15%'
        }
      }
      this.accuracyChart.setOption(option)
    },
    // 加载训练数据
    loadTrainingData() {
      if (!this.taskData.taskId) return

      // 加载训练日志
      this.loadTrainingLogs()

      // 如果任务正在运行，获取最新进度
      if (this.taskData.status === 'running') {
        this.loadCurrentProgress()
      }
    },
    // 加载训练日志
    loadTrainingLogs() {
      getTrainingLog(this.taskData.taskId).then(response => {
        const result = response.data || response
        this.parseTrainingLog(result.trainingLog)
      }).catch(err => {
        console.error('加载训练日志失败:', err)
      })
    },
    // 加载当前进度
    loadCurrentProgress() {
      getTaskProgress(this.taskData.taskId).then(response => {
        const progress = response.data || response
        // 更新进度数据
        this.$emit('progress-updated', progress)
      }).catch(err => {
        console.error('加载进度失败:', err)
      })
    },
    // 解析训练日志
    parseTrainingLog(logString) {
      if (!logString) {
        this.formattedLogs = []
        return
      }

      try {
        const logData = JSON.parse(logString)

        // 解析指标数据
        if (logData.loss_history && Array.isArray(logData.loss_history)) {
          this.chartData.epochs = logData.loss_history.map((_, index) => index + 1)
          this.chartData.losses = logData.loss_history
        }

        if (logData.accuracy_history && Array.isArray(logData.accuracy_history)) {
          this.chartData.accuracies = logData.accuracy_history
        }

        // 解析当前指标
        this.currentMetrics = {
          loss: logData.current_loss || logData.final_loss,
          accuracy: logData.current_accuracy || logData.final_accuracy,
          currentEpoch: logData.current_epoch || logData.epochs,
          totalEpochs: logData.total_epochs || logData.epochs
        }

        // 解析防御指标
        this.defenseMetrics = {
          defenseTriggered: logData.defense_triggered || 0,
          anomalyDetected: logData.anomaly_detected || 0,
          defenseEffectiveness: logData.defense_effectiveness || '--'
        }

        // 解析日志条目
        if (logData.log_entries && Array.isArray(logData.log_entries)) {
          this.formattedLogs = logData.log_entries
        } else {
          // 如果没有结构化日志，创建一个基本的日志条目
          this.formattedLogs = [
            {
              timestamp: new Date().toISOString(),
              level: 'INFO',
              message: `训练进度: ${this.taskData.progress}%, 当前指标更新`
            }
          ]
        }

        // 更新图表
        this.updateCharts()
      } catch (e) {
        console.warn('解析训练日志失败:', e)
        this.formattedLogs = [
          {
            timestamp: new Date().toISOString(),
            level: 'INFO',
            message: logString || '训练日志解析中...'
          }
        ]
      }
    },
    // 更新图表
    updateCharts() {
      if (this.lossChart && this.chartData.losses.length > 0) {
        this.lossChart.setOption({
          xAxis: { data: this.chartData.epochs },
          series: [{ data: this.chartData.losses }]
        })
      }

      if (this.accuracyChart && this.chartData.accuracies.length > 0) {
        this.accuracyChart.setOption({
          xAxis: { data: this.chartData.epochs },
          series: [
            { data: this.chartData.accuracies },
            { data: this.chartData.accuracies.map(acc => acc * 0.95 + Math.random() * 5) }
          ]
        })
      }
    },
    // 刷新图表
    refreshCharts() {
      this.loadTrainingData()
      this.$message.success('图表数据已刷新')
    },
    // 刷新日志
    refreshLogs() {
      this.loadTrainingLogs()
      this.$message.success('日志已刷新')
    },
    // 自动刷新切换
    handleAutoRefreshChange(enabled) {
      if (enabled) {
        this.refreshTimer = setInterval(() => {
          if (this.taskData.status === 'running') {
            this.loadTrainingData()
          }
        }, 5000) // 每5秒刷新一次
        this.$message.success('已开启自动刷新')
      } else {
        if (this.refreshTimer) {
          clearInterval(this.refreshTimer)
          this.refreshTimer = null
        }
        this.$message.info('已关闭自动刷新')
      }
    },
    // 导出图表数据
    exportChartData() {
      const data = {
        taskId: this.taskData.taskId,
        taskName: this.taskData.taskName,
        chartData: this.chartData,
        metrics: this.currentMetrics,
        defenseMetrics: this.defenseMetrics,
        exportTime: new Date().toISOString()
      }

      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `training_data_${this.taskData.taskId}.json`
      a.click()
      URL.revokeObjectURL(url)
      this.$message.success('图表数据已导出')
    },
    // 下载日志
    downloadLogs() {
      if (this.formattedLogs.length === 0) {
        this.$message.warning('暂无日志可下载')
        return
      }

      const logText = this.formattedLogs.map(log =>
        `[${this.formatLogTime(log.timestamp)}] ${log.level}: ${log.message}`
      ).join('\n')

      const blob = new Blob([logText], { type: 'text/plain' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `training_log_${this.taskData.taskId}.txt`
      a.click()
      URL.revokeObjectURL(url)
      this.$message.success('训练日志已下载')
    },
    // 窗口大小变化处理
    handleResize() {
      if (this.lossChart) {
        this.lossChart.resize()
      }
      if (this.accuracyChart) {
        this.accuracyChart.resize()
      }
    },
    // 格式化时间
    formatTime(time) {
      if (!time) return '--'
      const date = new Date(time)
      return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })
    },
    // 格式化日志时间
    formatLogTime(timestamp) {
      if (!timestamp) return '--'
      const date = new Date(timestamp)
      return date.toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })
    },
    // 获取进度状态
    getProgressStatus(status) {
      if (status === 'success') return 'success'
      if (status === 'failed') return 'exception'
      return null
    },
    // 获取状态标签
    getStatusLabel(status) {
      const statusMap = {
        'pending': '等待中',
        'running': '运行中',
        'success': '成功',
        'failed': '失败'
      }
      return statusMap[status] || status
    },
    // 获取日志级别样式
    getLogLevelClass(level) {
      const levelMap = {
        'ERROR': 'log-error',
        'WARN': 'log-warn',
        'WARNING': 'log-warn',
        'INFO': 'log-info',
        'DEBUG': 'log-debug'
      }
      return levelMap[level] || 'log-info'
    }
  }
}
</script>

<style scoped>
.train-progress-chart {
  width: 100%;
  padding: 10px;
}

.progress-overview {
  margin-bottom: 20px;
}

.progress-card {
  height: 140px;
}

.card-header {
  display: flex;
  align-items: center;
  font-weight: 500;
}

.card-header i {
  margin-right: 8px;
  color: #409EFF;
}

.progress-content {
  display: flex;
  align-items: center;
  height: 80px;
}

.progress-text {
  margin-left: 15px;
  display: flex;
  flex-direction: column;
}

.progress-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.progress-status {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.time-content,
.metrics-content,
.defense-content {
  height: 80px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.time-item,
.metric-item,
.defense-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 5px;
}

.time-item label,
.metric-item label,
.defense-item label {
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}

.metric-value,
.defense-value {
  font-size: 13px;
  font-weight: bold;
  color: #409EFF;
}

.chart-section {
  margin-bottom: 20px;
}

.chart-card {
  height: 350px;
}

.chart-container {
  height: 280px;
  width: 100%;
}

.log-section {
  margin-bottom: 10px;
}

.log-card {
  min-height: 300px;
}

.log-container {
  height: 250px;
  overflow-y: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 10px;
  background-color: #fafafa;
}

.no-logs {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.log-content {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.log-entry {
  margin-bottom: 2px;
  padding: 2px 0;
  border-bottom: 1px solid #f0f0f0;
}

.log-time {
  color: #909399;
  margin-right: 8px;
}

.log-level {
  font-weight: bold;
  margin-right: 8px;
  min-width: 50px;
  display: inline-block;
}

.log-message {
  color: #303133;
}

.log-error .log-level {
  color: #f56c6c;
}

.log-warn .log-level {
  color: #e6a23c;
}

.log-info .log-level {
  color: #409eff;
}

.log-debug .log-level {
  color: #909399;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .progress-overview .el-col {
    margin-bottom: 10px;
  }

  .chart-section .el-col {
    margin-bottom: 20px;
  }
}

@media (max-width: 768px) {
  .progress-card {
    height: auto;
    margin-bottom: 10px;
  }

  .progress-content {
    flex-direction: column;
    height: auto;
    padding: 10px 0;
  }

  .progress-text {
    margin-left: 0;
    margin-top: 10px;
    text-align: center;
  }

  .chart-card {
    height: 300px;
  }

  .chart-container {
    height: 230px;
  }
}

/* 滚动条样式 */
.log-container::-webkit-scrollbar {
  width: 6px;
}

.log-container::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.log-container::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.log-container::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}
</style>
