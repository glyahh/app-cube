import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/request', () => ({ default: { request: api } }))
import { createCredential, deleteCredential, loadCredentials, renameCredential, replaceCredential, type CredentialView } from '../credentials'
const id = '11111111-1111-4111-8111-111111111111'
const credential: CredentialView = { id, provider: 'GitHub', name: '测试', version: '1', revision: '0', state: 'ACTIVE' }
const response = (data: unknown, code = 20000) => ({ data: { code, data } })
const context = () => ({ ownerId: '12', signal: new AbortController().signal, isCurrent: () => true })

describe('真实凭据适配器', () => {
  beforeEach(() => api.mockReset())
  it('旧 Gitee 凭据保留脱敏读取和删除，不允许新建或修改', async () => {
    const retired: CredentialView = {...credential,provider:'Gitee'}
    api.mockResolvedValueOnce(response([retired]))
    expect(await loadCredentials(context())).toEqual([retired])
    api.mockReset()
    await expect(createCredential(context(),'Gitee','旧','FAKE_SECRET')).rejects.toThrow('仅支持 GitHub')
    await expect(renameCredential(context(),retired,'新')).rejects.toThrow('已停用')
    await expect(replaceCredential(context(),retired,'FAKE_NEW')).rejects.toThrow('已停用')
    expect(api).not.toHaveBeenCalled()
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({credential:{...retired,state:'DELETED'},stops:[]}))
    expect((await deleteCredential(context(),retired)).credential.state).toBe('DELETED')
  })
  it('列表仅接收元数据，不保留额外秘密字段', async () => {
    api.mockResolvedValueOnce(response([{ ...credential, token: 'FAKE_SECRET' }]))
    expect(await loadCredentials(context())).toEqual([credential])
  })
  it('添加携 owner 和 CSRF，Token 只在请求体中', async () => {
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(credential))
    expect(await createCredential(context(), 'GitHub', '测试', 'FAKE_SECRET')).toEqual(credential)
    expect(api.mock.calls[1]![0]).toMatchObject({ method: 'POST', url: '/project/import/credentials',
      headers: { 'X-Project-Import-CSRF': 'csrf' }, data: { ownerId: '12', provider: 'GitHub', name: '测试', token: 'FAKE_SECRET' } })
    expect(api.mock.calls[1]![0].url).not.toContain('FAKE_SECRET')
  })
  it('换身份或取消后不能继续写入，迟到列表不能返回', async () => {
    let valid = true
    const owner = { ...context(), isCurrent: () => valid }
    api.mockImplementationOnce(async () => { valid = false; return response('csrf') })
    await expect(createCredential(owner, 'GitHub', '测试', 'FAKE_SECRET')).rejects.toMatchObject({ name: 'AbortError' })
    expect(api).toHaveBeenCalledTimes(1)
    api.mockReset(); valid = true
    api.mockImplementationOnce(async () => { valid = false; return response([credential]) })
    await expect(loadCredentials(owner)).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('改备注不提交 Token，替换保留停止失败，不冒充已取消', async () => {
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({ ...credential, name: '新备注', revision: '1' }))
    expect((await renameCredential(context(), credential, '新备注')).revision).toBe('1')
    expect(api.mock.calls[1]![0].data).toEqual({ ownerId: '12', revision: '0', name: '新备注' })
    const stops = [{ taskId: id, status: 'STOP_FAILED', cleanup: 'UNVERIFIED', error: 'RESOURCE_STOP_UNCONFIRMED' }]
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({ credential: { ...credential, version: '2', revision: '1' }, stops }))
    expect((await replaceCredential(context(), credential, 'FAKE_NEW')).stops).toEqual(stops)
    expect(api.mock.calls[3]![0].data).toEqual({ ownerId: '12', revision: '0', token: 'FAKE_NEW' })
  })
  it('删除携带条件版本且不发送秘密，冲突不覆盖', async () => {
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(null, 40900))
    await expect(deleteCredential(context(), credential)).rejects.toThrow('凭据已变化')
    expect(api.mock.calls[1]![0]).toMatchObject({ method: 'DELETE', url: `/project/import/credentials/${id}?ownerId=12&revision=0` })
    expect(api.mock.calls[1]![0].data).toBeUndefined()
  })
  it('输入越限不发请求，网络异常不传播含 Token 的 Axios config', async () => {
    await expect(createCredential(context(), 'GitHub', '测试', 'bad token')).rejects.toThrow('Token 格式无效')
    await expect(createCredential(context(), 'GitHub', 'x'.repeat(41), 'FAKE_SECRET')).rejects.toThrow('备注限 40 字')
    expect(api).not.toHaveBeenCalled()
    api.mockResolvedValueOnce(response('csrf')).mockRejectedValueOnce({ config: { data: 'FAKE_SECRET' }, message: 'FAKE_SECRET' })
    try { await createCredential(context(), 'GitHub', '测试', 'FAKE_SECRET'); expect.fail('应失败') }
    catch (error) { expect(error).toBeInstanceOf(Error); expect(String(error)).not.toContain('FAKE_SECRET'); expect(error).not.toHaveProperty('config') }
  })
})
