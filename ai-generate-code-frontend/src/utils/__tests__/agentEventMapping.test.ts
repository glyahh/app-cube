import { describe, expect, it } from 'vitest'

import { mapAgentEvent } from '../agentEventMapping'
import { TOOL_EXEC_HEADER_RE_WRITE_FILE, TOOL_REQUEST_ANY_RE } from '../assistantToolStreamProtocol'

describe('mapAgentEvent · 正文', () => {
  it('文本增量直接透传', () => {
    expect(mapAgentEvent('text.delta', { content: '你好' })).toEqual({ kind: 'text', text: '你好' })
  })

  it('空增量被忽略，不产生空文本', () => {
    expect(mapAgentEvent('text.delta', { content: '' }).kind).toBe('ignore')
    expect(mapAgentEvent('text.delta', {}).kind).toBe('ignore')
  })

  it('终稿不重复追加 —— 增量已经把内容吐完了', () => {
    expect(mapAgentEvent('text.message', { content: '全文' }).kind).toBe('ignore')
  })
})

describe('mapAgentEvent · 工具卡片', () => {
  it('工具开始能被前端的“选择工具”正则识别', () => {
    const action = mapAgentEvent('tool.started', { toolName: 'write_file', file_path: '/index.html' })
    expect(action.kind).toBe('text')
    const matched = (action as { text: string }).text.match(TOOL_REQUEST_ANY_RE)
    expect(matched?.[1]).toContain('写入文件')
    expect(matched?.[1]).toContain('/index.html')
  })

  it('工具结束能被“工具调用”写入文件正则识别', () => {
    const action = mapAgentEvent('tool.finished', {
      toolName: 'write_file',
      file_path: '/index.html',
      status: 'succeeded',
    })
    const text = (action as { text: string }).text
    const matched = text.match(TOOL_EXEC_HEADER_RE_WRITE_FILE)
    expect(matched?.[1]).toBe('/index.html')
  })

  it('失败的工具有可见标记', () => {
    const action = mapAgentEvent('tool.finished', { toolName: 'execute', status: 'failed' })
    expect((action as { text: string }).text).toContain('失败')
  })

  it('未知工具名退化成原样显示而不是丢掉', () => {
    const action = mapAgentEvent('tool.started', { toolName: 'some_new_tool' })
    expect((action as { text: string }).text).toContain('some_new_tool')
  })

  it('参数过长会截断，避免刷屏', () => {
    const action = mapAgentEvent('tool.started', {
      toolName: 'execute',
      command: 'x'.repeat(500),
    })
    expect((action as { text: string }).text.length).toBeLessThan(200)
  })
})

describe('mapAgentEvent · 收尾与错误', () => {
  it('business-error 抽出可读消息', () => {
    expect(mapAgentEvent('business-error', { message: '当前生成任务较多' })).toEqual({
      kind: 'error',
      text: '当前生成任务较多',
    })
  })

  it('error 事件同样走错误路径', () => {
    expect(mapAgentEvent('error', { message: '模型超时' }).kind).toBe('error')
  })

  it('错误消息缺失时有兜底文案', () => {
    const action = mapAgentEvent('business-error', {})
    expect((action as { text: string }).text).toBeTruthy()
  })

  it('done 触发收尾', () => {
    expect(mapAgentEvent('done', {}).kind).toBe('done')
  })

  it('run.completed 不在映射里收尾 —— 交给 done，避免重复刷新', () => {
    expect(mapAgentEvent('run.completed', { status: 'succeeded' }).kind).toBe('ignore')
  })
})

describe('mapAgentEvent · 未知事件', () => {
  it('心跳与状态等事件被忽略而不是报错', () => {
    for (const name of ['heartbeat', 'status', 'todo.delta', 'artifact.changed', 'run.started']) {
      expect(mapAgentEvent(name, {}).kind).toBe('ignore')
    }
  })

  it('完全未知的事件名也忽略 —— 后端加新事件不该让前端崩', () => {
    expect(mapAgentEvent('brand.new.event', { x: 1 }).kind).toBe('ignore')
  })
})
