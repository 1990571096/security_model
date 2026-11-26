import http from './http'

/** Real workspace aggregates. Metrics are explicitly reported as not collected. */
export function getDashboardOverview(days = 7) {
  return http.get('/api/dashboard/overview', { params: { days } })
}
