const DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$/

function pad(value) {
  return String(value).padStart(2, '0')
}

/** Render backend timestamps as a stable local wall-clock value: YYYY-MM-DD HH:mm:ss. */
export function formatDateTime(value, fallback = '—') {
  if (value === null || value === undefined || value === '') return fallback
  if (typeof value === 'string') {
    const match = value.trim().match(DATE_TIME_PATTERN)
    if (match) {
      return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}:${match[6] || '00'}`
    }
  }
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return fallback
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
