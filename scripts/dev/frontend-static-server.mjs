import { createReadStream, promises as fs } from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..', '..')
const staticRoot = path.resolve(process.env.STATIC_ROOT || path.join(repoRoot, 'migration-frontend', 'dist'))
const staticPort = Number(process.env.STATIC_PORT || 18220)
const apiTarget = new URL(process.env.API_TARGET || 'http://127.0.0.1:18200')
const spaIndex = process.env.SPA_INDEX || 'index.html'

const contentTypes = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'],
  ['.ico', 'image/x-icon'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2'],
  ['.txt', 'text/plain; charset=utf-8']
])

function sendJson(res, statusCode, value) {
  const body = JSON.stringify(value)
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  })
  res.end(body)
}

function isProxyPath(pathname) {
  return pathname === '/api' || pathname.startsWith('/api/') ||
    pathname === '/auth' || pathname.startsWith('/auth/')
}

function proxyToBackend(req, res, pathname) {
  const headers = { ...req.headers }
  delete headers.host
  const proxied = http.request({
    protocol: apiTarget.protocol,
    hostname: apiTarget.hostname,
    port: apiTarget.port || (apiTarget.protocol === 'https:' ? 443 : 80),
    method: req.method,
    path: pathname + (req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''),
    headers
  }, proxyRes => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers)
    proxyRes.pipe(res)
  })
  proxied.on('error', error => {
    console.error(`proxy error ${req.method} ${pathname}: ${error.message}`)
    if (!res.headersSent) sendJson(res, 502, { status: 'UPSTREAM_ERROR', path: pathname })
    else res.destroy()
  })
  req.pipe(proxied)
}

function safeRelativePath(pathname) {
  const decoded = decodeURIComponent(pathname)
  const normalized = path.normalize(decoded).replace(/^([/\\])+/, '')
  if (normalized.includes('..')) throw new Error('path traversal rejected')
  return normalized
}

async function serveStatic(req, res, pathname) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    sendJson(res, 405, { status: 'METHOD_NOT_ALLOWED' })
    return
  }
  let relative
  try {
    relative = safeRelativePath(pathname)
  } catch {
    sendJson(res, 400, { status: 'BAD_PATH' })
    return
  }
  if (relative === '') relative = spaIndex
  let filePath = path.join(staticRoot, relative)
  let stat = null
  try {
    stat = await fs.stat(filePath)
    if (stat.isDirectory()) {
      filePath = path.join(filePath, spaIndex)
      stat = await fs.stat(filePath)
    }
  } catch {
    stat = null
  }
  if (!stat || !stat.isFile()) {
    // A client-side route such as /dashboard must still return the SPA shell.
    if (req.headers.accept && req.headers.accept.includes('text/html')) {
      filePath = path.join(staticRoot, spaIndex)
      stat = await fs.stat(filePath)
    }
  }
  if (!stat || !stat.isFile()) {
    sendJson(res, 404, { status: 'NOT_FOUND', path: pathname })
    return
  }
  const contentType = contentTypes.get(path.extname(filePath).toLowerCase()) || 'application/octet-stream'
  res.writeHead(200, {
    'Content-Type': contentType,
    'Content-Length': stat.size,
    'Cache-Control': filePath.endsWith(spaIndex) ? 'no-cache' : 'public, max-age=31536000, immutable',
    'X-Content-Type-Options': 'nosniff'
  })
  if (req.method === 'HEAD') {
    res.end()
    return
  }
  createReadStream(filePath).pipe(res)
}

const server = http.createServer(async (req, res) => {
  const parsed = new URL(req.url, 'http://127.0.0.1')
  const pathname = parsed.pathname
  if (pathname === '/healthz') {
    sendJson(res, 200, { status: 'UP', service: 'frontend-static' })
    return
  }
  if (isProxyPath(pathname)) {
    proxyToBackend(req, res, pathname)
    return
  }
  try {
    await serveStatic(req, res, pathname)
  } catch (error) {
    console.error(`static error ${pathname}: ${error.message}`)
    if (!res.headersSent) sendJson(res, 500, { status: 'INTERNAL_ERROR' })
    else res.destroy()
  }
})

server.listen(staticPort, '127.0.0.1', () => {
  console.log(`frontend static ready pid=${process.pid} port=${staticPort} root=${staticRoot} api=${apiTarget.origin}`)
})

function shutdown() {
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(1), 5000).unref()
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
