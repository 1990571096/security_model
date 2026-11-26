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
      <h2>{{ stageMeta.title }}算法</h2>
      <el-button :icon="Refresh" :loading="loading" @click="loadAlgorithms">刷新</el-button>
    </div>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon class="page-alert" />
    <el-empty v-else-if="!loading && !stageAlgorithms.length" description="该阶段暂无登记算法" />
    <div v-loading="loading" class="algorithm-grid defense-algorithm-grid">
      <el-card v-for="item in stageAlgorithms" :key="item.algorithmId" shadow="never" class="algorithm-card defense-algorithm-card">
        <div class="algorithm-card-head">
          <div class="algorithm-icon">{{ item.algorithmName?.slice(0, 3).toUpperCase() }}</div>
          <el-tag v-if="!item.enabled" type="info">已停用</el-tag>
          <el-tag v-else-if="isRunnable(item)" type="success">执行器已接入</el-tag>
          <el-tag v-else type="info">仅目录登记</el-tag>
        </div>
        <h3>{{ item.algorithmName }}</h3><p>{{ item.description }}</p>
        <div class="algorithm-meta"><span>{{ item.algorithmCategory || '未分类' }}</span></div>
        <el-button type="primary" :icon="View" @click="router.push(`/algorithms/${item.algorithmId}`)">查看详情</el-button>
        <el-button v-if="isRunnable(item)" :icon="Operation" @click="router.push('/tasks')">进入任务中心</el-button>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Operation, Refresh, View } from '@element-plus/icons-vue'
import { getAlgorithms } from '../api/algorithm'

const route = useRoute()
const router = useRouter()
const stage = computed(() => ['pre', 'during', 'post'].includes(route.params.stage) ? route.params.stage : 'pre')
const stages = [
  { key: 'pre', index: '01', title: '训练前防御', subtitle: '数据清洗与筛选' },
  { key: 'during', index: '02', title: '训练中防御', subtitle: '鲁棒训练与监测' },
  { key: 'post', index: '03', title: '训练后防御', subtitle: '模型评估与修复' }
]
const catalogs = {
  pre: { eyebrow: 'PRE-TRAINING DEFENSE', index: '01', title: '训练前防御', description: '数据清洗与筛选' },
  during: { eyebrow: 'IN-TRAINING DEFENSE', index: '02', title: '训练中防御', description: '鲁棒训练与监测' },
  post: { eyebrow: 'POST-TRAINING DEFENSE', index: '03', title: '训练后防御', description: '模型评估与修复' }
}
const algorithms = ref([])
const loading = ref(false)
const errorMessage = ref('')
const stageMeta = computed(() => catalogs[stage.value])
const stageAlgorithms = computed(() => algorithms.value.filter(item => item.defenseStage === stage.value))

function isRunnable(item) {
  return item.enabled && item.defenseStage === 'pre' && item.implementationPath === 'vab://pre-defense/ldd'
}

async function loadAlgorithms() {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await getAlgorithms()
    const result = data?.data ?? data
    algorithms.value = Array.isArray(result) ? result : (result?.records ?? [])
  } catch (error) {
    algorithms.value = []
    errorMessage.value = error.response?.data?.message || '算法目录读取失败'
  } finally {
    loading.value = false
  }
}

function goStage(key) { router.push(`/defense/${key}`) }
onMounted(loadAlgorithms)
</script>
