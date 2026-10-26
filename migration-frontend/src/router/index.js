import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/dashboard', component: () => import('../views/DashboardView.vue') },
    { path: '/datasets', component: () => import('../views/DatasetView.vue') },
    { path: '/algorithms', component: () => import('../views/AlgorithmView.vue') },
    { path: '/algorithms/:algorithmKey', component: () => import('../views/AlgorithmDetailView.vue') },
    { path: '/tasks', component: () => import('../views/TaskCenterView.vue') },
    { path: '/runtime-monitor', component: () => import('../views/RuntimeMonitorView.vue') },
    { path: '/training-defense', component: () => import('../views/TrainingDefenseView.vue') },
    { path: '/statistics', component: () => import('../views/StatisticsView.vue') },
    { path: '/cases', component: () => import('../views/CaseView.vue') },
    { path: '/defense/:stage', component: () => import('../views/DefenseStageView.vue') }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('security-model-token')) return '/login'
})

export default router
