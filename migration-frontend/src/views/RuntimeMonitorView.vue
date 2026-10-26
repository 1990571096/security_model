<template>
  <div class="page">
    <section class="hero-band monitor-hero">
      <div>
        <span class="eyebrow">RUNTIME OBSERVABILITY</span>
        <h2>运行监控中心</h2>
        <p>集中展示安全防御任务的运行状态、资源占用、执行节点与系统事件，为实验调度、汇报截图和运行态展示提供统一视图。</p>
      </div>
      <div class="hero-summary monitor-summary">
        <span>平台状态</span>
        <strong>运行正常</strong>
        <small>最近更新：2026-09-11 15:42:18</small>
      </div>
    </section>

    <section class="stat-grid monitor-stat-grid">
      <el-card v-for="item in overviewStats" :key="item.label" class="stat-card" shadow="never">
        <div class="stat-card-top">
          <span class="muted">{{ item.label }}</span>
          <el-tag :type="item.type" effect="light">{{ item.badge }}</el-tag>
        </div>
        <strong>{{ item.value }}</strong>
        <small>{{ item.desc }}</small>
      </el-card>
    </section>

    <section class="monitor-resource-grid">
      <el-card v-for="resource in resourceUsage" :key="resource.name" class="resource-card" shadow="never">
        <div class="resource-card-head">
          <div>
            <span>{{ resource.name }}</span>
            <strong>{{ resource.percent }}%</strong>
          </div>
          <el-tag :type="resource.type" effect="light">{{ resource.status }}</el-tag>
        </div>
        <el-progress :percentage="resource.percent" :stroke-width="10" :color="resource.color" />
        <div class="resource-meta">
          <span>{{ resource.used }} / {{ resource.total }}</span>
          <span>{{ resource.trend }}</span>
        </div>
      </el-card>
    </section>

    <section class="content-grid monitor-main-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>当前运行任务</strong>
              <span class="table-caption">展示正在执行和排队调度的防御任务</span>
            </div>
            <el-button type="primary" plain>刷新状态</el-button>
          </div>
        </template>

        <el-table :data="runningTasks" class="monitor-table">
          <el-table-column prop="name" label="任务名称" min-width="180" />
          <el-table-column prop="algorithm" label="算法" width="130" />
          <el-table-column prop="dataset" label="数据集" min-width="160" />
          <el-table-column prop="stage" label="当前阶段" width="130">
            <template #default="{ row }">
              <el-tag effect="light">{{ row.stage }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="node" label="执行节点" width="125" />
          <el-table-column label="进度" width="160">
            <template #default="{ row }">
              <el-progress :percentage="row.progress" :stroke-width="8" />
            </template>
          </el-table-column>
          <el-table-column prop="elapsed" label="已运行" width="110" />
          <el-table-column prop="remain" label="预计剩余" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.statusType" effect="light">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>最近 8 小时吞吐趋势</strong>
              <span class="table-caption">任务提交、完成与平均耗时变化</span>
            </div>
          </div>
        </template>
        <div class="monitor-trend-chart">
          <div v-for="item in trendData" :key="item.time" class="trend-column">
            <div class="trend-bars">
              <span :style="{ height: `${item.submitted}%` }"></span>
              <i :style="{ height: `${item.finished}%` }"></i>
            </div>
            <strong>{{ item.time }}</strong>
            <small>{{ item.duration }}m</small>
          </div>
        </div>
        <div class="chart-legend">
          <span><i class="legend-total"></i>提交任务</span>
          <span><i class="legend-success"></i>完成任务</span>
          <em>平均执行时长 36.8 分钟</em>
        </div>
      </el-card>
    </section>

    <section class="content-grid monitor-secondary-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>执行节点状态</strong>
              <span class="table-caption">覆盖 GPU 节点、CPU 节点与审计节点</span>
            </div>
          </div>
        </template>
        <div class="node-grid">
          <div v-for="node in nodes" :key="node.name" class="node-card">
            <div class="node-card-head">
              <div>
                <strong>{{ node.name }}</strong>
                <span>{{ node.type }}</span>
              </div>
              <el-tag :type="node.statusType" effect="light">{{ node.status }}</el-tag>
            </div>
            <div class="node-metrics">
              <div><span>任务</span><strong>{{ node.tasks }}</strong></div>
              <div><span>CPU</span><strong>{{ node.cpu }}%</strong></div>
              <div><span>内存</span><strong>{{ node.memory }}%</strong></div>
              <div><span>GPU</span><strong>{{ node.gpu }}</strong></div>
            </div>
            <p>最后心跳：{{ node.heartbeat }}</p>
          </div>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>告警与运行事件</strong>
              <span class="table-caption">记录任务调度、资源波动和报告归档情况</span>
            </div>
          </div>
        </template>
        <div class="monitor-alert-list">
          <div v-for="event in events" :key="event.time + event.title" class="monitor-event">
            <span class="event-time">{{ event.time }}</span>
            <i :class="event.level"></i>
            <div>
              <strong>{{ event.title }}</strong>
              <p>{{ event.desc }}</p>
            </div>
          </div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
const overviewStats = [
  { label: '运行中任务', value: '4', desc: '训练前、训练中、训练后任务同步执行', badge: '实时', type: 'success' },
  { label: '等待队列', value: '3', desc: '按资源类型与优先级自动排队', badge: '调度中', type: 'warning' },
  { label: '活跃节点', value: '4 / 5', desc: '1 个审计节点处于维护窗口', badge: '可用', type: 'primary' },
  { label: '今日完成', value: '18', desc: '成功率 94.7%，平均耗时 36.8 分钟', badge: '稳定', type: 'success' }
]

const resourceUsage = [
  { name: 'CPU 使用率', percent: 62, used: '9.9 Core', total: '16 Core', status: '正常', type: 'success', color: '#2f80ed', trend: '较 1 小时前 +8%' },
  { name: '内存使用率', percent: 71, used: '45.4 GB', total: '64 GB', status: '稳定', type: 'success', color: '#21a366', trend: '缓存命中率 86%' },
  { name: 'GPU 使用率', percent: 78, used: '18.7 GB', total: '24 GB · RTX 3090', status: '繁忙', type: 'warning', color: '#e6a23c', trend: '3 个任务占用中' },
  { name: '磁盘使用率', percent: 46, used: '0.92 TB', total: '2 TB', status: '充足', type: 'primary', color: '#6f7bd9', trend: '报告归档正常' }
]

const runningTasks = [
  { name: 'CIFAR-10 LDD 主实验', algorithm: 'LDD', dataset: 'CIFAR-10 Backdoor Benchmark', stage: '异常样本清洗', node: 'gpu-node-01', progress: 76, elapsed: '00:42:18', remain: '00:13:40', status: '运行中', statusType: 'success' },
  { name: 'GTSRB 数据清洗验证', algorithm: 'Spectral Signature', dataset: 'GTSRB Traffic Signs', stage: '特征谱分析', node: 'gpu-node-02', progress: 58, elapsed: '00:31:06', remain: '00:22:10', status: '运行中', statusType: 'success' },
  { name: '梯度异常监测实验', algorithm: 'Gradient Shield', dataset: 'Tiny-ImageNet Defense Set', stage: '梯度监控', node: 'cpu-node-02', progress: 44, elapsed: '00:18:22', remain: '00:27:50', status: '运行中', statusType: 'success' },
  { name: '模型后门审计任务', algorithm: 'Neural Cleanse', dataset: 'ResNet-18 Audit Pool', stage: '触发器反演', node: 'audit-node-01', progress: 12, elapsed: '00:04:18', remain: '00:38:00', status: '排队中', statusType: 'warning' }
]

const trendData = [
  { time: '08:00', submitted: 38, finished: 28, duration: 42 },
  { time: '09:00', submitted: 52, finished: 45, duration: 39 },
  { time: '10:00', submitted: 74, finished: 61, duration: 37 },
  { time: '11:00', submitted: 48, finished: 43, duration: 35 },
  { time: '12:00', submitted: 36, finished: 32, duration: 33 },
  { time: '13:00', submitted: 64, finished: 57, duration: 38 },
  { time: '14:00', submitted: 82, finished: 70, duration: 41 },
  { time: '15:00', submitted: 68, finished: 62, duration: 36 }
]

const nodes = [
  { name: 'gpu-node-01', type: 'RTX 3090 训练服务器', tasks: 2, cpu: 66, memory: 74, gpu: '78%', status: '运行中', statusType: 'success', heartbeat: '3 秒前' },
  { name: 'gpu-node-02', type: 'RTX 3090 训练服务器', tasks: 1, cpu: 54, memory: 68, gpu: '64%', status: '运行中', statusType: 'success', heartbeat: '5 秒前' },
  { name: 'cpu-node-01', type: 'CPU 清洗节点', tasks: 0, cpu: 18, memory: 31, gpu: '无', status: '空闲', statusType: 'primary', heartbeat: '4 秒前' },
  { name: 'cpu-node-02', type: 'CPU 调度节点', tasks: 1, cpu: 52, memory: 49, gpu: '无', status: '运行中', statusType: 'success', heartbeat: '6 秒前' },
  { name: 'audit-node-01', type: '模型审计节点', tasks: 0, cpu: 12, memory: 22, gpu: '预留', status: '维护中', statusType: 'info', heartbeat: '12 分钟前' }
]

const events = [
  { time: '15:41', level: 'warning', title: 'GPU 节点资源接近繁忙阈值', desc: 'gpu-node-01 显存占用达到 78%，调度器已限制低优先级任务进入。' },
  { time: '15:36', level: 'success', title: '自动重试任务执行成功', desc: 'TASK-20260911-006 在第二次重试后完成清洗索引生成，报告已进入归档队列。' },
  { time: '15:24', level: 'success', title: '节点心跳恢复', desc: 'gpu-node-02 网络心跳恢复稳定，当前可继续承载训练中防御任务。' },
  { time: '15:08', level: 'primary', title: '清洗结果报告已归档', desc: 'CIFAR-10 LDD 主实验生成异常样本索引、保留样本清单与指标对比摘要。' },
  { time: '14:52', level: 'primary', title: '参数快照写入完成', desc: '训练批次、轮数、模型结构和数据版本已写入实验台账，便于复现实验流程。' }
]
</script>
