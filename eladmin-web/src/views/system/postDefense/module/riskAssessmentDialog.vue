<template>
  <el-dialog
    title="风险评估报告"
    :visible="visible"
    width="1200px"
    :before-close="handleClose"
    append-to-body
  >
    <div v-if="!taskData">
      <div class="my-code">暂无任务数据</div>
    </div>
    <div v-else-if="!isTaskCompleted">
      <div class="my-code">
        <i class="el-icon-warning"></i>
        <p>任务尚未完成，无法查看风险评估报告</p>
      </div>
    </div>
    <div v-else>
      <!-- 基本信息卡片 -->
      <el-card class="info-card" shadow="never">
        <div slot="header" class="card-header">
          <span>基本信息</span>
          <el-tag :type="getDefenseResultTagType(taskData.defenseResult)" size="medium">
            {{ getDefenseResultLabel(taskData.defenseResult) }}
          </el-tag>
        </div>
        <el-row :gutter="20">
          <el-col :span="8">
            <div class="info-item">
              <span class="label">任务名称:</span>
              <span class="value">{{ taskData.taskName }}</span>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="info-item">
              <span class="label">检测算法:</span>
              <span class="value">{{ getAlgorithmName(taskData.algorithmId) }}</span>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="info-item">
              <span class="label">完成时间:</span>
              <span class="value">{{ taskData.endTime }}</span>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <!-- 风险评估核心指标 -->
      <el-row :gutter="20" style="margin-top: 20px;">
        <el-col :span="12">
          <el-card class="metric-card">
            <div slot="header">
              <span>风险评估概览</span>
            </div>
            <div class="risk-overview">
              <div class="risk-item">
                <div class="risk-label">检测置信度</div>
                <div class="risk-value confidence">
                  {{ getRiskData('confidence', 0.95) * 100 }}%
                </div>
              </div>
              <div class="risk-item">
                <div class="risk-label">风险等级</div>
                <div class="risk-value" :class="getRiskLevelClass(getRiskData('risk_level', 'low'))">
                  {{ getRiskLevelLabel(getRiskData('risk_level', 'low')) }}
                </div>
              </div>
              <div class="risk-item">
                <div class="risk-label">后门概率</div>
                <div class="risk-value backdoor-prob">
                  {{ getRiskData('backdoor_probability', 0.05) * 100 }}%
                </div>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="metric-card">
            <div slot="header">
              <span>风险等级分布</span>
            </div>
            <div id="riskLevelChart" style="width: 100%; height: 200px;"></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 性能指标对比 -->
      <el-row :gutter="20" style="margin-top: 20px;" v-if="hasPerformanceData">
        <el-col :span="12">
          <el-card>
            <div slot="header">
              <span>性能对比分析</span>
            </div>
            <div id="performanceChart" style="width: 100%; height: 300px;"></div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card>
            <div slot="header">
              <span>性能指标详情</span>
            </div>
            <el-table :data="performanceTableData" size="small">
              <el-table-column prop="metric" label="指标" width="120" />
              <el-table-column prop="original" label="原始值" width="80" />
              <el-table-column prop="after" label="防御后" width="80" />
              <el-table-column prop="change" label="变化" width="80">
                <template slot-scope="scope">
                  <span :class="scope.row.changeClass">{{ scope.row.change }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <!-- 检测详情报告 -->
      <el-card style="margin-top: 20px;">
        <div slot="header">
          <span>检测详情报告</span>
          <el-button
            style="float: right; padding: 3px 0"
            type="text"
            icon="el-icon-download"
            @click="exportReport"
          >
            导出报告
          </el-button>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="风险分析" name="risk">
            <div class="report-content">
              <h4>风险评估结果</h4>
              <p v-if="riskAssessmentData">
                <strong>置信度:</strong> {{ (getRiskData('confidence', 0.95) * 100).toFixed(1) }}% -
                检测算法对结果的确信程度
              </p>
              <p>
                <strong>风险等级:</strong> {{ getRiskLevelLabel(getRiskData('risk_level', 'low')) }} -
                {{ getRiskLevelDescription(getRiskData('risk_level', 'low')) }}
              </p>
              <p>
                <strong>后门概率:</strong> {{ (getRiskData('backdoor_probability', 0.05) * 100).toFixed(1) }}% -
                模型包含后门触发器的估计概率
              </p>

              <div v-if="getRiskData('suspicious_layers', []).length > 0" class="suspicious-info">
                <h5>可疑层分析</h5>
                <el-tag
                  v-for="layer in getRiskData('suspicious_layers', [])"
                  :key="layer"
                  type="warning"
                  size="small"
                  style="margin-right: 8px;"
                >
                  {{ layer }}
                </el-tag>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="检测过程" name="detection">
            <div class="report-content">
              <h4>检测过程详情</h4>
              <div v-if="detectionReportData">
                <p><strong>扫描耗时:</strong> {{ getDetectionData('scan_duration', '未知') }}</p>
                <p><strong>扫描层数:</strong> {{ getDetectionData('scanned_layers', 0) }}</p>
                <p><strong>分析神经元:</strong> {{ getDetectionData('analyzed_neurons', 0) | formatNumber }}</p>
                <p><strong>检测方法:</strong> {{ getDetectionData('detection_method', '未知') }}</p>

                <div v-if="getDetectionData('trigger_patterns', 'none') !== 'none'" class="trigger-info">
                  <h5>发现的触发器模式</h5>
                  <p>{{ getDetectionData('trigger_patterns', '') }}</p>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="建议措施" name="recommendations">
            <div class="report-content">
              <h4>安全建议</h4>
              <div class="recommendations">
                <div v-if="taskData.defenseResult === 'clean'" class="recommendation-item success">
                  <i class="el-icon-success"></i>
                  <div>
                    <strong>模型安全</strong>
                    <p>检测结果显示模型是清洁的，未发现后门。建议继续使用，但仍需定期检测。</p>
                  </div>
                </div>

                <div v-else-if="taskData.defenseResult === 'infected'" class="recommendation-item danger">
                  <i class="el-icon-warning"></i>
                  <div>
                    <strong>发现后门威胁</strong>
                    <p>模型存在后门风险，建议立即停用并采取净化措施。可选择神经元修剪或重训练等方法。</p>
                  </div>
                </div>

                <div v-else-if="taskData.defenseResult === 'purified'" class="recommendation-item warning">
                  <i class="el-icon-check"></i>
                  <div>
                    <strong>净化完成</strong>
                    <p>模型已成功净化，建议进行性能测试确认功能正常后投入使用。</p>
                  </div>
                </div>

                <div class="recommendation-item info">
                  <i class="el-icon-info"></i>
                  <div>
                    <strong>持续监控</strong>
                    <p>建议建立定期检测机制，对模型进行周期性安全评估。</p>
                  </div>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">关闭</el-button>
      <el-button v-if="isTaskCompleted" type="primary" @click="refreshData">刷新数据</el-button>
    </div>
  </el-dialog>
</template>

<script>
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
    }
  },
  data() {
    return {
      activeTab: 'risk',
      riskChart: null,
      performanceChart: null
    }
  },
  computed: {
    isTaskCompleted() {
      return this.taskData && this.taskData.status === 'success' && this.taskData.defenseResult
    },
    riskAssessmentData() {
      if (!this.taskData || !this.taskData.riskAssessment) return null
      try {
        return typeof this.taskData.riskAssessment === 'string'
          ? JSON.parse(this.taskData.riskAssessment)
          : this.taskData.riskAssessment
      } catch {
        return null
      }
    },
    detectionReportData() {
      if (!this.taskData || !this.taskData.detectionReport) return null
      try {
        return typeof this.taskData.detectionReport === 'string'
          ? JSON.parse(this.taskData.detectionReport)
          : this.taskData.detectionReport
      } catch {
        return null
      }
    },
    performanceMetricsData() {
      if (!this.taskData || !this.taskData.performanceMetrics) return null
      try {
        return typeof this.taskData.performanceMetrics === 'string'
          ? JSON.parse(this.taskData.performanceMetrics)
          : this.taskData.performanceMetrics
      } catch {
        return null
      }
    },
    hasPerformanceData() {
      return this.performanceMetricsData &&
        this.performanceMetricsData.original_accuracy !== undefined &&
        this.performanceMetricsData.purified_accuracy !== undefined
    },
    performanceTableData() {
      if (!this.hasPerformanceData) return []

      const data = this.performanceMetricsData
      const result = []

      if (data.original_accuracy !== undefined && data.purified_accuracy !== undefined) {
        const change = ((data.purified_accuracy - data.original_accuracy) * 100).toFixed(2)
        result.push({
          metric: '准确率',
          original: `${(data.original_accuracy * 100).toFixed(1)}%`,
          after: `${(data.purified_accuracy * 100).toFixed(1)}%`,
          change: `${change > 0 ? '+' : ''}${change}%`,
          changeClass: change >= 0 ? 'positive-change' : 'negative-change'
        })
      }

      if (data.inference_time_ms !== undefined) {
        result.push({
          metric: '推理时间',
          original: `${data.inference_time_ms}ms`,
          after: `${data.inference_time_ms}ms`,
          change: '0ms',
          changeClass: 'no-change'
        })
      }

      return result
    }
  },
  watch: {
    visible(val) {
      if (val && this.isTaskCompleted) {
        this.$nextTick(() => {
          this.initCharts()
        })
      } else {
        this.destroyCharts()
      }
    }
  },
  beforeDestroy() {
    this.destroyCharts()
  },
  methods: {
    // 获取风险评估数据
    getRiskData(key, defaultValue) {
      return this.riskAssessmentData ? (this.riskAssessmentData[key] || defaultValue) : defaultValue
    },

    // 获取检测报告数据
    getDetectionData(key, defaultValue) {
      return this.detectionReportData ? (this.detectionReportData[key] || defaultValue) : defaultValue
    },

    // 获取算法名称
    getAlgorithmName(algorithmId) {
      return `防御算法-${algorithmId}` // TODO: 从缓存获取真实名称
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

    // 获取风险等级样式类
    getRiskLevelClass(level) {
      const classMap = {
        'very_low': 'very-low-risk',
        'low': 'low-risk',
        'medium': 'medium-risk',
        'high': 'high-risk',
        'very_high': 'very-high-risk'
      }
      return classMap[level] || 'unknown-risk'
    },

    // 获取风险等级标签
    getRiskLevelLabel(level) {
      const labelMap = {
        'very_low': '很低',
        'low': '低',
        'medium': '中等',
        'high': '高',
        'very_high': '很高'
      }
      return labelMap[level] || '未知'
    },

    // 获取风险等级描述
    getRiskLevelDescription(level) {
      const descMap = {
        'very_low': '模型安全性很高，可放心使用',
        'low': '模型相对安全，建议定期检测',
        'medium': '存在一定风险，需要谨慎使用',
        'high': '存在较高风险，建议进一步检测',
        'very_high': '存在严重安全威胁，不建议使用'
      }
      return descMap[level] || '风险等级未知'
    },

    // 初始化图表
    initCharts() {
      this.initRiskLevelChart()
      if (this.hasPerformanceData) {
        this.initPerformanceChart()
      }
    },

    // 初始化风险等级图表
    initRiskLevelChart() {
      const chartDom = document.getElementById('riskLevelChart')
      if (!chartDom) return

      this.riskChart = echarts.init(chartDom)
      const confidence = this.getRiskData('confidence', 0.95)
      const backdoorProb = this.getRiskData('backdoor_probability', 0.05)

      const option = {
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
              name: '安全概率',
              itemStyle: { color: '#67C23A' }
            },
            {
              value: backdoorProb * 100,
              name: '风险概率',
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

    // 初始化性能对比图表
    initPerformanceChart() {
      const chartDom = document.getElementById('performanceChart')
      if (!chartDom) return

      this.performanceChart = echarts.init(chartDom)
      const data = this.performanceMetricsData

      const option = {
        title: {
          text: '防御前后性能对比',
          left: 'center',
          textStyle: { fontSize: 14 }
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' }
        },
        legend: {
          data: ['防御前', '防御后'],
          top: 30
        },
        xAxis: {
          type: 'category',
          data: ['准确率', '推理时间']
        },
        yAxis: {
          type: 'value',
          axisLabel: {
            formatter: function(value) {
              return value < 1 ? (value * 100).toFixed(1) + '%' : value + 'ms'
            }
          }
        },
        series: [
          {
            name: '防御前',
            type: 'bar',
            data: [
              data.original_accuracy || 0,
              data.inference_time_ms || 0
            ],
            itemStyle: { color: '#409EFF' }
          },
          {
            name: '防御后',
            type: 'bar',
            data: [
              data.purified_accuracy || data.original_accuracy || 0,
              data.inference_time_ms || 0
            ],
            itemStyle: { color: '#67C23A' }
          }
        ]
      }

      this.performanceChart.setOption(option)
    },

    // 销毁图表
    destroyCharts() {
      if (this.riskChart) {
        this.riskChart.dispose()
        this.riskChart = null
      }
      if (this.performanceChart) {
        this.performanceChart.dispose()
        this.performanceChart = null
      }
    },

    // 导出报告
    exportReport() {
      const reportData = {
        taskName: this.taskData.taskName,
        defenseResult: this.taskData.defenseResult,
        riskAssessment: this.riskAssessmentData,
        detectionReport: this.detectionReportData,
        performanceMetrics: this.performanceMetricsData,
        exportTime: new Date().toLocaleString()
      }

      const blob = new Blob([JSON.stringify(reportData, null, 2)], {
        type: 'application/json'
      })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `风险评估报告_${this.taskData.taskName}_${new Date().getTime()}.json`
      link.click()
      URL.revokeObjectURL(url)

      this.$message.success('报告导出成功')
    },

    // 刷新数据
    refreshData() {
      this.$emit('refresh')
      this.$message.success('数据已刷新')
    },

    // 关闭对话框
    handleClose() {
      this.$emit('update:visible', false)
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

.info-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-item {
  margin-bottom: 10px;
}

.info-item .label {
  color: #606266;
  margin-right: 8px;
}

.info-item .value {
  color: #303133;
  font-weight: 500;
}

.metric-card {
  height: 280px;
}

.risk-overview {
  display: flex;
  justify-content: space-around;
  align-items: center;
  height: 200px;
}

.risk-item {
  text-align: center;
}

.risk-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 10px;
}

.risk-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.risk-value.confidence {
  color: #67C23A;
}

.risk-value.backdoor-prob {
  color: #F56C6C;
}

.very-low-risk { color: #67C23A; }
.low-risk { color: #95D475; }
.medium-risk { color: #E6A23C; }
.high-risk { color: #F78989; }
.very-high-risk { color: #F56C6C; }

.report-content {
  padding: 20px 0;
}

.report-content h4 {
  margin-bottom: 15px;
  color: #303133;
}

.report-content h5 {
  margin: 15px 0 10px 0;
  color: #606266;
}

.suspicious-info {
  margin-top: 15px;
}

.trigger-info {
  margin-top: 15px;
}

.recommendations {
  margin-top: 15px;
}

.recommendation-item {
  display: flex;
  align-items: flex-start;
  padding: 15px;
  margin-bottom: 15px;
  border-radius: 4px;
  border-left: 4px solid;
}

.recommendation-item.success {
  background-color: #f0f9ff;
  border-left-color: #67C23A;
}

.recommendation-item.danger {
  background-color: #fef0f0;
  border-left-color: #F56C6C;
}

.recommendation-item.warning {
  background-color: #fdf6ec;
  border-left-color: #E6A23C;
}

.recommendation-item.info {
  background-color: #f4f4f5;
  border-left-color: #909399;
}

.recommendation-item i {
  font-size: 20px;
  margin-right: 12px;
  margin-top: 2px;
}

.recommendation-item strong {
  display: block;
  margin-bottom: 5px;
  color: #303133;
}

.recommendation-item p {
  margin: 0;
  color: #606266;
  line-height: 1.5;
}

.positive-change {
  color: #67C23A;
}

.negative-change {
  color: #F56C6C;
}

.no-change {
  color: #909399;
}

.dialog-footer {
  text-align: right;
}
</style>
