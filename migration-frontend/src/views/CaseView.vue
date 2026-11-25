<template>
  <div class="page case-page">
    <div class="section-heading">
      <div><span class="eyebrow">DEFENSE CASE LIBRARY</span><h2>防御案例</h2><p class="page-subtitle">沉淀典型数据污染、训练异常和模型后门场景，形成可复用的安全评测案例库。<strong>当前为演示占位数据，不代表真实实验结论。</strong></p></div>
    </div>
    <el-alert title="演示占位：案例库尚未接入后端数据，以下案例、风险等级与结论均为示例，不得作为真实运行数据或实验指标。" type="warning" :closable="false" show-icon class="page-alert" />
    <div class="case-filter"><el-radio-group v-model="stageFilter"><el-radio-button label="all">全部阶段</el-radio-button><el-radio-button label="pre">训练前防御</el-radio-button><el-radio-button label="during">训练中防御</el-radio-button><el-radio-button label="post">训练后防御</el-radio-button></el-radio-group><span class="muted">{{ filteredCases.length }} 个案例</span></div>
    <div class="case-grid"><el-card v-for="item in filteredCases" :key="item.caseId" shadow="never" class="case-card"><div class="case-card-top"><span class="case-id">{{ item.caseId }}</span><el-tag type="info" size="small">演示</el-tag><el-tag :type="item.status === '已完成' ? 'success' : item.status === '执行中' ? 'warning' : 'info'">{{ item.status }}</el-tag></div><h3>{{ item.title }}</h3><div class="case-line"><span>防御阶段</span><strong>{{ item.stage }}</strong></div><div class="case-line"><span>采用算法</span><strong>{{ item.algorithm }}</strong></div><div class="case-line"><span>样本规模</span><strong>{{ item.sampleCount }}</strong></div><div class="case-result"><span>分析结论</span><strong>{{ item.result }}</strong></div><div class="case-card-footer"><span>{{ item.date }}</span><el-button text type="primary" @click="showCase(item)">查看详情</el-button></div></el-card></div>
    <el-dialog v-model="dialogVisible" :title="`案例详情 · ${selectedCase?.caseId ?? ''}`" width="min(680px, 94vw)">
      <template v-if="selectedCase">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="案例名称">{{ selectedCase.title }}</el-descriptions-item>
          <el-descriptions-item label="风险等级">{{ selectedCase.risk }}</el-descriptions-item>
          <el-descriptions-item label="防御阶段">{{ selectedCase.stage }}</el-descriptions-item>
          <el-descriptions-item label="采用算法">{{ selectedCase.algorithm }}</el-descriptions-item>
          <el-descriptions-item label="样本规模">{{ selectedCase.sampleCount }}</el-descriptions-item>
          <el-descriptions-item label="处理样本">{{ selectedCase.removed }}</el-descriptions-item>
          <el-descriptions-item label="分析结论">{{ selectedCase.result }}</el-descriptions-item>
        </el-descriptions>
        <p class="muted">示意流程：数据登记 → 风险分析 → 防御处置 → 报告归档</p>
      </template>
      <template #footer><el-button @click="dialogVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { demoCases } from '../data/demoData'
const stageFilter = ref('all'); const dialogVisible = ref(false); const selectedCase = ref(null)
const filteredCases = computed(() => stageFilter.value === 'all' ? demoCases : demoCases.filter(item => item.stage.startsWith(stageFilter.value === 'pre' ? '训练前' : stageFilter.value === 'during' ? '训练中' : '训练后')))
function showCase(item) { selectedCase.value = item; dialogVisible.value = true }
</script>
