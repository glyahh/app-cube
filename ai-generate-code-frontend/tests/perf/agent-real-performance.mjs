// 正式前端 → Java → Python → Docker → 真实模型；专用账户及应用，不接触原应用。
// node tests/perf/agent-real-performance.mjs before|after <报告目录>
import { chromium } from 'playwright'
import { randomBytes } from 'node:crypto'
import { mkdir, writeFile, readFile, access } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { execFileSync } from 'node:child_process'

const phase = process.argv[2]
const iterations = Number(process.argv[4] ?? 3)
if (!Number.isInteger(iterations) || iterations < 1 || iterations > 10) throw Error('invalid iteration count')
const output = resolve(process.argv[3])
const repo = resolve('..')
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext()
const page = await context.newPage()
const account = `speed_test_${randomBytes(6).toString('hex')}`
const password = randomBytes(18).toString('hex')
const report = { phase, account, apps: [], runs: [], cleanup: [] }
await mkdir(output, { recursive: true })
async function api(path, data, method) {
  const response = await context.request.fetch(`http://localhost:5173/api${path}`, {
    method: method ?? (data === undefined ? 'GET' : 'POST'), data,
    headers: { Origin: 'http://localhost:5173' }, timeout: 120_000,
  })
  const body = await response.json()
  if (body.code !== 20000 && body.code !== 0) throw Error(`API ${path}: ${body.code}`)
  return body.data
}
try {
  report.ownerId = String(await api('/user/register', { userAccount: account, userPassword: password, checkPassword: password }))
  await api('/user/login', { userAccount: account, userPassword: password })
  for (let iteration = 0; iteration < iterations; iteration++) {
    const appId = String(await api('/app/add', { initPrompt: '已有文本项目：仅按要求修改两份说明文件，不创建新应用。', appName: '速度对照验收', dashboardFeatures: [] }))
    if (!/^\d+$/.test(appId)) throw Error('invalid fixture ID')
    report.apps.push(appId)
    const workspace = join(repo, 'temp', 'code_output', `project_${appId}`)
    await mkdir(workspace, { recursive: false })
    await writeFile(join(workspace, 'index.html'), '<!doctype html><h1>speed fixture</h1>')
    await writeFile(join(workspace, 'notesA.md'), 'alpha=old\n')
    await writeFile(join(workspace, 'notesB.md'), 'beta=old\n')
    const settings = await api(`/app/${appId}/agent-model`)
    await api(`/app/${appId}/agent-settings`, { policyRevision: settings.policyRevision ?? '0', mode: 'GUARDED_AUTO', sandboxMode: 'WORKSPACE_WRITE' }, 'PUT')
    const frames = []
    let done = false
    const onSocket = socket => socket.on('framereceived', ({ payload }) => {
      try {
        const frame = JSON.parse(String(payload))
        frames.push({ at: Date.now(), ...frame })
        if (frame.event === 'run.completed' || frame.event === 'done' || frame.event === 'error') done = true
      } catch { /* 非产品 JSON 帧不参与统计。 */ }
    })
    page.on('websocket', onSocket)
    await page.goto(`http://localhost:5173/app/${appId}/chat`)
    await page.locator('textarea.chat-input').waitFor({ state: 'visible' })
    const prompt = '请只处理现有项目的两个文件：先读取 notesA.md 和 notesB.md，把 notesA.md 中 alpha=old 替换为 alpha=ready，把 notesB.md 中 beta=old 替换为 beta=ready。仅修改这两个文件，不执行命令，不改其他文件。完成后分别读取这两个文件验证结果，然后简短回答。'
    await page.locator('textarea.chat-input').fill(prompt)
    const started = Date.now()
    await page.getByRole('button', { name: /^发\s*送$/ }).click()
    const manual = []
    while (!done && Date.now() - started < 300_000) {
      // 仅在专用应用点击正式审批按钮；这类等待单列，不冒充 AI 自动批准。
      const tool = page.locator('[data-testid="tool-card"] [data-testid="approve"]')
      const review = page.locator('[data-testid="review-card"] [data-testid="approve"]')
      for (const [kind, button] of [['tool', tool], ['candidate', review]]) {
        if (await button.isVisible() && await button.isEnabled()) {
          manual.push({ kind, at: Date.now() })
          await button.click()
        }
      }
      if (frames.some(x => x.event === 'import.review.requested')) {
        const latest = await api(`/app/${appId}/agent-permissions/reviews/latest`)
        if (latest?.status === 'APPROVED') done = true
      }
      await page.waitForTimeout(200)
    }
    const elapsedMs = Date.now() - started
    if (!done) {
      const stop = page.getByRole('button', { name: /停止生成/ })
      if (await stop.isVisible()) await stop.click()
    }
    const contents = [await readFile(join(workspace, 'notesA.md'), 'utf8'), await readFile(join(workspace, 'notesB.md'), 'utf8')]
    const succeeded = done && contents[0] === 'alpha=ready\n' && contents[1] === 'beta=ready\n' && !frames.some(x => x.event === 'error')
    report.runs.push({ appId, iteration, started, elapsedMs, succeeded, manual, contents,
      modelCalls: frames.filter(x => x.event === 'thinking.completed').length,
      toolCalls: frames.filter(x => x.event === 'tool.finished').length })
    await writeFile(join(output, `${phase}-${iteration}-events.json`), JSON.stringify(frames, null, 2))
    await page.screenshot({ path: join(output, `${phase}-${iteration}.png`), fullPage: true })
    page.off('websocket', onSocket)
    console.log(JSON.stringify(report.runs.at(-1)))
    await writeFile(join(output, `${phase}-real.json`), JSON.stringify(report, null, 2))
  }
} catch (error) {
  report.error = error.message
  console.error(error.message)
  await writeFile(join(output, `${phase}-failure-ui.txt`), await page.locator('body').innerText()).catch(() => {})
  process.exitCode = 1
} finally {
  for (const appId of report.apps) {
    try {
      const ids = execFileSync('wsl.exe', ['-d', 'Ubuntu', '--', 'docker', 'ps', '-aq', '--filter', `label=glyahh.session_id=app:${appId}`], { encoding: 'utf8' }).trim().split(/\s+/).filter(Boolean)
      for (const id of ids) {
        const [info] = JSON.parse(execFileSync('wsl.exe', ['-d', 'Ubuntu', '--', 'docker', 'inspect', id], { encoding: 'utf8' }))
        if (info.Config.Labels['glyahh.session_id'] !== `app:${appId}` || !info.Mounts.some(m => m.Destination === '/workspace' && m.Source.replace(/\\/g, '/').endsWith(`/temp/code_output/project_${appId}`))) {
          report.cleanup.push({ appId, error: 'container identity mismatch', containerId: id })
          process.exitCode = 1
          continue
        }
        execFileSync('wsl.exe', ['-d', 'Ubuntu', '--', 'docker', 'rm', '-f', id])
      }
      await api('/app/delete', { id: appId })
      let absent = false
      try { await access(join(repo, 'temp', 'code_output', `project_${appId}`)) } catch { absent = true }
      report.cleanup.push({ appId, containerIds: ids, workspaceAbsent: absent })
    } catch (error) { report.cleanup.push({ appId, error: error.message }) }
  }
  await api('/user/logout', {}).catch(() => {})
  await writeFile(join(output, `${phase}-real.json`), JSON.stringify(report, null, 2))
  if (report.ownerId) {
    try {
      const python = join(repo, 'agent/.venv', process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python')
      execFileSync(python, [join(repo, 'scripts/checks/cleanup-performance-account.py'), join(output, `${phase}-real.json`)], { cwd: repo })
    } catch { console.error('专用账户清理未确认，请查看报告。'); process.exitCode = 1 }
  }
  await browser.close()
}
