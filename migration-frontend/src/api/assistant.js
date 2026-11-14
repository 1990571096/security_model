import http from './http'

export function getAssistantStatus() {
  return http.get('/api/assistant/status')
}

export function getAssistantTools() {
  return http.get('/api/assistant/tools')
}

export function chatWithAssistant(question, sessionId) {
  return http.post('/api/assistant/chat', { question, sessionId })
}

export function confirmAssistantAction(confirmationId, token) {
  return http.post('/api/assistant/confirmations/confirm', { confirmationId, token })
}

export function getAssistantSessions(limit = 20) {
  return http.get('/api/assistant/sessions', { params: { limit } })
}

export function getAssistantSession(sessionId) {
  return http.get(`/api/assistant/sessions/${sessionId}`)
}

export function deleteAssistantSession(sessionId) {
  return http.delete(`/api/assistant/sessions/${sessionId}`)
}

export function previewAssistantTool(toolName, argumentsValue) {
  return http.post(`/api/assistant/tools/${toolName}/preview`, argumentsValue)
}
