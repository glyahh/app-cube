import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { Select } from 'ant-design-vue'
import PermissionPreview from '../PermissionPreview.vue'
import AgentComposerControls from '@/features/project-import/agent-permissions/AgentComposerControls.vue'
import AgentPermissionCard from '@/features/project-import/agent-permissions/AgentPermissionCard.vue'
import AgentChangeReview from '@/features/project-import/agent-permissions/AgentChangeReview.vue'

const wrappers: ReturnType<typeof mount>[] = []
function create(props = {}) {
  const wrapper = mount(PermissionPreview, { props, attachTo: document.body })
  wrappers.push(wrapper)
  return wrapper
}
async function change(wrapper: ReturnType<typeof create>, value: string) {
  wrapper.findComponent(Select).vm.$emit('change', value)
  await flushPromises()
}
async function send(wrapper: ReturnType<typeof create>) {
  await wrapper.get('[data-testid="send"]').trigger('click')
  await flushPromises()
}
async function approve(wrapper: ReturnType<typeof create>) {
  await wrapper.get('[data-testid="approve"]').trigger('click')
  await flushPromises()
}
async function auto(wrapper: ReturnType<typeof create>) {
  await change(wrapper, 'AUTO')
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('导入会话授权前端审核版', () => {
  it('模型和思考力度可独立选择，等待批准时统一锁定', async () => {
    const wrapper = create()
    const [, model, effort] = wrapper.findAllComponents(Select)
    expect(model!.props('value')).toBe('gpt-5.2')
    expect(effort!.props('value')).toBe('medium')
    model!.vm.$emit('change', 'claude-sonnet-4-5')
    await flushPromises()
    effort!.vm.$emit('change', 'high')
    await flushPromises()
    expect(model!.props('value')).toBe('claude-sonnet-4-5')
    expect(effort!.props('value')).toBe('high')
    await send(wrapper)
    expect(model!.props('disabled')).toBe(true)
    expect(effort!.props('disabled')).toBe(true)
  })

  it('不支持思考力度的模型禁用选项并清除旧参数，切回只选合法力度', async () => {
    const wrapper = create()
    const [, model, effort] = wrapper.findAllComponents(Select)
    model!.vm.$emit('change', 'deepseek-chat')
    await flushPromises()
    expect(effort!.props('disabled')).toBe(true)
    expect(effort!.props('options')).toEqual([{ value: '', label: '不支持', description: '' }])
    expect(wrapper.findComponent(AgentComposerControls).props('settings').effort).toBeNull()
    model!.vm.$emit('change', 'gpt-5.2')
    await flushPromises()
    expect(effort!.props('disabled')).toBe(false)
    expect(effort!.props('value')).toBe('low')
    expect(effort!.props('options').map((option: { value: string }) => option.value)).toEqual([
      'low',
      'medium',
      'high',
    ])
  })

  it('预览实际使用三个共享组件，文件差异仍由预览适配器按需加载', async () => {
    const wrapper = create()
    expect(wrapper.findComponent(AgentComposerControls).exists()).toBe(true)
    await send(wrapper)
    expect(wrapper.findComponent(AgentPermissionCard).exists()).toBe(true)
    await approve(wrapper)
    await approve(wrapper)
    await approve(wrapper)
    const review = wrapper.findComponent(AgentChangeReview)
    expect(review.exists()).toBe(true)
    expect(review.props('diffs')['src/components/TaskList.vue'].status).toBe('loaded')
    expect(review.props('diffs')['README.md']).toBeUndefined()
    const readme = review.findAll('details')[1]!
    ;(readme.element as HTMLDetailsElement).open = true
    await readme.trigger('toggle')
    await flushPromises()
    expect(review.props('diffs')['README.md'].status).toBe('loaded')
  })

  it.each(['failure', 'conflict'])('模型与力度保存 %s 不抢先更新显示值', async (saveOutcome) => {
    const wrapper = create({ saveOutcome })
    const [, model, effort] = wrapper.findAllComponents(Select)
    model!.vm.$emit('change', 'deepseek-chat')
    await flushPromises()
    expect(model!.props('value')).toBe('gpt-5.2')
    expect(effort!.props('value')).toBe('medium')
    effort!.vm.$emit('change', 'high')
    await flushPromises()
    expect(effort!.props('value')).toBe('medium')
    expect(wrapper.text()).toContain('当前设置未更改')
  })

  it('手动批准逐项询问，工具批准后仍须整批确认', async () => {
    const wrapper = create()
    expect(wrapper.findComponent(Select).props('value')).toBe('MANUAL')
    expect(
      wrapper
        .findComponent(Select)
        .props('options')
        .map((option: { label: string }) => option.label),
    ).toEqual(['手动批准', '自动批准', '自由发挥'])
    await send(wrapper)
    expect(wrapper.findComponent(Select).props('disabled')).toBe(true)
    for (const object of ['TaskList.vue', 'README.md', 'npm run test:unit']) {
      expect(wrapper.get('[data-testid="tool-card"]').text()).toContain(object)
      await approve(wrapper)
    }
    expect(wrapper.find('[data-testid="review-card"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('代码已合入')
    await approve(wrapper)
    expect(wrapper.text()).toContain('业务效果尚未验收')
  })

  it('自动批准展示AI非高风险审查，高风险工具和最终候选仍询问', async () => {
    const wrapper = create()
    await change(wrapper, 'GUARDED_AUTO')
    await send(wrapper)
    expect(wrapper.text()).toContain('TaskList.vue · AI审查：非高风险 · 自动批准')
    expect(wrapper.get('[data-testid="tool-card"]').text()).toContain('README.md')
    await approve(wrapper)
    await approve(wrapper)
    expect(wrapper.find('[data-testid="review-card"]').exists()).toBe(true)
  })

  it('高风险询问模式全部低风险时自动合入', async () => {
    const wrapper = create({ highRisk: false })
    await change(wrapper, 'GUARDED_AUTO')
    await send(wrapper)
    expect(wrapper.text()).toContain('最终候选 · AI审查：非高风险 · 自动批准')
    expect(wrapper.text()).toContain('代码已合入')
  })

  it('自由发挥直接保存，不显示帮助入口或确认弹窗', async () => {
    const wrapper = create()
    await auto(wrapper)
    expect(wrapper.findComponent(Select).props('value')).toBe('AUTO')
    expect(wrapper.find('[aria-label="授权模式说明"]').exists()).toBe(false)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    await send(wrapper)
    expect(wrapper.find('[data-testid="tool-card"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('策略自动批准')
    expect(wrapper.text()).not.toContain('用户整批确认')
  })

  it.each(['failure', 'conflict'])('模式保存 %s 保留原值并提示错误', async (saveOutcome) => {
    const wrapper = create({ saveOutcome })
    for (const mode of ['GUARDED_AUTO', 'AUTO']) {
      await change(wrapper, mode)
      expect(wrapper.findComponent(Select).props('value')).toBe('MANUAL')
      expect(wrapper.text()).toContain('当前模式未更改')
    }
  })

  it('等待中不能切模式，拒绝停止整轮且不合入', async () => {
    const wrapper = create()
    await send(wrapper)
    await change(wrapper, 'AUTO')
    expect(wrapper.findComponent(Select).props('value')).toBe('MANUAL')
    const reject = wrapper.findAll('button').find((button) => button.text() === '拒绝并停止本轮')!
    await reject.trigger('click')
    expect(wrapper.text()).toContain('候选修改未合入')
    expect(wrapper.find('[data-testid="tool-card"]').exists()).toBe(false)
    expect(wrapper.findComponent(Select).props('disabled')).toBe(false)
  })

  it.each(['failure', 'expired'])('决定 %s 不推进或冒充成功', async (decisionOutcome) => {
    const wrapper = create({ decisionOutcome })
    await send(wrapper)
    await approve(wrapper)
    expect(wrapper.text()).not.toContain('代码已合入')
    expect(wrapper.text()).toContain(decisionOutcome === 'failure' ? '尚未批准' : '批准对象已失效')
  })

  it.each(['MANUAL', 'GUARDED_AUTO', 'AUTO'])('%s 下硬禁止不可批准', async (value) => {
    const wrapper = create({ forbidden: true })
    if (value === 'AUTO') await auto(wrapper)
    else await change(wrapper, value)
    await send(wrapper)
    expect(wrapper.text()).toContain('任何授权模式都不能解除此限制')
    expect(wrapper.find('[data-testid="approve"]').exists()).toBe(false)
  })

  it('审批差异按行区分增删，整批确认不宣称示例已经校验', async () => {
    const diffText = '- <p>旧内容</p>\n+ <p>新内容</p>\n  保留内容'
    const wrapper = create({ highRisk: false, diffText })
    await send(wrapper)
    expect(wrapper.get('pre').element.textContent).toBe(diffText)
    expect(wrapper.get('.diff-removed').text()).toBe('- <p>旧内容</p>')
    expect(wrapper.get('.diff-added').text()).toBe('+ <p>新内容</p>')
    expect(wrapper.find('pre p').exists()).toBe(false)
    expect(wrapper.get('details').attributes('open')).toBeDefined()
    expect(wrapper.find('.intro').exists()).toBe(false)
    await approve(wrapper)
    expect(wrapper.get('[data-testid="review-card"]').text()).toContain('尚未合入源码')
    expect(wrapper.text()).not.toContain('独立校验通过')
    expect(wrapper.get('pre').element.textContent).toBe(diffText)
  })

  it.each(['MANUAL', 'GUARDED_AUTO'])('%s 会话同类批准跨轮有效，但不代替整批确认', async (mode) => {
    const wrapper = create()
    await change(wrapper, mode)
    await send(wrapper)
    const card = wrapper.get('[data-testid="tool-card"]')
    expect(card.get('.card-heading .object').exists()).toBe(true)
    expect(card.text()).not.toContain('小范围业务源码修改')
    expect(card.text()).not.toContain('仅批准当前操作，不代表最终代码合入')
    await card.get('[data-testid="approve-similar"]').trigger('click')
    await flushPromises()
    // 普通文件批准不能覆盖高风险文件；文件批准不能覆盖命令。
    if (mode === 'MANUAL') {
      expect(wrapper.get('[data-testid="tool-card"]').text()).toContain('README.md')
      await wrapper.get('[data-testid="approve-similar"]').trigger('click')
      await flushPromises()
    }
    expect(wrapper.get('[data-testid="tool-card"]').text()).toContain('npm run test:unit')
    await wrapper.get('[data-testid="approve-similar"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="review-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve-similar"]').exists()).toBe(false)
    await approve(wrapper)

    await change(wrapper, mode === 'MANUAL' ? 'GUARDED_AUTO' : 'MANUAL')
    await wrapper.get('textarea').setValue('继续调整')
    await send(wrapper)
    // 自动模式未请求批准过的普通文件，切回手动后仍须询问。
    if (mode === 'GUARDED_AUTO') await approve(wrapper)
    expect(wrapper.find('[data-testid="tool-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="review-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('README.md · 用户会话同类批准')
    expect(wrapper.text()).not.toContain('代码已合入')
    await approve(wrapper)

    await wrapper.setProps({ forbidden: true })
    await wrapper.get('textarea').setValue('再次调整')
    await send(wrapper)
    expect(wrapper.text()).toContain('任何授权模式都不能解除此限制')
    expect(wrapper.find('[data-testid="approve-similar"]').exists()).toBe(false)

    const fresh = create()
    await change(fresh, mode)
    await send(fresh)
    expect(fresh.find('[data-testid="tool-card"]').exists()).toBe(true)
  })

  it.each(['failure', 'expired'])('同类批准 %s 不保存会话授权', async (decisionOutcome) => {
    const wrapper = create({ decisionOutcome })
    await send(wrapper)
    await wrapper.get('[data-testid="approve-similar"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('用户会话同类批准')
    if (decisionOutcome === 'failure') {
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '停止本轮')!
        .trigger('click')
    }
    await wrapper.setProps({ decisionOutcome: 'success' })
    await wrapper.get('textarea').setValue('重新请求')
    await send(wrapper)
    expect(wrapper.get('[data-testid="tool-card"]').text()).toContain('TaskList.vue')
  })

  it('仅批准本次不会记住同类请求', async () => {
    const wrapper = create({ highRisk: false })
    await send(wrapper)
    await approve(wrapper)
    await approve(wrapper)
    await wrapper.get('textarea').setValue('继续调整')
    await send(wrapper)
    expect(wrapper.get('[data-testid="tool-card"]').text()).toContain('TaskList.vue')
  })

  it('差异只渲染纯文本，合入失败不显示成功', async () => {
    const wrapper = create({
      highRisk: false,
      diffText: '<img src=x onerror="alert(1)">',
      publicationOutcome: 'failure',
    })
    await send(wrapper)
    expect(wrapper.find('pre').text()).toContain('<img')
    expect(wrapper.find('pre img').exists()).toBe(false)
    await approve(wrapper)
    await approve(wrapper)
    expect(wrapper.text()).toContain('代码合入失败')
    expect(wrapper.text()).not.toContain('代码已合入')
  })
})
