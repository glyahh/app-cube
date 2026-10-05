// 第一项步骤4：正式工作台对真实后端（8124，经 Vite 5173 代理）的桌面/窄屏走查。只识别、拒绝、确认计划，不安装、不构建、不运行导入工程。
// 过期批准使用本脚本应用中的受控账本夹具（已标注），其余均为真实接口与真实扫描。
import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repository = path.resolve(fileURLToPath(new URL('../../', import.meta.url)))
const evidence = path.join(repository, 'temp', 'import-startup-workbench-20261005', `browser-${Date.now()}`)
const base = process.env.PROJECT_FRONTEND_URL || 'http://127.0.0.1:5173'
await mkdir(evidence, { recursive: true })
const result = { status: 'RUNNING', evidence, base, checks: [], applications: [], forbiddenExecution: [], pageErrors: [], consoleErrors: [], startupRequests: [],
  firstRequirement: 'NOT_COMPLETE', executionEntry: 'CLOSED', fixtureScope: 'CONTROLLED_DB_FIXTURE_FOR_EXPIRED_APPROVAL_ONLY' }
const python = path.join(repository, 'agent', '.venv', 'Scripts', 'python.exe')
// 受控夹具：按真实拒绝行确定哈希编码，插入一条已过期、未认领的 APPROVED 批准（从未执行）。
const fixture = String.raw`
import json,sys,re,uuid,hashlib,psycopg
from pathlib import Path
from datetime import datetime,timedelta,timezone
a=json.loads(sys.argv[1]);i=a['inspection'];owner=a['owner'];app=a['app']
dsn=next(l.split('=',1)[1].strip().strip('"').strip("'") for l in Path('.env').read_text(encoding='utf-8-sig').splitlines() if l.startswith('AGENT_POSTGRES_DSN='))
rules=re.search(r'RULES_VERSION="([^"]+)"',Path('src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupInspectionRepository.java').read_text(encoding='utf-8')).group(1)
def digest(n,approved):
    p=[n(owner),n(app),i['scanId'],i['importTaskId'],i['contentHash'],n(i['configRevision']),n(i['contextEpoch']),rules,['worker'],approved]
    return hashlib.sha256(json.dumps(p,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
with psycopg.connect(dsn) as db:
    stored=db.execute("SELECT request_hash FROM biz.project_import_startup_approval WHERE owner_id=%s AND app_id=%s AND scan_id=%s AND decision='REJECT'",(owner,app,i['scanId'])).fetchone()[0]
    n=next(f for f in (int,lambda v:str(int(v))) if digest(f,False)==stored)
    approval=str(uuid.uuid4())
    db.execute("""INSERT INTO biz.project_import_startup_approval(approval_id,request_id,owner_id,app_id,scan_id,import_task_id,content_hash,
        config_revision,context_epoch,rules_version,directories_json,request_hash,decision,state,revision,cancel_requested,expires_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'["worker"]',%s,'APPROVE','APPROVED',0,false,%s)""",
        (approval,str(uuid.uuid4()),owner,app,i['scanId'],i['importTaskId'],i['contentHash'],int(i['configRevision']),int(i['contextEpoch']),rules,digest(n,True),datetime.now(timezone.utc)-timedelta(seconds=1)))
    leases=db.execute("SELECT count(*) FROM biz.project_import_startup_model_execution WHERE app_id=%s",(app,)).fetchone()[0]
print(json.dumps({'approvalId':approval,'leases':leases}))
`
function expiredFixture(input) {
  const run = spawnSync(python, ['-c', fixture, JSON.stringify(input)], { cwd: repository, encoding: 'utf-8', timeout: 60_000 })
  if (run.status !== 0) throw new Error(`fixture failed: ${run.stderr}`)
  return JSON.parse(run.stdout.trim().split('\n').pop())
}

const server = "require('http').createServer().listen(3000)"
const files = {
  'web/package.json': JSON.stringify({ name: 'web', packageManager: 'npm@10.0.0', scripts: { dev: 'concurrently "npm:ui" "npm:api"', ui: 'vite', api: 'node ../api/server.js', predev: 'node check.js' } }),
  'web/package-lock.json': '{}',
  'api/package.json': JSON.stringify({ name: 'api', packageManager: 'npm@10.0.0', scripts: { start: 'node server.js' } }),
  'api/package-lock.json': '{}', 'api/server.js': server,
  'worker/package.json': JSON.stringify({ name: 'worker', scripts: {} }), 'worker/server.js': server,
}
const browser = await chromium.launch({ executablePath: process.env.IMPORT_PREVIEW_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true })
async function check(name, action) { await action(); result.checks.push({ name, status: 'PASS' }); console.log(`PASS ${name}`) }

async function session(viewport) {
  const context = await browser.newContext({ viewport })
  const page = await context.newPage()
  page.on('pageerror', error => result.pageErrors.push(error.message))
  page.on('console', entry => { if (entry.type() === 'error') result.consoleErrors.push(entry.text()) })
  page.on('request', request => {
    const url = new URL(request.url())
    if (/\/runtime\/start|\/install|\/run(s)?(\/|$)|\/chat\/gen|\/ws\/agent/.test(url.pathname)) result.forbiddenExecution.push(`${request.method()} ${url.pathname}`)
    if (url.pathname.includes('/startup/')) result.startupRequests.push(`${request.method()} ${url.pathname.replace(/\/[0-9a-f-]{36}/g, '/{id}').replace(/\/apps\/\d+/, '/apps/{app}')}`)
  })
  let csrf
  async function api(url, data, method) {
    const options = { headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {} }
    const verb = method ?? (data === undefined ? 'GET' : 'POST')
    const response = await context.request.fetch(`${base}/api${url}`, { method: verb, ...options, ...(data === undefined ? {} : Buffer.isBuffer(data) ? { data, headers: { ...options.headers, 'Content-Type': 'application/octet-stream' } } : { data }) })
    const json = await response.json()
    if (json.code !== 20000) throw new Error(`${url}: code=${json.code}`)
    return json.data
  }
  const account = `workbench_${randomBytes(8).toString('hex')}`, password = randomBytes(24).toString('hex')
  const ownerId = String(await api('/user/register', { userAccount: account, userPassword: password, checkPassword: password }))
  await api('/user/login', { userAccount: account, userPassword: password }); csrf = await api('/project/import/csrf')
  // 导入夹具工程（真实导入接口）；工程本身从不安装或运行。
  let task = await api('/project/import/tasks', { ownerId, appName: '工作台联调', files: Object.entries(files).map(([p, c]) => ({ path: p, size: Buffer.byteLength(c) })),
    options: { skipDependencies: false, skipBuildOutputs: false, includeSensitive: true } })
  for (const [index, content] of Object.values(files).entries()) task = await api(`/project/import/tasks/${task.taskId}/files/${index}?revision=${task.revision}`, Buffer.from(content), 'PUT')
  const committed = await api(`/project/import/tasks/${task.taskId}/commit`, { revision: task.revision, contentHash: task.contentHash })
  const app = String(committed.appId)
  result.applications.push({ viewport: viewport.width, appId: app, taskId: task.taskId, ownerId })
  return { context, page, api, app, ownerId }
}
const testid = (page, id) => page.locator(`[data-testid="${id}"]`)
async function layout(page, name) {
  const value = await page.evaluate(() => {
    const modal = [...document.querySelectorAll('.ant-modal')].filter(item => item.getBoundingClientRect().width > 0).pop()?.getBoundingClientRect()
    return { width: window.innerWidth, scroll: document.documentElement.scrollWidth, modalLeft: modal?.left ?? null, modalRight: modal?.right ?? null }
  })
  expect(value.scroll).toBeLessThanOrEqual(value.width)
  if (value.modalRight !== null) { expect(value.modalLeft).toBeGreaterThanOrEqual(0); expect(value.modalRight).toBeLessThanOrEqual(value.width + 0.5) }
  result[`layout-${name}`] = value
  await page.screenshot({ path: path.join(evidence, `${name}.png`) })
}

async function core(prefix, viewport) {
  const s = await session(viewport)
  const { page, api, app, ownerId } = s
  await page.goto(`${base}/app/${app}/chat`)
  await check(`${prefix}: 导入工程显示启动入口；安装不可执行，旧运行入口不出现`, async () => {
    await expect(testid(page, 'startup-open')).toBeVisible({ timeout: 30_000 })
    await expect(testid(page, 'startup-install')).toBeDisabled()
    await expect(page.locator('.top-actions').getByRole('button', { name: /^运\s?行/ })).toHaveCount(1)
    await layout(page, `${prefix}-workbench`)
  })
  let inspection
  await check(`${prefix}: 真实异步扫描完成，列出候选与未识别目录；运行所选不可执行`, async () => {
    await testid(page, 'startup-open').click()
    await expect(testid(page, 'startup-scan')).toBeEnabled({ timeout: 15_000 })
    const read = page.waitForResponse(response => /\/startup\/inspections\//.test(response.url()), { timeout: 60_000 })
    await testid(page, 'startup-scan').click()
    inspection = (await (await read).json()).data.inspection
    await expect(testid(page, 'startup-scan-status')).toContainText('已完成')
    await expect(testid(page, 'startup-candidate')).toHaveCount(inspection.result.candidates.length)
    expect(inspection.result.candidates.length).toBeGreaterThanOrEqual(2)
    await expect(testid(page, 'startup-scope').filter({ hasText: 'worker · 未识别' })).toHaveCount(1)
    await expect(testid(page, 'startup-run')).toBeDisabled()
    await layout(page, `${prefix}-scan`)
  })
  await check(`${prefix}: 拒绝 Agent 识别真实落库为 REJECTED，不产生候选`, async () => {
    await testid(page, 'startup-scope').filter({ hasText: 'worker' }).getByRole('button', { name: /Agent/ }).click()
    const decided = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/startup/approvals'))
    await testid(page, 'startup-agent-reject').click()
    const approval = (await (await decided).json()).data
    expect(approval.state).toBe('REJECTED')
    expect((await api(`/project/import/apps/${app}/startup/approvals/${approval.approvalId}`)).state).toBe('REJECTED')
    await expect(testid(page, 'startup-notice')).toContainText('已拒绝')
    await expect(testid(page, 'startup-candidate')).toHaveCount(inspection.result.candidates.length)
    await layout(page, `${prefix}-reject`)
  })
  await check(`${prefix}: [受控夹具] 过期批准刷新后找回只显示已过期，不产生候选、无执行租约`, async () => {
    const created = expiredFixture({ owner: ownerId, app, inspection })
    expect(created.leases).toBe(0)
    await page.evaluate(([key, id]) => sessionStorage.setItem(key, id), [`project-import-startup-approval:${ownerId}:${app}`, created.approvalId])
    await page.reload()
    await testid(page, 'startup-open').click()
    await expect(testid(page, 'startup-error')).toContainText('已过期', { timeout: 60_000 })
    await expect(testid(page, 'startup-approval')).toContainText('已失效')
    const state = await api(`/project/import/apps/${app}/startup/approvals/${created.approvalId}`)
    expect(state.state).toBe('STALE'); expect(state.error).toBe('APPROVAL_EXPIRED'); expect(state.resultRecorded).toBe(false)
    await expect(testid(page, 'startup-candidate')).toHaveCount(0)
    await expect(testid(page, 'startup-run')).toBeDisabled()
    await layout(page, `${prefix}-expired`)
  })
  return s
}

try {
  const desktop = await core('desktop', { width: 1440, height: 1000 })
  const { page, api, app } = desktop
  const workspace = path.join(repository, 'temp', 'code_output', `project_${app}`)
  await check('desktop: 选择服务生成草稿计划；聚合脚本须核对展开范围后才能确认；确认后仍不可安装/运行', async () => {
    await testid(page, 'startup-scan').click()
    await expect(testid(page, 'startup-scan-status')).toContainText('已完成', { timeout: 60_000 })
    await testid(page, 'startup-candidate').filter({ hasText: 'run dev' }).locator('input[type="checkbox"]').check()
    await testid(page, 'startup-plan-create').click()
    await expect(testid(page, 'startup-plan-state')).toHaveText('草稿')
    await expect(testid(page, 'startup-aggregate')).toContainText('将带上未选服务')
    await expect(testid(page, 'startup-confirm')).toBeDisabled()
    await layout(page, 'desktop-plan')
    await testid(page, 'startup-aggregate').locator('input[type="checkbox"]').check()
    await testid(page, 'startup-confirm').click()
    await expect(testid(page, 'startup-plan-state')).toHaveText('已确认')
    const current = await api(`/project/import/apps/${app}/startup/plans/current`)
    expect(current.state).toBe('CONFIRMED'); expect(current.executable).toBe(false); expect(current.aggregateAcknowledged).toBe(true)
    await expect(testid(page, 'startup-run')).toBeDisabled(); await expect(testid(page, 'startup-install')).toBeDisabled()
    await layout(page, 'desktop-confirmed')
  })
  await check('desktop: 外部改动后刷新计划显示 STALE/识别过期，不能再确认或运行', async () => {
    const target = path.join(workspace, 'api', 'server.js'), original = await readFile(target)
    try {
      await writeFile(target, Buffer.concat([original, Buffer.from('\n// changed outside platform')]))
      await page.getByRole('button', { name: '刷新计划' }).click()
      await expect(testid(page, 'startup-plan-state')).toHaveText('已失效', { timeout: 30_000 })
      await expect(testid(page, 'startup-plan')).toContainText('识别结果已过期')
      await expect(testid(page, 'startup-confirm')).toHaveCount(0)
      await expect(testid(page, 'startup-candidate')).toHaveCount(0)
      await expect(testid(page, 'startup-run')).toBeDisabled()
      await layout(page, 'desktop-stale')
    } finally { await writeFile(target, original) }
    expect((await api(`/project/import/apps/${app}/startup/plans/current`)).state).toBe('STALE')
    for (const dir of ['web', 'api', 'worker']) { expect(existsSync(path.join(workspace, dir, 'node_modules'))).toBe(false); expect(existsSync(path.join(workspace, dir, 'dist'))).toBe(false) }
  })
  await core('narrow', { width: 390, height: 844 })
  expect(result.forbiddenExecution).toEqual([])
  expect(result.pageErrors).toEqual([])
  result.status = 'PASS'
} catch (error) {
  result.status = 'FAIL'; result.error = error instanceof Error ? error.stack : String(error)
  process.exitCode = 1
} finally {
  await browser.close()
  await writeFile(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify({ status: result.status, evidence, checks: result.checks.length, forbiddenExecution: result.forbiddenExecution, pageErrors: result.pageErrors.length, error: result.error?.slice(0, 600) }))
}
