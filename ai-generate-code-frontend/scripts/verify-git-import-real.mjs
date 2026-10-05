import { chromium, expect } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { randomBytes, createHash } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(fileURLToPath(new URL('../../',import.meta.url)))
const evidence = path.join(root,'temp',`git-import-real-${Date.now()}`)
await mkdir(evidence,{recursive:true})
const base=process.env.PROJECT_FRONTEND_URL || 'http://localhost:5173'
const provider=process.env.IMPORT_GIT_PLATFORM || 'GitHub'
if(!['GitHub','Gitee'].includes(provider))throw new Error('Unsupported fixture provider')
const repositoryUrl=provider==='GitHub'?'https://github.com/octocat/Hello-World':'https://gitee.com/sdk/gitee5j'
const branch=provider==='GitHub'?'master':'main',credentialName=`${provider} 专用`
const report={status:'RUNNING',provider,checks:[],errors:[],requests:[],sources:[],applications:[],execution:[],privateSuccess:'DEFERRED_NOT_RUN',fullFirstRequirement:'NOT_COMPLETE'}
const browser=await chromium.launch({executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true})
const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage()
page.setDefaultTimeout(15_000)
page.on('pageerror',error=>report.errors.push(error.message))
page.on('request',request=>{const url=new URL(request.url());if(/\/runtime\/start|\/chat\/generate\/stream|\/install/.test(url.pathname))report.execution.push(url.pathname)})
page.on('response',response=>{const url=new URL(response.url());if(url.pathname.includes('/project/import/'))report.requests.push({path:url.pathname,status:response.status()})})
page.on('response',async response=>{if(new URL(response.url()).pathname.endsWith('/project/import/git/tasks') && response.request().method()==='POST')try{const body=await response.json();if(body.code===20000)report.sources.push(body.data.taskId)}catch{}})
async function api(route){const response=await context.request.get(`${base}/api${route}`);const value=await response.json();expect(value.code).toBe(20000);return value.data}
async function check(name,run){await run();report.checks.push({name,status:'PASS'});console.log(`PASS ${name}`)}
async function choice(label,value){await page.locator(`[aria-label="${label}"]`).click();await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:new RegExp(`^${value}$`)}).click()}
const manager=()=>page.locator('.ant-modal:visible').filter({has:page.getByText('仓库凭据',{exact:true})})
const editor=()=>page.locator('.ant-modal:visible').last()
let credentialId
try {
  const account=`import_git_browser_${randomBytes(8).toString('hex')}`,password=randomBytes(24).toString('hex')
  const register=await context.request.post(`${base}/api/user/register`,{data:{userAccount:account,userPassword:password,checkPassword:password}})
  const registered=await register.json();expect(registered.code).toBe(20000);const ownerId=String(registered.data)
  expect((await (await context.request.post(`${base}/api/user/login`,{data:{userAccount:account,userPassword:password}})).json()).code).toBe(20000)
  report.ownerId=ownerId;report.account=account;report.passwordRecorded=false
  await page.goto(`${base}/project/import`)
  await check('正式 Git 入口加载真实凭据，无预览路径',async()=>{
    await expect(page.getByRole('button',{name:'选择文件夹',exact:true})).toBeEnabled()
    await page.getByText('Git 仓库',{exact:true}).click()
    if(provider==='Gitee')await choice('仓库平台','Gitee')
    await expect(page.getByRole('button',{name:'管理凭据',exact:true})).toBeVisible()
    expect(await page.locator('body').innerText()).not.toMatch(/Mock|模拟导入|待实现/)
  })
  await check('真实添加凭据，只保留元数据且清空 Token',async()=>{
    await page.getByRole('button',{name:'管理凭据',exact:true}).click()
    await manager().getByRole('button',{name:/^添\s*加$/}).click()
    await editor().getByLabel('备注',{exact:true}).fill('浏览器专用')
    await editor().locator('#import-credential-token').fill('INVALID_BROWSER_FIXTURE_NOT_A_REAL_TOKEN')
    await editor().getByRole('button',{name:/^保\s*存$/}).click()
    await expect(manager().getByText('浏览器专用',{exact:true})).toBeVisible()
    const credentials=await api('/project/import/credentials');expect(credentials).toHaveLength(1)
    credentialId=credentials[0].id;expect(credentials[0].version).toBe('1');expect(credentials[0]).not.toHaveProperty('token')
    expect(await page.locator('#import-credential-token:visible').count()).toBe(0)
  })
  await check('备注不换身份，替换有警告且推进语义版本',async()=>{
    await manager().getByRole('button',{name:/^备\s*注$/}).click()
    await editor().getByLabel('备注',{exact:true}).fill(credentialName)
    await editor().getByRole('button',{name:/^保\s*存$/}).click()
    await expect(manager().getByText(credentialName,{exact:true})).toBeVisible()
    expect((await api('/project/import/credentials'))[0].version).toBe('1')
    await manager().getByRole('button',{name:/^替\s*换$/}).click()
    await expect(editor().getByText('使用旧 Token 的未完成导入将停止。')).toBeVisible()
    await editor().locator('#import-credential-token').fill('INVALID_BROWSER_REPLACED_NOT_A_REAL_TOKEN')
    await editor().getByRole('button',{name:/^替\s*换$/}).click()
    await expect(page.getByText('替换 Token',{exact:true})).not.toBeVisible()
    expect((await api('/project/import/credentials'))[0].version).toBe('2')
    await manager().getByRole('button',{name:'Close',exact:true}).click()
  })
  await check('无效 Token 真实认证失败，不匿名读取分支',async()=>{
    await choice('仓库凭据',credentialName)
    await page.getByLabel('仓库地址').fill(repositoryUrl)
    await expect(page.getByRole('button',{name:'读取分支',exact:true})).toBeEnabled()
    await page.getByRole('button',{name:'读取分支',exact:true}).click()
    await expect(page.getByText('Token 无效，请替换后重新导入。',{exact:true})).toBeVisible({timeout:60_000})
    expect(await page.locator('[aria-label="导入分支"]').count()).toBe(0)
    await expect(page.getByRole('button',{name:'确认导入',exact:true})).toBeDisabled()
  })
  await check('真实删除无效凭据，必须重新选择公开身份',async()=>{
    await page.getByRole('button',{name:'管理凭据',exact:true}).click()
    await manager().getByRole('button',{name:/^删\s*除$/}).click()
    await page.locator('.ant-popover:visible').getByRole('button',{name:/^删\s*除$/}).click()
    await expect(manager().getByText(credentialName,{exact:true})).not.toBeVisible()
    expect(await api('/project/import/credentials')).toEqual([])
    await manager().getByRole('button',{name:'Close',exact:true}).click()
    await expect(page.getByRole('button',{name:'读取分支',exact:true})).toBeDisabled()
    await choice('仓库凭据','公开仓库')
  })
  await check('读取真实全部 heads，单分支完整内容进入确认',async()=>{
    await page.getByRole('button',{name:'读取分支',exact:true}).click()
    await expect(page.locator('[aria-label="导入分支"]')).toBeVisible({timeout:60_000})
    await choice('导入分支',branch)
    await expect(page.getByRole('button',{name:'确认导入',exact:true})).toBeEnabled({timeout:330_000})
    expect(Number(await page.locator('[data-testid="included-count"]').innerText())).toBeGreaterThan(0)
    await page.locator('#import-name').fill('浏览器 Git 工程')
    await page.getByText('跳过构建产物',{exact:true}).click()
    await page.screenshot({path:path.join(evidence,'git-confirmation.png'),fullPage:true})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
    await page.setViewportSize({width:720,height:1000})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
    await page.screenshot({path:path.join(evidence,'git-confirmation-mobile.png'),fullPage:true})
    await page.setViewportSize({width:1440,height:1000})
  })
  const committed=page.waitForResponse(response=>/\/project\/import\/tasks\/[^/]+\/commit$/.test(new URL(response.url()).pathname),{timeout:360_000})
  await page.getByRole('button',{name:'确认导入',exact:true}).click()
  const response=await committed,value=await response.json();expect(value.code).toBe(20000)
  const {taskId,appId}=value.data;report.applications.push({taskId,appId})
  await check('名称/范围重建后真实提交，进入对应 Agent 工作台',async()=>{
    await expect(page).toHaveURL(new RegExp(`/app/${appId}/chat$`),{timeout:30_000})
    const app=await api(`/app/get/vo?id=${appId}`);expect(String(app.userId)).toBe(ownerId);expect(app.appName).toBe('浏览器 Git 工程')
    const source=await api(`/project/import/git/tasks/${taskId}`),main=await api(`/project/import/tasks/${taskId}`)
    expect(source.status).toBe('SUCCEEDED');expect(main.status).toBe('SUCCEEDED');expect(source.contentHash).toBe(main.contentHash)
    expect(source.commit).toMatch(/^[0-9a-f]{40}$/);expect(source.definition.options.skipBuildOutputs).toBe(true)
    expect(report.requests.some(row=>row.path.endsWith('/range'))).toBe(true)
    report.fixedCommit=source.commit
    report.finalFiles=[]
    for(const [index,file] of main.manifest.included.entries()){
      const final=await readFile(path.join(root,'temp','code_output',`project_${appId}`,file.path))
      const protectedBytes=await readFile(path.join(root,'temp','project_import_private',taskId,`${index}.bin`))
      expect(final.equals(protectedBytes)).toBe(true);expect(final.length).toBe(Number(file.size))
      report.finalHash=createHash('sha256').update(final).digest('hex')
      report.finalFiles.push({path:file.path,size:final.length,sha256:report.finalHash})
    }
  })
  await check('没有生成、安装、运行或泄露虚构 Token',async()=>{
    expect(report.execution).toEqual([]);expect(report.errors).toEqual([])
    expect(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).not.toContain('INVALID_BROWSER_')
    expect(JSON.stringify(report.requests)).not.toContain('INVALID_BROWSER_')
  })
  report.status='PASS'
}catch(error){
  report.status='FAIL';report.error=error.message
  await page.locator('input[type="password"]').evaluateAll(inputs=>inputs.forEach(input=>input.value=''))
  await writeFile(path.join(evidence,'failure-page.txt'),await page.locator('body').innerText())
  await page.screenshot({path:path.join(evidence,'failure.png'),fullPage:true})
  throw error
}
finally{
 if(report.status==='FAIL')for(const id of new Set(report.sources))try{
  const task=await api(`/project/import/git/tasks/${id}`)
  if(['SUCCEEDED','CANCELLED'].includes(task.status))continue
  const csrf=await api('/project/import/csrf')
  const response=await context.request.post(`${base}/api/project/import/git/tasks/${id}/cancel?revision=${task.revision}`,{headers:{Origin:base,'X-Project-Import-CSRF':csrf}})
  const stopped=await response.json();report.cleanup??=[];report.cleanup.push({taskId:id,code:stopped.code,status:stopped.data?.status,cleanup:stopped.data?.cleanup})
 }catch{report.cleanup??=[];report.cleanup.push({taskId:id,status:'UNCONFIRMED'})}
 await writeFile(path.join(evidence,'result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({status:report.status,evidence}));await browser.close()
}
