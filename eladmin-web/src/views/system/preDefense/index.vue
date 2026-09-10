<template>
  <div class="app-container">
    <!--工具栏-->
    <div class="head-container">
      <eHeader :permission="permission" />
      <crudOperation :permission="permission" />
    </div>
    <section class="task-overview" aria-label="LDD 任务运行概览">
      <div
        v-for="item in overviewItems"
        :key="item.key"
        class="overview-item"
        :class="'overview-' + item.key"
      >
        <div class="overview-icon">
          <i :class="item.icon" />
        </div>
        <div>
          <div class="overview-label">{{ item.label }}</div>
          <div class="overview-value">{{ taskStatistics[item.key] || 0 }}</div>
        </div>
      </div>
    </section>
    <!--表格渲染-->
    <el-table ref="table" v-loading="crud.loading" :data="crud.data" style="width: 100%;" @selection-change="crud.selectionChangeHandler" @row-dblclick="handleRowDoubleClick">
      <el-table-column type="selection" width="55" />
      <el-table-column :show-overflow-tooltip="true" prop="taskName" label="任务名称" width="150px" />
      <el-table-column prop="description" label="任务描述" width="80px">
        <template slot-scope="scope">
          <el-tooltip :content="scope.row.description || '无描述'" placement="top">
            <el-button type="text" size="small" icon="el-icon-info">描述</el-button>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="datasetName" label="数据集" width="120px">
        <template slot-scope="scope">
          {{ getDatasetName(scope.row.datasetId) }}
        </template>
      </el-table-column>
      <el-table-column prop="algorithmName" label="清洗算法" width="100px">
        <template slot-scope="scope">
          {{ getAlgorithmName(scope.row.algorithmId) }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="任务状态" width="80px">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getStatusTagType(scope.row.status)">
            {{ getStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="progress" label="进度" width="100px">
        <template slot-scope="scope">
          <el-progress
            :percentage="scope.row.progress || 0"
            :status="getProgressStatus(scope.row.status)"
            :stroke-width="6"
            :show-text="false"
          />
          <span style="margin-left: 8px; font-size: 12px;">{{ scope.row.progress || 0 }}%</span>
        </template>
      </el-table-column>
      <el-table-column prop="cleaningRate" label="清洗效率" width="80px">
        <template slot-scope="scope">
          <span v-if="scope.row.status === 'success'" style="color: #67C23A;">
            {{ getCleaningRate(scope.row) }}%
          </span>
          <span v-else style="color: #909399;">--</span>
        </template>
      </el-table-column>
      <el-table-column prop="startTime" label="开始时间" width="140px" />
      <el-table-column prop="endTime" label="结束时间" width="140px" />
      <el-table-column label="操作" width="200px" align="center" fixed="right">
        <template slot-scope="scope">
          <div class="operation-buttons">
            <!-- 详情按钮 - 橘色 -->
            <el-button
              size="mini"
              type="warning"
              icon="el-icon-view"
              @click="showTaskDetail(scope.row)"
            />
            <!-- 启动按钮 - 绿色方框 -->
            <el-button
              v-if="scope.row.status === 'pending' && checkPer(['admin','pre_defense:start'])"
              size="mini"
              type="success"
              icon="el-icon-video-play"
              @click="startTask(scope.row)"
            />
            <el-button
              v-if="scope.row.status === 'running' && checkPer(['admin','pre_defense:start'])"
              size="mini"
              type="danger"
              icon="el-icon-close"
              title="取消任务"
              @click="cancelRunningTask(scope.row)"
            />
            <!-- 标准编辑与删除按钮 -->
            <udOperation
              :data="scope.row"
              :permission="permission"
            />
          </div>
        </template>
      </el-table-column>
    </el-table>
    <!--分页组件-->
    <pagination />
    <!--表单渲染-->
    <eForm />

    <!-- 任务详情对话框 -->
    <taskDetailDialog
      :visible.sync="detailDialogVisible"
      :task-data="selectedTaskForDetail"
      :dataset-cache="datasetCache"
      :algorithm-cache="algorithmCache"
      :template-cache="templateCache"
      @refresh="refreshTaskData"
    />

    <!-- 批量启动按钮 -->
    <div v-if="crud.selections.length > 0" class="batch-operations">
      <el-button
        v-if="checkPer(['admin','pre_defense:start'])"
        type="warning"
        size="small"
        icon="el-icon-video-play"
        @click="batchStartTasks"
      >
        批量启动选中任务 ({{ pendingTasksCount }})
      </el-button>
    </div>
  </div>
</template>

<script>
import crudPreDefense from '@/api/system/preDefense'
import eHeader from './module/header'
import eForm from './module/form'
import taskDetailDialog from './taskDetailDialog'
import { getAlgorithmsByStage } from '@/api/system/algorithm'
import { getAllDataset } from '@/api/system/dataset'
import { getAllHyper } from '@/api/system/hyper'
import { startTask, startBatchTasks, cancelTask as cancelPreDefenseTask, getTaskStatistics } from '@/api/system/preDefense'
import CRUD, { presenter } from '@crud/crud'
import crudOperation from '@crud/CRUD.operation'
import pagination from '@crud/Pagination'
import udOperation from '@crud/UD.operation'

export default {
  name: 'PreDefense',
  components: { eHeader, eForm, taskDetailDialog, udOperation, crudOperation, pagination },
  cruds() {
    return CRUD({
      title: '训练前防御任务',
      url: 'api/pre-defense',
      crudMethod: { ...crudPreDefense },
      idField: 'taskId'
    })
  },
  mixins: [presenter()],
  data() {
    return {
      permission: {
        add: ['admin', 'pre_defense:add'],
        edit: ['admin', 'pre_defense:edit'],
        del: ['admin', 'pre_defense:del']
      },
      detailDialogVisible: false,
      selectedTaskForDetail: null,
      // 缓存数据
      datasetCache: {},
      algorithmCache: {},
      templateCache: {},
      statisticsTimer: null,
      taskStatistics: {
        totalCount: 0,
        pendingCount: 0,
        runningCount: 0,
        successCount: 0,
        failedCount: 0,
        canceledCount: 0
      }
    }
  },
  computed: {
    // 计算等待中的任务数量
    pendingTasksCount() {
      return this.crud.selections.filter(item => item.status === 'pending').length
    },
    overviewItems() {
      return [
        { key: 'totalCount', label: '全部任务', icon: 'el-icon-s-data' },
        { key: 'pendingCount', label: '待执行', icon: 'el-icon-time' },
        { key: 'runningCount', label: '运行中', icon: 'el-icon-loading' },
        { key: 'successCount', label: '执行成功', icon: 'el-icon-circle-check' },
        { key: 'failedCount', label: '执行失败', icon: 'el-icon-circle-close' },
        { key: 'canceledCount', label: '已取消', icon: 'el-icon-remove-outline' }
      ]
    }
  },
  created() {
    this.loadAllCacheData()
    this.loadTaskStatistics()
  },
  mounted() {
    this.startStatisticsMonitoring()
  },
  destroyed() {
    this.stopStatisticsMonitoring()
  },
  methods: {
    [CRUD.HOOK.afterRefresh]() {
      this.loadTaskStatistics()
    },
    refreshTaskData() {
      this.crud.refresh()
      this.loadTaskStatistics()
    },
    loadTaskStatistics() {
      getTaskStatistics().then(response => {
        const result = response.data || response || {}
        this.taskStatistics = {
          totalCount: Number(result.totalCount) || 0,
          pendingCount: Number(result.pendingCount) || 0,
          runningCount: Number(result.runningCount) || 0,
          successCount: Number(result.successCount) || 0,
          failedCount: Number(result.failedCount) || 0,
          canceledCount: Number(result.canceledCount) || 0
        }
      }).catch(err => {
        console.error('加载任务统计失败:', err)
      })
    },
    startStatisticsMonitoring() {
      this.stopStatisticsMonitoring()
      this.statisticsTimer = setInterval(() => {
        this.loadTaskStatistics()
      }, 10000)
    },
    stopStatisticsMonitoring() {
      if (this.statisticsTimer) {
        clearInterval(this.statisticsTimer)
        this.statisticsTimer = null
      }
    },
    // 显示任务详情
    showTaskDetail(task) {
      this.selectedTaskForDetail = task
      this.detailDialogVisible = true
    },
    // 双击行显示详情
    handleRowDoubleClick(row) {
      this.showTaskDetail(row)
    },
    // 启动单个任务
    startTask(task) {
      this.$confirm('确定要启动该清洗任务吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        startTask(task.taskId).then(() => {
          this.$message.success('任务启动成功')
          this.refreshTaskData()
        }).catch(() => {
          this.$message.error('任务启动失败')
        })
      })
    },
    // 取消运行中的任务
    cancelRunningTask(task) {
      this.$confirm('取消后将终止当前执行，并保留本次取消记录。确定继续吗？', '取消任务', {
        confirmButtonText: '确定取消',
        cancelButtonText: '暂不取消',
        type: 'warning'
      }).then(() => {
        return cancelPreDefenseTask(task.taskId)
      }).then(() => {
        this.$message.success('任务已取消')
        this.refreshTaskData()
      }).catch(err => {
        if (err !== 'cancel' && err !== 'close') {
          this.$message.error('取消任务失败')
        }
      })
    },
    // 批量启动任务
    batchStartTasks() {
      const pendingTasks = this.crud.selections.filter(item => item.status === 'pending')

      if (pendingTasks.length === 0) {
        this.$message.warning('只能启动等待中的任务')
        return
      }

      this.$confirm(`确定要批量启动 ${pendingTasks.length} 个任务吗？`, '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        const pendingTaskIds = pendingTasks.map(item => item.taskId)
        startBatchTasks(pendingTaskIds).then(response => {
          const result = response.data || response
          this.$message.success(`成功启动 ${result.successCount} 个任务`)
          if (result.failureCount > 0) {
            this.$message.warning(`${result.failureCount} 个任务启动失败`)
          }
          this.refreshTaskData()
        }).catch(() => {
          this.$message.error('批量启动失败')
        })
      })
    },
    // 修改：从JSON中计算清洗效率（去掉可选链操作符）
    getCleaningRate(task) {
      if (task.cleaningResult) {
        try {
          const result = typeof task.cleaningResult === 'string'
            ? JSON.parse(task.cleaningResult)
            : task.cleaningResult

          // 从sample_stats中获取retention_rate
          if (result.sample_stats && result.sample_stats.retention_rate) {
            return Math.round(result.sample_stats.retention_rate * 100)
          }

          // 从sample_stats中计算
          if (result.sample_stats) {
            const cleanedCount = result.sample_stats.cleaned_count || 0
            const removedCount = result.sample_stats.removed_count || 0
            const total = cleanedCount + removedCount

            if (total > 0) {
              return Math.round((cleanedCount / total) * 100)
            }
          }
        } catch (e) {
          console.warn('解析清洗结果失败:', e)
        }
      }

      return 0
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

      // 加载算法缓存
      getAlgorithmsByStage('pre').then(response => {
        const algorithms = (response.data || response || []).filter(item => item.implementationPath === 'vab://pre-defense/ldd')
        if (Array.isArray(algorithms)) {
          algorithms.forEach(alg => {
            this.algorithmCache[alg.algorithmId] = alg.algorithmName
          })
        }
      }).catch(err => {
        console.error('加载算法缓存失败:', err)
      })

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
    // 获取数据集名称
    getDatasetName(datasetId) {
      if (!datasetId) return '未选择'
      return this.datasetCache[datasetId] || `数据集-${datasetId}`
    },
    // 获取算法名称
    getAlgorithmName(algorithmId) {
      if (!algorithmId) return '未选择'
      return this.algorithmCache[algorithmId] || `算法-${algorithmId}`
    },
    // 获取进度状态
    getProgressStatus(status) {
      if (status === 'success') return 'success'
      if (status === 'failed') return 'exception'
      if (status === 'running') return null
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
    }
  }
}
</script>

<style scoped>
.task-overview {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.overview-item {
  min-height: 76px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-left: 3px solid #909399;
  background: #ffffff;
  border-radius: 4px;
}

.overview-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #606266;
  background: #f5f7fa;
  border-radius: 50%;
  font-size: 17px;
}

.overview-label {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}

.overview-value {
  color: #303133;
  font-size: 22px;
  font-weight: 600;
  line-height: 28px;
}

.overview-runningCount { border-left-color: #e6a23c; }
.overview-runningCount .overview-icon { color: #e6a23c; background: #fdf6ec; }
.overview-successCount { border-left-color: #67c23a; }
.overview-successCount .overview-icon { color: #67c23a; background: #f0f9eb; }
.overview-failedCount { border-left-color: #f56c6c; }
.overview-failedCount .overview-icon { color: #f56c6c; background: #fef0f0; }
.overview-canceledCount { border-left-color: #909399; }
.overview-canceledCount .overview-icon { color: #909399; background: #f4f4f5; }

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

.batch-operations {
  margin-top: 10px;
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  text-align: center;
}
::v-deep .operation-buttons .el-button {
  padding: 7px !important;
  margin: 0 !important;
  min-width: 32px !important; /* 设置最小宽度，确保所有按钮一样大 */
  width: 32px !important; /* 固定宽度 */
  height: 32px !important; /* 固定高度 */
}
/* 确保按钮内的图标居中 */
::v-deep .operation-buttons .el-button .el-icon {
  margin-right: 0 !important;
}
::v-deep .el-progress-bar__inner {
  transition: width 0.6s ease;
}

@media screen and (max-width: 1200px) {
  .task-overview {
    grid-template-columns: repeat(3, minmax(120px, 1fr));
  }
}

@media screen and (max-width: 768px) {
  .task-overview {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
