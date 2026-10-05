import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InspectionRecord, StartupPlan, StartupScan } from '@/features/project-import/startup'

const api = vi.hoisted(() => ({
  loadConfiguration: vi.fn(), currentPlan: vi.fn(), createScan: vi.fn(), loadScan: vi.fn(), cancelScan: vi.fn(), loadInspection: vi.fn(),
  decideApproval: vi.fn(), loadApproval: vi.fn(), cancelApproval: vi.fn(), createPlan: vi.fn(), loadPlan: vi.fn(), confirmPlan: vi.fn(), saveConfiguration: vi.fn(),
  startRun: vi.fn(), stopRun: vi.fn(), retryRun: vi.fn(),
}))
vi.mock('@/features/project-import/startup', async (original) => ({ ...(await original<typeof import('@/features/project-import/startup')>()), ...api }))
vi.mock('@/stores/UserLogin', () => ({ UserLoginStore: () => ({ userLogin: { id: '77' } }) }))
vi.mock('ant-design-vue', async () => {
  const { defineComponent, h } = await import('vue')
  const pass = (tag: string) => defineComponent({ inheritAttrs: false, setup: (_, { slots, attrs }) => () => h(tag, attrs, slots.default?.()) })
  return {
    Button: defineComponent({ props: { disabled: Boolean }, emits: ['click'], setup: (props, { slots, emit, attrs }) => () =>
      h('button', { ...attrs, disabled: props.disabled, onClick: () => emit('click') }, slots.default?.()) }),
    Modal: defineComponent({ props: { open: Boolean, title: String }, setup: (props, { slots }) => () => props.open ? h('section', { 'data-modal': props.title }, [slots.default?.(), slots.footer?.()]) : null }),
    Checkbox: defineComponent({ props: { checked: Boolean, disabled: Boolean }, emits: ['change'], setup: (props, { slots, emit }) => () =>
      h('label', [h('input', { type: 'checkbox', checked: props.checked, disabled: props.disabled, onChange: (event: Event) => emit('change', { target: { checked: (event.target as HTMLInputElement).checked } }) }), slots.default?.()]) }),
    Alert: defineComponent({ props: { message: String }, setup: (props, { attrs }) => () => h('div', { ...attrs, role: 'alert' }, props.message) }),
    Input: pass('input'), Select: pass('div'), Tag: pass('span'),
  }
})
import StartupRunPreview from '../StartupRunPreview.vue'

const scanId = '11111111-1111-4111-8111-111111111111'
const config = { appId: '2106', importTaskId: 't', configRevision: '3', contextEpoch: '1', scope: '.', services: [] }
const scan = (status: string): StartupScan => ({ scanId, requestId: scanId, scope: '.', status, revision: '1', error: status === 'FAILED' ? 'SCAN_FAILED' : null, cancelRequested: status === 'CANCELLED', cleanup: 'DONE' })
const record: InspectionRecord = { scanId, status: 'SUCCEEDED', diagnostic: null, inspection: { scanId, importTaskId: 't', contentHash: 'a'.repeat(64), scope: '.', configRevision: '3', contextEpoch: '1',
  result: { candidates: [{ id: 'web', name: '前端', directory: 'web', command: 'npm run dev', build: null, source: 'ALGORITHM', evidence: 'web/package.json' }],
    scopes: [{ directory: 'worker', phase: 'ALGORITHM', status: 'UNIDENTIFIED', diagnostic: 'NO_ENTRY' }] } } }
const plan = (state: string, extra: Partial<StartupPlan> = {}): StartupPlan => ({ planId: '22222222-2222-4222-8222-222222222222', scanId, planRevision: 'b'.repeat(64), state, revision: '0',
  aggregateAcknowledged: false, confirmedAt: null, executable: false, staleReason: null, diff: [], diffBase: null,
  plan: { contentHash: 'a'.repeat(64), services: [{ id: 'web', name: '前端', directory: 'web', source: 'ALGORITHM', evidence: 'x', command: 'npm run dev', expansion: ['npm run dev', 'vite'],
    lifecycle: [], build: null, dependencies: [], readiness: { method: 'PROCESS_ALIVE', detail: null }, unknowns: ['READINESS_PORT_UNDECLARED'] }],
    dependencies: [], builds: [], shared: [], aggregates: [{ id: 'web', reasons: ['MULTI_SCRIPT_RUNNER'], unselected: ['api'] }], available: [] }, ...extra })
const button = (wrapper: ReturnType<typeof mount>, id: string) => wrapper.get(`[data-testid="${id}"]`)

async function opened() {
  const wrapper = mount(StartupRunPreview, { props: { appId: '2106' }, attachTo: document.body })
  expect(button(wrapper, 'startup-install').attributes('disabled')).toBeDefined()
  await button(wrapper, 'startup-open').trigger('click'); await flushPromises()
  return wrapper
}
async function scanned() {
  api.createScan.mockResolvedValue(scan('SUCCEEDED')); api.loadInspection.mockResolvedValue(record)
  const wrapper = await opened()
  await button(wrapper, 'startup-scan').trigger('click'); await flushPromises()
  return wrapper
}

describe('正式启动识别工作台', () => {
  beforeEach(() => {
    sessionStorage.clear()
    for (const fn of Object.values(api)) fn.mockReset()
    api.loadConfiguration.mockResolvedValue(config); api.currentPlan.mockResolvedValue(null)
  })
  it('真实识别后列出候选与未识别目录；安装、运行按钮可见但不可执行', async () => {
    const wrapper = await scanned()
    expect(api.createScan).toHaveBeenCalledWith({ appId: '2106', ownerId: '77' }, config, '.', expect.any(String))
    expect(wrapper.findAll('[data-testid="startup-candidate"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('worker · 未识别')
    expect(button(wrapper, 'startup-run').attributes('disabled')).toBeDefined()
    expect(button(wrapper, 'startup-install').attributes('disabled')).toBeDefined()
  })
  it('拒绝 Agent 识别提交真实拒绝，不产生候选', async () => {
    const wrapper = await scanned()
    await wrapper.findAll('button').find(item => item.text() === 'Agent 识别')!.trigger('click')
    api.decideApproval.mockResolvedValue({ approvalId: '33333333-3333-4333-8333-333333333333', state: 'REJECTED', directories: ['worker'], approved: false, scanId })
    await button(wrapper, 'startup-agent-reject').trigger('click'); await flushPromises()
    expect(api.decideApproval).toHaveBeenCalledWith(expect.anything(), scanId, ['worker'], false, expect.any(String))
    expect(wrapper.get('[data-testid="startup-notice"]').text()).toContain('已拒绝')
    expect(wrapper.findAll('[data-testid="startup-candidate"]')).toHaveLength(1)
    expect(sessionStorage.length).toBe(0)
  })
  it('识别失败或取消不给出候选，也不能生成计划', async () => {
    for (const status of ['FAILED', 'CANCELLED']) {
      api.createScan.mockResolvedValue(scan(status))
      const wrapper = await opened()
      await button(wrapper, 'startup-scan').trigger('click'); await flushPromises()
      expect(wrapper.findAll('[data-testid="startup-candidate"]')).toHaveLength(0)
      expect(button(wrapper, 'startup-plan-create').attributes('disabled')).toBeDefined()
      expect(api.loadInspection).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  })
  it('聚合展开须核对后才能确认；确认后可以发起运行，但未执行前不算已启动', async () => {
    const wrapper = await scanned()
    await wrapper.get('[data-testid="startup-candidate"] input').setValue(true)
    api.createPlan.mockResolvedValue(plan('DRAFT'))
    await button(wrapper, 'startup-plan-create').trigger('click'); await flushPromises()
    expect(api.createPlan).toHaveBeenCalledWith(expect.anything(), scanId, ['web'])
    expect(wrapper.get('[data-testid="startup-aggregate"]').text()).toContain('将带上未选服务 api')
    expect(button(wrapper, 'startup-confirm').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="startup-aggregate"] input').setValue(true)
    api.confirmPlan.mockResolvedValue(plan('CONFIRMED', { aggregateAcknowledged: true }))
    await button(wrapper, 'startup-confirm').trigger('click'); await flushPromises()
    expect(api.confirmPlan).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ state: 'DRAFT' }), true)
    expect(wrapper.get('[data-testid="startup-plan-state"]').text()).toBe('已确认')
    expect(button(wrapper, 'startup-run').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="startup-notice"]').text()).toContain('不会启动')
    expect(wrapper.text()).not.toContain('已启动')
  })
  it('确认冲突时重读计划，展示失效原因与差异，不能确认', async () => {
    const { StartupError } = await import('@/features/project-import/startup')
    api.currentPlan.mockResolvedValue(plan('DRAFT', { plan: { ...plan('DRAFT').plan, aggregates: [] } }))
    api.loadScan.mockResolvedValue(scan('SUCCEEDED')); api.loadInspection.mockResolvedValue(record)
    const wrapper = await opened()
    api.confirmPlan.mockRejectedValue(new StartupError('计划内容已变化', 40900))
    api.loadPlan.mockResolvedValue(plan('STALE', { staleReason: 'PLAN_CHANGED', diff: [{ path: 'services[web].command', before: 'npm run dev', after: 'npm start' }] }))
    await button(wrapper, 'startup-confirm').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="startup-plan-state"]').text()).toBe('已失效')
    expect(wrapper.get('[data-testid="startup-diff"]').text()).toContain('npm run dev → npm start')
    expect(wrapper.find('[data-testid="startup-confirm"]').exists()).toBe(false)
    expect(button(wrapper, 'startup-run').attributes('disabled')).toBeDefined()
  })
  it('计划因识别过期失效时收起旧候选，必须重新识别', async () => {
    api.currentPlan.mockResolvedValue(plan('CONFIRMED', { aggregateAcknowledged: true }))
    api.loadScan.mockResolvedValue(scan('SUCCEEDED')); api.loadInspection.mockResolvedValue(record)
    const wrapper = await opened()
    expect(wrapper.findAll('[data-testid="startup-candidate"]')).toHaveLength(1)
    api.loadPlan.mockResolvedValue(plan('STALE', { staleReason: 'INSPECTION_STALE' }))
    await wrapper.findAll('button').find(item => item.text() === '刷新计划')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="startup-plan"]').text()).toContain('识别结果已过期')
    expect(wrapper.findAll('[data-testid="startup-candidate"]')).toHaveLength(0)
    expect(button(wrapper, 'startup-plan-create').attributes('disabled')).toBeDefined()
    expect(button(wrapper, 'startup-run').attributes('disabled')).toBeDefined()
  })
  it('已就绪且未声明端口时不编造地址；停止失败不提供重试', async () => {
    api.currentPlan.mockResolvedValue(plan('CONFIRMED', { aggregateAcknowledged: true }))
    api.loadScan.mockResolvedValue(scan('SUCCEEDED')); api.loadInspection.mockResolvedValue(record)
    const wrapper = await opened()
    const ready = {
      preparationId: '44444444-4444-4444-8444-444444444444', planId: plan('CONFIRMED').planId, planRevision: 'b'.repeat(64),
      kind: 'RUN', decision: 'APPROVED', state: 'READY', reason: null, runPermitted: true, servicesStarted: true, dependencies: [],
      services: [{ serviceId: 'web', phase: 'SERVE', state: 'READY', reason: null, executionId: 'e', address: null }],
    }
    api.startRun.mockResolvedValue(ready)
    await button(wrapper, 'startup-run').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="startup-services"]').text()).toContain('未声明端口，不提供访问地址')
    expect(wrapper.get('[data-testid="startup-services"]').text()).not.toContain('http')
    api.stopRun.mockResolvedValue({ ...ready, state: 'FAILED', reason: 'STOP_FAILED', servicesStarted: false })
    await button(wrapper, 'startup-stop').trigger('click'); await flushPromises()
    expect(api.stopRun).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="startup-retry"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('停止失败')
  })
  it('找回已过期的 Agent 批准只显示过期，不读取补充结果', async () => {
    sessionStorage.setItem('project-import-startup-approval:77:2106', '33333333-3333-4333-8333-333333333333')
    api.loadApproval.mockResolvedValue({ approvalId: '33333333-3333-4333-8333-333333333333', state: 'STALE', error: 'APPROVAL_EXPIRED', directories: ['worker'], approved: true, scanId })
    const wrapper = await opened()
    expect(wrapper.get('[data-testid="startup-error"]').text()).toContain('已过期')
    expect(api.loadInspection).not.toHaveBeenCalled()
    expect(button(wrapper, 'startup-run').attributes('disabled')).toBeDefined()
  })
})
