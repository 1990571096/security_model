<template>
  <div class="page dashboard-page">
    <section class="hero-band dashboard-hero">
      <div>
        <span class="eyebrow">SECURITY EXPERIMENT OVERVIEW</span>
        <h2>安全模型训练工作台</h2>
        <p>围绕数据集、算法、任务和报告构建可追溯的模型安全实验闭环，集中呈现当前防御体系运行态势。</p>
      </div>
      <div class="hero-summary">
        <span>当前实验周期</span>
        <strong>2026 / 09</strong>
        <small>数据已同步至 2026 年 09 月 08 日</small>
      </div>
    </section>

    <div class="stat-grid dashboard-stat-grid">
      <el-card v-for="item in stats" :key="item.label" shadow="never" class="stat-card">
        <div class="stat-card-top"><span class="muted">{{ item.label }}</span><el-tag :type="item.type" effect="plain">{{ item.badge }}</el-tag></div>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
      </el-card>
    </div>

    <section class="content-grid dashboard-main-grid">
      <el-card shadow="never" class="chart-card">
        <template #header><div class="card-heading"><div><strong>实验任务趋势</strong><span class="table-caption">近 7 日任务执行与成功情况</span></div><el-tag type="success">运行稳定</el-tag></div></template>
        <div class="bar-chart">
          <div v-for="item in taskTrend" :key="item.day" class="bar-group">
            <div class="bar-values"><span :style="{ height: `${item.total * 18}px` }"></span><i :style="{ height: `${item.success * 18}px` }"></i></div>
            <strong>{{ item.total }}</strong><small>{{ item.day }}</small>
          </div>
        </div>
        <div class="chart-legend"><span><i class="legend-total"></i>全部任务</span><span><i class="legend-success"></i>成功任务</span><em>累计成功率 80%</em></div>
      </el-card>

      <el-card shadow="never" class="risk-card">
        <template #header><div class="card-heading"><strong>防御体系覆盖</strong><el-button text type="primary" @click="$router.push('/defense/pre')">查看体系</el-button></div></template>
        <div v-for="item in defenseCoverage" :key="item.title" class="coverage-row">
          <div class="coverage-icon">{{ item.code }}</div>
          <div class="coverage-content"><div><strong>{{ item.title }}</strong><span>{{ item.algorithms }} 个算法方案</span></div><el-progress :percentage="item.progress" :stroke-width="8" :show-text="false" :color="item.color" /><small>{{ item.detail }}</small></div>
          <strong class="coverage-percent">{{ item.progress }}%</strong>
        </div>
      </el-card>
    </section>

    <section class="content-grid dashboard-secondary-grid">
      <el-card shadow="never">
        <template #header><div class="card-heading"><div><strong>近期实验任务</strong><span class="table-caption">任务状态、清洗规模和运行标识</span></div><el-button text type="primary" @click="$router.push('/tasks')">全部任务</el-button></div></template>
        <el-table :data="recentTasks" size="small">
          <el-table-column prop="taskName" label="任务名称" min-width="190" show-overflow-tooltip />
          <el-table-column prop="datasetName" label="数据集" min-width="160" show-overflow-tooltip />
          <el-table-column label="结果" width="120"><template #default="{ row }"><span class="result-text">{{ row.cleanCount }} / {{ row.removedCount }}</span></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        </el-table>
      </el-card>
      <el-card shadow="never">
        <template #header><div class="card-heading"><strong>平台动态</strong><el-tag type="info">今日 4 条</el-tag></div></template>
        <div class="activity-list">
          <div v-for="item in demoActivities" :key="`${item[0]}-${item[1]}`" class="activity-item">
            <span class="activity-time">{{ item[0] }}</span><span class="activity-dot" :class="item[3]"></span><div><strong>{{ item[1] }}</strong><p>{{ item[2] }}</p></div>
          </div>
        </div>
      </el-card>
    </section>

    <section class="dashboard-footer-grid">
      <el-card shadow="never"><template #header><strong>关键实验指标</strong></template><div class="metric-list"><div><span>平均清洗保留率</span><strong>90.0%</strong><small>较上周期 +2.4%</small></div><div><span>平均攻击成功率</span><strong class="success-text">4.7%</strong><small>较原始模型 -88.1%</small></div><div><span>报告生成完成度</span><strong>100%</strong><small>4 / 4 项结果已归档</small></div></div></el-card>
      <el-card shadow="never"><template #header><strong>当前实验基线</strong></template><div class="baseline-grid"><div><span>数据集</span><strong>CIFAR-10</strong></div><div><span>批次大小</span><strong>128</strong></div><div><span>训练轮数</span><strong>5</strong></div><div><span>防御策略</span><strong>多算法协同</strong></div></div></el-card>
    </section>
  </div>
</template>

<script setup>
import { demoActivities, demoTasks } from '../data/demoData'

const stats = [
  { label: '累计实验任务', value: '28', note: '本周期新增 6 个任务', badge: '+6', type: 'success' },
  { label: '数据资源', value: '12', note: '4 类数据集，版本可追溯', badge: '稳定', type: 'info' },
  { label: '算法方案', value: '9', note: '覆盖三大防御阶段', badge: '完整', type: 'warning' },
  { label: '平均攻击成功率', value: '4.7%', note: '防御处理后核心指标', badge: '-88.1%', type: 'success' }
]
const taskTrend = [
  { day: '09-02', total: 2, success: 2 }, { day: '09-03', total: 3, success: 2 }, { day: '09-04', total: 4, success: 3 },
  { day: '09-05', total: 5, success: 4 }, { day: '09-06', total: 6, success: 5 }, { day: '09-07', total: 4, success: 4 }, { day: '09-08', total: 5, success: 4 }
]
const defenseCoverage = [
  { code: '01', title: '训练前防御', algorithms: 3, progress: 100, detail: '数据清洗算法已接入，支持索引导出', color: '#2f80ed' },
  { code: '02', title: '训练中防御', algorithms: 3, progress: 78, detail: '鲁棒训练、梯度监测方案已登记', color: '#21a366' },
  { code: '03', title: '训练后防御', algorithms: 3, progress: 68, detail: '模型审计与修复方案已登记', color: '#e6a23c' }
]
const recentTasks = demoTasks.slice(0, 4)
const statusLabels = { pending: '等待中', running: '运行中', success: '已完成', failed: '失败', canceled: '已取消' }
function statusLabel(status) { return statusLabels[status] || status }
function statusType(status) { return { success: 'success', running: 'warning', failed: 'danger', canceled: 'info' }[status] || 'info' }
</script>

