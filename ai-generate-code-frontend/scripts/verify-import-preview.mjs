import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const evidence = path.resolve(frontend, '../temp/import-frontend-preview', `browser-${Date.now()}`)
console.log(`EVIDENCE_DIR=${evidence}`)
const url = process.env.IMPORT_PREVIEW_URL || 'http://127.0.0.1:5175/import-preview.html'
const fixture = path.join(evidence, 'fixtures', '客户管理台')
const secretOnly = path.join(evidence, 'fixtures', '仅敏感配置')
const emptyFolder = path.join(evidence, 'fixtures', '空工程')
// 只写隔离测试文件供浏览器选择；不执行工程，不使用真实凭据。
const fixtureSizes = {
  'package.json': 726, 'src/App.vue': 1842, 'src/页面/欢迎 页面.vue': 935,
  'public/logo.png': 8104, 'README.md': 0, 'node_modules/vue/package.json': 2516,
  '.venv/pyvenv.cfg': 203, 'dist/index.html': 682, 'target/classes/App.class': 413,
  '.env': 122, '.env.example': 90, 'backend/application-local.yml': 287,
}
for (const [name, size] of Object.entries(fixtureSizes)) {
  await mkdir(path.dirname(path.join(fixture, name)), { recursive: true })
  await writeFile(path.join(fixture, name), Buffer.alloc(size, 120))
}
await mkdir(secretOnly, { recursive: true })
await mkdir(emptyFolder, { recursive: true })
await writeFile(path.join(secretOnly, '.env'), 'FAKE_MARKER_ONLY')
const fixturePaths = [...Object.keys(fixtureSizes).map((name) => path.join(fixture, name)), path.join(secretOnly, '.env')]
async function hashFixtures() {
  return Promise.all(fixturePaths.map(async (file) => ({
    path: file, sha256: createHash('sha256').update(await readFile(file)).digest('hex'),
  })))
}
const fixtureBefore = await hashFixtures()
const browser = await chromium.launch({
  executablePath: process.env.IMPORT_PREVIEW_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const pageErrors = [], forbiddenRequests = [], results = [], layouts = []
page.on('pageerror', (error) => pageErrors.push(error.message))
page.on('console', (message) => { if (message.type() === 'error') pageErrors.push(message.text()) })
await context.route('**/*', (route) => {
  const request = new URL(route.request().url())
  if (request.origin !== new URL(url).origin || /^\/(api|static)(\/|$)/.test(request.pathname)) {
    forbiddenRequests.push(route.request().url())
    return route.abort()
  }
  return route.continue()
})
// 测试从隔离装配注入失败，不在产品界面放开关和诊断记录。
await page.addInitScript(() => {
  window.__IMPORT_EVENTS__ = []
  window.__IMPORT_PREVIEW_SCENARIO__ = {
    importOutcome: 'failure-once', installOutcome: 'failure-once',
    onTaskState: (event) => window.__IMPORT_EVENTS__.push(event),
  }
})
const choose = () => page.locator('input[type=file]').setInputFiles(fixture)
const submitButton = () => page.getByRole('button', { name: /确认导入|重新导入/ })
const included = page.getByTestId('included-count')
const excluded = page.getByTestId('excluded-count')
const nameInput = page.getByLabel('新应用名称', { exact: true })
const events = () => page.evaluate(() => window.__IMPORT_EVENTS__)
const checkbox = (name) => page.getByRole('checkbox', { name, exact: true })
async function normalCopy() {
  expect(await page.locator('body').innerText()).not.toMatch(/Mock|模拟|预览|待实现|虚构|preview-/i)
  expect(await page.title()).not.toMatch(/Mock|模拟|预览|待实现|0代码|零代码/i)
  expect(await page.locator('body').innerText()).not.toMatch(/0代码应用魔方|零代码应用魔方|Ai-generate-code/i)
  if (await page.locator('.brand').count()) await expect(page.locator('.brand')).toHaveText('◇应用魔方')
  await expect(page.locator('.preview-tools, .attempt-list, .task-id')).toHaveCount(0)
}
async function check(name, action) {
  try {
    await action()
    await normalCopy()
    results.push({ name, status: 'PASS' })
    console.log(`PASS ${name}`)
  } catch (error) {
    results.push({ name, status: 'FAIL', error: error.message })
    console.error(`FAIL ${name}: ${error.message}`)
    await page.screenshot({ path: path.join(evidence, `failure-${results.length}.png`), fullPage: true })
    throw error
  }
}
async function screenshot(name) { await page.screenshot({ path: path.join(evidence, name + '.png'), fullPage: true }) }
async function measureLayout(width) {
  await page.setViewportSize({ width, height: 1000 })
  const layout = await page.evaluate(() => {
    const rect = (selector) => {
      const { x, y, width, height, bottom, right } = document.querySelector(selector).getBoundingClientRect()
      return { x, y, width, height, bottom, right }
    }
    const fields = [...document.querySelectorAll('.filter-controls > label')].map((node) => node.getBoundingClientRect())
    return { viewport: innerWidth, overflow: document.documentElement.scrollWidth > innerWidth,
      source: rect('.source-panel'), range: rect('.range-panel'), card: rect('.import-card'),
      headingGap: rect('.import-card').y - rect('.page-heading').bottom,
      fieldGap: fields[1].y - fields[0].bottom,
      sourcePadding: getComputedStyle(document.querySelector('.source-panel')).padding,
      footer: rect('.import-footer'),
    }
  })
  expect(layout.overflow).toBe(false)
  expect(layout.headingGap).toBe(24)
  expect(layout.fieldGap).toBe(16)
  if (width > 760) expect(layout.source.y).toBe(layout.range.y)
  else expect(layout.range.y).toBe(layout.source.bottom)
  layouts.push(layout)
  await screenshot('layout-' + width)
}
try {
  await page.goto(new URL(url).origin)
  await expect(page).toHaveURL(url + '#/')
  const initialStorage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
  await check('正常产品文案、无测试工具、按需帮助', async () => {
    await expect(page.getByRole('heading', { name: '导入项目', exact: true })).toBeVisible()
    await expect(page.locator('.help-content')).not.toBeVisible()
    await page.locator('.filter-help summary').focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('.help-content')).toContainText('node_modules、.venv、venv')
    await page.locator('.filter-help summary').click()
    await expect(page.locator('.help-content')).not.toBeVisible()
    await screenshot('desktop-empty')
  })
  await check('实际文件夹选择、默认清单与名称限制', async () => {
    await expect(submitButton()).toBeDisabled()
    await choose()
    await expect(nameInput).toHaveValue('客户管理台')
    await expect(included).toHaveText('10')
    await expect(excluded).toHaveText('2')
    await expect(page.getByTestId('total-bytes')).toHaveText('15.1 KB')
    await nameInput.fill('  ')
    await expect(page.getByText('请输入应用名称。', { exact: true })).toBeVisible()
    await expect(submitButton()).toBeDisabled()
    await nameInput.fill('x'.repeat(81))
    await expect(page.getByText('名称最多 80 个字符。', { exact: true })).toBeVisible()
    await nameInput.fill('客户管理台')
  })
  await check('清单详情、依赖/构建过滤与敏感配置警告', async () => {
    await page.getByText('查看清单详情（12）', { exact: true }).click()
    await expect(page.locator('.file-row').filter({ hasText: 'node_modules/vue/package.json' })).toContainText('拟导入')
    await expect(page.locator('.file-row').filter({ hasText: '.env.example' })).toContainText('拟导入')
    await expect(page.locator('.file-row').filter({ hasText: 'README.md' })).toContainText('0 B')
    await checkbox('跳过依赖目录').check()
    await expect(included).toHaveText('8')
    await checkbox('跳过构建产物').check()
    await expect(included).toHaveText('6')
    await checkbox('本次包含敏感配置').check()
    await expect(included).toHaveText('8')
    await expect(page.getByText('可能包含密钥；此选择不授权模型读取。')).toBeVisible()
    await page.getByText('查看清单详情（12）', { exact: true }).click()
  })
  await check('单次选项与显式保存偏好分离', async () => {
    await choose()
    await expect(included).toHaveText('10')
    await checkbox('跳过依赖目录').check()
    await page.getByRole('button', { name: '保存为默认偏好', exact: true }).click()
    await expect(page.getByText('偏好已保存。', { exact: true })).toBeVisible()
    await checkbox('跳过依赖目录').uncheck()
    await choose()
    await expect(included).toHaveText('8')
    await page.reload()
    await choose()
    await expect(included).toHaveText('10')
  })
  await check('桌面/中等/窄屏间距、对齐与弹层边界', async () => {
    for (const width of [1440, 1024, 768, 375]) await measureLayout(width)
    await page.locator('.filter-help summary').click()
    const box = await page.locator('.help-content').boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(16)
    expect(box.x + box.width).toBeLessThanOrEqual(359)
    await page.locator('.filter-help summary').click()
    await page.setViewportSize({ width: 1440, height: 1000 })
  })
  let cancelledTask, failedTask
  await check('取消、重复提交保护与迟到结果阻断', async () => {
    await submitButton().click()
    cancelledTask = (await events()).at(-1).taskId
    await expect(page.getByRole('button', { name: '重新选择', exact: true })).toBeDisabled()
    await expect(submitButton()).toBeDisabled()
    await expect(nameInput).toBeDisabled()
    await page.getByRole('button', { name: '取消导入', exact: true }).click()
    await page.waitForTimeout(2200)
    await expect(page.getByText('导入已取消。', { exact: true })).toBeVisible()
    await expect(page).toHaveURL(url + '#/')
    await expect(page.locator('.agent-workspace')).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '导入完成', exact: true })).toHaveCount(0)
    expect((await events()).filter((event) => event.taskId === cancelledTask).map((event) => event.status)).toEqual(['importing', 'cancelled'])
  })
  await check('失败保留输入并提供重试', async () => {
    await submitButton().click()
    failedTask = (await events()).at(-1).taskId
    expect(failedTask).not.toBe(cancelledTask)
    await expect(page.getByText('导入失败，请重试。', { exact: true })).toBeVisible()
    await expect(nameInput).toHaveValue('客户管理台')
    await expect(submitButton()).toBeEnabled()
    await expect(page).toHaveURL(url + '#/')
    await screenshot('failure-state')
  })
  await check('成功自动进入正确应用工作台，不停留成功页、不自动生成', async () => {
    await nameInput.fill('客户管理台 · 新应用')
    await submitButton().click()
    await expect(page.locator('.app-name')).toHaveText('客户管理台 · 新应用')
    await expect(page).toHaveURL(/#\/app\/preview-app-[a-f0-9-]+\/chat$/)
    expect(page.url()).not.toContain('autoSend')
    await expect(page.locator('.result-panel')).toHaveCount(0)
    await expect(page.locator('.chat-input')).toHaveValue('')
    await expect(page.locator('.chat-bubble')).toHaveCount(0)
    expect((await events()).at(-1).taskId).not.toBe(failedTask)
    expect((await events()).at(-1).name).toBe('客户管理台 · 新应用')
    await expect(page.getByRole('dialog')).toContainText('是否安装依赖？')
    await page.getByRole('button', { name: '暂不安装', exact: true }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
    await expect(page.locator('.workspace-toolbar').getByRole('button', { name: '安装依赖', exact: true })).toBeEnabled()
    await screenshot('success-state')
  })
  await check('安装确认、进行中保护、失败重试与完成反馈', async () => {
    await page.locator('.workspace-toolbar').getByRole('button', { name: '安装依赖', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText('单独安装不会启动项目。')
    await page.getByRole('dialog').getByRole('button', { name: '安装依赖', exact: true }).click()
    await expect(page.getByText('正在安装依赖…', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '导入另一个工程', exact: true })).toBeDisabled()
    await expect(page.getByText('依赖安装失败，请重试。', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '重试安装', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '安装依赖', exact: true }).click()
    await expect(page.getByRole('button', { name: '已安装', exact: true })).toBeDisabled()
    await screenshot('installed-state')
    await page.setViewportSize({ width: 375, height: 812 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    for (const width of [375, 768]) {
      await page.setViewportSize({ width, height: 812 })
      const layout = await page.evaluate(() => {
        const chat = document.querySelector('.chat-panel').getBoundingClientRect()
        const input = document.querySelector('.chat-input-bar').getBoundingClientRect()
        const messages = document.querySelector('.chat-messages').getBoundingClientRect()
        const preview = document.querySelector('.preview-panel').getBoundingClientRect()
        return { chatHeight: chat.height, messageBottom: messages.bottom, inputTop: input.top,
          panelGap: preview.y - chat.bottom, overflow: document.documentElement.scrollWidth > innerWidth }
      })
      expect(layout.chatHeight).toBe(480)
      expect(layout.messageBottom).toBeLessThanOrEqual(layout.inputTop)
      expect(layout.panelGap).toBe(20)
      expect(layout.overflow).toBe(false)
      layouts.push({ kind: 'agent-workspace', viewport: width, ...layout })
    }
    await page.setViewportSize({ width: 375, height: 812 })
    await screenshot('mobile-result')
  })
  await check('工作台消息交互只在当前工程内，不写文件或调用模型', async () => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.getByPlaceholder('描述你想调整的内容…').fill('先看一下工程目录')
    await page.getByRole('button', { name: /发\s*送/ }).click()
    await expect(page.locator('.bubble-user')).toContainText('先看一下工程目录')
    await expect(page.locator('.bubble-ai')).toContainText('当前工程包含 10 个文件。')
    await screenshot('agent-chat')
  })
  await check('新工程重置、空范围与空目录错误', async () => {
    await page.getByRole('button', { name: '导入另一个工程', exact: true }).click()
    await expect(page).toHaveURL(url + '#/')
    await page.setViewportSize({ width: 375, height: 812 })
    await page.locator('input[type=file]').setInputFiles(secretOnly)
    await expect(included).toHaveText('0')
    await expect(page.getByText('当前范围没有可导入文件，请调整选项或重新选择。')).toBeVisible()
    await expect(submitButton()).toBeDisabled()
    await checkbox('本次包含敏感配置').check()
    await expect(included).toHaveText('1')
    await expect(submitButton()).toBeEnabled()
    await choose()
    await expect(included).toHaveText('10')
    await page.locator('input[type=file]').setInputFiles(emptyFolder)
    await expect(page.getByText('未发现文件，请选择非空文件夹。', { exact: true })).toBeVisible()
    await expect(submitButton()).toBeDisabled()
    await choose()
  })
  await check('窄屏浅深主题、文件清单与键盘交互', async () => {
    await page.getByText('查看清单详情（12）', { exact: true }).click()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await screenshot('mobile-light')
    await page.getByRole('button', { name: /深\s*色/ }).click()
    expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark')
    await screenshot('mobile-dark')
    await page.getByRole('button', { name: '重新选择', exact: true }).focus()
    await expect(page.getByRole('button', { name: '重新选择', exact: true })).toBeFocused()
    await checkbox('跳过依赖目录').focus()
    await page.keyboard.press('Space')
    await expect(included).toHaveText('8')
  })
  await check('干净默认入口完成导入与安装，不依赖测试装配', async () => {
    const cleanPage = await context.newPage()
    cleanPage.on('pageerror', (error) => pageErrors.push(error.message))
    cleanPage.on('console', (message) => { if (message.type() === 'error') pageErrors.push(message.text()) })
    await cleanPage.setViewportSize({ width: 1440, height: 1000 })
    await cleanPage.goto(url)
    expect(await cleanPage.evaluate(() => window.__IMPORT_PREVIEW_SCENARIO__)).toBeUndefined()
    await cleanPage.locator('input[type=file]').setInputFiles(fixture)
    await cleanPage.getByRole('button', { name: '确认导入', exact: true }).click()
    await expect(cleanPage.locator('.app-name')).toHaveText('客户管理台')
    await expect(cleanPage).toHaveURL(/#\/app\/preview-app-[a-f0-9-]+\/chat$/)
    await expect(cleanPage.getByRole('dialog')).toBeVisible()
    await cleanPage.getByRole('dialog').getByRole('button', { name: '安装依赖', exact: true }).click()
    await expect(cleanPage.getByRole('button', { name: '已安装', exact: true })).toBeDisabled()
    expect(await cleanPage.locator('body').innerText()).not.toMatch(/Mock|模拟|预览|待实现|preview-/i)
    await cleanPage.screenshot({ path: path.join(evidence, 'default-installed.png'), fullPage: true })
    await cleanPage.reload()
    await expect(cleanPage).toHaveURL(url + '#/')
    await expect(cleanPage.getByText('工程会话已失效，请重新导入。', { exact: true })).toBeVisible()
    await expect(cleanPage.locator('.agent-workspace')).toHaveCount(0)
    await cleanPage.close()
  })
  await check('返回中止旧安装，新工程不接收迟到状态，伪造地址不能进入工作台', async () => {
    const cleanPage = await context.newPage()
    cleanPage.on('pageerror', (error) => pageErrors.push(error.message))
    cleanPage.on('console', (message) => { if (message.type() === 'error') pageErrors.push(message.text()) })
    await cleanPage.goto(url)
    await cleanPage.locator('input[type=file]').setInputFiles(fixture)
    await cleanPage.getByLabel('新应用名称', { exact: true }).fill('x'.repeat(80))
    await cleanPage.getByRole('button', { name: '确认导入', exact: true }).click()
    await expect(cleanPage.locator('.app-name')).toHaveText('x'.repeat(80))
    await cleanPage.setViewportSize({ width: 375, height: 812 })
    expect(await cleanPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const previousUrl = cleanPage.url()
    await cleanPage.getByRole('dialog').getByRole('button', { name: '安装依赖', exact: true }).click()
    await expect(cleanPage.getByText('正在安装依赖…', { exact: true })).toBeVisible()
    await cleanPage.goBack()
    await expect(cleanPage).toHaveURL(url + '#/')
    await cleanPage.waitForTimeout(1500)
    await expect(cleanPage.getByRole('button', { name: '已安装', exact: true })).toHaveCount(0)
    await cleanPage.locator('input[type=file]').setInputFiles(fixture)
    await cleanPage.getByRole('button', { name: '确认导入', exact: true }).click()
    await expect(cleanPage.locator('.app-name')).toHaveText('客户管理台')
    expect(cleanPage.url()).not.toBe(previousUrl)
    await cleanPage.getByRole('button', { name: '暂不安装', exact: true }).click()
    await expect(cleanPage.locator('.workspace-toolbar').getByRole('button', { name: '安装依赖', exact: true })).toBeEnabled()
    await cleanPage.evaluate(() => { location.hash = '/app/not-this-project/chat' })
    await expect(cleanPage).toHaveURL(url + '#/')
    await expect(cleanPage.getByText('工程会话已失效，请重新导入。', { exact: true })).toBeVisible()
    await expect(cleanPage.locator('.agent-workspace')).toHaveCount(0)
    await cleanPage.close()
  })
  await check('无生产/外部请求、残留存储、文件修改及浏览器错误', async () => {
    expect(forbiddenRequests).toEqual([])
    expect(pageErrors).toEqual([])
    expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).toBe(initialStorage)
    expect(await hashFixtures()).toEqual(fixtureBefore)
  })
} catch {
  process.exitCode = 1
} finally {
  await writeFile(path.join(evidence, 'browser-results.json'), JSON.stringify({
    timestamp: new Date().toISOString(), url, browser: browser.version(),
    fixture, scope: 'FRONTEND_PRODUCT_SIMULATION_ONLY', results, layouts, pageErrors, forbiddenRequests,
    events: await events(), fixtureBefore, fixtureAfter: await hashFixtures(),
  }, null, 2))
  await browser.close()
}
