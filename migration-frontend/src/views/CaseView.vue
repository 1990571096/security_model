<template>
  <div class="page case-page">
    <div class="section-heading">
      <div><span class="eyebrow">DEFENSE CASE LIBRARY</span><h2>防御案例</h2><p class="page-subtitle">沉淀典型数据污染、训练异常和模型后门场景，形成可复用的安全评测案例库。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="refreshCases">刷新案例</el-button><el-button type="primary" :icon="Plus" @click="openCase">新增案例</el-button></div>
    </div>
    <div class="case-filter"><el-radio-group v-model="stageFilter"><el-radio-button label="all">全部阶段</el-radio-button><el-radio-button label="pre">训练前防御</el-radio-button><el-radio-button label="during">训练中防御</el-radio-button><el-radio-button label="post">训练后防御</el-radio-button></el-radio-group><span class="muted">{{ filteredCases.length }} 个案例</span></div>
    <div class="case-grid"><el-card v-for="item in filteredCases" :key="item.caseId" shadow="never" class="case-card"><div class="case-card-top"><span class="case-id">{{ item.caseId }}</span><el-tag :type="item.status === '已完成' ? 'success' : item.status === '执行中' ? 'warning' : 'info'">{{ item.status }}</el-tag></div><h3>{{ item.title }}</h3><div class="case-line"><span>防御阶段</span><strong>{{ item.stage }}</strong></div><div class="case-line"><span>采用算法</span><strong>{{ item.algorithm }}</strong></div><div class="case-line"><span>样本规模</span><strong>{{ item.sampleCount }}</strong></div><div class="case-result"><span>分析结论</span><strong>{{ item.result }}</strong></div><div class="case-card-footer"><span>{{ item.date }}</span><el-button text type="primary" @click="showCase(item)">查看详情</el-button></div></el-card></div>
    <el-dialog v-model="dialogVisible" :title="selectedCase ? `案例详情 · ${selectedCase.caseId}` : '新增防御案例'" width="680px"><template v-if="selectedCase"><el-descriptions :column="2" border><el-descriptions-item label="案例名称">{{ selectedCase.title }}</el-descriptions-item><el-descriptions-item label="风险等级"><el-tag type="danger">{{ selectedCase.risk }}</el-tag></el-descriptions-item><el-descriptions-item label="防御阶段">{{ selectedCase.stage }}</el-descriptions-item><el-descriptions-item label="采用算法">{{ selectedCase.algorithm }}</el-descriptions-item><el-descriptions-item label="样本规模">{{ selectedCase.sampleCount }}</el-descriptions-item><el-descriptions-item label="处理样本">{{ selectedCase.removed }}</el-descriptions-item><el-descriptions-item label="分析结论" :span="2">{{ selectedCase.result }}</el-descriptions-item></el-descriptions><el-divider content-position="left">案例处置流程</el-divider><el-steps :active="3" finish-status="success"><el-step title="数据登记" /><el-step title="风险分析" /><el-step title="防御处置" /><el-step title="报告归档" /></el-steps></template><el-form v-else label-position="top"><div class="form-grid"><el-form-item label="案例名称"><el-input placeholder="例如：CIFAR-10 数据污染清洗案例" /></el-form-item><el-form-item label="防御阶段"><el-select class="full-width" placeholder="请选择阶段"><el-option label="训练前防御" value="pre" /><el-option label="训练中防御" value="during" /><el-option label="训练后防御" value="post" /></el-select></el-form-item></div><el-form-item label="案例说明"><el-input type="textarea" :rows="4" placeholder="填写威胁背景、处置目标和预期结果" /></el-form-item></el-form><template #footer><el-button @click="dialogVisible = false">关闭</el-button><el-button v-if="!selectedCase" type="primary" @click="createCase">保存案例</el-button></template></el-dialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { demoCases } from '../data/demoData'
const stageFilter = ref('all'); const dialogVisible = ref(false); const selectedCase = ref(null)
const filteredCases = computed(() => stageFilter.value === 'all' ? demoCases : demoCases.filter(item => item.stage.startsWith(stageFilter.value === 'pre' ? '训练前' : stageFilter.value === 'during' ? '训练中' : '训练后')))
function showCase(item) { selectedCase.value = item; dialogVisible.value = true }
function openCase() { selectedCase.value = null; dialogVisible.value = true }
function createCase() { dialogVisible.value = false; ElMessage.success('防御案例已加入案例库') }
function refreshCases() { ElMessage.success('案例库已刷新') }
</script>
