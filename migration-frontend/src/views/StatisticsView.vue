<template>
  <div class="page statistics-page">
    <div class="section-heading">
      <div><span class="eyebrow">DATA INSIGHT CENTER</span><h2>数据统计</h2><p class="page-subtitle">从任务规模、样本清洗、模型安全性和防御阶段四个维度观察实验结果。</p></div>
      <div class="heading-actions"><el-tag type="success">统计周期：2026 年 09 月</el-tag><el-button :icon="Download" @click="exportSummary">导出统计摘要</el-button></div>
    </div>
    <div class="stat-grid">
      <el-card v-for="item in headlineStats" :key="item.label" shadow="never" class="stat-card"><span class="muted">{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small></el-card>
    </div>
    <section class="content-grid statistics-main-grid">
      <el-card shadow="never"><template #header><div class="card-heading"><div><strong>防御效果对比</strong><span class="table-caption">不同防御策略下的样本与安全指标</span></div><el-tag type="success">综合结果</el-tag></div></template><div class="effect-list"><div v-for="item in effectRows" :key="item.ratio" class="effect-row"><div class="effect-label"><strong>{{ item.ratio }}</strong><span>隔离比例</span></div><div class="effect-bars"><div><span>攻击成功率</span><div class="effect-track"><i class="risk-bar" :style="{ width: `${item.asr}%` }"></i></div><strong>{{ item.asr }}%</strong></div><div><span>样本保留率</span><div class="effect-track"><i class="keep-bar" :style="{ width: `${item.retention}%` }"></i></div><strong>{{ item.retention }}%</strong></div></div></div></div></el-card>
      <el-card shadow="never"><template #header><strong>风险等级分布</strong></template><div class="risk-summary"><div class="risk-ring"><strong>5,000</strong><span>疑似风险样本</span></div><div class="risk-legend"><div v-for="item in riskLevels" :key="item.label"><i :style="{ background: item.color }"></i><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div></div><el-alert title="高风险样本主要集中在目标标签的异常特征簇中" type="warning" :closable="false" show-icon /></el-card>
    </section>
    <section class="content-grid statistics-bottom-grid">
      <el-card shadow="never"><template #header><div class="card-heading"><strong>阶段能力统计</strong><el-tag type="info">3 个阶段</el-tag></div></template><el-table :data="stageRows" border size="small"><el-table-column prop="stage" label="防御阶段" width="140" /><el-table-column prop="algorithms" label="算法方案" width="100" /><el-table-column prop="datasets" label="适用数据集" width="120" /><el-table-column prop="coverage" label="能力覆盖度" width="150"><template #default="{ row }"><el-progress :percentage="row.coverage" :stroke-width="8" /></template></el-table-column><el-table-column prop="summary" label="阶段说明" /></el-table></el-card>
      <el-card shadow="never"><template #header><strong>指标口径</strong></template><div class="definition-list"><div><span>攻击成功率 ASR</span><p>触发器样本被模型预测为目标类别的比例，数值越低表示防御效果越好。</p></div><div><span>样本保留率</span><p>清洗后保留样本数占原始训练集的比例，用于衡量数据利用率。</p></div><div><span>干净准确率</span><p>模型在未触发的正常测试集上的分类准确率，反映业务性能保持情况。</p></div></div></el-card>
    </section>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'

const headlineStats = [
  { label: '累计处理样本', value: '168,418', note: '来自 28 次实验任务' }, { label: '累计移除样本', value: '16,240', note: '平均隔离比例 9.6%' },
  { label: '平均干净准确率', value: '91.8%', note: '较原始模型下降 1.6%' }, { label: '安全指标改善', value: '88.1%', note: '攻击成功率相对下降' }
]
const effectRows = [
  { ratio: '5%', asr: 8.6, retention: 95 }, { ratio: '10%', asr: 4.7, retention: 90 }, { ratio: '20%', asr: 2.9, retention: 80 }
]
const riskLevels = [{ label: '高风险', value: '1,240', color: '#e45656' }, { label: '中风险', value: '2,180', color: '#e6a23c' }, { label: '低风险', value: '1,580', color: '#67c23a' }]
const stageRows = [
  { stage: '训练前防御', algorithms: 3, datasets: 4, coverage: 100, summary: '支持数据清洗、索引筛选与安全子集导出' },
  { stage: '训练中防御', algorithms: 3, datasets: 3, coverage: 78, summary: '覆盖鲁棒训练、梯度监测和过程告警方案' },
  { stage: '训练后防御', algorithms: 3, datasets: 2, coverage: 68, summary: '覆盖触发器反演、模型修复与安全评估方案' }
]
function exportSummary() { ElMessage.success('统计摘要已生成，可用于实验记录归档') }
</script>
