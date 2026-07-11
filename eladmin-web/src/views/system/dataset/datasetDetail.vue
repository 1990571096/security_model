<template>
  <div>
    <div v-if="!selectedDataset">
      <div class="my-code">点击数据集查看详情</div>
    </div>
    <div v-else>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="数据集名称">
          {{ selectedDataset.datasetName }}
        </el-descriptions-item>
        <el-descriptions-item label="数据集类型">
          <el-tag size="small" :type="getTypeTagType(selectedDataset.datasetType)">
            {{ getTypeLabel(selectedDataset.datasetType) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="解析状态">
          <el-tag size="small" :type="getStatusTagType(selectedDataset.parseStatus)">
            {{ getStatusLabel(selectedDataset.parseStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="启用状态">
          <el-tag size="small" :type="selectedDataset.enabled ? 'success' : 'danger'">
            {{ selectedDataset.enabled ? '启用' : '禁用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="文件名称">
          {{ selectedDataset.fileName || '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="文件大小">
          {{ formatFileSize(selectedDataset.fileSize) }}
        </el-descriptions-item>
        <el-descriptions-item label="样本数量">
          {{ selectedDataset.sampleCount || '未解析' }}
        </el-descriptions-item>
        <el-descriptions-item label="特征数量">
          {{ selectedDataset.featureCount || '未解析' }}
        </el-descriptions-item>
        <el-descriptions-item label="类别数量">
          {{ selectedDataset.classCount || '未解析' }}
        </el-descriptions-item>
        <el-descriptions-item label="解析时间">
          {{ selectedDataset.parseTime || '未解析' }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">
          {{ selectedDataset.createTime }}
        </el-descriptions-item>
        <el-descriptions-item label="数据集描述" :span="2">
          {{ selectedDataset.description || '无描述' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="selectedDataset.parseMessage" label="解析消息" :span="2">
          <el-alert
            :title="selectedDataset.parseMessage"
            :type="selectedDataset.parseStatus === 'failed' ? 'error' : 'info'"
            :closable="false"
            show-icon
          />
        </el-descriptions-item>
      </el-descriptions>

      <!-- 操作按钮区域 -->
      <div style="margin-top: 20px;">
        <el-button
          v-if="selectedDataset.parseStatus === 'pending' && checkPer(['admin','dataset:edit'])"
          type="primary"
          size="small"
          icon="el-icon-refresh"
          @click="startParsing"
        >
          启动解析
        </el-button>
        <el-button
          v-if="selectedDataset.parseStatus === 'success'"
          type="success"
          size="small"
          icon="el-icon-download"
          @click="downloadDataset"
        >
          下载数据集
        </el-button>
        <el-button
          v-if="selectedDataset.parseStatus === 'failed' && checkPer(['admin','dataset:edit'])"
          type="warning"
          size="small"
          icon="el-icon-refresh"
          @click="retryParsing"
        >
          重新解析
        </el-button>
        <el-button
          size="small"
          icon="el-icon-check"
          @click="validateDataset"
        >
          验证可用性
        </el-button>
      </div>

      <!-- 进度条 -->
      <div v-if="selectedDataset.parseStatus === 'parsing'" style="margin-top: 15px;">
        <el-progress :percentage="parseProgress" :status="parseProgress === 100 ? 'success' : null" />
        <p style="text-align: center; margin-top: 10px; color: #666;">正在解析数据集，请稍候...</p>
      </div>

      <!-- 统计图表区域 -->
      <div v-if="selectedDataset.parseStatus === 'success' && selectedDataset.sampleCount" style="margin-top: 20px;">
        <el-divider content-position="left">数据集统计</el-divider>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-files stat-icon" style="color: #409EFF;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ selectedDataset.sampleCount }}</div>
                  <div class="stat-label">总样本数</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-data-analysis stat-icon" style="color: #67C23A;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ selectedDataset.featureCount || 0 }}</div>
                  <div class="stat-label">特征维度</div>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div class="stat-item">
                <i class="el-icon-collection-tag stat-icon" style="color: #E6A23C;"></i>
                <div class="stat-content">
                  <div class="stat-number">{{ selectedDataset.classCount || 0 }}</div>
                  <div class="stat-label">类别数量</div>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </div>
    </div>
  </div>
</template>

<script>
import crudDataset from '@/api/system/dataset'

export default {
  props: {
    permission: {
      type: Object,
      required: true
    }
  },
  data() {
    return {
      selectedDataset: null,
      parseProgress: 0,
      progressTimer: null
    }
  },
  watch: {
    selectedDataset: {
      handler(newVal) {
        if (newVal && newVal.parseStatus === 'parsing') {
          this.startProgressMonitoring()
        } else {
          this.stopProgressMonitoring()
        }
      },
      immediate: true
    }
  },
  beforeDestroy() {
    this.stopProgressMonitoring()
  },
  methods: {
    // 启动解析
    startParsing() {
      if (!this.selectedDataset) return

      this.$confirm('确定要启动数据集解析吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        crudDataset.startParsing(this.selectedDataset.datasetId).then(() => {
          this.$message.success('启动解析成功')
          this.selectedDataset.parseStatus = 'parsing'
          this.startProgressMonitoring()
          this.$emit('refresh')
        }).catch(err => {
          this.$message.error('启动解析失败：' + err.message)
        })
      })
    },
    // 重新解析
    retryParsing() {
      this.startParsing()
    },
    // 下载数据集
    downloadDataset() {
      if (!this.selectedDataset) return

      const link = document.createElement('a')
      link.href = `/api/dataset/download/${this.selectedDataset.datasetId}`
      link.download = this.selectedDataset.fileName || 'dataset.zip'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)

      this.$message.success('开始下载数据集')
    },
    // 验证数据集可用性
    validateDataset() {
      if (!this.selectedDataset) return

      crudDataset.validateDataset(this.selectedDataset.datasetId).then(response => {
        const result = response.data || response
        if (result.available) {
          this.$message.success(result.message || '数据集可用')
        } else {
          this.$message.warning(result.message || '数据集不可用')
        }
      }).catch(err => {
        this.$message.error('验证失败：' + err.message)
      })
    },
    // 开始进度监控
    startProgressMonitoring() {
      this.parseProgress = 10
      this.progressTimer = setInterval(() => {
        if (this.parseProgress < 90) {
          this.parseProgress += Math.random() * 10
        }
      }, 2000)
    },
    // 停止进度监控
    stopProgressMonitoring() {
      if (this.progressTimer) {
        clearInterval(this.progressTimer)
        this.progressTimer = null
      }
      this.parseProgress = 0
    },
    // 格式化文件大小
    formatFileSize(bytes) {
      if (!bytes || bytes === 0) return '未知'
      const sizes = ['Bytes', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(1024))
      return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i]
    },
    // 获取类型标签样式
    getTypeTagType(type) {
      const typeMap = {
        'image': 'primary',
        'text': 'success',
        'tabular': 'warning'
      }
      return typeMap[type] || 'info'
    },
    // 获取类型标签文字
    getTypeLabel(type) {
      const typeMap = {
        'image': '图像数据',
        'text': '文本数据',
        'tabular': '表格数据'
      }
      return typeMap[type] || type
    },
    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'pending': 'info',
        'parsing': 'warning',
        'success': 'success',
        'failed': 'danger'
      }
      return statusMap[status] || 'info'
    },
    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'pending': '等待解析',
        'parsing': '解析中',
        'success': '解析成功',
        'failed': '解析失败'
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
  font-size: 20px;
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

::v-deep .el-descriptions-item__label {
  width: 120px;
}
</style>
