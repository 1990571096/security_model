import request from '@/utils/request'

export function getAllDuringDefense() {
  const params = {
    page: 0,
    size: 9999
  }
  return request({
    url: 'api/during-defense',
    method: 'get',
    params
  })
}

export function add(data) {
  return request({
    url: 'api/during-defense',
    method: 'post',
    data
  })
}

export function del(ids) {
  return request({
    url: 'api/during-defense',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/during-defense',
    method: 'put',
    data
  })
}

export function getDuringDefense(taskId) {
  return request({
    url: 'api/during-defense/' + taskId,
    method: 'get'
  })
}

export function startTask(taskId) {
  return request({
    url: `api/during-defense/start/${taskId}`,
    method: 'post'
  })
}

export function getTaskProgress(taskId) {
  return request({
    url: `api/during-defense/progress/${taskId}`,
    method: 'get'
  })
}

export function updateTaskStatus(taskId, status, progress = 0) {
  return request({
    url: 'api/during-defense/status',
    method: 'put',
    params: { taskId, status, progress }
  })
}

export function updateTrainingResult(taskId, outputModelId, trainingLog) {
  return request({
    url: 'api/during-defense/result',
    method: 'put',
    params: { taskId, outputModelId, trainingLog }
  })
}

export function getTasksByStatus(status) {
  return request({
    url: `api/during-defense/status/${status}`,
    method: 'get'
  })
}

export function getTaskByName(taskName) {
  return request({
    url: `api/during-defense/name/${taskName}`,
    method: 'get'
  })
}

export function getTasksByDataset(datasetId) {
  return request({
    url: `api/during-defense/dataset/${datasetId}`,
    method: 'get'
  })
}

export function getTasksByInputModel(inputModelId) {
  return request({
    url: `api/during-defense/input-model/${inputModelId}`,
    method: 'get'
  })
}

export function getTasksByAlgorithm(algorithmId) {
  return request({
    url: `api/during-defense/algorithm/${algorithmId}`,
    method: 'get'
  })
}

export function getBatchTaskProgress(taskIds) {
  return request({
    url: 'api/during-defense/progress/batch',
    method: 'post',
    data: taskIds
  })
}

export function startBatchTasks(taskIds) {
  return request({
    url: 'api/during-defense/start/batch',
    method: 'post',
    data: taskIds
  })
}

export function getTaskStatistics() {
  return request({
    url: 'api/during-defense/statistics',
    method: 'get'
  })
}

export function getTrainingLog(taskId) {
  return request({
    url: `api/during-defense/training-log/${taskId}`,
    method: 'get'
  })
}

export function updateTrainingLog(taskId, trainingLog) {
  return request({
    url: 'api/during-defense/training-log',
    method: 'put',
    params: { taskId },
    data: trainingLog
  })
}

export function downloadTrainedModel(taskId) {
  return request({
    url: `api/during-defense/download-model/${taskId}`,
    method: 'get',
    responseType: 'blob'
  })
}

export default { add, edit, del }
