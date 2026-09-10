<template>
  <div class="app-container">
    <!-- 上半部分：系统监控 -->
    <SystemMonitor />

    <!-- 下半部分：任务列表 -->
    <div class="task-section">
      <!--工具栏-->
      <div class="head-container">
        <div class="crud-opts">
          <el-button-group class="crud-opts-right">
            <el-button size="mini" plain type="info" icon="el-icon-search" @click="toggleSearch">
              搜索
            </el-button>
            <el-button size="mini" icon="el-icon-refresh" @click="refreshData">
              刷新
            </el-button>
          </el-button-group>
        </div>
        <!-- 筛选条件 -->
        <div v-show="searchToggle" class="crud-query-item">
          <el-form ref="query" :model="query" size="small" :inline="true" label-width="68px">
            <el-form-item label="任务名称" prop="blurry">
              <el-input
                v-model="query.blurry"
                clearable
                placeholder="输入任务名称搜索"
                style="width: 200px;"
                @keyup.enter.native="loadData"
              />
            </el-form-item>
            <el-form-item label="任务类型" prop="taskType">
              <el-select v-model="query.taskType" clearable placeholder="请选择" class="filter-item" style="width: 130px">
                <el-option label="训练前防御" value="pre_defense" />
              </el-select>
            </el-form-item>
            <el-form-item label="任务状态" prop="status">
              <el-select v-model="query.status" clearable placeholder="请选择" class="filter-item" style="width: 130px">
                <el-option label="全部状态" value="" />
                <el-option label="等待中" value="pending" />
                <el-option label="运行中" value="running" />
                <el-option label="成功" value="success" />
                <el-option label="失败" value="failed" />
                <el-option label="已取消" value="canceled" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="success" size="mini" @click="loadData">查询</el-button>
              <el-button type="warning" size="mini" @click="resetQuery">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div class="executor-status-bar">
        <div class="executor-status-title">
          <i class="el-icon-s-operation"></i>
          <span>任务执行池</span>
          <el-tag
            size="mini"
            :type="executorStatus && executorStatus.acceptingTasks ? 'success' : 'danger'"
          >
            {{ executorStatus && executorStatus.acceptingTasks ? '可接收任务' : '已停止接收' }}
          </el-tag>
        </div>
        <div v-if="executorStatus" class="executor-status-items">
          <span>
            活跃线程
            <strong>{{ executorStatus.activeCount || 0 }}/{{ executorStatus.maxPoolSize || 0 }}</strong>
          </span>
          <span>
            排队任务
            <strong>{{ executorStatus.queueSize || 0 }}/{{ executorStatus.queueCapacity || 0 }}</strong>
          </span>
          <span>
            已完成
            <strong>{{ executorStatus.completedTaskCount || 0 }}</strong>
          </span>
          <el-tag size="mini" :type="executorStatus.saturated ? 'danger' : 'info'">
            {{ executorStatus.saturated ? '执行池已饱和' : '运行正常' }}
          </el-tag>
        </div>
        <span v-else class="executor-status-placeholder">正在获取执行池状态...</span>
      </div>

      <!--表格渲染-->
      <el-table
        ref="table"
        v-loading="loading"
        :data="pagedTaskList"
        style="width: 100%;"
        @row-dblclick="handleRowDoubleClick"
        :header-cell-style="{background:'#fafafa',color:'#606266'}"
        stripe
      >
        <el-table-column :show-overflow-tooltip="true" prop="taskName" label="任务名称" min-width="180px">
          <template slot-scope="scope">
            <el-link type="primary" @click="showTaskDetail(scope.row)">
              {{ scope.row.taskName }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column prop="taskType" label="任务类型" width="130px">
          <template slot-scope="scope">
            <el-tag :type="getTaskTypeTagType(scope.row.taskType)" size="small">
              <i :class="getTaskTypeIcon(scope.row.taskType)" style="margin-right: 5px;"></i>
              {{ formatTaskType(scope.row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="description" label="任务描述" width="100px">
          <template slot-scope="scope">
            <el-tooltip :content="scope.row.description || '无描述'" placement="top">
              <el-button type="text" size="small" icon="el-icon-info">描述</el-button>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column prop="algorithmName" label="算法" width="120px">
          <template slot-scope="scope">
            {{ scope.row.algorithmName || '-' }}
          </template>
        </el-table-column>

        <el-table-column prop="status" label="任务状态" width="100px">
          <template slot-scope="scope">
            <el-tag size="mini" :type="getStatusTagType(scope.row.status)">
              {{ getStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="progress" label="进度" width="140px">
          <template slot-scope="scope">
            <el-progress
              :percentage="scope.row.progress || 0"
              :status="getProgressStatus(scope.row.status)"
              :stroke-width="8"
              :show-text="true"
            />
          </template>
        </el-table-column>

        <el-table-column prop="startTime" label="开始时间" width="150px">
          <template slot-scope="scope">
            {{ formatDateTime(scope.row.startTime) }}
          </template>
        </el-table-column>

        <el-table-column label="运行时长" width="120px">
          <template slot-scope="scope">
            {{ calculateDuration(scope.row.startTime, scope.row.endTime) }}
          </template>
        </el-table-column>

        <el-table-column prop="createBy" label="创建人" width="80px">
          <template slot-scope="scope">
            {{ scope.row.createBy || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="80px" align="center" fixed="right">
          <template slot-scope="scope">
            <div class="operation-buttons">
              <!-- 详情按钮 -->
              <el-button
                size="mini"
                type="warning"
                icon="el-icon-view"
                @click="showTaskDetail(scope.row)"
                title="查看详情"
              >
              </el-button>
              <!-- 跳转按钮 -->
<!--              <el-button-->
<!--                size="mini"-->
<!--                type="primary"-->
<!--                icon="el-icon-link"-->
<!--                @click="navigateToTask(scope.row)"-->
<!--                title="跳转到任务页面"-->
<!--              >-->
<!--              </el-button>-->
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页组件 -->
      <div class="pagination-container">
        <el-pagination
          :current-page="currentPage"
          :page-sizes="[10, 20, 50, 100]"
          :page-size="pageSize"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>

      <!-- 无数据提示 -->
      <div v-if="!loading && pagedTaskList.length === 0" class="no-data">
        <i class="el-icon-info" style="font-size: 48px; color: #C0C4CC;"></i>
        <p style="color: #909399; margin-top: 10px;">暂无任务数据</p>
      </div>
    </div>

    <!-- 任务详情弹窗 -->
    <component
      :is="currentDetailComponent"
      v-if="detailDialogVisible"
      :visible.sync="detailDialogVisible"
      :task-data="selectedTask"
      :dataset-cache="datasetCache"
      :algorithm-cache="algorithmCache"
      :template-cache="templateCache"
      @refresh="loadData"
      @close="closeDetailDialog"
    />
  </div>
</template>

<script>
import SystemMonitor from './module/systemMonitor'
import { getAllTasks, getExecutorStatus } from '@/api/system/taskProgress'
import { getAllDataset } from '@/api/system/dataset'
import { getAllAlgorithm } from '@/api/system/algorithm'
import { getAllHyper } from '@/api/system/hyper'
import PreDefenseTaskDetailDialog from '@/views/system/preDefense/taskDetailDialog'

export default {
  name: 'TaskProgress',
  components: {
    SystemMonitor,
    PreDefenseTaskDetailDialog,
  },
  data() {
    return {
      loading: false,
      allTaskList: [],
      executorStatus: null,
      total: 0,
      currentPage: 1,
      pageSize: 20,
      searchToggle: false,
      query: {
        blurry: '',
        taskType: 'pre_defense',
        status: ''
      },
      detailDialogVisible: false,
      selectedTask: null,
      currentDetailComponent: null,
      // 缓存数据
      datasetCache: {},
      algorithmCache: {},
      templateCache: {},
      pollTimer: null
    }
  },
  computed: {
    pagedTaskList() {
      const start = (this.currentPage - 1) * this.pageSize
      return this.allTaskList.slice(start, start + this.pageSize)
    }
  },
  created() {
    this.loadAllCacheData()
    this.loadData()
    this.loadExecutorStatus()
    this.startPolling()
  },
  beforeDestroy() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer)
      this.pollTimer = null
    }
  },
  methods: {
    // 加载任务数据
    loadData() {
      this.loading = true
      getAllTasks(this.query.status, this.query.taskType, this.query.blurry).then(res => {
        this.allTaskList = Array.isArray(res) ? res : []
        this.total = this.allTaskList.length
        const maxPage = Math.max(1, Math.ceil(this.total / this.pageSize))
        if (this.currentPage > maxPage) {
          this.currentPage = maxPage
        }
      }).catch(error => {
        console.error('获取任务数据失败:', error)
        this.$message.error('获取任务数据失败')
        this.allTaskList = []
        this.total = 0
      }).finally(() => {
        this.loading = false
      })
    },

    // 刷新数据
    refreshData() {
      this.loadData()
      this.loadExecutorStatus()
      this.$message.success('数据刷新成功')
    },

    loadExecutorStatus() {
      getExecutorStatus().then(response => {
        this.executorStatus = response && response.data ? response.data : response
      }).catch(error => {
        console.error('获取执行池状态失败:', error)
        this.executorStatus = null
      })
    },

    // 切换搜索面板
    toggleSearch() {
      this.searchToggle = !this.searchToggle
    },

    // 重置查询条件
    resetQuery() {
      this.query = {
        blurry: '',
        taskType: 'pre_defense',
        status: ''
      }
      this.currentPage = 1
      this.loadData()
    },

    // 分页大小变化
    handleSizeChange(val) {
      this.pageSize = val
      this.currentPage = 1
    },

    // 当前页变化
    handleCurrentChange(val) {
      this.currentPage = val
    },

    // 显示任务详情
    showTaskDetail(task) {
      this.selectedTask = task
      if (task.taskType !== 'pre_defense') {
        this.$message.warning('当前平台仅展示训练前 LDD 清洗任务')
        return
      }
      this.currentDetailComponent = 'PreDefenseTaskDetailDialog'
      this.detailDialogVisible = true
    },

    // 关闭详情弹窗
    closeDetailDialog() {
      this.detailDialogVisible = false
      this.selectedTask = null
      this.currentDetailComponent = null
    },

    // 双击行显示详情
    handleRowDoubleClick(row) {
      this.showTaskDetail(row)
    },

    // 跳转到对应的任务页面
    navigateToTask(task) {
      if (task.taskType !== 'pre_defense') {
        this.$message.warning('当前平台仅展示训练前 LDD 清洗任务')
        return
      }

      // 跳转到对应页面
      this.$router.push({
        path: '/system/preDefense',
        query: { taskId: task.taskId }
      })
    },

    // 加载所有缓存数据
    loadAllCacheData() {
      // 加载数据集缓存
      getAllDataset().then(response => {
        const datasets = response.data || response
        if (Array.isArray(datasets)) {
          datasets.forEach(dataset => {
            this.datasetCache[dataset.datasetId] = dataset.datasetName
          })
        }
      }).catch(err => {
        console.error('加载数据集缓存失败:', err)
      })

      // 当前演示范围只加载 LDD 算法
      this.loadAlgorithmCache()

      // 加载模板缓存
      getAllHyper().then(response => {
        const templates = response.data || response
        if (Array.isArray(templates)) {
          templates.forEach(template => {
            this.templateCache[template.templateId] = template.templateName
          })
        }
      }).catch(err => {
        console.error('加载模板缓存失败:', err)
      })
    },

    // 加载算法缓存（分阶段加载，确保覆盖所有类型）
    async loadAlgorithmCache() {
      try {
        const allAlgorithmsResponse = await getAllAlgorithm()
        const allAlgorithms = (allAlgorithmsResponse.data || allAlgorithmsResponse || [])
          .filter(alg => alg.implementationPath === 'vab://pre-defense/ldd')
        allAlgorithms.forEach(alg => {
          this.algorithmCache[alg.algorithmId] = alg.algorithmName
        })
        if (allAlgorithms.length > 0) return
      } catch (error) {
        console.log('getAllAlgorithm失败，尝试分阶段加载:', error)
      }

      // 方法2：如果getAllAlgorithm失败，分阶段加载
      try {
        const { getAlgorithmsByStage } = await import('@/api/system/algorithm')

        // 加载训练前防御算法
        const preAlgorithms = await getAlgorithmsByStage('pre')
        const preAlgs = preAlgorithms.data || preAlgorithms
        if (Array.isArray(preAlgs)) {
          preAlgs.forEach(alg => {
            this.algorithmCache[alg.algorithmId] = alg.algorithmName
          })
        }

      } catch (err) {
        console.error('加载算法缓存失败:', err)
      }
    },

    // 格式化任务类型
    formatTaskType(taskType) {
      const typeMap = {
        'pre_defense': '训练前防御',
        'during_defense': '训练中防御',
        'post_defense': '训练后防御'
      }
      return typeMap[taskType] || taskType
    },

    // 获取任务类型标签样式
    getTaskTypeTagType(taskType) {
      const typeMap = {
        'pre_defense': 'warning',
        'during_defense': 'primary',
        'post_defense': 'success'
      }
      return typeMap[taskType] || 'info'
    },

    // 获取任务类型图标
    getTaskTypeIcon(taskType) {
      const iconMap = {
        'pre_defense': 'el-icon-document-remove',
        'during_defense': 'el-icon-cpu',
        'post_defense': 'el-icon-view'
      }
      return iconMap[taskType] || 'el-icon-document'
    },

    // 获取进度状态
    getProgressStatus(status) {
      if (status === 'success') return 'success'
      if (status === 'failed') return 'exception'
      return null
    },

    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'pending': 'info',
        'running': 'warning',
        'success': 'success',
        'failed': 'danger',
        'canceled': 'info'
      }
      return statusMap[status] || 'info'
    },

    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'pending': '等待中',
        'running': '运行中',
        'success': '成功',
        'failed': '失败',
        'canceled': '已取消'
      }
      return statusMap[status] || status
    },

    startPolling() {
      this.pollTimer = setInterval(() => {
        if (this.query.status === '' || this.query.status === 'pending'
          || this.query.status === 'running') {
          this.loadData()
        }
        this.loadExecutorStatus()
      }, 5000)
    },

    // 格式化时间
    formatDateTime(dateTime) {
      if (!dateTime) return '-'
      const date = new Date(dateTime)
      return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      })
    },

    // 计算运行时长
    calculateDuration(startTime, endTime) {
      if (!startTime) return '-'

      const start = new Date(startTime)
      const end = endTime ? new Date(endTime) : new Date()
      const duration = end.getTime() - start.getTime()

      if (duration < 0) return '-'

      const seconds = Math.floor(duration / 1000) % 60
      const minutes = Math.floor(duration / (1000 * 60)) % 60
      const hours = Math.floor(duration / (1000 * 60 * 60)) % 24
      const days = Math.floor(duration / (1000 * 60 * 60 * 24))

      if (days > 0) {
        return `${days}天${hours}时${minutes}分`
      } else if (hours > 0) {
        return `${hours}时${minutes}分`
      } else if (minutes > 0) {
        return `${minutes}分${seconds}秒`
      } else {
        return `${seconds}秒`
      }
    }
  }
}
</script>

<style scoped>
.app-container {
  padding: 20px;
}

.task-section {
  margin-top: 20px;
}

.operation-buttons {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 5px;
}

.operation-buttons .el-button {
  padding: 7px !important;
  margin: 0 !important;
  min-width: 28px;
}

.no-data {
  text-align: center;
  padding: 40px 0;
}

.head-container {
  margin-bottom: 20px;
}

.executor-status-bar {
  min-height: 36px;
  margin: 0 0 12px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #EBEEF5;
  background: #FAFAFA;
  color: #606266;
  font-size: 12px;
}

.executor-status-title,
.executor-status-items {
  display: flex;
  align-items: center;
  gap: 12px;
}

.executor-status-title i {
  color: #409EFF;
  font-size: 14px;
}

.executor-status-items strong {
  color: #303133;
  font-weight: 600;
  margin-left: 4px;
}

.executor-status-placeholder {
  color: #909399;
}

.crud-opts {
  padding: 4px 6px;
  display: flex;
  align-items: center;
}

.crud-opts .crud-label {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.crud-opts-right {
  margin-left: auto;
}

.crud-query-item {
  margin-bottom: 10px;
  background: #F5F7FA;
  padding: 15px;
  border-radius: 5px;
}

.el-table {
  font-size: 13px;
}

.pagination-container {
  margin-top: 20px;
  text-align: right;
}

/* 进度条样式调整 */
.el-progress {
  width: 120px;
}

.el-progress__text {
  font-size: 12px !important;
}

/* 标签样式调整 */
.el-tag {
  border-radius: 4px;
}

::v-deep .el-progress-bar__inner {
  transition: width 0.6s ease;
}

/* 系统监控图表大小调整 */
::v-deep .charts-section {
  flex: 1;
  display: flex;
  flex-direction: row !important;
  gap: 20px;
  height: 100%;
  max-width: 600px;
}

::v-deep .chart-container {
  flex: 1;
  height: 100% !important;
  min-height: auto !important;
  display: flex;
  flex-direction: column;
}

::v-deep .monitor-card {
  min-height: 320px !important;
}

::v-deep .chart-title {
  font-size: 14px;
  font-weight: 500;
  color: #666;
  margin-bottom: 10px;
  padding-bottom: 5px;
  border-bottom: 1px solid #EBEEF5;
  flex-shrink: 0;
}
</style>
