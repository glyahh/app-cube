import {chromium,expect} from '@playwright/test'
import {mkdir,writeFile,access} from 'node:fs/promises'
import {randomBytes} from 'node:crypto'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
const root=path.resolve(fileURLToPath(new URL('../../',import.meta.url)))
const evidence=path.join(root,'temp',`git-import-recovery-${Date.now()}`)
await mkdir(evidence,{recursive:true})
const result={status:'RUNNING',checks:[],errors:[],fault:'RESPONSE_ABORT_AFTER_REAL_SERVER_CREATE',wholeFirstRequirement:'NOT_COMPLETE'}
const browser=await chromium.launch({executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true})
const context=await browser.newContext(), page=await context.newPage(), base='http://localhost:5173'
page.on('pageerror',error=>result.errors.push(error.message))
async function api(route,data){const response=data?await context.request.post(`${base}/api${route}`,{data}):await context.request.get(`${base}/api${route}`);const value=await response.json();expect(value.code).toBe(20000);return value.data}
let createdId,requests=0
try {
 const account=`import_recovery_${randomBytes(8).toString('hex')}`,password=randomBytes(24).toString('hex')
 const ownerId=String(await api('/user/register',{userAccount:account,userPassword:password,checkPassword:password}))
 await api('/user/login',{userAccount:account,userPassword:password})
 result.ownerId=ownerId;result.account=account;result.passwordRecorded=false
 await page.goto(`${base}/project/import`)
 await expect(page.getByRole('button',{name:'选择文件夹',exact:true})).toBeEnabled()
 await page.getByText('Git 仓库',{exact:true}).click()
 await page.getByLabel('仓库地址').fill('https://github.com/octocat/Hello-World')
 await page.route('**/api/project/import/git/tasks',async route=>{
  if(route.request().method()!=='POST'){await route.continue();return}
  requests++
  const response=await route.fetch(),body=await response.json();expect(body.code).toBe(20000)
  createdId=body.data.taskId;result.taskId=createdId
  result.requestId=route.request().postDataJSON().requestId
  await route.abort('failed')
 })
 await expect(page.getByRole('button',{name:'读取分支',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'读取分支',exact:true}).click()
 await expect(page.locator('[aria-label="导入分支"]')).toBeVisible({timeout:150_000})
 expect(requests).toBe(1)
 const saved=await page.evaluate(id=>sessionStorage.getItem(`app-cube:import-source-request:${id}`),ownerId)
 expect(saved).toBe(result.requestId)
 const task=await api(`/project/import/git/tasks/request/${saved}`)
 expect(task.taskId).toBe(createdId);expect(task.status).toBe('WAITING_BRANCH')
 result.checks.push({name:'响应丢失后只读找回实际来源，无重复 POST',status:'PASS'})
 await page.reload()
 await expect(page.getByLabel('仓库地址')).toHaveValue('https://github.com/octocat/Hello-World.git',{timeout:30_000})
 await expect(page.locator('[aria-label="导入分支"]')).toBeVisible()
 expect(requests).toBe(1)
 expect((await api(`/project/import/git/tasks/request/${saved}`)).taskId).toBe(createdId)
 result.checks.push({name:'刷新恢复原仓库与分支，不自动选择或换身份',status:'PASS'})
 const branch=page.locator('[aria-label="导入分支"]');await branch.click()
 await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option-content').filter({hasText:new RegExp(`^${task.branches[0].name}$`)}).click()
 await expect(page.getByRole('button',{name:'确认导入',exact:true})).toBeEnabled({timeout:180_000})
 const ready=await api(`/project/import/git/tasks/request/${saved}`)
 expect(ready.status).toBe('WAITING_CONFIRMATION');expect(ready.mainTaskCreated).toBe(true)
 await page.reload()
 await expect(page.getByRole('button',{name:'确认导入',exact:true})).toBeEnabled({timeout:30_000})
 expect((await api(`/project/import/git/tasks/request/${saved}`)).contentHash).toBe(ready.contentHash)
 expect(requests).toBe(1)
 result.checks.push({name:'刷新恢复实际封存清单与固定 SHA，不重读仓库',status:'PASS'})
 await page.getByRole('button',{name:/清\s*空/,exact:true}).click()
 const stopped=await api(`/project/import/git/tasks/${createdId}`)
 expect(stopped.status).toBe('CANCELLED');expect(stopped.cleanup).toBe('DONE')
 expect(await page.evaluate(id=>sessionStorage.getItem(`app-cube:import-source-request:${id}`),ownerId)).toBeNull()
 await expect(access(path.join(root,'temp','project_import_private',`${createdId}.git`))).rejects.toThrow()
 await expect(access(path.join(root,'temp','project_import_private',createdId))).rejects.toThrow()
 expect(result.errors).toEqual([])
 result.checks.push({name:'恢复后取消核实停止、两类目录删除和记录释放',status:'PASS'})
 await page.screenshot({path:path.join(evidence,'recovered-cancelled.png'),fullPage:true})
 result.status='PASS'
}catch(error){result.status='FAIL';result.error=error.message;throw error}
finally{
 if(result.status==='FAIL'&&createdId){
  try {
   const task=await api(`/project/import/git/tasks/${createdId}`),csrf=await api('/project/import/csrf')
   const response=await context.request.post(`${base}/api/project/import/git/tasks/${createdId}/cancel?revision=${task.revision}`,{headers:{'X-Project-Import-CSRF':csrf}})
   const body=await response.json();result.failureCleanup={code:body.code,status:body.data?.status,cleanup:body.data?.cleanup}
  }catch(error){result.failureCleanup={error:error.message}}
 }
 await writeFile(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({status:result.status,evidence}));await browser.close()
}
