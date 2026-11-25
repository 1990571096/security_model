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
        <el-menu-item index="/assistant">
          <el-icon><ChatDotRound /></el-icon>
          <span>实验助手</span>
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

        <div v-if="isAdmin" class="menu-group-label">系统管理</div>
        <el-menu-item v-if="isAdmin" index="/accounts">
          <el-icon><User /></el-icon>
          <span>账号管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header v-if="isAuthenticated" class="topbar">
        <div class="topbar-title">
          <span class="eyebrow">SECURITY MODEL PLATFORM</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="topbar-account">
          <div class="account-identity" aria-live="polite">
            <template v-if="user">
              <strong>{{ user.nickName || user.username }}</strong>
              <span v-if="user.nickName && user.nickName !== user.username">@{{ user.username }}</span>
            </template>
            <span v-else>{{ identityLoading ? '正在读取账号' : '账号信息暂不可用' }}</span>
          </div>
          <el-tag v-if="user" size="small" :type="isAdmin ? 'primary' : 'info'">
            {{ isAdmin ? '管理员' : '实验员' }}
          </el-tag>
          <el-button text @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <nav v-if="isAuthenticated" class="mobile-nav" aria-label="移动端导航">
        <router-link to="/dashboard">工作台</router-link>
        <router-link to="/datasets">数据集</router-link>
        <router-link to="/algorithms">算法</router-link>
        <router-link to="/tasks">任务</router-link>
        <router-link to="/runtime-monitor">监控</router-link>
        <router-link to="/knowledge">知识</router-link>
        <router-link to="/assistant">助手</router-link>
        <router-link to="/statistics">统计</router-link>
        <router-link to="/cases">案例</router-link>
        <router-link to="/defense/pre">训练前</router-link>
        <router-link to="/defense/during">训练中</router-link>
        <router-link to="/defense/post">训练后</router-link>
        <router-link v-if="isAdmin" to="/accounts">账号管理</router-link>
      </nav>
      <el-main :class="{ 'plain-main': !isAuthenticated }">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserInfo } from './api/auth'
import {
  Collection,
  Cpu,
  ChatDotRound,
  DataAnalysis,
  Lock,
  Monitor,
  Operation,
  Promotion,
  TrendCharts,
  Memo,
  Search,
  User
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const isAuthenticated = computed(() => (
  route.path !== '/login' && Boolean(localStorage.getItem('security-model-token'))
))
const user = ref(null)
const identityLoading = ref(false)
const isAdmin = computed(() => user.value?.admin === true)
watch(isAuthenticated, async (authenticated, _, onCleanup) => {
  user.value = null
  identityLoading.value = authenticated
  if (!authenticated) return
  let current = true
  onCleanup(() => { current = false })
  try {
    const { data } = await getUserInfo()
    if (current) user.value = data
  } catch {
    if (current) user.value = null
  } finally {
    if (current) identityLoading.value = false
  }
}, { immediate: true })

const pageTitles = {
  '/dashboard': '安全训练工作台',
  '/tasks': '安全防御任务',
  '/runtime-monitor': '运行监控',
  '/knowledge': '知识检索',
  '/assistant': '受控实验助手',
  '/datasets': '数据集资源',
  '/algorithms': '算法资源',
  '/accounts': '账号管理',
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

<style scoped>
.topbar {
  gap: 12px;
}

.topbar-title,
.account-identity {
  min-width: 0;
}

.topbar-account {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.account-identity {
  display: grid;
  text-align: right;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.account-identity span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 600px) {
  .topbar {
    height: auto;
    flex-wrap: wrap;
    padding-block: 10px;
  }

  .account-identity {
    text-align: left;
  }
}
</style>
