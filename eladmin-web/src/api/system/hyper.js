import request from '@/utils/request'

// export function getAllHyper() {
//   const params = {
//     page: 0,
//     size: 9999,
//     enabled: true
//   }
//   return request({
//     url: 'api/hyper',
//     method: 'get',
//     params
//   })
// }

export function getAllHyper() {
  return request({
    url: 'api/hyper/available',
    method: 'get'
  })
}

export function add(data) {
  return request({
    url: 'api/hyper',
    method: 'post',
    data
  })
}

export function del(ids) {
  return request({
    url: 'api/hyper',
    method: 'delete',
    data: ids
  })
}

export function getDefaultTemplateByAlgorithm(algorithmId) {
  return request({
    url: 'api/hyper/algorithm/' + algorithmId + '/default',
    method: 'get'
  })
}

export function edit(data) {
  return request({
    url: 'api/hyper',
    method: 'put',
    data
  })
}

export function getHyper(templateId) {
  return request({
    url: 'api/hyper/' + templateId,
    method: 'get'
  })
}

export function getHyperByName(templateName) {
  return request({
    url: 'api/hyper/name/' + templateName,
    method: 'get'
  })
}

export function getTemplatesByAlgorithm(algorithmId) {
  return request({
    url: 'api/hyper/algorithm/' + algorithmId,
    method: 'get'
  })
}

export function findEnabledByAlgorithmId(algorithmId) {
  return request({
    url: 'api/hyper/algorithm/' + algorithmId + '/enabled',
    method: 'get'
  })
}

export function getTemplatesByEnabled(enabled) {
  return request({
    url: 'api/hyper/enabled/' + enabled,
    method: 'get'
  })
}

export function getApplicableTemplates(algorithmId, datasetType) {
  return request({
    url: 'api/hyper/applicable',
    method: 'get',
    params: { algorithmId, datasetType }
  })
}

export function validateParameterConfig(templateId, parameterConfig) {
  return request({
    url: 'api/hyper/validate',
    method: 'post',
    params: { templateId },
    data: parameterConfig
  })
}

export function getParameterSchema(templateId) {
  return request({
    url: 'api/hyper/' + templateId + '/schema',
    method: 'get'
  })
}

export function getTemplateStatistics() {
  return request({
    url: 'api/hyper/statistics',
    method: 'get'
  })
}

export function getTemplatesByCreator(createBy) {
  return request({
    url: 'api/hyper/creator/' + createBy,
    method: 'get'
  })
}

export function mergeParameterConfig(templateId, customConfig) {
  return request({
    url: 'api/hyper/merge',
    method: 'post',
    params: { templateId },
    data: customConfig
  })
}

export function getBatchTemplateConfig(templateIds) {
  return request({
    url: 'api/hyper/config/batch',
    method: 'post',
    data: templateIds
  })
}

export default { add, edit, del }
