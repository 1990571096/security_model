<template>
  <el-dialog
    title="任务详情"
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
        <el-descriptions-item label="数据集">
          {{ getDatasetName(taskData.datasetId) }}
        </el-descriptions-item>
        <el-descriptions-item label="清洗算法">
          {{ getAlgorithmName(taskData.algorithmId) }}
        </el-descriptions-item>
        <el-descriptions-item label="超参数模板">
          {{ getTemplateName(taskData.hyperId) }}
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ taskData.progress || 0 }}%
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ taskData.startTime || '未开始' }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ taskData.endTime || '未结束' }}
        </el-descriptions-item>
        <!-- 显示清洗统计信息（从JSON解析） -->
        <el-descriptions-item v-if="sampleStats.cleaned_count" label="清洗后样本数">
          {{ sampleStats.cleaned_count }}
        </el-descriptions-item>
        <el-descriptions-item v-if="sampleStats.removed_count" label="移除样本数">
          {{ sampleStats.removed_count }}
        </el-descriptions-item>
        <el-descriptions-item v-if="sampleStats.retention_rate" label="保留率">
          {{ Math.round(sampleStats.retention_rate * 100) }}%
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
        <!-- 显示完整清洗结果 -->
        <el-descriptions-item v-if="cleaningResultData" label="清洗结果详情" :span="2">
          <el-card class="parameter-card">
            <div style="max-height: 200px; overflow-y: auto;">
              <pre class="parameter-json">{{ formatJSON(cleaningResultData) }}</pre>
            </div>
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
        <p style="text-align: center; margin-top: 10px; color: #666;">正在执行清洗任务，请稍候...</p>
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
          启动任务
        </el-button>
        <el-button
          v-if="taskData.status === 'success' && taskData.outputDatasetId"
          type="success"
          size="small"
          icon="el-icon-download"
          @click="downloadResult"
        >
          下载清洗后数据集
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
          v-if="taskData.status === 'success'"
          type="info"
          size="small"
          icon="el-icon-document"
          @click="viewCleaningReport"
        >
          查看清洗报告
        </el-button>
      </div>

      <!-- 清洗结果统计图表 -->
      <div v-if="taskData.status === 'success' && hasValidStats" style="margin-top: 20px;">
        <el-divider content-position="left">清洗结果统计</el-divider>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-check stat-icon" style="color: #67C23A;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ sampleStats.cleaned_count || 0 }}</div>
                  <div class="stat-label">清洗后样本</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-close stat-icon" style="color: #F56C6C;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ sampleStats.removed_count || 0 }}</div>
                  <div class="stat-label">移除样本</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-data-analysis stat-icon" style="color: #409EFF;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ getCleaningRate() }}%</div>
                  <div class="stat-label">清洗效率</div>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 清洗结果可视化图表 -->
        <div style="margin-top: 15px;">
          <el-row :gutter="20">
            <el-col :span="12">
              <el-card>
                <div slot="header">
                  <span>清洗结果可视化</span>
                </div>
                <div id="cleaningChart" style="width: 100%; height: 300px;"></div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card>
                <div slot="header">
                  <span>清洗统计信息</span>
                </div>
                <div v-if="hasExtendedStats" class="stats-info">
                  <div class="stat-row">
                    <span class="stat-key">原始样本数:</span>
                    <span class="stat-value">{{ sampleStats.original_count || (sampleStats.cleaned_count + sampleStats.removed_count) }}</span>
                  </div>
                  <div class="stat-row">
                    <span class="stat-key">保留样本数:</span>
                    <span class="stat-value">{{ sampleStats.cleaned_count }}</span>
                  </div>
                  <div class="stat-row">
                    <span class="stat-key">移除样本数:</span>
                    <span class="stat-value">{{ sampleStats.removed_count }}</span>
                  </div>
                  <div class="stat-row">
                    <span class="stat-key">保留率:</span>
                    <span class="stat-value">{{ Math.round((sampleStats.retention_rate || getCleaningRate() / 100) * 100) }}%</span>
                  </div>
                  <div v-if="sampleStats.average_confidence" class="stat-row">
                    <span class="stat-key">平均置信度:</span>
                    <span class="stat-value">{{ Math.round(sampleStats.average_confidence * 100) }}%</span>
                  </div>
                </div>
                <div v-else style="text-align: center; padding: 50px; color: #999;">
                  <i class="el-icon-info"></i>
                  <p>暂无详细统计信息</p>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </div>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">关闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { startTask, getTaskProgress, downloadCleanedDataset, getCleaningReport } from '@/api/system/preDefense'
import { getParameterSchema } from '@/api/system/hyper'
import { get as getDataset } from '@/api/system/datasetDetail'
import { getHyper } from '@/api/system/hyper'
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
      chart: null,
      templateConfig: null
    }
  },
  computed: {
    // 解析清洗结果JSON
    cleaningResultData() {
      if (!this.taskData || !this.taskData.cleaningResult) return null
      try {
        return typeof this.taskData.cleaningResult === 'string'
          ? JSON.parse(this.taskData.cleaningResult)
          : this.taskData.cleaningResult
      } catch (e) {
        console.error('解析清洗结果失败:', e)
        return null
      }
    },

    // 样本统计数据（去掉可选链操作符）
    sampleStats() {
      if (!this.cleaningResultData) return {}

      // 从sample_stats中获取
      if (this.cleaningResultData.sample_stats) {
        return this.cleaningResultData.sample_stats
      }

      // 返回空对象
      return {}
    },

    // 损失分布数据（去掉可选链操作符）
    lossDistribution() {
      if (!this.cleaningResultData) return {}
      return this.cleaningResultData.loss_distribution || {}
    },

    // 是否有有效的统计数据
    hasValidStats() {
      return this.sampleStats.cleaned_count > 0 || this.sampleStats.removed_count > 0
    },

    // 是否有扩展统计信息
    hasExtendedStats() {
      return this.sampleStats.cleaned_count || this.sampleStats.removed_count
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
    if (this.chart) {
      this.chart.dispose()
    }
  },
  methods: {
    // 初始化任务数据
    initTaskData() {
      if (this.taskData.status === 'running') {
        this.startProgressMonitoring()
      }

      if (this.taskData.status === 'success' && this.hasValidStats) {
        this.$nextTick(() => {
          this.initChart()
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

      this.$confirm('确定要启动该清洗任务吗？', '提示', {
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
          this.$message.error('任务启动失败：' + (err.message || '未知错误'))
        })
      })
    },

    // 重新启动任务
    retryTask() {
      this.startTask()
    },

    // 下载清洗结果
    downloadResult() {
      if (!this.taskData || !this.taskData.outputDatasetId) return

      downloadCleanedDataset(this.taskData.taskId).then(() => {
        this.$message.success('开始下载清洗后数据集')
      }).catch(err => {
        this.$message.error('下载失败：' + (err.message || '未知错误'))
      })
    },

    // 查看清洗报告
    viewCleaningReport() {
      if (!this.taskData) return

      getCleaningReport(this.taskData.taskId).then(response => {
        const report = response.data || response
        // 这里可以打开一个新的对话框显示详细报告
        this.$alert(JSON.stringify(report, null, 2), '清洗报告', {
          confirmButtonText: '确定',
          customClass: 'report-dialog'
        })
      }).catch(err => {
        this.$message.error('获取报告失败：' + (err.message || '未知错误'))
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

    // 计算清洗效率（简化版，只支持新JSON格式）
    getCleaningRate() {
      const stats = this.sampleStats

      // 优先使用retention_rate
      if (stats.retention_rate) {
        return Math.round(stats.retention_rate * 100)
      }

      // 从cleaned_count和removed_count计算
      if (stats.cleaned_count || stats.removed_count) {
        const cleanedCount = stats.cleaned_count || 0
        const removedCount = stats.removed_count || 0
        const total = cleanedCount + removedCount
        if (total > 0) {
          return Math.round((cleanedCount / total) * 100)
        }
      }

      return 0
    },

    // 初始化图表（简化版）
    initChart() {
      const chartDom = document.getElementById('cleaningChart')
      if (!chartDom) return

      this.chart = echarts.init(chartDom)

      // 获取统计数据
      const cleanedCount = this.sampleStats.cleaned_count || 0
      const removedCount = this.sampleStats.removed_count || 0

      if (cleanedCount === 0 && removedCount === 0) {
        // 显示空数据提示
        this.chart.setOption({
          title: {
            text: '暂无清洗数据',
            left: 'center',
            top: 'center',
            textStyle: { color: '#999', fontSize: 16 }
          }
        })
        return
      }

      const option = {
        title: {
          text: '清洗结果对比',
          left: 'center',
          textStyle: { fontSize: 16 }
        },
        tooltip: {
          trigger: 'item',
          formatter: '{a} <br/>{b}: {c} ({d}%)'
        },
        legend: {
          orient: 'vertical',
          left: 'left'
        },
        series: [{
          name: '样本分布',
          type: 'pie',
          radius: '60%',
          data: [
            {
              value: cleanedCount,
              name: '保留样本',
              itemStyle: { color: '#67C23A' }
            },
            {
              value: removedCount,
              name: '移除样本',
              itemStyle: { color: '#F56C6C' }
            }
          ],
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          }
        }]
      }

      this.chart.setOption(option)
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

.stats-info {
  padding: 20px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.stat-row:last-child {
  border-bottom: none;
}

.stat-key {
  font-weight: 500;
  color: #606266;
}

.stat-value {
  font-weight: bold;
  color: #303133;
}

::v-deep .el-descriptions-item__label {
  width: 120px;
}

::v-deep .report-dialog {
  width: 80%;
}

::v-deep .report-dialog .el-message-box__content {
  max-height: 400px;
  overflow-y: auto;
}
</style>
