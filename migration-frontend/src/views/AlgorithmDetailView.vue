<template>
  <div class="page detail-page">
    <div class="detail-topbar"><el-button text :icon="ArrowLeft" @click="$router.push('/algorithms')">返回算法资源</el-button><span class="muted">算法详情 / {{ algorithm.name }}</span></div>
    <section class="detail-hero">
      <div class="algorithm-icon large-icon">{{ algorithm.shortName }}</div>
      <div class="detail-hero-copy"><div class="detail-title-row"><div><span class="eyebrow">{{ algorithm.stage }} · {{ algorithm.category }}</span><h2>{{ algorithm.name }}</h2></div><el-tag type="success" size="large">已接入运行</el-tag></div><p>{{ algorithm.description }}</p><div class="detail-meta"><span>版本 {{ algorithm.version }}</span><span>责任组 {{ algorithm.owner }}</span><span>输入：{{ algorithm.input }}</span><span>输出：{{ algorithm.output }}</span></div></div>
    </section>

    <div class="detail-stat-grid">
      <el-card v-for="item in metricCards" :key="item.label" shadow="never"><span class="muted">{{ item.label }}</span><strong :class="item.className">{{ item.value }}</strong><small>{{ item.note }}</small></el-card>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="算法说明" name="overview">
        <section class="content-grid detail-content-grid">
          <el-card shadow="never"><template #header><strong>算法原理</strong></template><p class="detail-paragraph">{{ algorithm.principle }}</p><div class="flow-list"><div v-for="(step, index) in workflow" :key="step.title" class="flow-item"><span>{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ step.title }}</strong><p>{{ step.detail }}</p></div></div></div></el-card>
          <el-card shadow="never"><template #header><strong>适用场景</strong></template><div class="scene-list"><div><span>威胁类型</span><strong>数据投毒 / 后门样本</strong></div><div><span>适用阶段</span><strong>{{ algorithm.stage }}</strong></div><div><span>数据类型</span><strong>图像分类训练集</strong></div><div><span>结果形态</span><strong>{{ algorithm.output }}</strong></div></div><el-alert title="当前平台已支持统一任务调用与结果归档" type="success" :closable="false" show-icon /></el-card>
        </section>
      </el-tab-pane>
      <el-tab-pane label="参数配置" name="parameters">
        <el-card shadow="never"><template #header><div class="card-heading"><strong>参数配置快照</strong><el-tag type="info">生产配置 / 2026-09-08</el-tag></div></template><el-table :data="parameterRows" border><el-table-column prop="name" label="参数名称" width="220" /><el-table-column prop="value" label="当前值" width="220" /><el-table-column prop="description" label="参数说明" /><el-table-column label="校验状态" width="120"><template #default><el-tag type="success" size="small">已校验</el-tag></template></el-table-column></el-table></el-card>
        <el-card shadow="never" class="detail-card-gap"><template #header><strong>运行环境</strong></template><div class="environment-grid"><div><span>Python</span><strong>3.10.12</strong></div><div><span>PyTorch</span><strong>2.2.2</strong></div><div><span>运行设备</span><strong>CUDA / RTX 3090</strong></div><div><span>随机种子</span><strong>2026</strong></div></div></el-card>
      </el-tab-pane>
      <el-tab-pane label="实验结果" name="results">
        <el-card shadow="never"><template #header><div class="card-heading"><strong>实验结果对比</strong><span class="muted">指标越低越安全，准确率保持率越高越好</span></div></template><el-table :data="comparisonRows" border><el-table-column prop="name" label="实验方案" min-width="180" /><el-table-column prop="accuracy" label="干净准确率" width="150" /><el-table-column prop="asr" label="攻击成功率" width="150"><template #default="{ row }"><span :class="row.name.includes(algorithm.name) ? 'success-text' : ''">{{ row.asr }}</span></template></el-table-column><el-table-column prop="retention" label="样本保留率" width="150" /><el-table-column label="综合评价" min-width="180"><template #default="{ row }"><el-tag :type="row.name.includes(algorithm.name) ? 'success' : 'info'">{{ row.name.includes(algorithm.name) ? '推荐方案' : '参考基线' }}</el-tag></template></el-table-column></el-table></el-card>
        <div class="result-note"><el-alert title="结果说明" :description="`${algorithm.name} 已完成当前配置下的实验执行，结果指标、清洗索引和运行日志已写入任务报告。`" type="success" :closable="false" show-icon /></div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { demoAlgorithms } from '../data/demoData'

const route = useRoute()
const activeTab = ref('overview')
const algorithm = computed(() => demoAlgorithms.find(item => item.key === route.params.algorithmKey) || demoAlgorithms[0])
const metricCards = computed(() => [
  { label: '干净准确率', value: algorithm.value.metrics.cleanAccuracy, note: '清洗后模型正常任务性能', className: '' },
  { label: '攻击成功率', value: algorithm.value.metrics.attackSuccessRate, note: '目标后门触发成功率', className: 'success-text' },
  { label: '样本保留率', value: algorithm.value.metrics.retentionRate, note: `移除样本 ${algorithm.value.metrics.removedCount}`, className: '' },
  { label: '实验状态', value: '已验证', note: '统一接口能力状态', className: 'success-text' }
])
const parameterRows = computed(() => algorithm.value.parameters.map(item => ({ name: item[0], value: item[1], description: item[2] })))
const comparisonRows = computed(() => algorithm.value.comparisons.map(item => ({ name: item[0], accuracy: item[1], asr: item[2], retention: item[3] })))
const workflow = [
  { title: '数据读取与校验', detail: '检查样本、标签和元数据完整性，建立本次实验的数据版本标识。' },
  { title: '风险分数计算', detail: '提取样本特征并计算异常分数，保留可追溯的中间结果。' },
  { title: '索引筛选与重建', detail: '按隔离比例生成保留索引和移除索引，重建清洗后的数据子集。' },
  { title: '报告归档', detail: '写入运行标识、参数快照、指标结果和可下载产物。' }
]
</script>
