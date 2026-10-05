import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/request', () => ({ default: { request: api } }))
import { submitFolderImport } from '../service'
import { defaultOptions } from '../manifest'
const response = (data: unknown, code = 20000) => ({ data: { code, data, message: '失败' } })
const request = () => ({ ownerId: '12', appName: '工程', options: defaultOptions(), files: [{ metadata: { path: 'a', size: 1 }, file: new File(['x'], 'a') }] })
describe('真实导入适配器', () => {
  beforeEach(() => api.mockReset())
  it('只在真实提交成功后返回应用，保留服务端任务与内容版本', async () => {
    api.mockResolvedValueOnce(response('csrf'))
      .mockResolvedValueOnce(response({ taskId: 'task', revision: '0', manifest: { included: [{ path: 'a', size: '1' }] } }))
      .mockResolvedValueOnce(response({ taskId: 'task', revision: '2', status: 'WAITING_CONFIRMATION', contentHash: 'a'.repeat(64) }))
      .mockResolvedValueOnce(response({ appId: '123', taskId: 'task' }))
    expect(await submitFolderImport(request(), new AbortController().signal)).toEqual({ appId: '123', taskId: 'task' })
    expect(api.mock.calls[2]![0].headers['Content-Type']).toBe('application/octet-stream')
    expect(api.mock.calls[3]![0].data).toEqual({ revision: '2', contentHash: 'a'.repeat(64) })
  })
  it('提交之前取消不会请求后端', async () => {
    const controller = new AbortController(); controller.abort()
    await expect(submitFolderImport(request(), controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(api).not.toHaveBeenCalled()
  })
  it('服务端错误不能成为成功应用', async () => {
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(null, 40000))
    await expect(submitFolderImport(request(), new AbortController().signal)).rejects.toThrow('失败')
    expect(api).toHaveBeenCalledTimes(2)
  })
  it('取消必须确认服务端取消与清理，停止失败不能显示已取消', async () => {
    const controller = new AbortController()
    api.mockResolvedValueOnce(response('csrf')).mockImplementationOnce(async () => {
      controller.abort()
      return response({ taskId: 'task', revision: '0', manifest: { included: [{ path: 'a', size: 1 }] } })
    }).mockResolvedValueOnce(response({ status: 'READING_CONTENT', revision: '0' }))
      .mockResolvedValueOnce(response({ status: 'STOP_FAILED', cleanup: 'UNVERIFIED' }))
    await expect(submitFolderImport(request(), controller.signal)).rejects.toThrow('停止未确认')
  })
  it('取消晚于服务端完成时，只有重读真实完成状态和结果才标记已完成',async()=>{
    const controller=new AbortController()
    api.mockResolvedValueOnce(response('csrf')).mockImplementationOnce(async()=>{
      controller.abort();return response({taskId:'task',revision:'0',manifest:{included:[{path:'a',size:1}]}})
    }).mockResolvedValueOnce(response({taskId:'task',status:'SUCCEEDED'})).mockResolvedValueOnce(response({taskId:'task',appId:'123'}))
    expect(await submitFolderImport(request(),controller.signal)).toEqual({taskId:'task',appId:'123',cancellationOutcome:'ALREADY_SUCCEEDED'})
    expect(api.mock.calls[3]![0].url).toBe('/project/import/tasks/task/result')
  })
})
