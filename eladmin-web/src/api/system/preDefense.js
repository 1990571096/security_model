import request from '@/utils/request'

export function getAllPreDefense() {
  const params = {
    page: 0,
    size: 9999
  }
  return request({
    url: 'api/pre-defense',
    method: 'get',
    params
  })
}

export function add(data) {
  return request({
    url: 'api/pre-defense',
    method: 'post',
    data
  })
}

export function del(ids) {
  return request({
    url: 'api/pre-defense',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/pre-defense',
    method: 'put',
    data
  })
}

export function getPreDefense(taskId) {
  return request({
    url: 'api/pre-defense/' + taskId,
    method: 'get'
  })
}

export function startTask(taskId) {
  return request({
    url: `api/pre-defense/start/${taskId}`,
    method: 'post'
  })
}

export function getTaskProgress(taskId) {
  return request({
    url: `api/pre-defense/progress/${taskId}`,
    method: 'get'
  })
}

export function updateTaskStatus(taskId, status, progress = 0) {
  return request({
    url: 'api/pre-defense/status',
    method: 'put',
    params: { taskId, status, progress }
  })
}

// 修改：参数与后端Controller保持一致
export function updateTaskResult(taskId, outputDatasetId, cleaningResult) {
  return request({
    url: 'api/pre-defense/result',
    method: 'put',
    params: { taskId, outputDatasetId, cleaningResult }
  })
}

export function getTasksByStatus(status) {
  return request({
    url: `api/pre-defense/status/${status}`,
    method: 'get'
  })
}

export function getTaskByName(taskName) {
  return request({
    url: `api/pre-defense/name/${taskName}`,
    method: 'get'
  })
}

export function getBatchTaskProgress(taskIds) {
  return request({
    url: 'api/pre-defense/progress/batch',
    method: 'post',
    data: taskIds
  })
}

export function startBatchTasks(taskIds) {
  return request({
    url: 'api/pre-defense/start/batch',
    method: 'post',
    data: taskIds
  })
}

export function getTaskStatistics() {
  return request({
    url: 'api/pre-defense/statistics',
    method: 'get'
  })
}

// 新增：获取清洗结果详情
export function getCleaningResult(taskId) {
  return request({
    url: `api/pre-defense/cleaning-result/${taskId}`,
    method: 'get'
  })
}

// 新增：获取清洗统计信息
export function getCleaningStatistics() {
  return request({
    url: 'api/pre-defense/cleaning-statistics',
    method: 'get'
  })
}

// 新增：获取清洗效果统计
export function getCleaningEffectStatistics() {
  return request({
    url: 'api/pre-defense/cleaning-effect-statistics',
    method: 'get'
  })
}

// 新增：获取算法使用统计
export function getAlgorithmUsageStatistics() {
  return request({
    url: 'api/pre-defense/algorithm-usage-statistics',
    method: 'get'
  })
}

// 新增：下载清洗后数据集
export function downloadCleanedDataset(taskId, isolationRatio) {
  return request({
    url: `api/pre-defense/download-cleaned-dataset/${taskId}`,
    method: 'get',
    params: { isolationRatio },
    responseType: 'blob'
  })
}

export function retryTask(taskId) {
  return request({
    url: `api/pre-defense/retry/${taskId}`,
    method: 'post'
  })
}

// 新增：获取完整清洗报告
export function getCleaningReport(taskId) {
  return request({
    url: `api/pre-defense/report/${taskId}`,
    method: 'get'
  })
}

export function getExecutionLogs(taskId) {
  return request({
    url: `api/pre-defense/logs/${taskId}`,
    method: 'get'
  })
}

export function previewTaskArtifact(taskId, artifactPath) {
  return request({
    url: `api/pre-defense/artifact-preview/${taskId}`,
    method: 'get',
    params: { artifactPath },
    responseType: 'blob'
  })
}

// 为了兼容数据集相关的查询，参考其他js文件的实现
export function getTasksByDataset(datasetId) {
  return request({
    url: `api/pre-defense/dataset/${datasetId}`,
    method: 'get'
  })
}

export function getTasksByAlgorithm(algorithmId) {
  return request({
    url: `api/pre-defense/algorithm/${algorithmId}`,
    method: 'get'
  })
}

export default { add, edit, del }
