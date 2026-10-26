import http from './http'

export function createTask(data) {
  return http.post('/api/pre-defense', data)
}

export function updateTask(data) {
  return http.put('/api/pre-defense', data)
}

export function deleteTasks(taskIds) {
  return http.delete('/api/pre-defense', { data: taskIds })
}

export function startTask(taskId) {
  return http.post(`/api/pre-defense/start/${taskId}`)
}

export function startBatchTasks(taskIds) {
  return http.post('/api/pre-defense/start/batch', taskIds)
}

export function getExecutionLogs(taskId, offsets = {}) {
  return http.get(`/api/pre-defense/logs/${taskId}`, {
    params: { offsets: JSON.stringify(offsets || {}) }
  })
}

export function submitCleanedDatasetExport(taskId, isolationRatio) {
  return http.post(`/api/pre-defense/cleaned-dataset/exports/${taskId}`, null, {
    params: { isolationRatio }
  })
}

export function getCleanedDatasetExport(exportId) {
  return http.get(`/api/pre-defense/cleaned-dataset/exports/${exportId}`)
}

export function getCleanedDatasetExports(taskId) {
  return http.get(`/api/pre-defense/cleaned-dataset/exports/task/${taskId}`)
}

export function downloadCleanedDatasetExport(exportId) {
  return http.get(`/api/pre-defense/cleaned-dataset/exports/${exportId}/download`, {
    responseType: 'blob'
  })
}

export function previewTaskArtifact(taskId, artifactPath) {
  return http.get(`/api/pre-defense/artifact-preview/${taskId}`, {
    params: { artifactPath },
    responseType: 'blob'
  })
}
