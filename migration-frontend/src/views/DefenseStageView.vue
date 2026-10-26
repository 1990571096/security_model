<template>
  <div class="page defense-page">
    <section class="hero-band defense-hero">
      <div><span class="eyebrow">{{ stageMeta.eyebrow }}</span><h2>{{ stageMeta.title }}</h2><p>{{ stageMeta.description }}</p></div>
      <div class="stage-progress"><span>防御流程</span><strong>{{ stageMeta.index }} / 3</strong></div>
    </section>
    <div class="stage-switcher">
      <button v-for="item in stages" :key="item.key" :class="{ active: item.key === stage }" @click="goStage(item.key)">
        <span>{{ item.index }}</span><div><strong>{{ item.title }}</strong><small>{{ item.subtitle }}</small></div>
      </button>
    </div>
    <div class="section-heading compact-heading">
      <div><span class="eyebrow">ALGORITHM CATALOG</span><h2>{{ stageMeta.title }}算法</h2><p class="page-subtitle">统一展示算法定位、适用场景和平台接入状态。</p></div>
      <el-tag type="success">算法已接入</el-tag>
    </div>
    <div class="algorithm-grid defense-algorithm-grid">
      <el-card v-for="item in stageMeta.algorithms" :key="item.name" shadow="never" class="algorithm-card defense-algorithm-card">
        <div class="algorithm-card-head"><div class="algorithm-icon">{{ item.shortName }}</div><el-tag type="success">已接入</el-tag></div>
        <h3>{{ item.name }}</h3><p>{{ item.description }}</p>
        <div class="algorithm-meta"><span>{{ item.category }}</span><span>{{ item.input }}</span></div>
        <div class="algorithm-detail-row"><span>输出结果</span><strong>{{ item.output }}</strong></div>
        <el-button type="primary" :icon="View" @click="$router.push(`/algorithms/${item.key}`)">查看详情</el-button>
        <el-button v-if="item.real" :icon="Operation" @click="$router.push('/tasks')">进入任务中心</el-button>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Operation, View } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const stage = computed(() => ['pre', 'during', 'post'].includes(route.params.stage) ? route.params.stage : 'pre')
const stages = [
  { key: 'pre', index: '01', title: '训练前防御', subtitle: '数据清洗与筛选' },
  { key: 'during', index: '02', title: '训练中防御', subtitle: '鲁棒训练与监测' },
  { key: 'post', index: '03', title: '训练后防御', subtitle: '模型评估与修复' }
]
const catalogs = {
  pre: { eyebrow: 'PRE-TRAINING DEFENSE', index: '01', title: '训练前防御', description: '在模型训练开始前识别疑似污染样本，完成数据清洗、筛选与安全数据集重建。', algorithms: [
    { key: 'ldd', name: 'LDD', shortName: 'LDD', category: '论文方法', input: '图像训练集', output: '保留样本索引', description: '基于标签分布与特征差异识别潜在后门样本，输出可复用的干净数据子集。', real: true },
    { key: 'spectral', name: 'Spectral Signature', shortName: 'SS', category: '经典基线', input: '特征表示', output: '异常样本列表', description: '利用特征空间谱分析定位离群样本，为训练前清洗提供对比基线。', real: true },
    { key: 'activation', name: 'Activation Clustering', shortName: 'AC', category: '聚类检测', input: '中间层激活', output: '聚类风险报告', description: '通过激活空间聚类发现潜在触发器群组，形成数据风险初筛结果。', real: true }
  ] },
  during: { eyebrow: 'IN-TRAINING DEFENSE', index: '02', title: '训练中防御', description: '在模型训练过程中持续观测样本、梯度和损失变化，降低后门行为对模型收敛的影响。', algorithms: [
    { key: 'robust', name: 'Robust Training', shortName: 'RT', category: '鲁棒优化', input: '训练批次', output: '安全模型权重', description: '通过稳健损失、样本重加权和训练策略约束，降低异常样本对模型更新的影响。', real: true },
    { key: 'gradient', name: 'Gradient Shield', shortName: 'GS', category: '梯度防护', input: '梯度流', output: '梯度异常报告', description: '监测梯度方向与幅值异常，约束高风险模型更新。', real: true },
    { key: 'training-monitor', name: 'Training Monitor', shortName: 'TM', category: '过程监测', input: '训练指标', output: '实时告警', description: '统一记录训练轮次、损失曲线和异常告警，为安全训练过程提供可视化依据。', real: true }
  ] },
  post: { eyebrow: 'POST-TRAINING DEFENSE', index: '03', title: '训练后防御', description: '针对已训练模型开展安全评估、后门触发验证和模型修复，形成最终交付前的安全检查。', algorithms: [
    { key: 'cleanse', name: 'Neural Cleanse', shortName: 'NC', category: '触发器反演', input: '已训练模型', output: '触发器报告', description: '通过反演潜在触发器评估模型后门风险，生成模型安全诊断结果。', real: true },
    { key: 'pruning', name: 'Fine-Pruning', shortName: 'FP', category: '模型修复', input: '模型权重', output: '修复后模型', description: '结合神经元重要性分析与微调流程，削弱模型中的后门响应路径。', real: true },
    { key: 'model-evaluation', name: 'Model Evaluation', shortName: 'ME', category: '安全评估', input: '模型与测试集', output: '综合评估报告', description: '统一呈现干净准确率、攻击成功率和模型性能保持情况。', real: true }
  ] }
}
const stageMeta = computed(() => catalogs[stage.value])
function goStage(key) { router.push(`/defense/${key}`) }
</script>
