import http from './http'

export function queryKnowledge(question) {
  return http.post('/api/knowledge/query', { question })
}

export function listKnowledgeDocuments(page, size) {
  return http.get('/api/knowledge/documents', { params: { page, size } })
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
