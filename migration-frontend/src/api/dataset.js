import http from './http'

export function getAvailableDatasets(datasetType) {
  return http.get('/api/dataset/available', {
    params: datasetType ? { datasetType } : undefined
  })
}

export function getDataset(datasetId) {
  return http.get(`/api/dataset/${datasetId}`)
}

export const getDatasets = () => http.get('/api/dataset')
export const createDataset = data => http.post('/api/dataset', data)
export const updateDataset = (id, data) => http.put(`/api/dataset/${id}`, data)
export const disableDataset = id => http.delete(`/api/dataset/${id}`)
