<template>
  <el-container class="app-shell">
    <el-aside v-if="isAuthenticated" width="232px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">SM</div>
        <div>
          <strong>Security Model</strong>
          <span>安全模型训练平台</span>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="menu">
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/datasets">
          <el-icon><Collection /></el-icon>
          <span>数据集资源</span>
        </el-menu-item>
        <el-menu-item index="/algorithms">
          <el-icon><Cpu /></el-icon>
          <span>算法资源</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><Operation /></el-icon>
          <span>安全防御任务</span>
        </el-menu-item>
        <el-menu-item index="/runtime-monitor">
          <el-icon><Monitor /></el-icon>
          <span>运行监控</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Search /></el-icon>
          <span>知识检索</span>
        </el-menu-item>
        <el-menu-item index="/statistics">
          <el-icon><TrendCharts /></el-icon>
          <span>数据统计</span>
        </el-menu-item>
        <el-menu-item index="/cases">
          <el-icon><Memo /></el-icon>
          <span>防御案例</span>
        </el-menu-item>

        <div class="menu-group-label">防御体系</div>
        <el-menu-item index="/defense/pre">
          <el-icon><Lock /></el-icon>
          <span>训练前防御</span>
        </el-menu-item>
        <el-menu-item index="/defense/during">
          <el-icon><Monitor /></el-icon>
          <span>训练中防御</span>
        </el-menu-item>
        <el-menu-item index="/defense/post">
          <el-icon><Promotion /></el-icon>
          <span>训练后防御</span>
        </el-menu-item>

        <div class="menu-group-label">系统管理</div>
        <el-menu-item index="/training-defense">
          <el-icon><Setting /></el-icon>
          <span>参数配置台账</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header v-if="isAuthenticated" class="topbar">
        <div>
          <span class="eyebrow">SECURITY MODEL PLATFORM</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <el-button text @click="logout">退出登录</el-button>
      </el-header>
      <nav v-if="isAuthenticated" class="mobile-nav" aria-label="移动端导航">
        <router-link to="/dashboard">工作台</router-link>
        <router-link to="/datasets">数据集</router-link>
        <router-link to="/algorithms">算法</router-link>
        <router-link to="/tasks">任务</router-link>
        <router-link to="/runtime-monitor">监控</router-link>
        <router-link to="/knowledge">知识</router-link>
        <router-link to="/statistics">统计</router-link>
        <router-link to="/cases">案例</router-link>
        <router-link to="/defense/pre">训练前</router-link>
        <router-link to="/defense/during">训练中</router-link>
        <router-link to="/defense/post">训练后</router-link>
      </nav>
      <el-main :class="{ 'plain-main': !isAuthenticated }">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Collection,
  Cpu,
  DataAnalysis,
  Lock,
  Monitor,
  Operation,
  Promotion,
  Setting,
  TrendCharts,
  Memo,
  Search
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const isAuthenticated = computed(() => (
  route.path !== '/login' && Boolean(localStorage.getItem('security-model-token'))
))

const pageTitles = {
  '/dashboard': '安全训练工作台',
  '/tasks': '安全防御任务',
  '/runtime-monitor': '运行监控',
  '/knowledge': '知识检索',
  '/datasets': '数据集资源',
  '/algorithms': '算法资源',
  '/training-defense': '参数配置台账',
  '/statistics': '数据统计',
  '/cases': '防御案例',
  '/defense/pre': '训练前防御',
  '/defense/during': '训练中防御',
  '/defense/post': '训练后防御'
}

const pageTitle = computed(() => pageTitles[route.path] || (route.path.startsWith('/algorithms/') ? '算法详情' : '安全训练工作台'))

function logout() {
  localStorage.removeItem('security-model-token')
  localStorage.removeItem('security-model-demo')
  router.push('/login')
}
</script>
