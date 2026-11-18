import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..', '..')
const staticRoot = path.join(repoRoot, 'migration-frontend', 'dist')
const port = 18231
const base = `http://127.0.0.1:${port}`
const serverScript = path.join(scriptDir, 'frontend-static-server.mjs')

if (!(await fs.stat(path.join(staticRoot, 'index.html')).catch(() => null))) {
  throw new Error(`dist/index.html not found: ${staticRoot}`)
}

const server = spawn(process.execPath, [serverScript], {
  env: {
    ...process.env,
    STATIC_ROOT: staticRoot,
    STATIC_PORT: String(port),
    API_TARGET: 'http://127.0.0.1:9'
  },
  stdio: ['ignore', 'pipe', 'pipe']
})
let output = ''
server.stdout.on('data', chunk => { output += chunk })
server.stderr.on('data', chunk => { output += chunk })

async function waitReady() {
  for (let i = 0; i < 40; i++) {
    try {
      const response = await fetch(`${base}/healthz`)
      if (response.ok) return
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(`static server did not become ready: ${output}`)
}

async function assertStatus(urlPath, expectedStatus, check) {
  const response = await fetch(base + urlPath, { headers: { Accept: 'text/html' } })
  if (response.status !== expectedStatus) {
    throw new Error(`${urlPath}: expected ${expectedStatus}, got ${response.status}`)
  }
  if (check) await check(response)
  console.log(`${urlPath} ${response.status} OK`)
}

try {
  await waitReady()
  await assertStatus('/', 200, async response => {
    const body = await response.text()
    if (!body.includes('<div id="app">')) throw new Error('/ does not return the SPA shell')
  })
  await assertStatus('/dashboard', 200)
  await assertStatus('/algorithms/14', 200)

  const index = await (await fetch(base + '/')).text()
  const asset = index.match(/\/assets\/[^"']+\.js/)?.[0]
  if (!asset) throw new Error('index.html has no JS asset')
  const assetResponse = await fetch(base + asset)
  if (!assetResponse.ok || !assetResponse.headers.get('content-type')?.includes('text/javascript')) {
    throw new Error(`asset ${asset} not served as JS: ${assetResponse.status}`)
  }
  console.log(`${asset} ${assetResponse.status} OK`)

  // Unreachable API target must be an explicit 502, never SPA fallback.
  await assertStatus('/api/algorithm', 502, async response => {
    const body = await response.json()
    if (body.status !== 'UPSTREAM_ERROR') throw new Error('/api proxy did not fail closed')
  })

  // A traversal attempt must never return a file outside STATIC_ROOT.
  const traversal = await fetch(base + '/%2e%2e/%2e%2e/windows/win.ini')
  const traversalBody = await traversal.text()
  if (traversal.status === 200 || traversalBody.includes('[fonts]')) {
    throw new Error(`traversal leaked content: ${traversal.status}`)
  }
  console.log(`traversal ${traversal.status} rejected OK`)

  console.log('frontend static hosting checks OK: SPA fallback, asset, proxy, traversal')
} finally {
  server.kill('SIGTERM')
  await new Promise(resolve => server.once('exit', resolve))
}
