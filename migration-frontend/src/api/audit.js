import http from './http'

export function getTaskAudits(taskId) {
  return http.get(`/api/audit/task/${taskId}`)
}
