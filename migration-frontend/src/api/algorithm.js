import http from './http'

export function getAvailableAlgorithms(defenseStage = 'pre', algorithmType) {
  return http.get('/api/algorithm/available', {
    params: { defenseStage, algorithmType }
  })
}

export function getAlgorithm(algorithmId) {
  return http.get(`/api/algorithm/${algorithmId}`)
}

export const getAlgorithms = () => http.get('/api/algorithm')
export const createAlgorithm = data => http.post('/api/algorithm', data)
export const updateAlgorithm = (id, data) => http.put(`/api/algorithm/${id}`, data)
export const disableAlgorithm = id => http.delete(`/api/algorithm/${id}`)
