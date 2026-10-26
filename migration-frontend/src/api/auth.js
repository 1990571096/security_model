import http from './http'

export function getCaptcha() {
  return http.get('/auth/code')
}

export function login(payload) {
  return http.post('/auth/login', payload)
}

export function getUserInfo() {
  return http.get('/auth/info')
}

export function logout() {
  return http.delete('/auth/logout')
}
