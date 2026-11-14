import http from './http'

export function queryKnowledge(question) {
  return http.post('/api/knowledge/query', { question })
}

export function getKnowledgeStatus() {
  return http.get('/api/knowledge/status')
}

export function reindexKnowledge() {
  return http.post('/api/knowledge/reindex')
}

export function reindexKnowledgeAlgorithms() {
  return http.post('/api/knowledge/algorithms/reindex')
}

export function indexTaskReport(taskId) {
  return http.post(`/api/knowledge/tasks/${taskId}/index`)
}

export function retireKnowledgeDocument(documentId) {
  return http.delete(`/api/knowledge/documents/${documentId}`)
}
