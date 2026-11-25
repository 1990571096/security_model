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
        <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">登记数据集</el-button>
      </div>
    </div>
    <el-alert v-if="message" :title="message" type="info" :closable="false" show-icon class="page-alert" />
    <div class="resource-summary">
      <div><span class="muted">已核验统计</span><strong>{{ datasets.filter(item => item.enabled && hasVerifiedStats(item)).length }}</strong></div>
      <div><span class="muted">仅路径可用</span><strong>{{ datasets.filter(item => item.enabled && item.parseStatus === 'success' && !hasVerifiedStats(item)).length }}</strong></div>
      <div><span class="muted">导出方式</span><strong>索引重建</strong></div>
    </div>
    <el-card shadow="never" class="resource-table-card">
      <template #header><div class="card-heading"><strong>已登记数据集</strong><el-tag type="success">资源可追溯</el-tag></div></template>
      <el-table v-loading="loading" :data="datasets" row-key="datasetId" empty-text="暂无已登记数据集">
        <el-table-column label="名称" min-width="190" show-overflow-tooltip><template #default="{ row }">{{ row.name || row.datasetName || `数据集 #${row.id}` }}</template></el-table-column>
        <el-table-column label="类型" width="140"><template #default="{ row }"><el-tag effect="plain">{{ row.datasetType || 'image_classification' }}</el-tag></template></el-table-column>
        <el-table-column label="训练样本" width="115"><template #default="{ row }">{{ hasVerifiedStats(row) ? row.sampleCount.toLocaleString('zh-CN') : '—' }}</template></el-table-column>
        <el-table-column label="类别数" width="90"><template #default="{ row }">{{ hasVerifiedStats(row) ? row.classCount : '—' }}</template></el-table-column>
        <el-table-column label="状态" width="145">
          <template #default="{ row }">
            <el-tooltip :content="row.parseMessage" :disabled="!row.parseMessage">
              <el-tag :type="!row.enabled ? 'info' : row.parseStatus === 'success' ? 'success' : row.parseStatus === 'failed' ? 'danger' : 'warning'">
                {{ !row.enabled ? '已停用' : hasVerifiedStats(row) ? '内容已核验' : row.parseStatus === 'success' ? '仅路径可用' : row.parseStatus === 'failed' ? '验证失败' : '待验证' }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="240" show-overflow-tooltip><template #default="{ row }">{{ row.description || '-' }}</template></el-table-column>
        <el-table-column v-if="isAdmin" label="操作" width="195" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.enabled" link type="primary" :loading="validatingId === row.datasetId" :disabled="validatingId !== null" @click="verify(row)">验证数据</el-button>
            <el-button v-if="row.enabled" link type="danger" @click="removeDataset(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑数据集' : '登记数据集'" width="min(620px, 94vw)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="数据集名称" prop="datasetName"><el-input v-model="form.datasetName" placeholder="例如：CIFAR-10 训练集" /></el-form-item>
          <el-form-item label="数据类型" prop="datasetType"><el-select v-model="form.datasetType" class="full-width"><el-option label="图像分类" value="image_classification" /><el-option label="文本分类" value="text_classification" /><el-option label="表格数据" value="tabular" /></el-select></el-form-item>
        </div>
        <el-form-item label="数据集路径" prop="filePath"><el-input v-model="form.filePath" placeholder="例如：D:/datasets/cifar-10-batches-py" /></el-form-item>
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
import { getDatasets, createDataset, updateDataset, disableDataset, validateDataset } from '../api/dataset'
import { getUserInfo } from '../api/auth'

const loading = ref(false)
const saving = ref(false)
const message = ref('')
const datasets = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const isAdmin = ref(false)
const validatingId = ref(null)
const formRef = ref()
function emptyForm() {
  return { datasetId: null, datasetName: '', datasetType: 'image_classification', filePath: '', description: '', enabled: true }
}
const form = ref(emptyForm())
const rules = { datasetName: [{ required: true, message: '请输入数据集名称', trigger: 'blur' }], datasetType: [{ required: true, message: '请选择数据类型', trigger: 'change' }], filePath: [{ required: true, message: '请输入数据集路径', trigger: 'blur' }] }
function unwrap(data) { return data?.data ?? data ?? {} }
function hasVerifiedStats(row) {
  return row.parseStatus === 'success' && Number.isInteger(row.sampleCount) && Number.isInteger(row.classCount)
}
async function loadDatasets() {
  loading.value = true
  message.value = ''
  try {
    const data = unwrap(await getDatasets())
    const records = Array.isArray(data) ? data : data.records || data.content || []
    datasets.value = records
  } catch (error) {
    datasets.value = []
    const status = error.response?.status
    message.value = error.response?.data?.message
      || (status === 401 ? '登录会话已失效，请重新登录后查看数据集。'
        : status === 502 || status === 503 ? '后端服务暂不可用，请确认服务已启动。'
          : '数据集读取失败，请检查服务连接后重试。')
  } finally {
    loading.value = false
  }
}
function openCreate() { editing.value = false; form.value = emptyForm(); dialogVisible.value = true }
function openEdit(row) { editing.value = true; form.value = { datasetId: row.datasetId, datasetName: row.datasetName, datasetType: row.datasetType, filePath: row.filePath, description: row.description, enabled: row.enabled }; dialogVisible.value = true }
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
async function verify(row) {
  if (validatingId.value !== null) return
  validatingId.value = row.datasetId
  try {
    const result = unwrap(await validateDataset(row.datasetId))
    if (result.parseStatus === 'success') ElMessage.success(result.parseMessage || '路径验证通过')
    else ElMessage.warning(result.parseMessage || '路径验证失败')
    await loadDatasets()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '数据集验证请求失败')
  } finally {
    validatingId.value = null
  }
}
onMounted(() => {
  loadDatasets()
  getUserInfo().then(response => { isAdmin.value = unwrap(response).admin === true })
    .catch(() => { isAdmin.value = false })
})
</script>
