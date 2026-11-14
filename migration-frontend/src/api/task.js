import http from './http'

export function getTasks(params = {}) {
  return http.get('/api/task-progress', {
    params: { ...params, taskType: 'pre_defense' }
  })
}

export function getTaskPage(params = {}) {
  return http.get('/api/task-progress/page', { params })
}

export function getTaskStatistics() {
  return http.get('/api/task-progress/statistics')
}

export function getObservabilitySummary() {
  return http.get('/api/observability/summary')
}

export function getExecutorStatus() {
  return http.get('/api/task-progress/executor-status')
}

export function getRunningTasks() {
  return http.get('/api/task-progress/running')
}

export function getTaskDetail(taskId) {
  return http.get(`/api/task-progress/pre_defense/${taskId}`)
}

export function getCleaningReport(taskId) {
  return http.get(`/api/pre-defense/report/${taskId}`)
}

export function retryTask(taskId, idempotencyKey) {
  return http.post(`/api/pre-defense/retry/${taskId}`, null, idempotencyKey ? {
    headers: { 'Idempotency-Key': idempotencyKey }
  } : undefined)
}

export function cancelTask(taskId) {
  return http.post(`/api/pre-defense/cancel/${taskId}`)
}

export function refreshTaskStatus() {
  return http.post('/api/task-progress/refresh')
}
