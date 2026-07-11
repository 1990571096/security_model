import request from '@/utils/request'

/**
 * 获取所有运行中的任务
 */
export function getRunningTasks() {
  return request({
    url: 'api/task-progress/running',
    method: 'get'
  })
}

/**
 * 获取所有任务（支持筛选）
 * @param {String} status 任务状态（可选）
 * @param {String} taskType 任务类型（可选）
 */
export function getAllTasks(status, taskType) {
  return request({
    url: 'api/task-progress',
    method: 'get',
    params: { status, taskType }
  })
}

/**
 * 根据任务类型和ID获取任务详情
 * @param {String} taskType 任务类型
 * @param {Number} taskId 任务ID
 */
export function getTaskDetail(taskType, taskId) {
  return request({
    url: `api/task-progress/${taskType}/${taskId}`,
    method: 'get'
  })
}

/**
 * 获取任务统计信息
 */
export function getTaskStatistics() {
  return request({
    url: 'api/task-progress/statistics',
    method: 'get'
  })
}

/**
 * 批量获取任务进度
 * @param {Array} taskInfos 任务信息列表 [{"taskType": "pre_defense", "taskId": 1}, ...]
 */
export function getBatchTaskProgress(taskInfos) {
  return request({
    url: 'api/task-progress/progress/batch',
    method: 'post',
    data: taskInfos
  })
}

/**
 * 刷新任务状态
 */
export function refreshTaskStatus() {
  return request({
    url: 'api/task-progress/refresh',
    method: 'post'
  })
}

// 以下是一些便捷方法，用于特定场景的查询

/**
 * 获取等待中的任务
 */
export function getPendingTasks() {
  return getAllTasks('pending')
}

/**
 * 获取成功完成的任务
 */
export function getSuccessTasks() {
  return getAllTasks('success')
}

/**
 * 获取失败的任务
 */
export function getFailedTasks() {
  return getAllTasks('failed')
}

/**
 * 按任务类型获取任务
 * @param {String} taskType 任务类型: pre_defense, during_defense, post_defense
 */
export function getTasksByType(taskType) {
  return getAllTasks(null, taskType)
}

/**
 * 获取训练前防御任务
 */
export function getPreDefenseTasks() {
  return getTasksByType('pre_defense')
}

/**
 * 获取训练中防御任务
 */
export function getDuringDefenseTasks() {
  return getTasksByType('during_defense')
}

/**
 * 获取训练后防御任务
 */
export function getPostDefenseTasks() {
  return getTasksByType('post_defense')
}

/**
 * 跳转到对应的任务详情页面
 * @param {String} taskType 任务类型
 * @param {Number} taskId 任务ID
 * @param {Object} router Vue Router实例
 */
export function navigateToTaskDetail(taskType, taskId, router) {
  let routePath
  switch (taskType) {
    case 'pre_defense':
      routePath = `/system/preDefense?taskId=${taskId}`
      break
    case 'during_defense':
      routePath = `/system/duringDefense?taskId=${taskId}`
      break
    case 'post_defense':
      routePath = `/system/postDefense?taskId=${taskId}`
      break
    default:
      console.error('不支持的任务类型:', taskType)
      return
  }

  if (router) {
    router.push(routePath)
  } else {
    // 如果没有传入router实例，使用window.location
    window.location.href = `#${routePath}`
  }
}

/**
 * 格式化任务类型显示名称
 * @param {String} taskType 任务类型
 */
export function formatTaskType(taskType) {
  const typeMap = {
    'pre_defense': '训练前防御',
    'during_defense': '训练中防御',
    'post_defense': '训练后防御'
  }
  return typeMap[taskType] || taskType
}

/**
 * 格式化任务状态显示名称
 * @param {String} status 任务状态
 */
export function formatTaskStatus(status) {
  const statusMap = {
    'pending': '等待中',
    'running': '运行中',
    'success': '成功',
    'failed': '失败'
  }
  return statusMap[status] || status
}

/**
 * 获取任务状态对应的颜色
 * @param {String} status 任务状态
 */
export function getTaskStatusColor(status) {
  const colorMap = {
    'pending': '#909399',
    'running': '#409EFF',
    'success': '#67C23A',
    'failed': '#F56C6C'
  }
  return colorMap[status] || '#909399'
}

/**
 * 获取任务类型对应的图标
 * @param {String} taskType 任务类型
 */
export function getTaskTypeIcon(taskType) {
  const iconMap = {
    'pre_defense': 'el-icon-document-remove',
    'during_defense': 'el-icon-cpu',
    'post_defense': 'el-icon-view'
  }
  return iconMap[taskType] || 'el-icon-document'
}

/**
 * 计算运行时长（格式化显示）
 * @param {String} startTime 开始时间
 * @param {String} endTime 结束时间（可选，未提供则使用当前时间）
 */
export function calculateDuration(startTime, endTime) {
  if (!startTime) return '未开始'

  const start = new Date(startTime)
  const end = endTime ? new Date(endTime) : new Date()
  const duration = end.getTime() - start.getTime()

  if (duration < 0) return '0秒'

  const seconds = Math.floor(duration / 1000) % 60
  const minutes = Math.floor(duration / (1000 * 60)) % 60
  const hours = Math.floor(duration / (1000 * 60 * 60)) % 24
  const days = Math.floor(duration / (1000 * 60 * 60 * 24))

  if (days > 0) {
    return `${days}天${hours}小时${minutes}分钟`
  } else if (hours > 0) {
    return `${hours}小时${minutes}分钟`
  } else if (minutes > 0) {
    return `${minutes}分钟${seconds}秒`
  } else {
    return `${seconds}秒`
  }
}

// 默认导出主要的API方法
export default {
  getRunningTasks,
  getAllTasks,
  getTaskDetail,
  getTaskStatistics,
  getBatchTaskProgress,
  refreshTaskStatus,
  navigateToTaskDetail,
  formatTaskType,
  formatTaskStatus,
  getTaskStatusColor,
  getTaskTypeIcon,
  calculateDuration
}
