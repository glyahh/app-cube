import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import request from '@/request'
import {
  installWorkbenchTransport,
  openAgentStream,
  setWorkbenchProject,
} from '../workbenchTransport'

const originalAdapter = request.defaults.adapter
const originalFetch = window.fetch
const project = {
  appId: 'preview-app-a',
  taskId: 'preview-task-a',
  name: '工程甲',
  files: [{ path: 'src/App.vue', size: 12 }],
}
let fetchSpy: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchSpy = vi.fn().mockRejectedValue(new Error('unexpected network'))
  window.fetch = fetchSpy as typeof fetch
  setWorkbenchProject(project)
  installWorkbenchTransport()
})
afterEach(() => {
  request.defaults.adapter = originalAdapter
  window.fetch = originalFetch
  setWorkbenchProject()
  vi.useRealTimers()
})

describe('隔离工作台传输，不代表真实 Agent/业务验收', () => {
  it('应用名和 ID 绑定当前导入，无初始生成提示', async () => {
    const result = await request('/app/get/vo', { params: { id: project.appId } })
    expect(result.data.data).toMatchObject({ id: project.appId, appName: '工程甲', initPrompt: '' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })
  it('不同工程 ID 和未知写操作明确失败，不使用旧应用兜底', async () => {
    await expect(request('/app/get/vo', { params: { id: 'other' } })).rejects.toThrow(
      '工程会话已失效',
    )
    await expect(request('/app/add', { method: 'POST' })).rejects.toThrow('当前无法完成此操作')
    const response = await fetch(`/api/app/${project.appId}/runtime/start`, { method: 'POST' })
    expect(response.ok).toBe(false)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
  it('只读取当前工程的未运行状态，不建立真实运行入口', async () => {
    const response = await fetch(`/api/app/${project.appId}/runtime/status`)
    expect((await response.json()).data).toMatchObject({ running: false, status: 'STOPPED' })
    await expect(fetch('https://invalid.example/api/app/a/workspace')).rejects.toThrow(
      '当前无法访问此服务',
    )
    expect(fetchSpy).not.toHaveBeenCalled()
  })
  it('取消和换工程阻止旧流回写', () => {
    vi.useFakeTimers()
    const onEvent = vi.fn()
    openAgentStream('/api', { appId: project.appId, message: '看目录' }, { onEvent }).close()
    vi.advanceTimersByTime(700)
    expect(onEvent).not.toHaveBeenCalled()
    openAgentStream('/api', { appId: project.appId, message: '看目录' }, { onEvent })
    setWorkbenchProject({ ...project, taskId: 'new-task' })
    vi.advanceTimersByTime(700)
    expect(onEvent).not.toHaveBeenCalled()
  })
  it('当前工程交互结束，不产生文件改写或成功执行宣称', () => {
    vi.useFakeTimers()
    const onEvent = vi.fn()
    openAgentStream('/api', { appId: project.appId, message: '看目录' }, { onEvent })
    vi.advanceTimersByTime(700)
    expect(onEvent).toHaveBeenCalledWith('text.delta', {
      content: '当前工程包含 1 个文件。你想先调整哪个页面或功能？',
    })
    expect(onEvent).toHaveBeenCalledWith('done', {})
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
