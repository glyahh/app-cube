// 真实浏览器加载 Vue 构建页面，并经真实 HTTP 修改接口业务状态；不拦截或 Mock 请求。
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
const require = createRequire(new URL('../../ai-generate-code-frontend/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const [input, output] = process.argv.slice(2)
const job = JSON.parse(await readFile(input, 'utf8'))
for (const address of [job.web, job.api].filter(Boolean)) {
  const url = new URL(address)
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || !url.port) throw new Error('非本机夹具地址')
}
const result = { status: 'RUNNING', checks: [], requests: [], pageErrors: [] }
const browser = await chromium.launch({ executablePath: process.env.IMPORT_PREVIEW_BROWSER || chromium.executablePath(), headless: true })
try {
  const page = await browser.newPage()
  page.on('pageerror', error => result.pageErrors.push(error.message))
  page.on('response', response => result.requests.push({ url: response.url(), method: response.request().method(), status: response.status() }))
  await page.goto(job.web, { waitUntil: 'networkidle' })
  await expect(page.locator('h1')).toHaveText('记录')
  await expect(page.locator('main')).toHaveAttribute('data-vue-version', '3.5.27')
  expect(result.requests.some(row => /\/assets\/[^/]+\.js/.test(new URL(row.url).pathname) && row.status === 200)).toBe(true)
  result.checks.push({ name: '真实Vue页面和构建资源加载', status: 'PASS' })
  if (job.api) {
    const expectedValue = job.expectedValue ?? job.value
    const before = await page.request.get(new URL('records', job.api).href)
    const priorValues = (await before.json()).values
    if (job.priorValues) expect(priorValues).toEqual(job.priorValues)
    const expectedValues = [...priorValues, expectedValue]
    await page.locator('#api').fill(job.api)
    await page.locator('#value').fill(job.value)
    await page.locator('#save').click()
    await expect(page.locator('#result')).toContainText(expectedValue)
    const read = await page.request.get(new URL('records', job.api).href)
    expect((await read.json()).values).toEqual(expectedValues)
    result.checks.push({ name: '浏览器跨服务写入且权威接口读回相同业务状态', status: 'PASS' })
    await page.locator('#value').fill('')
    await page.locator('#save').click()
    await expect(page.locator('#result')).toHaveText('输入不能为空')
    const unchanged = await page.request.get(new URL('records', job.api).href)
    expect((await unchanged.json()).values).toEqual(expectedValues)
    expect(result.requests.some(row => row.method === 'POST' && row.status === 400)).toBe(true)
    result.checks.push({ name: '真实后端拒绝非法输入，页面显示错误且原状态不变', status: 'PASS' })
  }
  expect(result.pageErrors).toEqual([])
  await page.screenshot({ path: path.join(path.dirname(output), path.basename(output, '.json') + '.png') })
  result.status = 'PASS'
} catch (error) {
  result.status = 'FAIL'; result.error = String(error).slice(0, 1000)
} finally {
  await browser.close()
  await writeFile(output, JSON.stringify(result, null, 2))
}
console.log(JSON.stringify(result))
if (result.status !== 'PASS') process.exitCode = 1
