import http from './http'

export function getAssistantStatus() {
  return http.get('/api/assistant/status')
}

export function getAssistantTools(scope) {
  return http.get('/api/assistant/tools', { params: { scope } })
}

export function getAssistantScenarios() {
  return http.get('/api/assistant/scenarios')
}

export function chatWithAssistant(question, sessionId, scenario, failureContext, experimentContext) {
  return http.post('/api/assistant/chat', { question, sessionId, scenario, failureContext, experimentContext }, { timeout: 65000 })
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

export function createExperimentNote(payload) {
  return http.post('/api/experiment-notes', payload)
}

export function getExperimentNotes(limit = 20) {
  return http.get('/api/experiment-notes', { params: { limit } })
}

export function getExperimentNote(noteId) {
  return http.get(`/api/experiment-notes/${noteId}`)
}

export function updateExperimentNote(noteId, payload) {
  return http.put(`/api/experiment-notes/${noteId}`, payload)
}

export function deleteExperimentNote(noteId) {
  return http.delete(`/api/experiment-notes/${noteId}`)
}

export async function exportExperimentNote(noteId) {
  return http.get(`/api/experiment-notes/${noteId}/markdown`, { responseType: 'text' })
}
