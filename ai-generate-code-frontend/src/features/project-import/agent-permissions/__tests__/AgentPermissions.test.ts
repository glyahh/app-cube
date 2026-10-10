import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { Button, Select } from 'ant-design-vue'
import AgentComposerControls from '../AgentComposerControls.vue'
import AgentPermissionCard from '../AgentPermissionCard.vue'
import AgentChangeReview from '../AgentChangeReview.vue'
import type {
  AgentChangeCandidate,
  AgentComposerSettings,
  AgentModelOption,
  AgentPermissionRequest,
} from '../types'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
function track<T extends ReturnType<typeof mount>>(wrapper: T): T {
  wrappers.push(wrapper)
  return wrapper
}
const settings: AgentComposerSettings = { mode: 'MANUAL', modelId: 'configured', effort: 'medium' }
const models: AgentModelOption[] = [
  { id: 'configured', name: '已配置模型', provider: '提供方', efforts: ['medium', 'high'] },
  { id: 'read-only-effort', name: '无力度模型', provider: '另一提供方', efforts: [] },
  { id: 'limited', name: '限定力度模型', provider: '提供方', efforts: ['low'] },
]
const request: AgentPermissionRequest = {
  id: 'permission-1',
  revision: '9007199254740993',
  actionHash: 'action-hash',
  action: '修改文件',
  object: 'src/Task.vue',
  highRisk: false,
  reason: '普通修改无需重复说明',
  detailTitle: '文件差异',
  detailText: '- <p>旧内容</p>\n+ <img src=x onerror="alert(1)">\n  保留内容',
}
const candidate: AgentChangeCandidate = {
  id: 'review-1',
  revision: '9007199254740993',
  reviewHash: 'review-hash',
  highRisk: true,
  files: [
    { path: 'src/Task.vue', change: 'MODIFIED', highRisk: false, binary: false },
    { path: 'README.md', change: 'ADDED', highRisk: true, binary: false },
    { path: 'icon.png', change: 'DELETED', highRisk: false, binary: true },
  ],
}
const reviewIdentity = {
  reviewId: candidate.id,
  revision: candidate.revision,
  reviewHash: candidate.reviewHash,
}

function controls(props = {}) {
  return track(
    mount(AgentComposerControls, { props: { settings: { ...settings }, models, ...props } }),
  )
}
function review(props = {}) {
  return track(mount(AgentChangeReview, { props: { candidate, diffs: {}, ...props } }))
}

describe('输入栏受控设置', () => {
  it.each([
    [0, 'AUTO', { ...settings, mode: 'AUTO' }],
    [1, 'read-only-effort', { ...settings, modelId: 'read-only-effort', effort: null }],
    [2, 'high', { ...settings, effort: 'high' }],
  ] as const)('选择器 %s 只提交候选，父组件保存后才更新', async (index, value, expected) => {
    const wrapper = controls()
    const selects = wrapper.findAllComponents(Select)
    selects[index]!.vm.$emit('change', value)
    expect(wrapper.emitted('change')).toEqual([[expected]])
    expect(selects.map((select) => select.props('value'))).toEqual([
      'MANUAL',
      'configured',
      'medium',
    ])
    await wrapper.setProps({ settings: expected })
    expect(wrapper.props('settings')).toEqual(expected)
  })

  it.each([{ busy: true }, { saving: true }])('忙时三个控件全部锁定 %j', (state) => {
    const wrapper = controls(state)
    const selects = wrapper.findAllComponents(Select)
    expect(selects.every((select) => select.props('disabled'))).toBe(true)
    ;['AUTO', 'limited', 'high'].forEach((value, index) =>
      selects[index]!.vm.$emit('change', value),
    )
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('保留四字无箭头胶囊、独立菜单宽度与模型搜索', () => {
    const wrapper = controls()
    const [mode, model] = wrapper.findAllComponents(Select)
    expect(mode!.props('showArrow')).toBe(false)
    expect(mode!.props('dropdownMatchSelectWidth')).toBe(false)
    expect(mode!.props('options').map((option: { label: string }) => option.label)).toEqual([
      '手动批准',
      '自动批准',
      '自由发挥',
    ])
    expect(model!.props('showSearch')).toBe(true)
    expect(model!.props('optionFilterProp')).toBe('label')
    expect(model!.props('options')).toEqual(
      models.map((model) => ({ value: model.id, label: model.name, provider: model.provider })),
    )
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('只使用模型声明的力度，拒绝未知模式、模型和力度', () => {
    const wrapper = controls()
    const selects = wrapper.findAllComponents(Select)
    ;['UNKNOWN', 'missing', 'low'].forEach((value, index) =>
      selects[index]!.vm.$emit('change', value),
    )
    expect(wrapper.emitted('change')).toBeUndefined()
    selects[1]!.vm.$emit('change', 'limited')
    expect(wrapper.emitted('change')).toEqual([
      [{ ...settings, modelId: 'limited', effort: 'low' }],
    ])
  })

  it('无模型不填入示例，不支持力度明确标记并禁用', () => {
    const wrapper = controls({ models: [] })
    const [, model, effort] = wrapper.findAllComponents(Select)
    expect(model!.props('options')).toEqual([])
    expect(model!.props('disabled')).toBe(true)
    expect(effort!.props('disabled')).toBe(true)
    expect(effort!.props('options')).toEqual([{ value: '', label: '不支持', description: '' }])
  })
})

describe('工具批准卡片', () => {
  it.each(['APPROVE_ONCE', 'APPROVE_SIMILAR', 'REJECT'] as const)(
    '决定 %s 携带当前对象身份，不改变状态',
    async (decision) => {
      const wrapper = track(mount(AgentPermissionCard, { props: { request } }))
      const selector =
        decision === 'APPROVE_ONCE'
          ? '[data-testid="approve"]'
          : decision === 'APPROVE_SIMILAR'
            ? '[data-testid="approve-similar"]'
            : 'button'
      await wrapper.get(selector).trigger('click')
      expect(wrapper.emitted('decision')).toEqual([
        [
          {
            requestId: request.id,
            revision: request.revision,
            actionHash: request.actionHash,
            decision,
          },
        ],
      ])
      expect(wrapper.find('[data-testid="tool-card"]').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('代码已合入')
    },
  )

  it('差异只显示纯文本及增删色，不重复普通操作理由', () => {
    const wrapper = track(mount(AgentPermissionCard, { props: { request } }))
    expect(wrapper.get('pre').element.textContent).toBe(request.detailText)
    expect(wrapper.find('pre img').exists()).toBe(false)
    expect(wrapper.get('.diff-added').text()).toContain('<img')
    expect(wrapper.get('.diff-removed').text()).toContain('<p>')
    expect(wrapper.text()).not.toContain(request.reason)
    expect(wrapper.get('.card-heading .object').text()).toBe(request.object)
    expect(wrapper.get('details').attributes('open')).toBeDefined()
  })

  it('高风险理由保留，忙时拒绝所有决定', () => {
    const wrapper = track(
      mount(AgentPermissionCard, {
        props: { request: { ...request, highRisk: true, reason: '高风险理由' }, busy: true },
      }),
    )
    expect(wrapper.text()).toContain('高风险理由')
    expect(
      wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    wrapper.findAllComponents(Button).forEach((button) => button.vm.$emit('click'))
    expect(wrapper.emitted('decision')).toBeUndefined()
  })
})

describe('完整候选审阅', () => {
  it('列出所有文件，首文件与展开文件按文件发出加载请求，二进制不伪造文本', async () => {
    const wrapper = review()
    expect(wrapper.findAll('summary').map((summary) => summary.text())).toEqual([
      'src/Task.vue · 修改',
      'README.md · 新增 · 高风险',
      'icon.png · 删除',
    ])
    expect(wrapper.text()).toContain('文本差异尚未加载')
    expect(wrapper.text()).toContain('二进制文件，不提供文本差异')
    expect(wrapper.emitted('loadFile')).toEqual([
      [{ ...reviewIdentity, path: 'src/Task.vue', startLine: 1 }],
    ])
    const files = wrapper.findAll('details')
    ;(files[1]!.element as HTMLDetailsElement).open = true
    await files[1]!.trigger('toggle')
    expect(wrapper.emitted('loadFile')!.at(-1)).toEqual([
      { ...reviewIdentity, path: 'README.md', startLine: 1 },
    ])
    const count = wrapper.emitted('loadFile')!.length
    ;(files[2]!.element as HTMLDetailsElement).open = true
    await files[2]!.trigger('toggle')
    expect(wrapper.emitted('loadFile')).toHaveLength(count)
    expect(wrapper.find('pre').exists()).toBe(false)
  })

  it('部分差异明确前后未加载范围，纯文本渲染并支持按范围加载', async () => {
    const wrapper = review({
      diffs: {
        'src/Task.vue': {
          status: 'loaded',
          text: request.detailText,
          startLine: 5,
          endLine: 7,
          totalLines: 12,
        },
      },
    })
    expect(wrapper.get('pre').element.textContent).toBe(request.detailText)
    expect(wrapper.find('pre img').exists()).toBe(false)
    expect(wrapper.text()).toContain('第 1–4 行尚未加载')
    expect(wrapper.text()).toContain('第 8–12 行尚未加载')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载后续差异')!
      .trigger('click')
    expect(wrapper.emitted('loadFile')).toEqual([
      [{ ...reviewIdentity, path: 'src/Task.vue', startLine: 8 }],
    ])
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '加载前段差异')!
      .trigger('click')
    expect(wrapper.emitted('loadFile')!.at(-1)).toEqual([
      { ...reviewIdentity, path: 'src/Task.vue', startLine: 1 },
    ])
  })

  it.each(['APPROVE', 'REJECT'] as const)('候选仅整批 %s，无同类或逐文件批准', async (decision) => {
    const wrapper = review()
    expect(wrapper.find('[data-testid="approve-similar"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('尚未合入源码')
    const selector = decision === 'APPROVE' ? '[data-testid="approve"]' : '.buttons button'
    await wrapper.get(selector).trigger('click')
    expect(wrapper.emitted('decision')).toEqual([[{ ...reviewIdentity, decision }]])
    expect(wrapper.text()).not.toContain('代码已合入')
  })

  it('加载失败明确提示并按原文件重试', async () => {
    const wrapper = review({
      diffs: { 'src/Task.vue': { status: 'error', message: '读取失败，差异未加载。' } },
    })
    expect(wrapper.get('[role="alert"]').text()).toContain('读取失败')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重新加载差异')!
      .trigger('click')
    expect(wrapper.emitted('loadFile')).toEqual([
      [{ ...reviewIdentity, path: 'src/Task.vue', startLine: 1 }],
    ])
  })

  it('加载中不重复请求；提交决定时锁定所有按钮', async () => {
    const wrapper = review({ diffs: { 'src/Task.vue': { status: 'loading' } } })
    expect(wrapper.text()).toContain('差异加载中')
    expect(wrapper.emitted('loadFile')).toBeUndefined()
    await wrapper.setProps({ busy: true })
    wrapper.findAllComponents(Button).forEach((button) => button.vm.$emit('click'))
    expect(wrapper.emitted('decision')).toBeUndefined()
    expect(wrapper.emitted('loadFile')).toBeUndefined()
    expect(
      wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
  })

  it('候选身份变化后使用新身份发出加载和决定', async () => {
    const wrapper = review()
    const next = { ...candidate, id: 'review-2', revision: 'next', reviewHash: 'next-hash' }
    await wrapper.setProps({ candidate: next })
    expect(wrapper.emitted('loadFile')!.at(-1)).toEqual([
      {
        reviewId: next.id,
        revision: next.revision,
        reviewHash: next.reviewHash,
        path: 'src/Task.vue',
        startLine: 1,
      },
    ])
    await wrapper.get('[data-testid="approve"]').trigger('click')
    expect(wrapper.emitted('decision')).toEqual([
      [
        {
          reviewId: next.id,
          revision: next.revision,
          reviewHash: next.reviewHash,
          decision: 'APPROVE',
        },
      ],
    ])
  })
})
