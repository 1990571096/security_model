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
      <p class="login-description">围绕训练前数据清洗、训练过程防护和训练后模型审计，统一沉淀数据资源、实验任务、参数快照与安全评估结果。</p>
      <div class="login-highlights">
        <div>
          <strong>3</strong>
          <span>防御阶段</span>
        </div>
        <div>
          <strong>9</strong>
          <span>算法方案</span>
        </div>
        <div>
          <strong>4</strong>
          <span>数据资源</span>
        </div>
      </div>
      <div class="login-status">
        <i></i>
        <span>平台运行正常</span>
        <em>数据与实验环境已就绪</em>
      </div>
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
        <el-form-item class="captcha-row">
          <el-input v-model="captcha" placeholder="请输入验证码" size="large" />
          <img v-if="captchaImage" :src="captchaImage" alt="验证码，点击刷新" @click="loadCaptcha" />
        </el-form-item>
        <el-button type="primary" size="large" class="full-width" @click="login">进入工作台</el-button>
      </el-form>
      <p class="login-footnote">平台已完成数据资源、算法资源、实验任务和安全评估模块接入。</p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCaptcha, login as loginRequest } from '../api/auth'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const captcha = ref('')
const captchaUuid = ref('')
const captchaImage = ref('')

async function loadCaptcha() {
  try {
    const { data } = await getCaptcha()
    captchaUuid.value = data.uuid
    captchaImage.value = data.img
  } catch {
    ElMessage.error('验证码加载失败')
  }
}

async function login() {
  if (!username.value || !password.value || !captcha.value || !captchaUuid.value) {
    ElMessage.warning('请填写用户名、密码和验证码')
    return
  }
  try {
    const { data } = await loginRequest({
      username: username.value,
      password: password.value,
      code: captcha.value,
      uuid: captchaUuid.value
    })
    localStorage.setItem('security-model-token', data.token)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '登录失败')
    loadCaptcha()
  }
}

loadCaptcha()
</script>
