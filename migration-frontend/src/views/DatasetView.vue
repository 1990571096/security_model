<template>
  <div class="page resource-page">
    <div class="section-heading">
      <div>
        <span class="eyebrow">DATA RESOURCE CENTER</span>
        <h2>数据集资源</h2>
        <p class="page-subtitle">统一登记训练数据、污染配置和清洗后索引，保证每次实验都有明确的数据来源。</p>
      </div>
      <div class="heading-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadDatasets">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">登记数据集</el-button>
      </div>
    </div>
    <el-alert v-if="message" :title="message" type="info" :closable="false" show-icon class="page-alert" />
    <div class="resource-summary">
      <div><span class="muted">可用数据集</span><strong>{{ datasets.length }}</strong></div>
      <div><span class="muted">待验证资源</span><strong>{{ datasets.filter(item => item.parseStatus !== 'success').length }}</strong></div>
      <div><span class="muted">导出方式</span><strong>索引重建</strong></div>
    </div>
    <el-card shadow="never" class="resource-table-card">
      <template #header><div class="card-heading"><strong>已登记数据集</strong><el-tag type="success">资源可追溯</el-tag></div></template>
      <el-table v-loading="loading" :data="datasets" row-key="datasetId" empty-text="暂无已登记数据集">
        <el-table-column label="名称" min-width="190" show-overflow-tooltip><template #default="{ row }">{{ row.name || row.datasetName || `数据集 #${row.id}` }}</template></el-table-column>
        <el-table-column label="类型" width="140"><template #default="{ row }"><el-tag effect="plain">{{ row.datasetType || 'image_classification' }}</el-tag></template></el-table-column>
        <el-table-column label="样本量" width="120"><template #default="{ row }">{{ row.sampleCount || row.dataCount || '-' }}</template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="!row.enabled ? 'info' : row.parseStatus === 'success' ? 'success' : 'warning'">{{ !row.enabled ? '已停用' : row.parseStatus === 'success' ? '可用' : '待验证' }}</el-tag></template></el-table-column>
        <el-table-column label="说明" min-width="240" show-overflow-tooltip><template #default="{ row }">{{ row.description || '可用于 LDD 训练前数据清洗实验' }}</template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button><el-button v-if="row.enabled" link type="danger" @click="removeDataset(row)">停用</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑数据集' : '登记数据集'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="数据集名称" prop="datasetName"><el-input v-model="form.datasetName" placeholder="例如：CIFAR-10 训练集" /></el-form-item>
          <el-form-item label="数据类型" prop="datasetType"><el-select v-model="form.datasetType" class="full-width"><el-option label="图像分类" value="image_classification" /><el-option label="文本分类" value="text_classification" /><el-option label="表格数据" value="tabular" /></el-select></el-form-item>
          <el-form-item label="样本数量" prop="sampleCount"><el-input-number v-model="form.sampleCount" :min="1" class="full-width" /></el-form-item>
          <el-form-item label="类别数量"><el-input-number v-model="form.classCount" :min="1" class="full-width" /></el-form-item>
        </div>
        <el-form-item label="数据集路径" prop="filePath"><el-input v-model="form.filePath" placeholder="例如：D:/datasets/cifar10/train" /></el-form-item>
        <el-form-item label="数据集说明"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明数据来源、用途和版本信息" /></el-form-item>
        <el-form-item label="资源状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitForm">保存数据集</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { getDatasets, createDataset, updateDataset, disableDataset } from '../api/dataset'

const loading = ref(false)
const saving = ref(false)
const message = ref('')
const datasets = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const formRef = ref()
const form = ref({ datasetId: null, datasetName: '', datasetType: 'image', sampleCount: 50000, classCount: 10, filePath: '', description: '', enabled: true })
const rules = { datasetName: [{ required: true, message: '请输入数据集名称', trigger: 'blur' }], datasetType: [{ required: true, message: '请选择数据类型', trigger: 'change' }], sampleCount: [{ required: true, message: '请输入样本数量', trigger: 'change' }], filePath: [{ required: true, message: '请输入数据集路径', trigger: 'blur' }] }
function unwrap(data) { return data?.data ?? data ?? {} }
async function loadDatasets() {
  loading.value = true
  message.value = ''
  try {
    const data = unwrap(await getDatasets())
    const records = Array.isArray(data) ? data : data.records || data.content || []
    datasets.value = records
  } catch (error) {
    datasets.value = []
    message.value = error.response?.data?.message || '数据集读取失败，请检查服务连接后重试。'
  } finally {
    loading.value = false
  }
}
function openCreate() { editing.value = false; form.value = { datasetId: null, datasetName: '', datasetType: 'image', sampleCount: 50000, classCount: 10, filePath: '', description: '', enabled: true }; dialogVisible.value = true }
function openEdit(row) { editing.value = true; form.value = { datasetId: row.datasetId, datasetName: row.datasetName, datasetType: row.datasetType, sampleCount: row.sampleCount, classCount: row.classCount, filePath: row.filePath, description: row.description, enabled: row.enabled }; dialogVisible.value = true }
async function submitForm() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const { datasetId, ...payload } = form.value
    if (editing.value) await updateDataset(datasetId, payload)
    else await createDataset(payload)
    dialogVisible.value = false
    ElMessage.success(editing.value ? '数据集已更新' : '数据集已登记，待文件验证')
    await loadDatasets()
  } catch (error) { ElMessage.error(error.response?.data?.message || '保存失败') }
  finally { saving.value = false }
}
async function removeDataset(row) {
  try {
    await ElMessageBox.confirm(`停用“${row.datasetName}”后历史任务仍可查看，继续吗？`, '停用数据集', { type: 'warning' })
    await disableDataset(row.datasetId)
    await loadDatasets()
    ElMessage.success('数据集已停用')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '停用失败') }
}
onMounted(loadDatasets)
</script>
