<template>
  <div class="app-container">
    <!--工具栏-->
    <div class="head-container">
      <eHeader :permission="permission" />
      <crudOperation :permission="permission" />
    </div>
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
            >
            </el-button>
            <!-- 启动按钮 - 绿色方框 -->
            <el-button
              v-if="scope.row.status === 'pending' && checkPer(['admin','pre_defense:start'])"
              size="mini"
              type="success"
              icon="el-icon-video-play"
              @click="startTask(scope.row)"
            >
            </el-button>
            <!-- 下载按钮 - 蓝色 -->
            <el-button
              v-if="scope.row.status === 'success' && scope.row.outputDatasetId && checkPer(['admin','pre_defense:list'])"
              size="mini"
              type="primary"
              icon="el-icon-download"
              @click="downloadCleanedDataset(scope.row)"
            >
            </el-button>
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
      @refresh="crud.refresh"
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
import { startTask, startBatchTasks, downloadCleanedDataset } from '@/api/system/preDefense'
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
      templateCache: {}
    }
  },
  computed: {
    // 计算等待中的任务数量
    pendingTasksCount() {
      return this.crud.selections.filter(item => item.status === 'pending').length
    }
  },
  created() {
    this.loadAllCacheData()
  },
  methods: {
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
          this.crud.refresh()
        }).catch(() => {
          this.$message.error('任务启动失败')
        })
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
          this.crud.refresh()
        }).catch(() => {
          this.$message.error('批量启动失败')
        })
      })
    },
    // 下载清洗后数据集
    downloadCleanedDataset(task) {
      this.$confirm('确定要下载清洗后的数据集吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'info'
      }).then(() => {
        downloadCleanedDataset(task.taskId).then(() => {
          this.$message.success('开始下载清洗后数据集')
        }).catch(err => {
          this.$message.error('下载失败：' + (err.message || '未知错误'))
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
        const algorithms = response.data || response
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
</style>

