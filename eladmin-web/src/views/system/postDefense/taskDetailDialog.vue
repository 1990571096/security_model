<template>
  <el-dialog
    title="任务详情"
    :visible="visible"
    width="1200px"
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
        <el-descriptions-item label="输入模型">
          {{ getModelName(taskData.inputModelId) }}
        </el-descriptions-item>
        <el-descriptions-item label="测试数据集">
          {{ getDatasetName(taskData.testDatasetId) }}
        </el-descriptions-item>
        <el-descriptions-item label="防御算法">
          {{ getAlgorithmName(taskData.algorithmId) }}
        </el-descriptions-item>
        <el-descriptions-item label="检测结果">
          <el-tag
            v-if="taskData.defenseResult"
            size="small"
            :type="getDefenseResultTagType(taskData.defenseResult)"
          >
            {{ getDefenseResultLabel(taskData.defenseResult) }}
          </el-tag>
          <span v-else class="text-muted">-</span>
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ taskData.progress || 0 }}%
        </el-descriptions-item>
        <el-descriptions-item label="超参数模板">
          {{ getTemplateName(taskData.hyperId) }}
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
      </el-descriptions>

      <!-- 进度条 -->
      <div v-if="taskData.status === 'running'" style="margin-top: 15px;">
        <el-progress
          :percentage="currentProgress"
          :status="currentProgress === 100 ? 'success' : null"
          :stroke-width="8"
        />
        <p style="text-align: center; margin-top: 10px; color: #666;">
          正在执行{{ getProgressPhase() }}，请稍候...
        </p>
      </div>

      <!-- 快速操作按钮 -->
      <div style="margin-top: 20px;">
        <el-button
          v-if="taskData.status === 'pending'"
          type="primary"
          size="small"
          icon="el-icon-video-play"
          @click="startTask"
        >
          启动任务
        </el-button>
        <el-button
          v-if="taskData.status === 'success' && taskData.defenseResult"
          type="warning"
          size="small"
          icon="el-icon-data-analysis"
          @click="showRiskAssessment"
        >
          查看风险评估
        </el-button>
        <el-button
          v-if="taskData.status === 'success' && taskData.outputModelId"
          type="success"
          size="small"
          icon="el-icon-download"
          @click="downloadPurifiedModel"
        >
          下载净化模型
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
      </div>

      <!-- 检测结果统计卡片 -->
      <div v-if="taskData.status === 'success' && taskData.defenseResult" style="margin-top: 20px;">
        <el-divider content-position="left">检测结果概览</el-divider>
        <el-row :gutter="20">
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-shield stat-icon" :style="{ color: getResultIconColor() }"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ getDefenseResultLabel(taskData.defenseResult) }}</div>
                  <div class="stat-label">检测结果</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-time stat-icon" style="color: #409EFF;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ getDetectionDuration() }}</div>
                  <div class="stat-label">检测耗时</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-pie-chart stat-icon" style="color: #67C23A;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ getConfidencePercent() }}</div>
                  <div class="stat-label">检测置信度</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-warning stat-icon" style="color: #E6A23C;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ getRiskLevel() }}</div>
                  <div class="stat-label">风险等级</div>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 性能对比分析 -->
      <div v-if="hasPerformanceData" style="margin-top: 20px;">
        <el-divider content-position="left">性能对比分析</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-card>
              <div slot="header">
                <span>性能指标对比</span>
              </div>
              <div id="performanceComparisonChart" style="width: 100%; height: 300px;"></div>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card>
              <div slot="header">
                <span>详细性能数据</span>
              </div>
              <div class="performance-details">
                <div class="performance-item">
                  <span class="performance-label">原始准确率:</span>
                  <span class="performance-value">{{ getPerformanceValue('original_accuracy') }}</span>
                </div>
                <div class="performance-item" v-if="taskData.defenseResult === 'purified'">
                  <span class="performance-label">净化后准确率:</span>
                  <span class="performance-value">{{ getPerformanceValue('purified_accuracy') }}</span>
                </div>
                <div class="performance-item">
                  <span class="performance-label">推理时间:</span>
                  <span class="performance-value">{{ getPerformanceValue('inference_time_ms') }}ms</span>
                </div>
                <div class="performance-item" v-if="taskData.defenseResult === 'purified'">
                  <span class="performance-label">性能损失:</span>
                  <span class="performance-value" :class="getPerformanceLossClass()">
                    {{ getPerformanceLoss() }}
                  </span>
                </div>
                <div class="performance-item">
                  <span class="performance-label">性能稳定性:</span>
                  <span class="performance-value">{{ getPerformanceStability() }}</span>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 检测详情和风险分析 -->
      <div v-if="taskData.status === 'success' && (hasRiskData || hasDetectionData)" style="margin-top: 20px;">
        <el-divider content-position="left">检测详情分析</el-divider>
        <el-row :gutter="20">
          <el-col :span="12" v-if="hasRiskData">
            <el-card>
              <div slot="header">
                <span>风险评估概览</span>
                <el-button
                  style="float: right; padding: 3px 0"
                  type="text"
                  @click="showRiskAssessment"
                >
                  详细报告
                </el-button>
              </div>
              <div id="riskAssessmentChart" style="width: 100%; height: 250px;"></div>
            </el-card>
          </el-col>
          <el-col :span="12" v-if="hasDetectionData">
            <el-card>
              <div slot="header">
                <span>检测过程摘要</span>
              </div>
              <div class="detection-summary">
                <div class="detection-item">
                  <strong>扫描层数:</strong> {{ getDetectionValue('scanned_layers', '未知') }}
                </div>
                <div class="detection-item">
                  <strong>分析神经元:</strong> {{ getDetectionValue('analyzed_neurons', 0) | formatNumber }}
                </div>
                <div class="detection-item">
                  <strong>检测方法:</strong> {{ getDetectionValue('detection_method', '未知') }}
                </div>
                <div class="detection-item" v-if="getSuspiciousLayers().length > 0">
                  <strong>可疑层:</strong>
                  <el-tag
                    v-for="layer in getSuspiciousLayers()"
                    :key="layer"
                    size="mini"
                    type="warning"
                    style="margin-left: 5px;"
                  >
                    {{ layer }}
                  </el-tag>
                </div>
                <div class="detection-item" v-if="getTriggerPatterns() !== 'none'">
                  <strong>触发器模式:</strong> {{ getTriggerPatterns() }}
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>

      <!-- 模板配置显示 -->
      <div v-if="templateConfig" style="margin-top: 20px;">
        <el-divider content-position="left">模板配置</el-divider>
        <el-card class="parameter-card">
          <pre class="parameter-json">{{ formatJSON(templateConfig) }}</pre>
        </el-card>
      </div>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">关闭</el-button>
    </div>

    <!-- 风险评估对话框 -->
    <riskAssessmentDialog
      :visible.sync="riskDialogVisible"
      :task-data="taskData"
      @refresh="refreshTaskInfo"
    />
  </el-dialog>
</template>

<script>
import { startTask, getTaskProgress, downloadPurifiedModel } from '@/api/system/postDefense'
import { getParameterSchema } from '@/api/system/hyper'
import { get as getDataset } from '@/api/system/datasetDetail'
import { getModel } from '@/api/system/model'
import { getHyper } from '@/api/system/hyper'
import riskAssessmentDialog from './module/riskAssessmentDialog'
import * as echarts from 'echarts'

export default {
  components: { riskAssessmentDialog },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    taskData: {
      type: Object,
      default: null
    },
    modelCache: {
      type: Object,
      default: () => ({})
    },
    datasetCache: {
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
      riskDialogVisible: false,
      templateConfig: null,
      performanceChart: null,
      riskChart: null
    }
  },
  computed: {
    hasPerformanceData() {
      return this.taskData && this.taskData.performanceMetrics
    },
    hasRiskData() {
      return this.taskData && this.taskData.riskAssessment
    },
    hasDetectionData() {
      return this.taskData && this.taskData.detectionReport
    },
    performanceMetricsData() {
      if (!this.hasPerformanceData) return null
      try {
        return typeof this.taskData.performanceMetrics === 'string'
          ? JSON.parse(this.taskData.performanceMetrics)
          : this.taskData.performanceMetrics
      } catch {
        return null
      }
    },
    riskAssessmentData() {
      if (!this.hasRiskData) return null
      try {
        return typeof this.taskData.riskAssessment === 'string'
          ? JSON.parse(this.taskData.riskAssessment)
          : this.taskData.riskAssessment
      } catch {
        return null
      }
    },
    detectionReportData() {
      if (!this.hasDetectionData) return null
      try {
        return typeof this.taskData.detectionReport === 'string'
          ? JSON.parse(this.taskData.detectionReport)
          : this.taskData.detectionReport
      } catch {
        return null
      }
    }
  },
  watch: {
    visible(val) {
      if (val && this.taskData) {
        this.initTaskData()
      } else {
        this.stopProgressMonitoring()
        this.destroyCharts()
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
    this.destroyCharts()
  },
  methods: {
    // 初始化任务数据
    initTaskData() {
      if (this.taskData.status === 'running') {
        this.startProgressMonitoring()
      }

      // 如果任务完成且有可视化数据，初始化图表
      if (this.taskData.status === 'success') {
        this.$nextTick(() => {
          this.initCharts()
        })
      }

      // 加载模板配置
      if (this.taskData.hyperId) {
        this.loadTemplateConfig(this.taskData.hyperId)
      }

      // 主动加载缺失的缓存数据
      this.loadMissingCacheData()
    },

    // 加载缺失的缓存数据
    loadMissingCacheData() {
      // 加载模型名称
      if (this.taskData.inputModelId && !this.modelCache[this.taskData.inputModelId]) {
        this.loadModelNameAsync(this.taskData.inputModelId)
      }

      // 加载数据集名称
      if (this.taskData.testDatasetId && !this.datasetCache[this.taskData.testDatasetId]) {
        this.loadDatasetNameAsync(this.taskData.testDatasetId)
      }

      // 加载模板名称
      if (this.taskData.hyperId && !this.templateCache[this.taskData.hyperId]) {
        this.loadTemplateNameAsync(this.taskData.hyperId)
      }
    },

    // 主动加载模型名称
    loadModelNameAsync(modelId) {
      if (!modelId) return
      getModel(modelId).then(response => {
        const model = response.data || response
        const modelName = model.modelName || `模型-${modelId}`
        this.$set(this.modelCache, modelId, modelName)
      }).catch(err => {
        console.error('加载模型名称失败:', err)
        this.$set(this.modelCache, modelId, `模型-${modelId}`)
      })
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
      this.$confirm('确定要启动该检测任务吗？', '提示', {
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

    // 下载净化模型
    downloadPurifiedModel() {
      if (!this.taskData || !this.taskData.outputModelId) return

      downloadPurifiedModel(this.taskData.taskId).then(() => {
        this.$message.success('开始下载净化后模型')
      }).catch(err => {
        this.$message.error('下载失败：' + err.message)
      })
    },

    // 显示风险评估
    showRiskAssessment() {
      this.riskDialogVisible = true
    },

    // 刷新任务信息
    refreshTaskInfo() {
      this.$emit('refresh')
    },

    // 开始进度监控
    startProgressMonitoring() {
      if (!this.taskData) return

      this.currentProgress = this.taskData.progress || 10
      this.progressTimer = setInterval(() => {
        if (this.currentProgress < 90) {
          this.currentProgress += Math.random() * 10
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

    // 获取进度阶段描述
    getProgressPhase() {
      if (this.currentProgress < 30) return '模型分析'
      if (this.currentProgress < 60) return '后门检测'
      if (this.currentProgress < 90) return '结果分析'
      return '任务完成'
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

    // 初始化图表
    initCharts() {
      if (this.hasPerformanceData) {
        this.initPerformanceChart()
      }
      if (this.hasRiskData) {
        this.initRiskChart()
      }
    },

    // 初始化性能对比图表
    initPerformanceChart() {
      const chartDom = document.getElementById('performanceComparisonChart')
      if (!chartDom || !this.performanceMetricsData) return

      this.performanceChart = echarts.init(chartDom)
      const data = this.performanceMetricsData

      const option = {
        title: {
          text: '性能对比分析',
          left: 'center',
          textStyle: { fontSize: 14 }
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: function(params) {
            let result = params[0].name + '<br/>'
            params.forEach(function(item) {
              let value = item.value
              if (item.seriesName.includes('准确率')) {
                value = (value * 100).toFixed(1) + '%'
              } else if (item.seriesName.includes('时间')) {
                value = value + 'ms'
              }
              result += item.marker + item.seriesName + ': ' + value + '<br/>'
            })
            return result
          }
        },
        legend: {
          data: ['原始准确率', '净化后准确率', '推理时间'],
          top: 30
        },
        xAxis: {
          type: 'category',
          data: ['性能指标']
        },
        yAxis: [
          {
            type: 'value',
            name: '准确率',
            position: 'left',
            axisLabel: {
              formatter: '{value}%'
            }
          },
          {
            type: 'value',
            name: '时间(ms)',
            position: 'right',
            axisLabel: {
              formatter: '{value}ms'
            }
          }
        ],
        series: [
          {
            name: '原始准确率',
            type: 'bar',
            yAxisIndex: 0,
            data: [(data.original_accuracy || 0) * 100],
            itemStyle: { color: '#409EFF' }
          },
          {
            name: '净化后准确率',
            type: 'bar',
            yAxisIndex: 0,
            data: [(data.purified_accuracy || data.original_accuracy || 0) * 100],
            itemStyle: { color: '#67C23A' }
          },
          {
            name: '推理时间',
            type: 'line',
            yAxisIndex: 1,
            data: [data.inference_time_ms || 0],
            itemStyle: { color: '#E6A23C' }
          }
        ]
      }

      this.performanceChart.setOption(option)
    },

    // 初始化风险评估图表
    initRiskChart() {
      const chartDom = document.getElementById('riskAssessmentChart')
      if (!chartDom || !this.riskAssessmentData) return

      this.riskChart = echarts.init(chartDom)
      const confidence = this.riskAssessmentData.confidence || 0.95
      const backdoorProb = this.riskAssessmentData.backdoor_probability || 0.05

      const option = {
        title: {
          text: '风险评估分布',
          left: 'center',
          textStyle: { fontSize: 14 }
        },
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c}%'
        },
        series: [{
          type: 'pie',
          radius: '60%',
          data: [
            {
              value: confidence * 100,
              name: '安全置信度',
              itemStyle: { color: '#67C23A' }
            },
            {
              value: backdoorProb * 100,
              name: '后门风险',
              itemStyle: { color: '#F56C6C' }
            }
          ],
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          },
          label: {
            formatter: '{b}\n{c}%'
          }
        }]
      }

      this.riskChart.setOption(option)
    },

    // 销毁图表
    destroyCharts() {
      if (this.performanceChart) {
        this.performanceChart.dispose()
        this.performanceChart = null
      }
      if (this.riskChart) {
        this.riskChart.dispose()
        this.riskChart = null
      }
    },

    // 获取检测耗时
    getDetectionDuration() {
      if (!this.taskData.startTime || !this.taskData.endTime) return '未知'
      const start = new Date(this.taskData.startTime)
      const end = new Date(this.taskData.endTime)
      const duration = Math.round((end - start) / 1000 / 60) // 分钟
      return duration + '分钟'
    },

    // 获取置信度百分比
    getConfidencePercent() {
      if (!this.riskAssessmentData) return '未知'
      const confidence = this.riskAssessmentData.confidence || 0
      return (confidence * 100).toFixed(1) + '%'
    },

    // 获取风险等级
    getRiskLevel() {
      if (!this.riskAssessmentData) return '未知'
      const level = this.riskAssessmentData.risk_level || 'unknown'
      const levelMap = {
        'very_low': '很低',
        'low': '低',
        'medium': '中等',
        'high': '高',
        'very_high': '很高'
      }
      return levelMap[level] || '未知'
    },

    // 获取结果图标颜色
    getResultIconColor() {
      const colorMap = {
        'clean': '#67C23A',
        'purified': '#E6A23C',
        'infected': '#F56C6C',
        'failed': '#909399'
      }
      return colorMap[this.taskData.defenseResult] || '#909399'
    },

    // 获取性能数据值
    getPerformanceValue(key) {
      if (!this.performanceMetricsData) return '未知'
      const value = this.performanceMetricsData[key]
      if (key.includes('accuracy')) {
        return (value * 100).toFixed(1) + '%'
      }
      return value || '未知'
    },

    // 获取性能损失
    getPerformanceLoss() {
      if (!this.performanceMetricsData) return '未知'
      const original = this.performanceMetricsData.original_accuracy
      const purified = this.performanceMetricsData.purified_accuracy
      if (!original || !purified) return '未知'
      const loss = ((original - purified) * 100).toFixed(2)
      return loss + '%'
    },

    // 获取性能损失样式类
    getPerformanceLossClass() {
      if (!this.performanceMetricsData) return ''
      const original = this.performanceMetricsData.original_accuracy
      const purified = this.performanceMetricsData.purified_accuracy
      if (!original || !purified) return ''
      const loss = original - purified
      if (loss < 0.01) return 'low-loss'
      if (loss < 0.05) return 'medium-loss'
      return 'high-loss'
    },

    // 获取性能稳定性
    getPerformanceStability() {
      if (!this.performanceMetricsData) return '未知'
      return this.performanceMetricsData.performance_stable ? '稳定' : '不稳定'
    },

    // 获取检测数据值
    getDetectionValue(key, defaultValue) {
      return this.detectionReportData ? (this.detectionReportData[key] || defaultValue) : defaultValue
    },

    // 获取可疑层列表
    getSuspiciousLayers() {
      if (!this.riskAssessmentData) return []
      return this.riskAssessmentData.suspicious_layers || []
    },

    // 获取触发器模式
    getTriggerPatterns() {
      if (!this.riskAssessmentData) return 'none'
      return this.riskAssessmentData.trigger_patterns || 'none'
    },

    // 关闭对话框
    handleClose() {
      this.$emit('update:visible', false)
    },

    // 获取模型名称
    getModelName(modelId) {
      if (!modelId) return '未选择'
      if (this.modelCache[modelId]) {
        return this.modelCache[modelId]
      }
      if (!this.modelCache[`loading_${modelId}`]) {
        this.modelCache[`loading_${modelId}`] = true
        this.loadModelNameAsync(modelId)
      }
      return `模型-${modelId}`
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
    },

    // 获取防御结果标签样式
    getDefenseResultTagType(result) {
      const resultMap = {
        'clean': 'success',
        'purified': 'warning',
        'infected': 'danger',
        'failed': 'info'
      }
      return resultMap[result] || 'info'
    },

    // 获取防御结果标签文字
    getDefenseResultLabel(result) {
      const resultMap = {
        'clean': '清洁',
        'purified': '已净化',
        'infected': '发现后门',
        'failed': '检测失败'
      }
      return resultMap[result] || result
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
    }
  },
  filters: {
    formatNumber(value) {
      if (!value) return '0'
      return value.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
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

.text-muted {
  color: #909399;
  font-size: 12px;
}

.stat-card {
  margin-bottom: 10px;
}

.stat-item {
  display: flex;
  align-items: center;
  padding: 15px;
}

.stat-icon {
  font-size: 32px;
  margin-right: 15px;
}

.stat-content {
  flex: 1;
}

.stat-number {
  font-size: 20px;
  font-weight: bold;
  color: #303133;
  line-height: 1;
  margin-bottom: 5px;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.performance-details {
  padding: 10px 0;
}

.performance-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.performance-item:last-child {
  border-bottom: none;
}

.performance-label {
  color: #606266;
  font-size: 14px;
}

.performance-value {
  color: #303133;
  font-weight: 500;
}

.low-loss {
  color: #67C23A;
}

.medium-loss {
  color: #E6A23C;
}

.high-loss {
  color: #F56C6C;
}

.detection-summary {
  padding: 10px 0;
}

.detection-item {
  margin-bottom: 12px;
  line-height: 1.5;
}

.detection-item strong {
  color: #303133;
  margin-right: 8px;
}

.parameter-card {
  margin-top: 5px;
}

.parameter-json {
  background-color: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 150px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  margin: 0;
}

.dialog-footer {
  text-align: right;
}

::v-deep .el-descriptions-item__label {
  width: 120px;
}

::v-deep .el-progress-bar__inner {
  transition: width 0.6s ease;
}
</style>
