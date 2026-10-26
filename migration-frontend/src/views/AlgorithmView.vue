<template>
  <div class="page resource-page">
    <div class="section-heading">
      <div><span class="eyebrow">ALGORITHM REGISTRY</span><h2>算法资源</h2><p class="page-subtitle">统一维护三大防御阶段的算法元数据、适用场景和接入状态。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" :loading="loading" @click="loadAlgorithms">刷新</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新增算法</el-button></div>
    </div>
    <el-alert v-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon class="page-alert" />
    <div class="algorithm-filter"><el-radio-group v-model="stageFilter"><el-radio-button label="all">全部阶段</el-radio-button><el-radio-button label="pre">训练前防御</el-radio-button><el-radio-button label="during">训练中防御</el-radio-button><el-radio-button label="post">训练后防御</el-radio-button></el-radio-group><span class="muted">{{ algorithmItems.length }} 个算法方案</span></div>
    <div class="algorithm-grid">
      <el-card v-for="item in algorithmItems" :key="item.key" shadow="never" class="algorithm-card">
        <div class="algorithm-card-head"><div class="algorithm-icon">{{ item.shortName }}</div><el-tag :type="item.enabled ? 'success' : 'info'">{{ item.enabled ? '已登记' : '已停用' }}</el-tag></div>
        <h3>{{ item.name }}</h3><p>{{ item.description }}</p><div class="algorithm-meta"><span>{{ item.stage }}</span><span>{{ item.type }}</span></div><div class="algorithm-detail-row"><span>算法分类</span><strong>{{ item.category }}</strong></div>
        <div class="card-actions"><el-button type="primary" text @click="$router.push(`/algorithms/${item.key}`)">查看详情</el-button><el-button v-if="item.implementationPath === 'vab://pre-defense/ldd' && item.enabled" text @click="$router.push('/tasks')">进入任务中心</el-button><el-button text @click="openEdit(item)">编辑</el-button><el-button v-if="item.enabled" type="danger" text @click="removeAlgorithm(item)">停用</el-button></div>
      </el-card>
    </div>
    <el-empty v-if="!loading && !algorithmItems.length" description="暂无已登记算法" />
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑算法' : '新增算法'" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top"><div class="form-grid">
        <el-form-item label="算法名称" prop="algorithmName"><el-input v-model="form.algorithmName" placeholder="例如：Spectral Signature" /></el-form-item>
        <el-form-item label="防御阶段" prop="defenseStage"><el-select v-model="form.defenseStage" class="full-width"><el-option label="训练前防御" value="pre" /><el-option label="训练中防御" value="during" /><el-option label="训练后防御" value="post" /></el-select></el-form-item>
        <el-form-item label="算法类型" prop="algorithmType"><el-select v-model="form.algorithmType" class="full-width"><el-option label="数据清洗" value="data_cleaning" /><el-option label="模型训练" value="model_training" /><el-option label="模型评估" value="model_evaluation" /></el-select></el-form-item>
        <el-form-item label="算法分类"><el-input v-model="form.algorithmCategory" placeholder="例如：经典基线" /></el-form-item>
      </div><el-form-item label="算法描述" prop="description"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item><el-form-item label="实现路径"><el-input v-model="form.implementationPath" placeholder="例如：vab://pre-defense/ldd" /></el-form-item><el-form-item label="目录状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item></el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitForm">保存算法</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { getAlgorithms, createAlgorithm, updateAlgorithm, disableAlgorithm } from '../api/algorithm'
const loading = ref(false); const saving = ref(false); const errorMessage = ref(''); const algorithms = ref([]); const stageFilter = ref('all'); const dialogVisible = ref(false); const editing = ref(false); const formRef = ref()
const form = ref({ algorithmId: null, algorithmName: '', defenseStage: 'pre', algorithmType: 'data_cleaning', algorithmCategory: '经典基线', description: '', implementationPath: '', enabled: true })
const rules = { algorithmName: [{ required: true, message: '请输入算法名称', trigger: 'blur' }], defenseStage: [{ required: true, message: '请选择防御阶段', trigger: 'change' }], algorithmType: [{ required: true, message: '请选择算法类型', trigger: 'change' }], description: [{ required: true, message: '请输入算法描述', trigger: 'blur' }] }
const algorithmItems = computed(() => { const source = algorithms.value.map(item => ({ ...item, key: item.algorithmId, name: item.algorithmName, shortName: item.algorithmName?.slice(0, 3).toUpperCase(), stage: item.defenseStage === 'pre' ? '训练前防御' : item.defenseStage === 'during' ? '训练中防御' : '训练后防御', stageKey: item.defenseStage, type: item.algorithmType, category: item.algorithmCategory || '未分类' })); return stageFilter.value === 'all' ? source : source.filter(item => item.stageKey === stageFilter.value) })
async function loadAlgorithms() { loading.value = true; errorMessage.value = ''; try { const response = await getAlgorithms(); const data = response?.data?.data ?? response?.data ?? response; algorithms.value = Array.isArray(data) ? data : data.records || [] } catch (error) { algorithms.value = []; errorMessage.value = error.response?.data?.message || '算法目录读取失败，请重试。' } finally { loading.value = false } }
function openCreate() { editing.value = false; form.value = { algorithmId: null, algorithmName: '', defenseStage: 'pre', algorithmType: 'data_cleaning', algorithmCategory: '经典基线', description: '', implementationPath: '', enabled: true }; dialogVisible.value = true }
function openEdit(item) { editing.value = true; form.value = { algorithmId: item.algorithmId, algorithmName: item.algorithmName, defenseStage: item.defenseStage, algorithmType: item.algorithmType, algorithmCategory: item.algorithmCategory, description: item.description, implementationPath: item.implementationPath, enabled: item.enabled }; dialogVisible.value = true }
async function submitForm() { const valid = await formRef.value?.validate().catch(() => false); if (!valid) return; saving.value = true; try { const { algorithmId, ...payload } = form.value; if (editing.value) await updateAlgorithm(algorithmId, payload); else await createAlgorithm(payload); dialogVisible.value = false; ElMessage.success(editing.value ? '算法已更新' : '算法已登记'); await loadAlgorithms() } catch (error) { ElMessage.error(error.response?.data?.message || '保存失败') } finally { saving.value = false } }
async function removeAlgorithm(item) { try { await ElMessageBox.confirm(`确认停用“${item.name}”吗？`, '停用算法', { type: 'warning' }); await disableAlgorithm(item.algorithmId); await loadAlgorithms(); ElMessage.success('算法已停用') } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '停用失败') } }
onMounted(loadAlgorithms)
</script>
