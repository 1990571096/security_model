<template>
  <el-dialog
    title="训练任务详情"
    :visible="visible"
    width="1000px"
    :before-close="handleClose"
    append-to-body
  >
    <div v-if="!taskData">
      <div class="my-code">暂无任务数据</div>
    </div>
    <div v-else>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="任务名称">
          {{ taskData.taskName }}
        </el-descriptions-item>
        <el-descriptions-item label="任务状态">
          <el-tag size="small" :type="getStatusTagType(taskData.status)">
            {{ getStatusLabel(taskData.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="训练数据集">
          {{ getDatasetName(taskData.datasetId) }}
        </el-descriptions-item>
        <el-descriptions-item label="输入模型">
          {{ getInputModelName(taskData.inputModelId) }}
        </el-descriptions-item>
        <el-descriptions-item label="防御算法">
          {{ getAlgorithmName(taskData.algorithmId) }}
        </el-descriptions-item>
        <el-descriptions-item label="超参数模板">
          {{ getTemplateName(taskData.hyperId) }}
        </el-descriptions-item>
        <el-descriptions-item label="训练进度">
          {{ taskData.progress || 0 }}%
        </el-descriptions-item>
        <el-descriptions-item label="输出模型">
          {{ getOutputModelName(taskData.outputModelId) }}
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ taskData.startTime || '未开始' }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ taskData.endTime || '未结束' }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">
          {{ taskData.createTime }}
        </el-descriptions-item>
        <el-descriptions-item label="任务描述" :span="2">
          {{ taskData.description || '无描述' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="taskData.customParams" label="自定义参数" :span="2">
          <el-card class="parameter-card">
            <pre class="parameter-json">{{ formatJSON(taskData.customParams) }}</pre>
          </el-card>
        </el-descriptions-item>
        <!-- 显示模板配置 -->
        <el-descriptions-item v-if="templateConfig" label="模板默认配置" :span="2">
          <el-card class="parameter-card">
            <pre class="parameter-json">{{ formatJSON(templateConfig) }}</pre>
          </el-card>
        </el-descriptions-item>
        <!-- 训练日志 -->
        <el-descriptions-item v-if="taskData.trainingLog" label="训练日志" :span="2">
          <el-card class="parameter-card">
            <pre class="parameter-json">{{ formatJSON(taskData.trainingLog) }}</pre>
          </el-card>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="failureReason"
        :title="failureReason"
        type="error"
        show-icon
        style="margin-top: 15px;"
      />

      <!-- 进度条 -->
      <div v-if="taskData.status === 'running'" style="margin-top: 15px;">
        <el-progress
          :percentage="currentProgress"
          :status="currentProgress === 100 ? 'success' : null"
          :stroke-width="8"
        />
        <p style="text-align: center; margin-top: 10px; color: #666;">正在执行训练任务，请稍候...</p>
      </div>

      <!-- 操作按钮区域 -->
      <div style="margin-top: 20px;">
        <el-button
          v-if="taskData.status === 'pending'"
          type="primary"
          size="small"
          icon="el-icon-video-play"
          @click="startTask"
        >
          启动训练
        </el-button>
        <el-button
          v-if="taskData.status === 'success' && taskData.outputModelId"
          type="success"
          size="small"
          icon="el-icon-download"
          @click="downloadTrainedModel"
        >
          下载训练后模型
        </el-button>
        <el-button
          v-if="taskData.status === 'failed'"
          type="warning"
          size="small"
          icon="el-icon-refresh"
          @click="retryTask"
        >
          重新启动
        </el-button>
        <el-button
          size="small"
          icon="el-icon-refresh"
          @click="refreshTaskInfo"
        >
          刷新状态
        </el-button>
        <el-button
          v-if="taskData.status === 'running' || taskData.trainingLog"
          type="info"
          size="small"
          icon="el-icon-view"
          @click="showTrainingLog = true"
        >
          查看训练日志
        </el-button>
      </div>

      <!-- 训练结果统计 -->
      <div v-if="taskData.status === 'success' && trainingLogData" style="margin-top: 20px;">
        <el-divider content-position="left">训练结果统计</el-divider>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-cpu stat-icon" style="color: #67C23A;" />
                <div class="stat-content">
                  <div class="stat-number">{{ getTrainingEpochs() }}</div>
                  <div class="stat-label">训练轮数</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-data-analysis stat-icon" style="color: #409EFF;" />
                <div class="stat-content">
                  <div class="stat-number">{{ getFinalAccuracy() }}%</div>
                  <div class="stat-label">最终精度</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-time stat-icon" style="color: #E6A23C;" />
                <div class="stat-content">
                  <div class="stat-number">{{ getTrainingDuration() }}</div>
                  <div class="stat-label">训练时长</div>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 训练过程可视化图表 -->
        <div style="margin-top: 15px;">
          <el-row :gutter="20">
            <el-col :span="12">
              <el-card>
                <div slot="header">
                  <span>训练损失曲线</span>
                </div>
                <div id="lossChart" style="width: 100%; height: 300px;" />
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card>
                <div slot="header">
                  <span>训练精度曲线</span>
                </div>
                <div id="accuracyChart" style="width: 100%; height: 300px;" />
              </el-card>
            </el-col>
          </el-row>
        </div>
      </div>
    </div>

    <!-- 训练日志对话框 -->
    <el-dialog
      title="训练日志详情"
      :visible.sync="showTrainingLog"
      width="800px"
      append-to-body
    >
      <div v-if="taskData && taskData.trainingLog">
        <el-card>
          <pre class="training-log">{{ formatJSON(taskData.trainingLog) }}</pre>
        </el-card>
      </div>
      <div v-else style="text-align: center; padding: 20px; color: #999;">
        <i class="el-icon-info" />
        <p>暂无训练日志</p>
      </div>
    </el-dialog>

    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { startTask, getTaskProgress, downloadTrainedModel } from '@/api/system/duringDefense'
import { getParameterSchema } from '@/api/system/hyper'
import { get as getDataset } from '@/api/system/datasetDetail'
import { getHyper } from '@/api/system/hyper'
import { getModel } from '@/api/system/model'
import * as echarts from 'echarts'

export default {
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    taskData: {
      type: Object,
      default: null
    },
    datasetCache: {
      type: Object,
      default: () => ({})
    },
    modelCache: {
      type: Object,
      default: () => ({})
    },
    algorithmCache: {
      type: Object,
      default: () => ({})
    },
    templateCache: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      currentProgress: 0,
      progressTimer: null,
      lossChart: null,
      accuracyChart: null,
      templateConfig: null,
      showTrainingLog: false
    }
  },
  computed: {
    trainingLogData() {
      if (!this.taskData || !this.taskData.trainingLog) return null
      try {
        return typeof this.taskData.trainingLog === 'string'
          ? JSON.parse(this.taskData.trainingLog)
          : this.taskData.trainingLog
      } catch (e) {
        return null
      }
    },
    failureReason() {
      if (!this.trainingLogData || this.taskData.status !== 'failed') return ''
      return this.trainingLogData.failure_reason || this.trainingLogData.error_message || ''
    }
  },
  watch: {
    visible(val) {
      if (val && this.taskData) {
        this.initTaskData()
      } else {
        this.stopProgressMonitoring()
      }
    },
    taskData: {
      handler(newVal) {
        if (newVal && this.visible) {
          this.initTaskData()
        }
      },
      immediate: true
    }
  },
  beforeDestroy() {
    this.stopProgressMonitoring()
    if (this.lossChart) {
      this.lossChart.dispose()
    }
    if (this.accuracyChart) {
      this.accuracyChart.dispose()
    }
  },
  methods: {
    // 初始化任务数据
    initTaskData() {
      if (this.taskData.status === 'running') {
        this.startProgressMonitoring()
      }

      if (this.taskData.status === 'success' && this.trainingLogData) {
        this.$nextTick(() => {
          this.initCharts()
        })
      }

      // 加载模板配置
      if (this.taskData.hyperId) {
        this.loadTemplateConfig(this.taskData.hyperId)
      }

      // 如果缓存中没有数据集名称，主动加载
      if (this.taskData.datasetId && !this.datasetCache[this.taskData.datasetId]) {
        this.loadDatasetNameAsync(this.taskData.datasetId)
      }

      // 如果缓存中没有输入模型名称，主动加载
      if (this.taskData.inputModelId && !this.modelCache[this.taskData.inputModelId]) {
        this.loadModelNameAsync(this.taskData.inputModelId, 'input')
      }

      // 如果缓存中没有输出模型名称，主动加载
      if (this.taskData.outputModelId && !this.modelCache[this.taskData.outputModelId]) {
        this.loadModelNameAsync(this.taskData.outputModelId, 'output')
      }

      // 如果缓存中没有模板名称，主动加载
      if (this.taskData.hyperId && !this.templateCache[this.taskData.hyperId]) {
        this.loadTemplateNameAsync(this.taskData.hyperId)
      }
    },
    // 主动加载数据集名称
    loadDatasetNameAsync(datasetId) {
      if (!datasetId) return

      getDataset(datasetId).then(response => {
        const dataset = response.data || response
        const datasetName = dataset.datasetName || `数据集-${datasetId}`
        this.$set(this.datasetCache, datasetId, datasetName)
      }).catch(err => {
        console.error('加载数据集名称失败:', err)
        this.$set(this.datasetCache, datasetId, `数据集-${datasetId}`)
      })
    },
    // 主动加载模型名称
    loadModelNameAsync(modelId, type) {
      if (!modelId) return

      getModel(modelId).then(response => {
        const model = response.data || response
        const modelName = model.modelName || `模型-${modelId}`
        this.$set(this.modelCache, modelId, modelName)
      }).catch(err => {
        console.error(`加载${type}模型名称失败:`, err)
        this.$set(this.modelCache, modelId, `模型-${modelId}`)
      })
    },
    // 主动加载模板名称
    loadTemplateNameAsync(templateId) {
      if (!templateId) return

      getHyper(templateId).then(response => {
        const template = response.data || response
        const templateName = template.templateName || `模板-${templateId}`
        this.$set(this.templateCache, templateId, templateName)
      }).catch(err => {
        console.error('加载模板名称失败:', err)
        this.$set(this.templateCache, templateId, `模板-${templateId}`)
      })
    },
    // 启动任务
    startTask() {
      if (!this.taskData) return

      this.$confirm('确定要启动该训练任务吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        startTask(this.taskData.taskId).then(() => {
          this.$message.success('任务启动成功')
          this.taskData.status = 'running'
          this.startProgressMonitoring()
          this.$emit('refresh')
        }).catch(err => {
          this.$message.error('任务启动失败：' + err.message)
        })
      })
    },
    // 重新启动任务
    retryTask() {
      this.startTask()
    },
    // 下载训练后模型
    downloadTrainedModel() {
      if (!this.taskData || !this.taskData.outputModelId) return

      downloadTrainedModel(this.taskData.taskId).then(() => {
        this.$message.success('开始下载训练后模型')
      }).catch(err => {
        this.$message.error('下载失败：' + err.message)
      })
    },
    // 刷新任务信息
    refreshTaskInfo() {
      this.$emit('refresh')
    },
    // 关闭对话框
    handleClose() {
      this.$emit('update:visible', false)
    },
    // 开始进度监控
    startProgressMonitoring() {
      if (!this.taskData) return

      this.currentProgress = this.taskData.progress || 5
      this.progressTimer = setInterval(() => {
        if (this.currentProgress < 90) {
          this.currentProgress += Math.random() * 5
        }
        // 实际获取进度
        getTaskProgress(this.taskData.taskId).then(response => {
          const progress = response.data || response
          if (typeof progress === 'number') {
            this.currentProgress = progress
            if (progress >= 100) {
              this.stopProgressMonitoring()
              this.$emit('refresh')
            }
          }
        }).catch(err => {
          console.error('获取进度失败:', err)
        })
      }, 10000)
    },
    // 停止进度监控
    stopProgressMonitoring() {
      if (this.progressTimer) {
        clearInterval(this.progressTimer)
        this.progressTimer = null
      }
      this.currentProgress = 0
    },
    // 加载模板配置
    loadTemplateConfig(templateId) {
      if (!templateId) return

      getParameterSchema(templateId).then(response => {
        const result = response.data || response
        this.templateConfig = result.parameterConfig
      }).catch(err => {
        console.error('加载模板配置失败:', err)
      })
    },
    // 获取训练轮数
    getTrainingEpochs() {
      if (!this.trainingLogData) return 0
      return this.trainingLogData.epochs || this.trainingLogData.current_epoch || 0
    },
    // 获取最终精度
    getFinalAccuracy() {
      if (!this.trainingLogData) return 0
      const accuracy = this.trainingLogData.final_accuracy || this.trainingLogData.current_accuracy || 0
      return (accuracy * 100).toFixed(2)
    },
    // 获取训练时长
    getTrainingDuration() {
      if (!this.taskData.startTime || !this.taskData.endTime) return '未知'
      const start = new Date(this.taskData.startTime)
      const end = new Date(this.taskData.endTime)
      const duration = Math.floor((end - start) / 1000 / 60) // 分钟
      if (duration < 60) {
        return `${duration}分钟`
      } else {
        const hours = Math.floor(duration / 60)
        const minutes = duration % 60
        return `${hours}小时${minutes}分钟`
      }
    },
    // 初始化图表
    initCharts() {
      this.initLossChart()
      this.initAccuracyChart()
    },
    // 初始化损失曲线图表
    initLossChart() {
      const chartDom = document.getElementById('lossChart')
      if (!chartDom) return

      this.lossChart = echarts.init(chartDom)

      // 模拟损失数据
      const lossData = this.generateLossData()

      const option = {
        title: {
          text: '训练损失',
          left: 'center',
          textStyle: {
            fontSize: 14
          }
        },
        tooltip: {
          trigger: 'axis'
        },
        xAxis: {
          type: 'category',
          data: lossData.epochs,
          name: 'Epoch'
        },
        yAxis: {
          type: 'value',
          name: 'Loss'
        },
        series: [{
          data: lossData.values,
          type: 'line',
          smooth: true,
          itemStyle: { color: '#F56C6C' }
        }]
      }

      this.lossChart.setOption(option)
    },
    // 初始化精度曲线图表
    initAccuracyChart() {
      const chartDom = document.getElementById('accuracyChart')
      if (!chartDom) return

      this.accuracyChart = echarts.init(chartDom)

      // 模拟精度数据
      const accuracyData = this.generateAccuracyData()

      const option = {
        title: {
          text: '训练精度',
          left: 'center',
          textStyle: {
            fontSize: 14
          }
        },
        tooltip: {
          trigger: 'axis'
        },
        xAxis: {
          type: 'category',
          data: accuracyData.epochs,
          name: 'Epoch'
        },
        yAxis: {
          type: 'value',
          name: 'Accuracy',
          min: 0,
          max: 1
        },
        series: [{
          data: accuracyData.values,
          type: 'line',
          smooth: true,
          itemStyle: { color: '#67C23A' }
        }]
      }

      this.accuracyChart.setOption(option)
    },
    // 生成模拟损失数据
    generateLossData() {
      const curve = this.getTrainingCurve('lossCurve')
      if (curve.length > 0) {
        return this.buildCurveData(curve)
      }

      const epochs = []
      const values = []
      const epochCount = this.getTrainingEpochs() || 20

      for (let i = 1; i <= epochCount; i++) {
        epochs.push(i.toString())
        // 模拟损失递减
        values.push((2.0 * Math.exp(-i * 0.1) + Math.random() * 0.1).toFixed(4))
      }

      return { epochs, values }
    },
    // 生成模拟精度数据
    generateAccuracyData() {
      const curve = this.getTrainingCurve('accuracyCurve')
      if (curve.length > 0) {
        return this.buildCurveData(curve)
      }

      const epochs = []
      const values = []
      const epochCount = this.getTrainingEpochs() || 20

      for (let i = 1; i <= epochCount; i++) {
        epochs.push(i.toString())
        // 模拟精度递增
        values.push(Math.min(0.95, 0.3 + 0.6 * (1 - Math.exp(-i * 0.1)) + Math.random() * 0.05).toFixed(4))
      }

      return { epochs, values }
    },
    // 从训练日志读取后端返回的曲线数据
    getTrainingCurve(fieldName) {
      if (!this.trainingLogData) return []
      return Array.isArray(this.trainingLogData[fieldName]) ? this.trainingLogData[fieldName] : []
    },
    // 将曲线数组转换为 ECharts 数据
    buildCurveData(curve) {
      return {
        epochs: curve.map((item, index) => (index + 1).toString()),
        values: curve.map(item => Number(item).toFixed(4))
      }
    },
    // 格式化JSON显示
    formatJSON(jsonString) {
      if (!jsonString) return ''
      try {
        const obj = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString
        return JSON.stringify(obj, null, 2)
      } catch (e) {
        return jsonString
      }
    },
    // 获取数据集名称
    getDatasetName(datasetId) {
      if (!datasetId) return '未选择'
      if (this.datasetCache[datasetId]) {
        return this.datasetCache[datasetId]
      }
      if (!this.datasetCache[`loading_${datasetId}`]) {
        this.datasetCache[`loading_${datasetId}`] = true
        this.loadDatasetNameAsync(datasetId)
      }
      return `数据集-${datasetId}`
    },
    // 获取输入模型名称
    getInputModelName(inputModelId) {
      if (!inputModelId) return '未选择'
      if (this.modelCache[inputModelId]) {
        return this.modelCache[inputModelId]
      }
      if (!this.modelCache[`loading_${inputModelId}`]) {
        this.modelCache[`loading_${inputModelId}`] = true
        this.loadModelNameAsync(inputModelId, 'input')
      }
      return `模型-${inputModelId}`
    },
    // 获取输出模型名称
    getOutputModelName(outputModelId) {
      if (!outputModelId) return '未生成'
      if (this.modelCache[outputModelId]) {
        return this.modelCache[outputModelId]
      }
      if (!this.modelCache[`loading_${outputModelId}`]) {
        this.modelCache[`loading_${outputModelId}`] = true
        this.loadModelNameAsync(outputModelId, 'output')
      }
      return `模型-${outputModelId}`
    },
    // 获取算法名称
    getAlgorithmName(algorithmId) {
      if (!algorithmId) return '未选择'
      return this.algorithmCache[algorithmId] || `算法-${algorithmId}`
    },
    // 获取模板名称
    getTemplateName(templateId) {
      if (!templateId) return '未使用模板'
      if (this.templateCache[templateId]) {
        return this.templateCache[templateId]
      }
      if (!this.templateCache[`loading_${templateId}`]) {
        this.templateCache[`loading_${templateId}`] = true
        this.loadTemplateNameAsync(templateId)
      }
      return `模板-${templateId}`
    },
    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'pending': 'info',
        'running': 'warning',
        'success': 'success',
        'failed': 'danger'
      }
      return statusMap[status] || 'info'
    },
    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'pending': '等待中',
        'running': '运行中',
        'success': '成功',
        'failed': '失败'
      }
      return statusMap[status] || status
    }
  }
}
</script>

<style scoped>
.my-code {
  color: #999;
  text-align: center;
  padding: 50px 0;
  font-size: 14px;
}

.stat-card {
  margin-bottom: 10px;
}

.stat-item {
  display: flex;
  align-items: center;
  padding: 10px;
}

.stat-icon {
  font-size: 30px;
  margin-right: 15px;
}

.stat-content {
  flex: 1;
}

.stat-number {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
  line-height: 1;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.dialog-footer {
  text-align: right;
}

.parameter-card {
  margin-top: 5px;
}

.parameter-json {
  background-color: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  margin: 0;
}

.training-log {
  background-color: #f5f7fa;
  padding: 15px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 400px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  margin: 0;
  font-family: 'Courier New', monospace;
}

::v-deep .el-descriptions-item__label {
  width: 120px;
}
</style>
