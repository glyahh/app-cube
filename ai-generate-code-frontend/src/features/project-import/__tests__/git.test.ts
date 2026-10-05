import { beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/request', () => ({ default: { request: api } }))
import { repository, prepareGit, getGit, selectGitBranch, rebuildGit, getGitMain, commitGit, cancelGit, getGitByRequest, getGitResult } from '../git'
import type { GitSourceView } from '../git'
const id = '11111111-1111-4111-8111-111111111111', hash = 'a'.repeat(64), commit = 'b'.repeat(40)
const scope = { skipDependencies:false,skipBuildOutputs:false,includeSensitive:false }
const context = () => ({ ownerId:'12',signal:new AbortController().signal,isCurrent:()=>true })
const source = (status = 'WAITING_CONFIRMATION'): GitSourceView => ({ taskId:id,revision:'4',status,branch:'main',commit,branches:[{name:'main',commit}],
  credentialId:null,credentialVersion:null,mainTaskCreated:true,contentHash:hash,error:null,cleanup:'NONE',definition:{appName:'repo',options:scope,source:{provider:'GitHub',cloneUrl:'https://github.com/example/repo.git'}} })
const main = () => ({taskId:id,revision:'9007199254740993',status:'WAITING_CONFIRMATION',appName:'repo',contentHash:hash,manifest:{included:[{path:'src/a',size:'1'}],excluded:[{file:{path:'.env',size:'2'},reason:'SENSITIVE'}]}})
const response = (data:unknown,code=20000) => ({data:{code,data}})
describe('真实 Git 业务适配器', () => {
  beforeEach(()=>api.mockReset())
  it('只接受双平台规范 HTTPS，不接受秘密 URL 或跨平台',()=>{
    expect(repository('GitHub','https://github.com/example/repo')).toEqual({name:'repo',cloneUrl:'https://github.com/example/repo.git'})
    for(const url of ['https://x:y@github.com/example/repo','https://gitee.com/example/repo','http://github.com/example/repo','https://github.com/example/repo?token=fake']) expect(()=>repository('GitHub',url)).toThrow()
    expect(api).not.toHaveBeenCalled()
  })
  it('只发送选择的凭据 UUID/版本，正文不包含 Token',async()=>{
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({...source('RESOLVING_SOURCE'),credentialId:id,credentialVersion:'2'}))
    await prepareGit(context(),'GitHub','https://github.com/example/repo',scope,{id,provider:'GitHub',version:'2',revision:'1',state:'ACTIVE',name:'专用'})
    expect(api.mock.calls[1]![0].data).toMatchObject({ownerId:'12',credentialId:id,credentialVersion:'2'})
    expect(JSON.stringify(api.mock.calls)).not.toContain('token')
  })
  it('跨平台凭据在网络前拒绝，公开来源不偷偷带凭据',async()=>{
    await expect(prepareGit(context(),'Gitee','https://gitee.com/example/repo',scope,{id,provider:'GitHub',version:'1',revision:'0',state:'ACTIVE',name:'专用'})).rejects.toThrow('当前平台')
    expect(api).not.toHaveBeenCalled()
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(source('RESOLVING_SOURCE')))
    await prepareGit(context(),'GitHub','https://github.com/example/repo',scope)
    expect(api.mock.calls[1]![0].data).not.toHaveProperty('credentialId')
  })
  it('迟到或跨任务结果不进入当前页面',async()=>{
    let valid=true
    api.mockImplementationOnce(async()=>{valid=false;return response(source())})
    await expect(getGit({...context(),isCurrent:()=>valid},id)).rejects.toMatchObject({name:'AbortError'})
    api.mockResolvedValueOnce(response({...source(),taskId:'22222222-2222-4222-8222-222222222222'}))
    await expect(getGit(context(),id)).rejects.toThrow('关联')
  })
  it('只提交实际广告分支，不发送客户端伪造 SHA',async()=>{
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(source('READING_CONTENT')))
    await selectGitBranch(context(),source('WAITING_BRANCH'),'main')
    expect(api.mock.calls[1]![0].data).toEqual({ownerId:'12',revision:'4',branch:'main'})
    await expect(selectGitBranch(context(),source('WAITING_BRANCH'),'other')).rejects.toThrow('一个分支')
  })
  it('范围重建必须保持固定 SHA，旧结果不能续跑',async()=>{
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({...source('READING_CONTENT'),commit:'c'.repeat(40)}))
    await expect(rebuildGit(context(),source(),'repo',{...scope,skipBuildOutputs:true})).rejects.toThrow('固定内容')
    expect(api.mock.calls[1]![0].data).toEqual({ownerId:'12',revision:'4',appName:'repo',options:{...scope,skipBuildOutputs:true}})
  })
  it('完整清单包含排除文件，版本用 string，非法字节数拒绝',async()=>{
    api.mockResolvedValueOnce(response(main()))
    expect(await getGitMain(context(),source())).toMatchObject({revision:'9007199254740993',manifest:{included:[{path:'src/a',size:1}],excluded:[{path:'.env',size:2,reason:'SENSITIVE'}]}})
    api.mockResolvedValueOnce(response({...main(),manifest:{included:[{path:'a',size:'bad'}],excluded:[]}}))
    await expect(getGitMain(context(),source())).rejects.toThrow('路径或大小')
  })
  it('再次核验源/主任务/范围/内容后提交，不把 HTTP 200 当应用结果',async()=>{
    api.mockResolvedValueOnce(response(source())).mockResolvedValueOnce(response(main())).mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({taskId:id,appId:'2100000000000000001'}))
    expect(await commitGit(context(),source(),'repo',scope)).toEqual({taskId:id,appId:'2100000000000000001'})
    expect(api.mock.calls[3]![0].data).toEqual({revision:'9007199254740993',contentHash:hash})
    api.mockResolvedValueOnce(response(source())).mockResolvedValueOnce(response({...main(),contentHash:'c'.repeat(64)}))
    await expect(commitGit(context(),source(),'repo',scope)).rejects.toThrow('内容已变化')
  })
  it('实际停止/清理未确认不返回取消成功',async()=>{
    api.mockResolvedValueOnce(response(source())).mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response({...source('STOP_FAILED'),cleanup:'UNVERIFIED',error:'PROCESS_EXIT_128'}))
    await expect(cancelGit(context(),source())).rejects.toThrow('停止或清理未确认')
  })
  it('网络错误不透传 Axios 的秘密字段，服务异常不能断言任务未创建',async()=>{
    api.mockRejectedValueOnce({config:{data:'FAKE_SECRET'},message:'FAKE_SECRET'})
    await expect(getGit(context(),id)).rejects.toThrow('来源请求失败')
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(null,50001))
    await expect(prepareGit(context(),'GitHub','https://github.com/example/repo',scope)).rejects.not.toMatchObject({name:'SourceCreationRejected'})
  })
  it('请求标识只用于只读找回，不重发创建',async()=>{
    api.mockResolvedValueOnce(response(source('CANCELLED')))
    expect((await getGitByRequest(context(),id)).status).toBe('CANCELLED')
    expect(api.mock.calls[0]![0]).toMatchObject({url:`/project/import/git/tasks/request/${id}`,method:'GET'})
    await expect(getGitByRequest(context(),'bad')).rejects.toThrow()
    expect(api).toHaveBeenCalledTimes(1)
  })
  it('创建带请求标识，已成功任务只读最终业务结果',async()=>{
    api.mockResolvedValueOnce(response('csrf')).mockResolvedValueOnce(response(source('RESOLVING_SOURCE')))
    await prepareGit(context(),'GitHub','https://github.com/example/repo',scope,undefined,id)
    expect(api.mock.calls[1]![0].data.requestId).toBe(id)
    api.mockResolvedValueOnce(response({appId:'2100000000000000001',taskId:id}))
    expect((await getGitResult(context(),source('SUCCEEDED'))).appId).toBe('2100000000000000001')
    expect(api.mock.calls[2]![0].method).toBe('GET')
    await expect(getGitResult(context(),source())).rejects.toThrow('尚未完成')
  })
})
