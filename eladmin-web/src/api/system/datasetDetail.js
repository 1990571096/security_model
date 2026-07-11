import request from '@/utils/request'

export function get(datasetId) {
  return request({
    url: `api/dataset/${datasetId}`,
    method: 'get'
  })
}

export function getAvailableDatasets(datasetType) {
  return request({
    url: 'api/dataset/available',
    method: 'get',
    params: { datasetType }
  })
}

export function startParsing(datasetId) {
  return request({
    url: `api/dataset/parse/start/${datasetId}`,
    method: 'post'
  })
}

export function updateParseStatus(datasetId, parseStatus, parseMessage) {
  return request({
    url: 'api/dataset/parse/status',
    method: 'put',
    params: { datasetId, parseStatus, parseMessage }
  })
}

export function updateParseResult(datasetId, sampleCount, featureCount, classCount) {
  return request({
    url: 'api/dataset/parse/result',
    method: 'put',
    params: { datasetId, sampleCount, featureCount, classCount }
  })
}

export function validateDataset(datasetId) {
  return request({
    url: `api/dataset/validate/${datasetId}`,
    method: 'get'
  })
}

export function downloadDataset(datasetId) {
  return request({
    url: `api/dataset/download/${datasetId}`,
    method: 'get',
    responseType: 'blob'
  })
}

export function getDatasetStatistics() {
  return request({
    url: 'api/dataset/statistics',
    method: 'get'
  })
}

export function getDatasetsByType(datasetType) {
  return request({
    url: `api/dataset/type/${datasetType}`,
    method: 'get'
  })
}

export function getDatasetsByParseStatus(parseStatus) {
  return request({
    url: `api/dataset/parse-status/${parseStatus}`,
    method: 'get'
  })
}

export default { get, startParsing, validateDataset, downloadDataset }
