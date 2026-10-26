import http from './http'

export function getAvailableHyper() {
  return http.get('/api/hyper/available')
}

export function getDefaultTemplateByAlgorithm(algorithmId) {
  return http.get(`/api/hyper/algorithm/${algorithmId}/default`)
}
