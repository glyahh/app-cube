import { chromium,expect } from '@playwright/test'
import { mkdir,writeFile,access } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root=path.resolve(fileURLToPath(new URL('../../',import.meta.url))),evidence=path.join(root,'temp',`git-import-cancel-${Date.now()}`)
await mkdir(evidence,{recursive:true})
const base='http://localhost:5173',result={status:'RUNNING',checks:[],fault:'RESPONSE_DELAY_AFTER_REAL_SERVER_CREATE',errors:[],wholeFirstRequirement:'NOT_COMPLETE'}
const browser=await chromium.launch({executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true})
const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',error=>result.errors.push(error.message))
async function api(route,data){const response=data?await context.request.post(`${base}/api${route}`,{data}):await context.request.get(`${base}/api${route}`);const value=await response.json();expect(value.code).toBe(20000);return value.data}
try {
 const account=`import_cancel_browser_${randomBytes(8).toString('hex')}`,password=randomBytes(24).toString('hex')
 const ownerId=String(await api('/user/register',{userAccount:account,userPassword:password,checkPassword:password}));await api('/user/login',{userAccount:account,userPassword:password})
 result.ownerId=ownerId;result.account=account;result.passwordRecorded=false
 await page.goto(`${base}/project/import`);await expect(page.getByRole('button',{name:'选择文件夹',exact:true})).toBeEnabled()
 await page.getByText('Git 仓库',{exact:true}).click();await page.getByLabel('仓库地址').fill('https://github.com/octocat/Hello-World')
 let resolveCreated;const created=new Promise(resolve=>resolveCreated=resolve)
 await page.route('**/api/project/import/git/tasks',async route=>{
  if(route.request().method()!=='POST'){await route.continue();return}
  const response=await route.fetch(),body=await response.json();expect(body.code).toBe(20000)
  resolveCreated(body.data.taskId)
  await new Promise(resolve=>setTimeout(resolve,1200))
  await route.fulfill({response})
 })
 await expect(page.getByRole('button',{name:'读取分支',exact:true})).toBeEnabled()
 await page.getByRole('button',{name:'读取分支',exact:true}).click();const taskId=await created
 result.taskId=taskId;result.checks.push({name:'延迟的是实际创建响应，未伪造任务',status:'PASS'})
 await page.getByRole('button',{name:'取消导入',exact:true}).click()
 await expect(page.getByText('导入已取消。',{exact:true})).toBeVisible({timeout:30_000})
 const task=await api(`/project/import/git/tasks/${taskId}`);expect(task.status).toBe('CANCELLED');expect(task.cleanup).toBe('DONE');expect(task.mainTaskCreated).toBe(false)
 await expect(access(path.join(root,'temp','project_import_private',`${taskId}.git`))).rejects.toThrow()
 result.checks.push({name:'真实 ID 到达后核对停止与私有目录清理',status:'PASS'})
 await expect(page.getByRole('button',{name:'重新导入',exact:true})).toBeDisabled()
 expect(await page.locator('[aria-label="导入分支"]').count()).toBe(0);expect(result.errors).toEqual([])
 result.checks.push({name:'迟到结果不恢复分支或导入资格',status:'PASS'})
 await page.screenshot({path:path.join(evidence,'cancelled.png'),fullPage:true})
 result.status='PASS'
}catch(error){result.status='FAIL';result.error=error.message;throw error}
finally{await writeFile(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({status:result.status,evidence}));await browser.close()}
