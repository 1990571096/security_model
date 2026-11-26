<template>
  <div class="page">
    <div class="section-heading">
      <h2>账号管理</h2>
      <div class="heading-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadAccounts">刷新</el-button>
        <el-button v-if="authorized" type="primary" :icon="Plus" @click="openCreate">新建实验员</el-button>
      </div>
    </div>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
    <el-table v-if="authorized" v-loading="loading" :data="accounts" row-key="userId">
      <el-table-column prop="username" label="用户名" min-width="150" />
      <el-table-column prop="nickName" label="显示名称" min-width="150" />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">{{ row.admin ? '管理员' : '实验员' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="155">
        <template #default="{ row }">
          <template v-if="!row.admin">
            <el-button link :type="row.enabled ? 'danger' : 'primary'"
                       :loading="changingId === row.userId" :disabled="accountActionBusy" @click="changeStatus(row)">
              {{ row.enabled ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" :loading="deletingId === row.userId"
                       :disabled="accountActionBusy" @click="deleteAccount(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="createVisible" title="新建实验员" width="min(460px, 94vw)" @closed="clearForm">
      <el-form label-position="top" @submit.prevent="createAccount">
        <el-form-item label="用户名"><el-input v-model.trim="form.username" maxlength="64" autocomplete="off" placeholder="3–64 位字母、数字或 _ . -" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model.trim="form.nickName" maxlength="100" /></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="72" placeholder="至少 8 个字符" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving">创建账号</el-button>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const accounts = ref([])
const authorized = ref(false)
const loading = ref(false)
const saving = ref(false)
const changingId = ref(null)
const deletingId = ref(null)
const accountActionBusy = computed(() => changingId.value !== null || deletingId.value !== null)
const createVisible = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', nickName: '', password: '' })
const endpoint = '/api/admin/accounts'

async function loadAccounts() {
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await http.get(endpoint)
    accounts.value = data
    authorized.value = true
  } catch (error) {
    if (error.response?.status === 403) {
      authorized.value = false
      accounts.value = []
    }
    errorMessage.value = error.response?.data?.message || '账号列表读取失败'
  } finally {
    loading.value = false
  }
}

function clearForm() {
  Object.assign(form, { username: '', nickName: '', password: '' })
}

function openCreate() {
  clearForm()
  createVisible.value = true
}

async function createAccount() {
  if (saving.value) return
  if (!/^[A-Za-z0-9_.-]{3,64}$/.test(form.username) || !form.nickName || form.password.length < 8) {
    ElMessage.warning('请填写有效的用户名、显示名称和至少 8 个字符的密码')
    return
  }
  saving.value = true
  try {
    await http.post(endpoint, { ...form })
    createVisible.value = false
    await loadAccounts()
    ElMessage.success('实验员账号已创建')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '创建账号失败')
  } finally {
    saving.value = false
  }
}

async function changeStatus(row) {
  if (accountActionBusy.value) return
  changingId.value = row.userId
  try {
    if (row.enabled) {
      await ElMessageBox.confirm(`禁用 ${row.username} 后，其现有会话也将失去访问权限。`, '禁用账号', { type: 'warning' })
    }
    await http.put(`${endpoint}/${row.userId}/enabled`, { enabled: !row.enabled })
    await loadAccounts()
    ElMessage.success(row.enabled ? '账号已禁用' : '账号已启用')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '账号状态变更失败')
  } finally {
    changingId.value = null
  }
}

async function deleteAccount(row) {
  if (accountActionBusy.value) return
  deletingId.value = row.userId
  try {
    await ElMessageBox.confirm(
      `删除 ${row.username} 后，该账号将失去登录权限，用户名不可复用。历史任务、报告和审计保留，已启动的任务将继续运行。`,
      '删除实验员账号',
      { type: 'warning', confirmButtonText: '删除账号', cancelButtonText: '取消' },
    )
    await http.delete(`${endpoint}/${row.userId}`)
    accounts.value = accounts.value.filter(account => account.userId !== row.userId)
    ElMessage.success('实验员账号已删除')
    await loadAccounts()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '删除账号失败')
  } finally {
    deletingId.value = null
  }
}

onMounted(loadAccounts)
</script>
