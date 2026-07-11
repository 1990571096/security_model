import request from '@/utils/request'

// export function getAllAlgorithm() {
//   const params = {
//     page: 0,
//     size: 9999,
//     enabled: true
//   }
//   return request({
//     url: 'api/algorithm',
//     method: 'get',
//     params
//   })
// }
export function getAllAlgorithm() {
  return request({
    url: 'api/algorithm',
    method: 'get'
  })
}
export function add(data) {
  return request({
    url: 'api/algorithm',
    method: 'post',
    data
  })
}

export function del(ids) {
  return request({
    url: 'api/algorithm',
    method: 'delete',
    data: ids
  })
}

export function edit(data) {
  return request({
    url: 'api/algorithm',
    method: 'put',
    data
  })
}

export function getAlgorithm(algorithmId) {
  return request({
    url: 'api/algorithm/' + algorithmId,
    method: 'get'
  })
}

export function getAlgorithmByName(algorithmName) {
  return request({
    url: 'api/algorithm/name/' + algorithmName,
    method: 'get'
  })
}

export function getAlgorithmsByType(algorithmType) {
  return request({
    url: 'api/algorithm/type/' + algorithmType,
    method: 'get'
  })
}

// 在现有方法后面添加
export function getAlgorithmConfigSchema(algorithmId) {
  return request({
    url: 'api/algorithm/' + algorithmId + '/config-schema',
    method: 'get'
  })
}

// 如果需要单独验证配置模式JSON的方法
export function validateConfigSchema(algorithmId, configSchema) {
  return request({
    url: 'api/algorithm/config/validate-schema',
    method: 'post',
    params: { algorithmId },
    data: configSchema
  })
}

export function getAlgorithmsByStage(defenseStage) {
  return request({
    url: 'api/algorithm/stage/' + defenseStage,
    method: 'get'
  })
}

export function getAlgorithmsByCategory(algorithmCategory) {
  return request({
    url: 'api/algorithm/category/' + algorithmCategory,
    method: 'get'
  })
}

export function getAlgorithmsByEnabled(enabled) {
  return request({
    url: 'api/algorithm/enabled/' + enabled,
    method: 'get'
  })
}

export function getAvailableAlgorithms(defenseStage, algorithmType) {
  return request({
    url: 'api/algorithm/available',
    method: 'get',
    params: { defenseStage, algorithmType }
  })
}

export function updateDefaultConfig(algorithmId, defaultConfig) {
  return request({
    url: 'api/algorithm/config/default',
    method: 'put',
    params: { algorithmId },
    data: defaultConfig
  })
}

export function updateConfigSchema(algorithmId, configSchema) {
  return request({
    url: 'api/algorithm/config/schema',
    method: 'put',
    params: { algorithmId },
    data: configSchema
  })
}

export function getAlgorithmStatistics() {
  return request({
    url: 'api/algorithm/statistics',
    method: 'get'
  })
}

export function getAlgorithmsByCreator(createBy) {
  return request({
    url: 'api/algorithm/creator/' + createBy,
    method: 'get'
  })
}

export function getDefenseStages() {
  return request({
    url: 'api/algorithm/defense-stages',
    method: 'get'
  })
}

export function getAlgorithmTypes() {
  return request({
    url: 'api/algorithm/algorithm-types',
    method: 'get'
  })
}

export function getAlgorithmCategories() {
  return request({
    url: 'api/algorithm/categories',
    method: 'get'
  })
}

export function getBatchAlgorithmConfig(algorithmIds) {
  return request({
    url: 'api/algorithm/config/batch',
    method: 'post',
    data: algorithmIds
  })
}

export function validateAlgorithmConfig(algorithmId, customConfig) {
  return request({
    url: 'api/algorithm/config/validate',
    method: 'post',
    params: { algorithmId },
    data: customConfig
  })
}

export function getAlgorithmConfig(algorithmId) {
  return request({
    url: 'api/algorithm/' + algorithmId + '/config',
    method: 'get'
  })
}

export function getAlgorithmsGroupedByStage() {
  return request({
    url: 'api/algorithm/grouped-by-stage',
    method: 'get'
  })
}

export default { add, edit, del, getAlgorithmConfigSchema }
