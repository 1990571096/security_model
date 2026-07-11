<template>
  <div class="task-list">
    <el-card class="box-card">
      <div slot="header" class="clearfix">
        <span style="font-weight: bold; color: #666; font-size: 15px">运行中的任务</span>
        <div style="float: right;">
          <!-- 筛选工具 -->
          <el-select v-model="filterStatus" placeholder="状态筛选" size="small" clearable @change="handleFilterChange">
            <el-option label="全部状态" value="" />
            <el-option label="等待中" value="pending" />
            <el-option label="运行中" value="running" />
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
          </el-select>

          <el-select v-model="filterType" placeholder="类型筛选" size="small" clearable @change="handleFilterChange" style="margin-left: 10px;">
            <el-option label="全部类型" value="" />
            <el-option label="训练前防御" value="pre_defense" />
            <el-option label="训练中防御" value="during_defense" />
            <el-option label="训练后防御" value="post_defense" />
          </el-select>

          <el-button icon="el-icon-refresh" size="small" @click="refreshData" style="margin-left: 10px;">
            刷新
          </el-button>
        </div>
      </div>

      <!-- 任务列表表格 -->
      <el-table
        :data="taskList"
        v-loading="loading"
        style="width: 100%"
        :header-cell-style="{background:'#fafafa',color:'#606266'}"
        stripe
      >
        <el-table-column prop="taskName" label="任务名称" min-width="150">
          <template slot-scope="scope">
            <el-link type="primary" @click="showTaskDetail(scope.row)">
              {{ scope.row.taskName }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column prop="taskType" label="任务类型" width="120">
          <template slot-scope="scope">
            <el-tag :type="getTaskTypeTagType(scope.row.taskType)" size="small">
              <i :class="getTaskTypeIcon(scope.row.taskType)" style="margin-right: 5px;"></i>
              {{ formatTaskType(scope.row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="getStatusTagType(scope.row.status)" size="small">
              {{ formatStatus(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="progress" label="进度" width="120">
          <template slot-scope="scope">
            <el-progress
              :percentage="scope.row.progress || 0"
              :status="scope.row.status === 'failed' ? 'exception' :
                      scope.row.status === 'success' ? 'success' : ''"
              :show-text="true"
              :stroke-width="8"
            />
          </template>
        </el-table-column>

        <el-table-column prop="algorithmName" label="算法" min-width="120">
          <template slot-scope="scope">
            <span>{{ scope.row.algorithmName || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="startTime" label="开始时间" width="140">
          <template slot-scope="scope">
            <span>{{ formatDateTime(scope.row.startTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="运行时长" width="120">
          <template slot-scope="scope">
            <span>{{ calculateDuration(scope.row.startTime, scope.row.endTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="createBy" label="创建人" width="100">
          <template slot-scope="scope">
            <span>{{ scope.row.createBy || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right">
          <template slot-scope="scope">
            <el-button
              size="mini"
              type="text"
              icon="el-icon-view"
              @click="showTaskDetail(scope.row)"
            >
              详情
            </el-button>
            <el-button
              size="mini"
              type="text"
              icon="el-icon-link"
              @click="navigateToTask(scope.row)"
            >
              跳转
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 无数据提示 -->
      <div v-if="!loading && taskList.length === 0" class="no-data">
        <i class="el-icon-info" style="font-size: 48px; color: #C0C4CC;"></i>
        <p style="color: #909399; margin-top: 10px;">暂无运行中的任务</p>
      </div>
    </el-card>

    <!-- 任务详情弹窗 -->
    <component
      :is="currentDetailComponent"
      v-if="detailDialogVisible"
      :dialog="detailDialogVisible"
      :data="selectedTask"
      @close="closeDetailDialog"
    />
  </div>
</template>

<script>
import { getRunningTasks, getAllTasks } from '@/api/system/taskProgress'
import PreDefenseTaskDetailDialog from '@/views/system/preDefense/taskDetailDialog'
import DuringDefenseTaskDetailDialog from '@/views/system/duringDefense/taskDetailDialog'
import PostDefenseTaskDetailDialog from '@/views/system/postDefense/taskDetailDialog'

export default {
  name: 'TaskList',
  components: {
    PreDefenseTaskDetailDialog,
    DuringDefenseTaskDetailDialog,
    PostDefenseTaskDetailDialog
  },
  data() {
    return {
      taskList: [],
      loading: false,
      filterStatus: 'running', // 默认只显示运行中的任务
      filterType: '',
      refreshTimer: null,
      detailDialogVisible: false,
      selectedTask: null,
      currentDetailComponent: null
    }
  },
  mounted() {
    this.loadTasks()
    this.startAutoRefresh()
  },
  destroyed() {
    this.stopAutoRefresh()
  },
  methods: {
    loadTasks() {
      this.loading = true

      const loadFunction = this.filterStatus === 'running' ? getRunningTasks : getAllTasks
      const params = this.filterStatus === 'running' ? [] : [this.filterStatus, this.filterType]

      loadFunction(...params).then(res => {
        this.taskList = res || []
      }).catch(error => {
        console.error('获取任务列表失败:', error)
        this.$message.error('获取任务列表失败')
        this.taskList = []
      }).finally(() => {
        this.loading = false
      })
    },
    refreshData() {
      this.loadTasks()
      this.$message.success('数据刷新成功')
    },
    handleFilterChange() {
      this.loadTasks()
    },
    startAutoRefresh() {
      // 每15秒自动刷新
      this.refreshTimer = setInterval(() => {
        this.loadTasks()
      }, 15000)
    },
    stopAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
    },
    showTaskDetail(task) {
      this.selectedTask = task
      // 根据任务类型选择对应的详情组件
      switch (task.taskType) {
        case 'pre_defense':
          this.currentDetailComponent = 'PreDefenseTaskDetailDialog'
          break
        case 'during_defense':
          this.currentDetailComponent = 'DuringDefenseTaskDetailDialog'
          break
        case 'post_defense':
          this.currentDetailComponent = 'PostDefenseTaskDetailDialog'
          break
        default:
          this.$message.warning('不支持的任务类型')
          return
      }
      this.detailDialogVisible = true
    },
    closeDetailDialog() {
      this.detailDialogVisible = false
      this.selectedTask = null
      this.currentDetailComponent = null
    },
    navigateToTask(task) {
      let routePath
      switch (task.taskType) {
        case 'pre_defense':
          routePath = '/system/preDefense'
          break
        case 'during_defense':
          routePath = '/system/duringDefense'
          break
        case 'post_defense':
          routePath = '/system/postDefense'
          break
        default:
          this.$message.warning('不支持的任务类型')
          return
      }

      // 跳转到对应页面
      this.$router.push({
        path: routePath,
        query: { taskId: task.taskId }
      })
    },
    formatTaskType(taskType) {
      const typeMap = {
        'pre_defense': '训练前防御',
        'during_defense': '训练中防御',
        'post_defense': '训练后防御'
      }
      return typeMap[taskType] || taskType
    },
    formatStatus(status) {
      const statusMap = {
        'pending': '等待中',
        'running': '运行中',
        'success': '成功',
        'failed': '失败'
      }
      return statusMap[status] || status
    },
    getTaskTypeTagType(taskType) {
      const typeMap = {
        'pre_defense': 'warning',
        'during_defense': 'primary',
        'post_defense': 'success'
      }
      return typeMap[taskType] || 'info'
    },
    getStatusTagType(status) {
      const statusMap = {
        'pending': 'info',
        'running': 'primary',
        'success': 'success',
        'failed': 'danger'
      }
      return statusMap[status] || 'info'
    },
    getTaskTypeIcon(taskType) {
      const iconMap = {
        'pre_defense': 'el-icon-document-remove',
        'during_defense': 'el-icon-cpu',
        'post_defense': 'el-icon-view'
      }
      return iconMap[taskType] || 'el-icon-document'
    },
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
.task-list {
  margin-top: 20px;
}

.box-card {
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.clearfix::after {
  content: "";
  display: table;
  clear: both;
}

.no-data {
  text-align: center;
  padding: 40px 0;
}

.el-table {
  font-size: 13px;
}

.el-table .el-table__body-wrapper {
  max-height: 500px;
  overflow-y: auto;
}

/* 进度条样式调整 */
.el-progress {
  width: 100px;
}

.el-progress__text {
  font-size: 12px !important;
}

/* 标签样式调整 */
.el-tag {
  border-radius: 4px;
}

/* 操作按钮样式 */
.el-button--mini {
  padding: 2px 6px;
  font-size: 12px;
}
</style>
