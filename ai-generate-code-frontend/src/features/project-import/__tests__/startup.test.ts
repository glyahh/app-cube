import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/request', () => ({ default: { request: api } }))
import { agentDirectories, attemptAllowed, confirmPlan, createScan, currentPreparation, executionAllowed, importedApp, loadConfiguration, loadExecution, loadExecutionLog, reconcilePreparations, selectableCandidates, stopExecution, StartupError, type InspectionRecord, type StartupPlan, type StartupScope, type StartupScan } from '../startup'

const ok = (data: unknown, code = 20000, message = '失败') => ({ data: { code, data, message } })
const context = { appId: '2106', ownerId: '77' }
const config = { appId: '2106', importTaskId: 't', configRevision: '3', contextEpoch: '1', scope: '.', services: [] }
const scan = (status: string, scanId = '11111111-1111-4111-8111-111111111111'): StartupScan => ({ scanId, requestId: scanId, scope: '.', status, revision: '1', error: null, cancelRequested: false, cleanup: 'DONE' })
const record = (status: string, scopes: StartupScope[] = []): InspectionRecord => ({
  scanId: scan('SUCCEEDED').scanId, status, diagnostic: null,
  inspection: { scanId: scan('SUCCEEDED').scanId, importTaskId: 't', contentHash: 'a'.repeat(64), scope: '.', configRevision: '3', contextEpoch: '1',
    result: { candidates: [{ id: 'web', name: 'web', directory: 'web', command: 'npm run dev', build: null, source: 'ALGORITHM', evidence: 'web/package.json' }], scopes } } })
const plan = (state: string, executable = false) => ({ planId: '22222222-2222-4222-8222-222222222222', planRevision: 'b'.repeat(64), state, executable } as StartupPlan)

describe('启动识别正式接口适配', () => {
  beforeEach(() => api.mockReset())
  it('写操作先取会话 CSRF，携带 owner 与数字版本；读取不带 CSRF', async () => {
    api.mockResolvedValueOnce(ok(config))
    await loadConfiguration(context)
    expect(api.mock.calls[0]![0]).toMatchObject({ url: '/project/import/apps/2106/startup/configuration', method: 'GET', headers: {} })
    api.mockResolvedValueOnce(ok('token')).mockResolvedValueOnce(ok(scan('QUEUED')))
    await createScan(context, config, 'web', '33333333-3333-4333-8333-333333333333')
    expect(api.mock.calls[1]![0].url).toBe('/project/import/csrf')
    expect(api.mock.calls[2]![0]).toMatchObject({ url: '/project/import/apps/2106/startup/scans', method: 'POST', headers: { 'X-Project-Import-CSRF': 'token' },
      data: { ownerId: '77', requestId: '33333333-3333-4333-8333-333333333333', configRevision: 3, contextEpoch: 1, scope: 'web' } })
  })
  it('执行查询、日志和对账都绑定计划；日志默认限长且停止携带身份', async () => {
    const p = plan('CONFIRMED', true)
    const executionId = '44444444-4444-4444-8444-444444444444'
    api.mockResolvedValueOnce(ok({ preparationId: 'p' }))
    await currentPreparation(context, p)
    expect(api.mock.calls[0]![0]).toMatchObject({ url: `/project/import/apps/2106/startup/preparations/current?planId=${p.planId}`, method: 'GET' })
    api.mockResolvedValueOnce(ok({ id: executionId }))
    await loadExecution(context, p, executionId)
    expect(api.mock.calls[1]![0].url).toContain(`/executions/${executionId}?planId=${p.planId}`)
    api.mockResolvedValueOnce(ok({ executionId, state: 'RUNNING', content: 'x', truncated: false }))
    await loadExecutionLog(context, p, executionId)
    expect(api.mock.calls[2]![0].url).toContain('maxBytes=262144')
    api.mockResolvedValueOnce(ok('token')).mockResolvedValueOnce(ok({ id: executionId }))
    await stopExecution(context, p, executionId)
    expect(api.mock.calls[4]![0]).toMatchObject({ method: 'POST', data: { ownerId: '77', planId: p.planId } })
    api.mockResolvedValueOnce(ok('token')).mockResolvedValueOnce(ok({ rows: 1, containers: 0, files: 0, unresolved: [] }))
    await reconcilePreparations(context, p)
    expect(api.mock.calls[6]![0]).toMatchObject({ method: 'POST', data: { ownerId: '77', planId: p.planId } })
  })
  it('业务码与 HTTP 拒绝都成为带码错误，不当作成功', async () => {
    api.mockResolvedValueOnce(ok('token')).mockResolvedValueOnce(ok(null, 40900, '计划版本不一致'))
    await expect(confirmPlan(context, plan('DRAFT'), false)).rejects.toMatchObject({ code: 40900, message: '计划版本不一致' })
    api.mockRejectedValueOnce({ response: { status: 403, data: { code: 40300, message: '请求来源不被允许' } } })
    await expect(loadConfiguration(context)).rejects.toBeInstanceOf(StartupError)
    await expect(confirmPlan(context, { ...plan('DRAFT'), planRevision: 'x' }, false)).rejects.toMatchObject({ code: 40000 })
  })
  it('只有导入资格读取成功才切换到启动入口', async () => {
    api.mockResolvedValueOnce(ok(config))
    expect(await importedApp(context)).toBe(true)
    api.mockResolvedValueOnce(ok(null, 40400))
    expect(await importedApp(context)).toBe(false)
    expect(await importedApp({ appId: '2106', ownerId: '' })).toBe(false)
    api.mockResolvedValueOnce(ok(null, 40900, '导入内容尚不可用'))
    await expect(importedApp(context)).rejects.toMatchObject({ code: 40900 })
    api.mockRejectedValueOnce({ response: { status: 500, data: { code: 50000, message: '请求失败' } } })
    await expect(importedApp(context)).rejects.toMatchObject({ code: 50000 })
  })
  it('失败、取消、过期或 STALE 的识别不提供候选', () => {
    expect(selectableCandidates(scan('SUCCEEDED'), record('SUCCEEDED'))).toHaveLength(1)
    for (const status of ['FAILED', 'CANCELLED', 'STALE', 'STOP_FAILED']) expect(selectableCandidates(scan(status), record('SUCCEEDED'))).toEqual([])
    expect(selectableCandidates(scan('SUCCEEDED'), record('STALE'))).toEqual([])
    expect(selectableCandidates(scan('SUCCEEDED', '44444444-4444-4444-8444-444444444444'), record('SUCCEEDED'))).toEqual([])
  })
  it('仅规则未识别且无 Agent 结果的目录可申请 Agent', () => {
    const value = record('SUCCEEDED', [
      { directory: 'a', phase: 'ALGORITHM', status: 'UNIDENTIFIED', diagnostic: 'NO_ENTRY' },
      { directory: 'b', phase: 'ALGORITHM', status: 'UNIDENTIFIED', diagnostic: 'NO_ENTRY' }, { directory: 'b', phase: 'AGENT', status: 'UNIDENTIFIED', diagnostic: 'AGENT_NO_ENTRY' },
      { directory: 'c', phase: 'FIXED', status: 'FAILED', diagnostic: 'CONFIG_INVALID' }, { directory: 'd', phase: 'DISCOVERY', status: 'INCOMPLETE', diagnostic: 'FILE_LIMIT' }])
    expect(agentDirectories(value)).toEqual(['a'])
    expect(agentDirectories({ ...value, status: 'STALE' })).toEqual([])
  })
  it('已确认计划可以发起安装或运行请求；未获执行许可时仍不可当作已执行', () => {
    for (const state of ['DRAFT', 'STALE', 'SUPERSEDED']) expect(attemptAllowed(plan(state))).toBe(false)
    expect(attemptAllowed(plan('CONFIRMED'))).toBe(true)
    for (const state of ['DRAFT', 'STALE', 'SUPERSEDED', 'CONFIRMED']) expect(executionAllowed(plan(state))).toBe(false)
    expect(executionAllowed(plan('CONFIRMED', true))).toBe(false)
    expect(executionAllowed(plan('STALE', true), true)).toBe(false)
    expect(executionAllowed(undefined, true)).toBe(false)
  })
})
