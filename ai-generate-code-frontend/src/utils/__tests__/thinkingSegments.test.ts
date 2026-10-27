import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ThinkingStage from '@/components/ThinkingStage.vue'
import {
  mergeThinkingAction,
  parseThinkingSegments,
  type ThinkingSegment,
} from '../thinkingSegments'

describe('thinking stages', () => {
  it('restores actual JSONB history and never fabricates missing history', () => {
    const rows = [
      {
        thinkingId: 'run:thinking:1',
        summary: '构思星空校徽',
        details: ['设计星轨', '确定配色'],
        status: 'completed',
      },
    ]
    expect(parseThinkingSegments(JSON.stringify(rows))).toEqual(parseThinkingSegments(rows))
    for (const value of [null, undefined, '', 'broken', '{}', [{ summary: '' }]]) {
      expect(parseThinkingSegments(value)).toEqual([])
    }
  })

  it('updates one stable stage, preserves details and handles duplicate completion', () => {
    const stages: ThinkingSegment[] = []
    const base = {
      thinkingId: 'run:thinking:1',
      summary: '构思星轨',
      details: '使用环绕构图',
      status: 'running',
    }
    mergeThinkingAction(stages, { ...base, kind: 'thinking_started' })
    mergeThinkingAction(stages, {
      ...base,
      summary: '确定星轨配色',
      details: '采用蓝紫色',
      kind: 'thinking_delta',
    })
    mergeThinkingAction(stages, { ...base, summary: '', details: '', kind: 'thinking_completed' })
    mergeThinkingAction(stages, { ...base, summary: '', details: '', kind: 'thinking_completed' })
    expect(stages).toHaveLength(1)
    expect(stages[0]).toMatchObject({
      summary: '确定星轨配色',
      details: ['使用环绕构图', '采用蓝紫色'],
      done: true,
    })
  })

  it('renders a flat timeline immediately and escapes HTML without mutating stage data', async () => {
    const stage = parseThinkingSegments([
      {
        thinkingId: 'run:1',
        summary: '构思校徽',
        details: '<img src=x onerror=alert(1)>',
        status: 'running',
      },
    ])[0]!
    const wrapper = mount(ThinkingStage, { props: { stage } })
    expect(wrapper.text()).toContain('构思校徽')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ stage: { ...stage, done: true } })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(stage.details).toEqual(['<img src=x onerror=alert(1)>'])
    wrapper.unmount()
  })
})
