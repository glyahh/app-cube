import { describe, expect, it } from 'vitest'

import { mapAgentEvent } from '../agentEventMapping'

describe('mapAgentEvent · 正文', () => {
  it('映射 thinking 事件并保留稳定 ID', () => {
    expect(mapAgentEvent('thinking.delta', { thinkingId: 't-1', summary: '正在构思画报', details: '确定配色' })).toEqual({
      kind: 'thinking_delta', thinkingId: 't-1', summary: '正在构思画报', details: '确定配色', status: 'running',
    })
  })
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
  it('按 Python 真实 inputPreview 生成结构化开始动作', () => {
    expect(
      mapAgentEvent('tool.started', {
        callId: 'call-1',
        toolName: 'write_file',
        inputPreview: '{"file_path":"/index.html","content":"<html>"}',
      }),
    ).toEqual({
      kind: 'tool_started',
      callId: 'call-1',
      toolName: 'write_file',
      label: '写入文件',
      target: '/index.html',
    })
  })

  it('工具结束保持结构化，不再伪造旧文本协议', () => {
    expect(mapAgentEvent('tool.finished', {
      callId: 'call-1',
      toolName: 'write_file',
      status: 'succeeded',
      outputPreview: 'ok',
    })).toEqual({
      kind: 'tool_finished',
      callId: 'call-1',
      toolName: 'write_file',
      label: '写入文件',
      status: 'succeeded',
      outputPreview: 'ok',
    })
  })

  it('失败状态保留给卡片层展示', () => {
    const action = mapAgentEvent('tool.finished', { toolName: 'execute', status: 'failed' })
    expect(action).toMatchObject({ kind: 'tool_finished', label: '执行命令', status: 'failed' })
  })

  it('保留命令失败的退出码并兼容没有退出码的旧事件', () => {
    expect(mapAgentEvent('tool.finished', {
      toolName: 'execute', status: 'failed', exitCode: 127, outputPreview: 'python: command not found',
    })).toMatchObject({ kind: 'tool_finished', status: 'failed', exitCode: 127, outputPreview: 'python: command not found' })
    expect(mapAgentEvent('tool.finished', { toolName: 'execute', status: 'succeeded' })).not.toHaveProperty('exitCode')
  })

  it('未知工具名仍生成结构化动作', () => {
    const action = mapAgentEvent('tool.started', { toolName: 'some_new_tool' })
    expect(action).toMatchObject({ kind: 'tool_started', label: '正在处理' })
  })

  it('命令参数过长会截断，避免刷屏', () => {
    const action = mapAgentEvent('tool.started', {
      toolName: 'execute',
      inputPreview: JSON.stringify({ command: 'x'.repeat(500) }),
    })
    expect(action.kind).toBe('tool_started')
    if (action.kind === 'tool_started') expect(action.target.length).toBeLessThan(100)
  })

  it('截断的 JSON 仍能提取文件路径', () => {
    const action = mapAgentEvent('tool.started', {
      toolName: 'write_file',
      inputPreview: '{"file_path":"/long/index.html","content":"' + 'x'.repeat(500),
    })
    expect(action).toMatchObject({ kind: 'tool_started', target: '/long/index.html' })
  })

  it.each([
    ['ls', '读取目录结构'],
    ['glob', '查找文件'],
    ['grep', '搜索内容'],
    ['execute', '执行命令'],
  ])('%s 都进入结构化卡片路径', (toolName, label) => {
    expect(mapAgentEvent('tool.started', { callId: toolName, toolName })).toMatchObject({
      kind: 'tool_started',
      label,
    })
    expect(mapAgentEvent('tool.finished', { callId: toolName, toolName })).toMatchObject({
      kind: 'tool_finished',
      label,
    })
  })
})

describe('mapAgentEvent · 收尾与错误', () => {
  it('business-error 抽出可读消息', () => {
    expect(mapAgentEvent('business-error', { message: '当前生成任务较多' })).toEqual({
      kind: 'error',
      text: '当前生成任务较多',
      // 后端未标 retryable 时按不可重试处理：业务错误重试同一请求通常无效。
      retryable: false,
    })
  })

  it('error 事件保留后端的 retryable 语义', () => {
    expect(mapAgentEvent('error', { message: '模型超时', retryable: true })).toEqual({
      kind: 'error',
      text: '模型超时',
      retryable: true,
    })
    // 环境类失败（数据库、Docker）后端会标 false，前端要能区分。
    expect(mapAgentEvent('error', { message: '数据库不可用', retryable: false }).retryable).toBe(false)
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

  it('等待审批超时的 cancelled 映射成暂停提示，不当作错误', () => {
    expect(mapAgentEvent('run.completed', {
      status: 'cancelled', summary: '等待审批超时，本轮已暂停',
    })).toEqual({ kind: 'paused', text: '等待审批超时，本轮已暂停' })
  })
})

describe('mapAgentEvent · 未知事件', () => {
  it('模型路由降级映射为一次性 warning 动作', () => {
    expect(mapAgentEvent('model.route.fallback', {
      fromRoute: 'proxy', toRoute: 'direct', reason: 'connect_error',
    })).toEqual({ kind: 'model_route_fallback' })
  })

  it('心跳与状态等事件被忽略而不是报错', () => {
    for (const name of ['heartbeat', 'status', 'todo.delta', 'artifact.changed', 'run.started']) {
      expect(mapAgentEvent(name, {}).kind).toBe('ignore')
    }
  })

  it('完全未知的事件名也忽略 —— 后端加新事件不该让前端崩', () => {
    expect(mapAgentEvent('brand.new.event', { x: 1 }).kind).toBe('ignore')
  })
})


it('preserves the actual tool label instead of the generic server status', () => {
  expect(mapAgentEvent('tool.started', { toolName: 'read_file', label: '正在检查文件' }))
    .toMatchObject({ kind: 'tool_started', label: '读取文件' })
  expect(mapAgentEvent('tool.finished', { toolName: 'execute', label: '正在检查结果' }))
    .toMatchObject({ kind: 'tool_finished', label: '执行命令' })
})

it('does not repeat a cancellation summary already delivered as complete text', () => {
  const actions = [
    mapAgentEvent('text.delta', { content: '本轮已停止。' }),
    mapAgentEvent('text.message', { content: '本轮已停止。' }),
    mapAgentEvent('run.completed', { status: 'cancelled', summary: '本轮已停止。', summaryDelivered: true }),
    mapAgentEvent('done', {}),
  ]
  expect(actions).toEqual([
    { kind: 'text', text: '本轮已停止。' },
    { kind: 'ignore' },
    { kind: 'ignore' },
    { kind: 'done' },
  ])
})

it('a cancellation without a delivered answer still shows its actual reason', () => {
  expect(mapAgentEvent('run.completed', { status: 'cancelled', summary: '用户拒绝操作。' }))
    .toEqual({ kind: 'paused', text: '用户拒绝操作。' })
  expect(mapAgentEvent('run.completed', { status: 'cancelled' }))
    .toEqual({ kind: 'paused', text: '本轮已停止' })
})
