// 正式聊天组件回放；仅替换网络数据，不替换渲染组件。原始正文已等长去标识。
// node tests/perf/chat-performance.mjs http://localhost:5173 <报告路径>
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createServer } from 'vite'

const fixture = JSON.parse(await readFile(new URL('./fixtures/chat-history.json', import.meta.url), 'utf8'))
const baseline = process.argv[4] === '--baseline'
const baselineRevision = '85c95c8'
const source = baseline
  ? execFileSync('git', ['show', `${baselineRevision}:ai-generate-code-frontend/src/page/App/AppChatView.vue`], { encoding: 'utf8' })
  : await readFile(new URL('../../src/page/App/AppChatView.vue', import.meta.url), 'utf8')
let server
if (process.argv[4]) {
  const address = new URL(process.argv[2])
  server = await createServer({ server: { host: address.hostname, port: Number(address.port), strictPort: true },
    plugins: baseline ? [{ name: 'chat-baseline', enforce: 'pre', load(id) {
      if (id.replace(/\\/g, '/').endsWith('/src/page/App/AppChatView.vue')) return source
    } }] : [] })
  await server.listen()
}
const browser = await chromium.launch({ headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('pageerror', error => console.error(error.message))
  await page.goto(`${process.argv[2] ?? 'http://localhost:5173'}/user/login`)
  await page.waitForFunction(() => document.querySelector('#app')?.__vue_app__)
  await page.route(/^https?:\/\/[^/]+\/api\//, route => route.fulfill({ json: { code: 0, data: null } }))
  const result = await page.evaluate(async fixture => {
    const V = await import('/node_modules/.vite/deps/vue.js')
    const C = (await import('/src/page/App/AppChatView.vue')).default
    const request = (await import('/src/request.ts')).default
    request.defaults.adapter = async config => ({ data: { code: 0, data:
      config.url?.includes('/app/get/vo') ? { id: 'perf-replay', userId: 'perf-user', codeGenType: 'html' } :
        config.url?.includes('/chatHistory/app/') ? { records: [], totalRow: 0 } : null },
    status: 200, statusText: 'OK', headers: {}, config, request: { responseURL: config.url } })
    const context = document.querySelector('#app').__vue_app__._context
    const child = { ...context, provides: Object.create(context.provides) }
    const routeKey = Reflect.ownKeys(context.provides).find(key => String(key) === 'Symbol(route location)')
    child.provides[routeKey] = V.reactive({ params: { id: 'perf-replay' }, query: {} })
    const node = document.createElement('div')
    document.querySelector('#app').style.display = 'none'
    document.body.append(node)
    const vnode = V.createVNode(C)
    vnode.appContext = child
    V.render(vnode, node)
    await new Promise(resolve => setTimeout(resolve, 500))
    const vm = vnode.component.setupState
    const longTasks = []
    const observer = new PerformanceObserver(list => longTasks.push(...list.getEntries().map(x => x.duration)))
    observer.observe({ type: 'longtask', buffered: false })
    const stats = values => {
      const sorted = [...values].sort((a, b) => a - b)
      return { samples: values, p50: sorted[Math.floor(sorted.length * .5)], p95: sorted[Math.floor(sorted.length * .95)] }
    }
    const measure = async action => {
      const values = []
      for (let i = 0; i < 5; i++) { action(i); await V.nextTick() }
      for (let i = 0; i < 40; i++) {
        const start = performance.now()
        action(i + 5)
        await V.nextTick()
        values.push(performance.now() - start)
        // 每次事件独立进入事件循环，允许绘制与长任务观测。
        await new Promise(resolve => requestAnimationFrame(resolve))
      }
      return stats(values)
    }
    const results = []
    for (const count of [18, 100, 300]) {
      vm.historyMessages = Array.from({ length: count }, (_, i) => vm.historyToMessage({ ...fixture[i % fixture.length], id: i + 1 }))
      await V.nextTick()
      results.push({ kind: 'history', count, input: await measure(i => { vm.inputMessage = `输入${i}` }), nodes: node.querySelectorAll('*').length })
    }
    for (const stages of [34, 100, 300]) {
      vm.historyMessages = fixture.slice(-10).map(vm.historyToMessage)
      const ui = vm.createAssistantUiState()
      for (let i = 0; i < stages; i++) {
        ui.segments.push({ kind: 'thinking', thinkingId: `perf:${i}`, summary: `分析步骤${i}`, details: ['检查代码并验证处理结果。'.repeat(12)], done: true })
        ui.segments.push({ kind: 'tool_executed_simple_tool', agentToolName: 'grep', callId: `tool:${i}`, toolTitle: '搜索内容', filePath: 'src/example.ts', message: '检查结果\n'.repeat(15), done: true, status: 'succeeded' })
      }
      vm.sessionMessages = [{ id: 999999, role: 'assistant', streamId: 'perf', content: '', uiState: ui, streamStartedAt: Date.now() }]
      vm.activeStreamMeta = { perf: { assistantMessageId: 999999, mode: 'legacy' } }
      await V.nextTick()
      const input = await measure(i => { vm.inputMessage = `长过程输入${i}` })
      const stream = await measure(i => vm.commitThinkingActionToStream('perf', { kind: 'thinking_delta', thinkingId: `perf:${stages - 1}`, summary: `继续分析${i}`, details: `验证步骤${i}` }))
      const process = node.querySelectorAll('.execution-process__toggle')
      const toggle = await measure(() => process[process.length - 1]?.click())
      const scroll = await measure(i => { const el = node.querySelector('.chat-messages'); el.scrollTop = i % 2 ? el.scrollHeight : 0 })
      results.push({ kind: 'live', stages, input, stream, toggle, scroll, nodes: node.querySelectorAll('*').length })
      vm.activeStreamMeta = {}; vm.sessionMessages = []
      await V.nextTick()
    }
    await new Promise(resolve => setTimeout(resolve, 100))
    observer.disconnect()
    const heapBytes = performance.memory?.usedJSHeapSize
    V.render(null, node); node.remove()
    return { results, longTasks, heapBytes, userAgent: navigator.userAgent, viewport: [innerWidth, innerHeight] }
  }, fixture)
  const report = { measuredAt: new Date().toISOString(), baseline, baselineRevision, fixtureHash: createHash('sha256').update(JSON.stringify(fixture)).digest('hex'), sourceHash: createHash('sha256').update(source).digest('hex'), sha: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), mode: 'vite-dev, formal component, network replay', ...result }
  const output = process.argv[3]
  if (output) { await mkdir(dirname(output), { recursive: true }); await writeFile(output, JSON.stringify(report, null, 2)) }
  console.log(JSON.stringify(report.results.map(({ kind, count, stages, input, stream }) => ({ kind, count, stages, inputP95: input.p95, streamP95: stream?.p95 })), null, 2))
} finally { await browser.close(); await server?.close() }
