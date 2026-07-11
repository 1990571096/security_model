<template>
  <div class="dashboard-container">
    <section class="overview">
      <div class="overview-header">
        <div>
          <h1>安全训练工作台</h1>
          <p>数据清洗、算法防御、模型训练与评估报告的任务入口</p>
        </div>
        <el-button type="primary" icon="el-icon-refresh" :loading="loading" @click="loadDashboard">
          刷新
        </el-button>
      </div>

      <el-row :gutter="16" class="metric-row">
        <el-col v-for="item in metrics" :key="item.key" :xs="12" :sm="12" :lg="6">
          <div class="metric-card">
            <div class="metric-icon" :class="item.iconClass">
              <i :class="item.icon" />
            </div>
            <div class="metric-body">
              <span class="metric-label">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
        </el-col>
      </el-row>
    </section>

    <el-row :gutter="16" class="content-row">
      <el-col :xs="24" :lg="15">
        <section class="panel">
          <div class="panel-header">
            <h2>任务队列</h2>
            <el-button type="text" @click="$router.push('/system/taskProgress')">查看全部</el-button>
          </div>
          <el-table v-loading="loading" :data="recentTasks" size="small" stripe>
            <el-table-column prop="taskName" label="任务名称" min-width="150" show-overflow-tooltip />
            <el-table-column prop="taskType" label="阶段" width="110">
              <template slot-scope="scope">
                <el-tag size="mini" :type="getStageTag(scope.row.taskType)">
                  {{ formatStage(scope.row.taskType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="90">
              <template slot-scope="scope">
                <el-tag size="mini" :type="getStatusTag(scope.row.status)">
                  {{ formatStatus(scope.row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="progress" label="进度" width="150">
              <template slot-scope="scope">
                <el-progress :percentage="scope.row.progress || 0" :show-text="false" />
              </template>
            </el-table-column>
            <el-table-column prop="algorithmName" label="算法" min-width="120" show-overflow-tooltip />
          </el-table>
        </section>
      </el-col>

      <el-col :xs="24" :lg="9">
        <section class="panel">
          <div class="panel-header">
            <h2>主流程</h2>
          </div>
          <div class="workflow">
            <button v-for="step in workflow" :key="step.path" class="workflow-step" @click="$router.push(step.path)">
              <i :class="step.icon" />
              <span>{{ step.title }}</span>
              <small>{{ step.desc }}</small>
            </button>
          </div>
        </section>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { getTaskStatistics, getAllTasks } from '@/api/system/taskProgress'
import { getAllDataset } from '@/api/system/dataset'
import { getAllAlgorithm } from '@/api/system/algorithm'

export default {
  name: 'Dashboard',
  data() {
    return {
      loading: false,
      stats: {
        datasets: 0,
        algorithms: 0,
        running: 0,
        totalTasks: 0
      },
      recentTasks: [],
      workflow: [
        { title: '数据集', desc: '导入和解析图像训练数据', path: '/system/dataset', icon: 'el-icon-folder-opened' },
        { title: '算法库', desc: '维护防御算法和参数 schema', path: '/system/algorithm', icon: 'el-icon-cpu' },
        { title: '训练前防御', desc: '检测中毒样本并筛选干净子集', path: '/system/preDefense', icon: 'el-icon-document-checked' },
        { title: '训练中防御', desc: '执行安全训练和训练阶段防御', path: '/system/duringDefense', icon: 'el-icon-data-line' },
        { title: '模型库', desc: '管理内置和自定义图像模型', path: '/system/model', icon: 'el-icon-box' },
        { title: '任务进度', desc: '跟踪清洗、训练和评估任务', path: '/system/taskProgress', icon: 'el-icon-tickets' }
      ]
    }
  },
  computed: {
    metrics() {
      return [
        { key: 'datasets', label: '可用数据集', value: this.stats.datasets, icon: 'el-icon-folder-opened', iconClass: 'blue' },
        { key: 'algorithms', label: '防御算法', value: this.stats.algorithms, icon: 'el-icon-cpu', iconClass: 'green' },
        { key: 'running', label: '运行任务', value: this.stats.running, icon: 'el-icon-loading', iconClass: 'orange' },
        { key: 'totalTasks', label: '任务总数', value: this.stats.totalTasks, icon: 'el-icon-tickets', iconClass: 'red' }
      ]
    }
  },
  created() {
    this.loadDashboard()
  },
  methods: {
    async loadDashboard() {
      this.loading = true
      try {
        const [datasetRes, algorithmRes, taskStatsRes, tasksRes] = await Promise.all([
          getAllDataset(),
          getAllAlgorithm(),
          getTaskStatistics(),
          getAllTasks()
        ])

        const datasets = this.normalizeList(datasetRes)
        const algorithms = this.normalizeList(algorithmRes)
        const taskStats = taskStatsRes.data || taskStatsRes || {}
        const tasks = this.normalizeList(tasksRes)

        this.stats.datasets = datasets.length
        this.stats.algorithms = algorithms.filter(item => item.enabled !== false).length
        this.stats.running = taskStats.runningCount || 0
        this.stats.totalTasks = taskStats.totalCount || tasks.length
        this.recentTasks = tasks.slice(0, 8)
      } catch (error) {
        this.stats = {
          datasets: 0,
          algorithms: 0,
          running: 0,
          totalTasks: 0
        }
        this.recentTasks = []
      } finally {
        this.loading = false
      }
    },
    normalizeList(response) {
      const data = response && response.data ? response.data : response
      if (Array.isArray(data)) return data
      if (data && Array.isArray(data.content)) return data.content
      return []
    },
    formatStage(stage) {
      const map = {
        pre_defense: '训练前',
        during_defense: '训练中',
        post_defense: '训练后'
      }
      return map[stage] || stage || '-'
    },
    formatStatus(status) {
      const map = {
        pending: '等待',
        running: '运行',
        success: '成功',
        failed: '失败'
      }
      return map[status] || status || '-'
    },
    getStageTag(stage) {
      const map = {
        pre_defense: 'success',
        during_defense: 'primary',
        post_defense: 'warning'
      }
      return map[stage] || 'info'
    },
    getStatusTag(status) {
      const map = {
        pending: 'info',
        running: '',
        success: 'success',
        failed: 'danger'
      }
      return map[status] || 'info'
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
.dashboard-container {
  min-height: calc(100vh - 84px);
  padding: 20px;
  background: #f4f6f8;
}

.overview,
.panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.overview {
  padding: 20px;
  margin-bottom: 16px;
}

.overview-header,
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.overview-header h1,
.panel-header h2 {
  margin: 0;
  color: #1f2937;
  font-weight: 600;
}

.overview-header h1 {
  font-size: 24px;
  line-height: 32px;
}

.overview-header p {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 14px;
}

.metric-row {
  margin-top: 20px;
}

.metric-card {
  display: flex;
  align-items: center;
  min-height: 92px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid #edf0f3;
  border-radius: 6px;
  background: #fbfcfd;
}

.metric-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  margin-right: 14px;
  border-radius: 6px;
  font-size: 24px;
}

.metric-icon.blue {
  color: #2563eb;
  background: #eff6ff;
}

.metric-icon.green {
  color: #059669;
  background: #ecfdf5;
}

.metric-icon.orange {
  color: #d97706;
  background: #fffbeb;
}

.metric-icon.red {
  color: #dc2626;
  background: #fef2f2;
}

.metric-body {
  display: flex;
  flex-direction: column;
}

.metric-label {
  color: #6b7280;
  font-size: 13px;
  line-height: 20px;
}

.metric-body strong {
  color: #111827;
  font-size: 26px;
  line-height: 34px;
}

.content-row {
  align-items: stretch;
}

.panel {
  min-height: 420px;
  padding: 18px;
  margin-bottom: 16px;
}

.panel-header {
  margin-bottom: 14px;
}

.panel-header h2 {
  font-size: 18px;
  line-height: 26px;
}

.workflow {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.workflow-step {
  display: grid;
  grid-template-columns: 36px 1fr;
  grid-template-rows: auto auto;
  column-gap: 10px;
  width: 100%;
  padding: 12px;
  text-align: left;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}

.workflow-step:hover {
  border-color: #409eff;
  background: #f8fbff;
}

.workflow-step i {
  grid-row: 1 / span 2;
  color: #409eff;
  font-size: 24px;
  align-self: center;
}

.workflow-step span {
  color: #1f2937;
  font-size: 14px;
  font-weight: 600;
  line-height: 22px;
}

.workflow-step small {
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

@media (max-width: 768px) {
  .dashboard-container {
    padding: 12px;
  }

  .overview-header {
    align-items: flex-start;
    gap: 12px;
  }

  .overview-header h1 {
    font-size: 20px;
  }
}
</style>
