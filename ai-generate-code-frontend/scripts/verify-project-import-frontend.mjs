import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const evidence = path.resolve(frontend, '../temp', `formal-import-browser-${Date.now()}`)
const fixture = path.join(evidence, 'fixtures', '客户管理台')
const empty = path.join(evidence, 'fixtures', '空工程')
const base = process.env.PROJECT_FRONTEND_URL || 'http://127.0.0.1:5176'
const sample = { 'package.json': '{}', 'src/App.vue': '<template />', '.env': 'FAKE_TEST_ONLY', '.env.example': 'TOKEN=', 'node_modules/pkg/index.js': 'test', 'dist/index.html': '', 'README.md': '' }
for (const [name, content] of Object.entries(sample)) {
  await mkdir(path.dirname(path.join(fixture, name)), { recursive: true })
  await writeFile(path.join(fixture, name), content)
}
await mkdir(empty, { recursive: true })
const hashes = () => Promise.all(Object.keys(sample).map(async name => ({ name, sha256: createHash('sha256').update(await readFile(path.join(fixture, name))).digest('hex') })))
const before = await hashes()
const browser = await chromium.launch({ executablePath: process.env.IMPORT_PREVIEW_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true })
const results = [], errors = [], apiCalls = [], forbidden = [], layouts = []
console.log(`EVIDENCE_DIR=${evidence}`)

async function contextFor(user) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  // 身份响应只在浏览器测试上下文内拦截；正式页面代码和 Axios/fetch 不注入演示适配器。
  await context.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== new URL(base).origin) {
      forbidden.push(url.href)
      return route.abort()
    }
    if (url.pathname.startsWith('/api/')) {
      apiCalls.push({ path: url.pathname, method: route.request().method() })
      const data = url.pathname === '/api/user/get/login' ? user : { records: [], total: 0 }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 20000, data }) })
    }
    await route.continue()
  })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  return { context, page }
}

async function check(name, action) {
  await action()
  results.push({ name, status: 'PASS' })
  console.log(`PASS ${name}`)
}

try {
  const anonymous = await contextFor(null)
  await check('未登录访问正式导入路由保留登录后目的地', async () => {
    await anonymous.page.goto(`${base}/project/import`)
    await expect(anonymous.page).toHaveURL(`${base}/user/login?redirect=/project/import`)
    await expect(anonymous.page.locator('input[type=file]')).toHaveCount(0)
  })
  await anonymous.context.close()
  const { context, page } = await contextFor({ id: 'formal-test-user', userName: '开发者', userRole: 'user' })
  await check('正式导航入口、品牌及独立预览装配隔离', async () => {
    await page.goto(`${base}/`)
    await page.getByRole('button', { name: '导入项目', exact: true }).click()
    await expect(page).toHaveURL(`${base}/project/import`)
    await expect(page.getByRole('heading', { name: '导入项目', exact: true })).toBeVisible()
    await expect(page.locator('.site-title')).toHaveText('应用魔方')
    await expect(page.locator('.filter-help')).not.toHaveAttribute('open', '')
    await page.locator('.filter-help summary').click()
    await expect(page.locator('.help-content p')).toHaveText([
      '依赖：node_modules、.venv、venv', '产物：dist、target、build',
      '默认排除敏感配置，保留示例。', '仅本次生效；保存偏好后沿用。',
    ])
    await page.locator('.filter-help summary').click()
    await expect(page.locator('.help-content')).not.toBeVisible()
    expect(await page.locator('body').innerText()).not.toMatch(/Mock|模拟|预览|待实现|0代码|preview-/i)
    expect(await page.evaluate(() => '__IMPORT_PREVIEW_SCENARIO__' in window)).toBe(false)
  })
  await check('实际文件夹选择、默认依赖保留、敏感默认排除及名称限制', async () => {
    await page.locator('input[type=file]').setInputFiles(fixture)
    await expect(page.getByTestId('included-count')).toHaveText('6')
    await expect(page.getByTestId('excluded-count')).toHaveText('1')
    await page.getByLabel('新应用名称', { exact: true }).fill('a'.repeat(81))
    await expect(page.getByText('名称最多 80 个字符。')).toBeVisible()
    await expect(page.getByRole('button', { name: '确认导入' })).toBeDisabled()
    await page.getByLabel('新应用名称', { exact: true }).fill('客户管理台')
  })
  await check('过滤摘要和敏感警告，偏好失败不伪造保存', async () => {
    await page.getByRole('checkbox', { name: '跳过依赖目录', exact: true }).check()
    await page.getByRole('checkbox', { name: '跳过构建产物', exact: true }).check()
    await expect(page.getByTestId('included-count')).toHaveText('4')
    await page.getByRole('checkbox', { name: '本次包含敏感配置', exact: true }).check()
    await expect(page.getByText('可能包含密钥；此选择不授权模型读取。')).toBeVisible()
    await expect(page.getByTestId('included-count')).toHaveText('5')
    await page.getByRole('button', { name: '保存为默认偏好', exact: true }).click()
    await expect(page.getByText('偏好保存服务尚未开通，请稍后重试。')).toBeVisible()
    await expect(page.getByText('偏好已保存。')).toHaveCount(0)
  })
  await check('正式提交明确失败、保留输入并可重试，不进入虚构工作台', async () => {
    await page.getByRole('button', { name: '确认导入', exact: true }).click()
    await expect(page.getByText('导入服务尚未开通，请稍后重试。')).toBeVisible()
    await expect(page).toHaveURL(`${base}/project/import`)
    await expect(page.getByLabel('新应用名称', { exact: true })).toHaveValue('客户管理台')
    await page.getByRole('button', { name: '重新导入', exact: true }).click()
    await expect(page.getByText('导入服务尚未开通，请稍后重试。')).toBeVisible()
    await page.screenshot({ path: path.join(evidence, 'formal-failure-desktop.png'), fullPage: true })
  })
  await check('正式布局 1440/1024/768/375，主要模块间距和清单键盘可用', async () => {
    await page.locator('.file-details summary').click()
    for (const width of [1440, 1024, 768, 375]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.waitForTimeout(350)
      const layout = await page.evaluate(() => {
        const form = document.querySelector('.project-import-form')
        const panel = document.querySelector('.source-panel')
        const controls = document.querySelector('.filter-controls')
        const title = document.querySelector('.page-heading')
        return { viewport: innerWidth, width: document.documentElement.scrollWidth,
          panelPadding: getComputedStyle(panel).paddingLeft,
          controlsGap: getComputedStyle(controls).gap,
          headingGap: getComputedStyle(title).marginBottom,
          formLeft: form.getBoundingClientRect().left, formRight: form.getBoundingClientRect().right }
      })
      expect(layout.width).toBeLessThanOrEqual(width)
      expect(layout.headingGap).toBe('24px')
      expect(layout.controlsGap).toBe('16px')
      expect(layout.formLeft).toBeGreaterThanOrEqual(0)
      expect(layout.formRight).toBeLessThanOrEqual(width)
      const alignment = await page.evaluate(() => {
        const rect = element => element.getBoundingClientRect()
        const center = element => { const box = rect(element); return box.top + box.height / 2 }
        const summary = document.querySelector('.summary')
        const summaryOffsets = [...summary.children].map(cell => {
          const first = rect(cell.querySelector('strong'))
          const last = rect(cell.querySelector('span'))
          return Math.abs((first.top + last.bottom) / 2 - center(summary))
        })
        const rowOffsets = [...document.querySelectorAll('.file-row')].flatMap(row =>
          [...row.children].map(child => Math.abs(center(child) - center(row))))
        return { summaryOffsets, rowOffsets }
      })
      for (const offset of alignment.summaryOffsets) expect(offset).toBeLessThanOrEqual(1)
      for (const offset of alignment.rowOffsets) expect(offset).toBeLessThanOrEqual(1)
      layout.alignment = alignment
      layouts.push(layout)
      await page.screenshot({ path: path.join(evidence, `formal-layout-${width}.png`), fullPage: true })
    }
    await page.getByLabel('文件清单详情', { exact: true }).focus()
    await expect(page.getByLabel('文件清单详情', { exact: true })).toBeFocused()
  })
  await check('清空、空文件夹与刷新释放原清单和临时选择', async () => {
    await page.getByRole('button', { name: /^清\s*空$/ }).click()
    await expect(page.getByTestId('included-count')).toHaveCount(0)
    await page.locator('input[type=file]').setInputFiles(empty)
    await expect(page.getByText('未发现文件，请选择非空文件夹。')).toBeVisible()
    await page.locator('input[type=file]').setInputFiles(fixture)
    await page.reload()
    await expect(page.getByLabel('新应用名称', { exact: true })).toHaveValue('')
    await expect(page.getByTestId('included-count')).toHaveCount(0)
  })
  await check('只拦截认证和首页读取，无导入、模型、安装或外部请求；来源哈希不变', async () => {
    const allowedReads = new Set(['/api/app/my/list/page/vo', '/api/app/good/list/page/vo', '/api/loop/my/list/page/vo'])
    expect(apiCalls.every(call => (call.method === 'GET' && call.path === '/api/user/get/login') ||
      (call.method === 'POST' && allowedReads.has(call.path)))).toBe(true)
    expect(apiCalls.some(call => /import|install|generate|runtime/.test(call.path))).toBe(false)
    expect(forbidden).toEqual([])
    expect(errors).toEqual([])
    expect(await hashes()).toEqual(before)
  })
  await context.close()
} catch (error) {
  results.push({ status: 'FAIL', error: error.stack || String(error) })
  process.exitCode = 1
} finally {
  await writeFile(path.join(evidence, 'browser-results.json'), JSON.stringify({ base, results, layouts, apiCalls, forbidden, errors, fixtureBefore: before, fixtureAfter: await hashes(), backendValidation: 'NOT_RUN' }, null, 2))
  await browser.close()
}
