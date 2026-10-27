import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { randomBytes, createHash } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repository = path.resolve(fileURLToPath(new URL('../../', import.meta.url)))
const evidence = path.join(repository, 'temp', `folder-import-real-${Date.now()}`)
const source = path.join(evidence, 'source', '测试工程')
const base = process.env.PROJECT_FRONTEND_URL || 'http://localhost:5173'
await mkdir(source, { recursive: true })
const entries = {
  'index.html': `<meta charset="utf-8"><h1 id="fixture">已导入工程</h1><script>document.body.dataset.previewReady='true';try{window.parent.document.body.dataset.crossLeak='yes';document.body.dataset.parentAccess='unexpected'}catch{document.body.dataset.parentAccess='blocked'}fetch('/api/user/get/login').then(()=>document.body.dataset.apiAccess='unexpected').catch(()=>document.body.dataset.apiAccess='blocked')</script>`,
  'src/中文 文件.js': "console.log('known fixture')", 'empty.bin': '',
  'node_modules/pkg/index.js': 'dependency fixture', 'dist/index.html': 'build fixture',
  '.env': 'FAKE_IMPORT_SECRET_NOT_A_REAL_KEY', '.env.example': 'EXAMPLE=placeholder',
  'assets/raw.bin': Buffer.from([0, 255, 1, 128, 42]),
  'app.py': 'print(1)\n',
}
entries['dist/index.html'] = entries['index.html']
for (const [relative, content] of Object.entries(entries)) {
  const file = path.join(source, relative); await mkdir(path.dirname(file), { recursive: true }); await writeFile(file, content)
}
const hash = buffer => createHash('sha256').update(buffer).digest('hex')
const before = Object.fromEntries(await Promise.all(Object.keys(entries).map(async name => [name, hash(await readFile(path.join(source, name)))])))
const result = { status: 'RUNNING', evidence, base, checks: [], applications: [], forbiddenExecution: [], errors: [], isolationConsole: [], fullImportAcceptance: 'NOT_RUN' }
const browser = await chromium.launch({ executablePath: process.env.IMPORT_PREVIEW_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
page.on('pageerror', error => result.errors.push(error.message))
page.on('console', entry => { if (entry.type() === 'error') result.isolationConsole.push(entry.text()) })
page.on('request', request => {
  const url = new URL(request.url())
  if (/\/runtime\/start|\/chat\/generate\/stream|\/project\/import\/.*\/install/.test(url.pathname)) result.forbiddenExecution.push(url.pathname)
})
async function check(name, action) { await action(); result.checks.push({ name, status: 'PASS' }); console.log(`PASS ${name}`) }
async function api(url, data) {
  const response = data === undefined ? await context.request.get(`${base}/api${url}`) : await context.request.post(`${base}/api${url}`, { data })
  const json = await response.json()
  if (json.code !== 20000) throw new Error(`${url}: code=${json.code}`)
  return json.data
}
try {
  const account = `import_browser_${randomBytes(8).toString('hex')}`
  const password = randomBytes(24).toString('hex')
  const ownerId = String(await api('/user/register', { userAccount: account, userPassword: password, checkPassword: password }))
  await api('/user/login', { userAccount: account, userPassword: password })
  result.user = { account, ownerId, passwordRecorded: false }
  await page.goto(`${base}/project/import`)
  await check('读取实际偏好后可选择文件夹', async () => {
    await expect(page.getByRole('button', { name: '选择文件夹', exact: true })).toBeEnabled()
    await page.locator('input[webkitdirectory]').setInputFiles(source)
    await expect(page.getByRole('button', { name: '确认导入', exact: true })).toBeEnabled()
    await expect(page.locator('[data-testid="included-count"]')).toHaveText('8')
  })
  await check('布局无横向溢出且模块不重叠', async () => {
    const layout = await page.evaluate(() => {
      const source = document.querySelector('.source-panel').getBoundingClientRect()
      const range = document.querySelector('.range-panel').getBoundingClientRect()
      return { width: window.innerWidth, scroll: document.documentElement.scrollWidth, sourceRight: source.right, rangeLeft: range.left }
    })
    expect(layout.scroll).toBeLessThanOrEqual(layout.width)
    expect(layout.sourceRight).toBeLessThanOrEqual(layout.rangeLeft + 1)
    result.layout = layout
    await page.screenshot({ path: path.join(evidence, 'before-import.png'), fullPage: true })
  })
  const responsePromise = page.waitForResponse(response => /\/project\/import\/tasks\/[^/]+\/commit$/.test(new URL(response.url()).pathname))
  await page.getByRole('button', { name: '确认导入', exact: true }).click()
  const commitResponse = await responsePromise
  const commit = await commitResponse.json()
  expect(commit.code).toBe(20000)
  const appId = String(commit.data.appId), taskId = commit.data.taskId
  result.applications.push({ appId, taskId })
  await check('真实导入成功后进入对应工作台且无 autoSend', async () => {
    await expect(page).toHaveURL(new RegExp(`/app/${appId}/chat$`), { timeout: 30_000 })
    const app = await api(`/app/get/vo?id=${appId}`)
    expect(String(app.userId)).toBe(ownerId); expect(app.appName).toBe('测试工程')
    const state = await api(`/project/import/tasks/${taskId}`)
    expect(state.status).toBe('SUCCEEDED')
  })
  await check('最终文件与来源一致，默认敏感配置未接入', async () => {
    const output = path.join(repository, 'temp', 'code_output', `project_${appId}`)
    for (const [name, expected] of Object.entries(before)) {
      expect(hash(await readFile(path.join(source, name)))).toBe(expected)
      if (name !== '.env') expect(hash(await readFile(path.join(output, name)))).toBe(expected)
    }
    await expect(readFile(path.join(output, '.env'))).rejects.toThrow()
    result.sourceHashes = before
  })
  await check('现有编辑器可保存Python文件且不改来源文件夹', async () => {
    const original = await api(`/app/${appId}/files/content?path=app.py`)
    await page.getByRole('button', { name: '项目文件', exact: true }).click()
    await page.locator('.pf-tree').getByText('app.py', { exact: true }).click()
    const editor = page.locator('.pf-editor')
    await expect(editor).toBeEnabled()
    await expect(editor).toHaveValue(original.content)
    await editor.fill('print(2)\n')
    const save = page.locator('.pf-viewer-head').getByRole('button', { name: /^保\s*存$/ })
    await expect(save).toBeEnabled()
    const [response] = await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === `/api/app/${appId}/files/content` && response.request().method() === 'POST'),
      save.click(),
    ])
    expect((await response.json()).code).toBe(20000)
    const saved = await api(`/app/${appId}/files/content?path=app.py`)
    expect(saved.content).toBe('print(2)\n'); expect(saved.version).not.toBe(original.version)
    expect(hash(await readFile(path.join(source, 'app.py')))).toBe(before['app.py'])
    result.editor = { appId, oldVersion: original.version, newVersion: saved.version, sourceUnchanged: true }
    await page.locator('.ant-modal-close:visible').click()
  })
  await check('私有预览认证与浏览器隔离有效', async () => {
    const own = await context.request.get(`${base}/api/static/project_${appId}/index.html`)
    expect(own.status()).toBe(200)
    expect(own.headers()['content-security-policy']).toContain('sandbox allow-scripts')
    const anonymous = await browser.newContext()
    expect((await anonymous.request.get(`${base}/api/static/project_${appId}/index.html`)).status()).toBe(404)
    await anonymous.close()
    await expect(page.frameLocator('iframe').locator('body')).toHaveAttribute('data-parent-access', 'blocked')
    expect(await page.evaluate(() => document.body.dataset.crossLeak)).toBeUndefined()
    await page.goto(`${base}/api/static/project_${appId}/index.html`)
    await expect(page.locator('#fixture')).toHaveText('已导入工程')
    await expect(page.locator('body')).toHaveAttribute('data-api-access', 'blocked')
    result.previewCsp = own.headers()['content-security-policy']
  })
  await check('未自动生成、安装或启动工程', async () => {
    expect(result.forbiddenExecution).toEqual([])
    expect(result.errors).toEqual([])
  })
  result.status = 'PASS'
} catch (error) {
  result.status = 'FAIL'; result.failure = error.message
  await page.screenshot({ path: path.join(evidence, 'failure.png'), fullPage: true }).catch(() => {})
  throw error
} finally {
  result.cleanup = []
  for (const { appId } of result.applications) {
    try {
      const deleted = await api('/app/delete', { id: appId })
      if (deleted !== true) {
        result.status = 'FAIL'
        result.cleanup.push({ appId, error: '业务删除未成功' })
        process.exitCode = 1
      } else {
        result.cleanup.push({ appId, deleted })
      }
    } catch (error) {
      result.status = 'FAIL'
      result.cleanup.push({ appId, error: error.message })
      process.exitCode = 1
    }
  }
  await writeFile(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2))
  await context.close(); await browser.close()
  console.log(`EVIDENCE=${evidence}`)
}
