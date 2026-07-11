import request from '@/utils/request'

export function getAllPostDefense() {
  const params = {
    page: 0,
    size: 9999
  }
  return request({
    url: 'api/post-defense',
    method: 'get',
    params
  })
}

export function add(data) {
  return request({
    url: 'api/post-defense',
    method: 'post',
    data
  })
}

export function del(ids) {
  return request({
    url: 'api/post-defense',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/post-defense',
    method: 'put',
    data
  })
}

export function getPostDefense(taskId) {
  return request({
    url: 'api/post-defense/' + taskId,
    method: 'get'
  })
}

export function startTask(taskId) {
  return request({
    url: `api/post-defense/start/${taskId}`,
    method: 'post'
  })
}

export function getTaskProgress(taskId) {
  return request({
    url: `api/post-defense/progress/${taskId}`,
    method: 'get'
  })
}

export function updateTaskStatus(taskId, status, progress = 0) {
  return request({
    url: 'api/post-defense/status',
    method: 'put',
    params: { taskId, status, progress }
  })
}

export function updateDefenseResult(taskId, defenseResult, outputModelId, performanceMetrics) {
  return request({
    url: 'api/post-defense/result',
    method: 'put',
    params: { taskId, defenseResult, outputModelId },
    data: performanceMetrics
  })
}

export function getTasksByStatus(status) {
  return request({
    url: `api/post-defense/status/${status}`,
    method: 'get'
  })
}

export function getTaskByName(taskName) {
  return request({
    url: `api/post-defense/name/${taskName}`,
    method: 'get'
  })
}

export function getTasksByInputModel(inputModelId) {
  return request({
    url: `api/post-defense/input-model/${inputModelId}`,
    method: 'get'
  })
}

export function getTasksByAlgorithm(algorithmId) {
  return request({
    url: `api/post-defense/algorithm/${algorithmId}`,
    method: 'get'
  })
}

export function getBatchTaskProgress(taskIds) {
  return request({
    url: 'api/post-defense/progress/batch',
    method: 'post',
    data: taskIds
  })
}

export function startBatchTasks(taskIds) {
  return request({
    url: 'api/post-defense/start/batch',
    method: 'post',
    data: taskIds
  })
}

export function getTaskStatistics() {
  return request({
    url: 'api/post-defense/statistics',
    method: 'get'
  })
}

// 新增：获取防御结果详情
export function getDefenseReport(taskId) {
  return request({
    url: `api/post-defense/defense-report/${taskId}`,
    method: 'get'
  })
}

// 新增：获取风险评估报告
export function getRiskAssessment(taskId) {
  return request({
    url: `api/post-defense/risk-assessment/${taskId}`,
    method: 'get'
  })
}

// 新增：获取检测报告
export function getDetectionReport(taskId) {
  return request({
    url: `api/post-defense/detection-report/${taskId}`,
    method: 'get'
  })
}

// 新增：下载净化后模型
export function downloadPurifiedModel(taskId) {
  return request({
    url: `api/post-defense/download-model/${taskId}`,
    method: 'get',
    responseType: 'blob'
  })
}

// 新增：获取性能对比数据
export function getPerformanceComparison(taskId) {
  return request({
    url: `api/post-defense/performance-comparison/${taskId}`,
    method: 'get'
  })
}

// 新增：获取检测历史趋势
export function getDetectionTrends(params) {
  return request({
    url: 'api/post-defense/detection-trends',
    method: 'get',
    params
  })
}

// 新增：获取算法效果统计
export function getAlgorithmEffectiveness() {
  return request({
    url: 'api/post-defense/algorithm-effectiveness',
    method: 'get'
  })
}

export default { add, edit, del }
