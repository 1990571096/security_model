<template>
  <div class="login-page">
    <section class="login-copy">
      <div class="login-brand-line">
        <div class="brand-mark login-brand-mark">SM</div>
        <div>
          <strong>安全模型训练平台</strong>
          <span>数据安全防护与实验管理中心</span>
        </div>
      </div>
      <span class="eyebrow">SECURE MODEL TRAINING CENTER</span>
      <h1>面向数据清洗的安全模型训练系统</h1>
      <p class="login-description">管理数据集、LDD 清洗实验、参数快照与结果报告。</p>
    </section>
    <el-card class="login-card" shadow="never">
      <div class="login-card-heading">
        <div>
          <span class="eyebrow">ACCOUNT ACCESS</span>
          <h2>登录平台</h2>
          <p class="muted">进入安全实验与任务管理中心</p>
        </div>
        <span class="login-card-badge">安全接入</span>
      </div>
      <div class="login-divider"></div>
      <div class="login-form-label">账户信息</div>
      <el-form @submit.prevent="login">
        <el-form-item><el-input v-model="username" placeholder="请输入用户名" size="large" /></el-form-item>
        <el-form-item><el-input v-model="password" type="password" placeholder="请输入密码" size="large" show-password /></el-form-item>
        <el-button type="primary" native-type="submit" size="large" class="full-width" :loading="loading">进入工作台</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login as loginRequest } from '../api/auth'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const loading = ref(false)

async function login() {
  if (loading.value) return
  if (!username.value.trim() || !password.value) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  loading.value = true
  try {
    const { data } = await loginRequest({
      username: username.value.trim(),
      password: password.value
    })
    localStorage.setItem('security-model-token', data.token)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>
