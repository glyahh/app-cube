import { spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { gzipSync } from 'node:zlib'
import { fileURLToPath } from 'node:url'

import { chromium } from '@playwright/test'

import { evaluateInitialLoad } from './initial-load-budget.mjs'

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const port = Number(process.env.INITIAL_LOAD_AUDIT_PORT || 4175)
const origin = `http://127.0.0.1:${port}`

async function waitForPreview() {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const response = await fetch(origin)
      if (response.ok) return
    } catch {
      // Preview is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`preview did not start at ${origin}`)
}

async function entryGzipBytes() {
  const html = await readFile(path.join(projectDir, 'dist', 'index.html'), 'utf8')
  const source = html.match(/<script[^>]+src="([^"]+)"/)?.[1]
  if (!source) throw new Error('production entry script was not found in dist/index.html')
  const content = await readFile(path.join(projectDir, 'dist', source.replace(/^\//, '')))
  return gzipSync(content).byteLength
}

const viteBin = path.join(projectDir, 'node_modules', 'vite', 'bin', 'vite.js')
const preview = spawn(process.execPath, [viteBin, 'preview', '--host', '127.0.0.1', '--port', String(port)], {
  cwd: projectDir,
  stdio: 'ignore',
  windowsHide: true,
})

let browser
try {
  await waitForPreview()
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  })
  const page = await browser.newPage()
  const apiRequests = []

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    apiRequests.push(url.pathname)
    const data = url.pathname === '/api/user/get/login'
      ? { id: '1', userName: 'performance-audit', userRole: 'user' }
      : url.pathname === '/api/app/get/vo'
        ? { id: '1', appName: 'Performance Audit', codeGenType: 'multi_file' }
        : []
    await route.fulfill({ json: { code: 20000, data } })
  })

  await page.goto(`${origin}/app/1/chat`, { waitUntil: 'networkidle', timeout: 30_000 })
  const loadedUrls = await page.evaluate(() => performance.getEntriesByType('resource').map((entry) => entry.name))
  const metrics = {
    entryGzipBytes: await entryGzipBytes(),
    loginRequestCount: apiRequests.filter((url) => url === '/api/user/get/login').length,
    testRequestCount: apiRequests.filter((url) => url === '/api/test/').length,
    loadedUrls,
  }
  const result = evaluateInitialLoad(metrics)

  console.log(JSON.stringify({ metrics: { ...metrics, loadedUrls: undefined }, warnings: result.warnings }, null, 2))
  for (const warning of result.warnings) console.warn(`[performance warning] ${warning}`)
  if (!result.passed) process.exitCode = 1
} finally {
  await browser?.close()
  preview.kill()
}
