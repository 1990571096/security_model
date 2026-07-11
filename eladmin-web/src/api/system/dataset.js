import request from '@/utils/request'

// export function getAllDataset() {
//   const params = {
//     page: 0,
//     size: 9999,
//     enabled: true
//   }
//   return request({
//     url: 'api/dataset',
//     method: 'get',
//     params
//   })
// }
export function getAllDataset() {
  return request({
    url: 'api/dataset/available',
    method: 'get'
  })
}

export function add(data) {
  return request({
    url: 'api/dataset',
    method: 'post',
    data
  })
}

// 在dataset.js中添加这个方法
export function get(datasetId) {
  return request({
    url: 'api/dataset/' + datasetId,
    method: 'get'
  })
}

export function del(ids) {
  return request({
    url: 'api/dataset',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/dataset',
    method: 'put',
    data
  })
}

// export function getAvailableDatasets(datasetType) {
//   return request({
//     url: 'api/dataset/available',
//     method: 'get',
//     params: { datasetType }
//   })
// }

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

export function getDatasetTypes() {
  return request({
    url: 'api/dataset/types',
    method: 'get'
  })
}

export function validateDataset(datasetId) {
  return request({
    url: `api/dataset/validate/${datasetId}`,
    method: 'get'
  })
}

export function getDatasetStatistics() {
  return request({
    url: 'api/dataset/statistics',
    method: 'get'
  })
}

export function uploadDataset(formData) {
  return request({
    url: 'api/dataset/upload',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 300000 // 5分钟超时
  })
}
export function getDatasetsByCreator(createBy) {
  return request({
    url: 'api/dataset/creator/' + createBy,
    method: 'get'
  })
}

export default { add, edit, del, get }
