import request from '@/utils/request'

export function add(data) {
  return request({
    url: 'api/model',
    method: 'post',
    data
  })
}

export function del(ids) {
  console.log('前端删除模型请求，IDs:', ids)
  return request({
    url: 'api/model',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/model',
    method: 'put',
    data
  })
}

export function getModel(id) {
  return request({
    url: 'api/model/' + id,
    method: 'get'
  })
}

// 新增：获取所有模型（不限状态）
export function getAllModelUnrestricted() {
  const params = {
    page: 0,
    size: 9999
  }
  return request({
    url: 'api/model',
    method: 'get',
    params
  })
}
// 新增：获取所有可用模型
export function getAllModel() {
  const params = {
    page: 0,
    size: 9999,
    status: 'available'
  }
  return request({
    url: 'api/model',
    method: 'get',
    params
  })
}
export function updateParseStatus(data) {
  return request({
    url: 'api/model/parse-status',
    method: 'put',
    params: data
  })
}

export function updateStatus(data) {
  return request({
    url: 'api/model/status',
    method: 'put',
    params: data
  })
}

export function getModelsBySource(modelSource) {
  return request({
    url: 'api/model/source/' + modelSource,
    method: 'get'
  })
}

export function getModelsByCreator(createBy) {
  return request({
    url: 'api/model/creator/' + createBy,
    method: 'get'
  })
}

export default { add, edit, del, getModel, updateParseStatus, updateStatus, getModelsBySource, getModelsByCreator }
