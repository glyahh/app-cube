import {chromium,expect} from '@playwright/test'
import {mkdir,writeFile} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
const root=path.resolve(fileURLToPath(new URL('../../',import.meta.url))),evidence=path.join(root,'temp',`import-startup-preview-${Date.now()}`)
await mkdir(evidence,{recursive:true})
const fixture=path.join(evidence,'fixture','客户管理台');await mkdir(fixture,{recursive:true});await writeFile(path.join(fixture,'README.md'),'ISOLATED_UI_FIXTURE_ONLY')
const browser=await chromium.launch({executablePath:'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',headless:true})
const result={status:'RUNNING',kind:'ISOLATED_FRONTEND_ONLY',checks:[],forbiddenRequests:[],errors:[],backend:'NOT_IMPLEMENTED',wholeFirstRequirement:'NOT_COMPLETE'}
const url='http://127.0.0.1:5175/import-preview.html#/?startup=1'
async function launch(scenario,failure){
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage()
 page.on('pageerror',error=>result.errors.push(error.message))
 await context.route('**/*',route=>{
  const parsed=new URL(route.request().url())
  if(parsed.origin!=='http://127.0.0.1:5175'||/^\/(api|static)(\/|$)/.test(parsed.pathname)){result.forbiddenRequests.push(parsed.pathname);return route.abort()}
  return route.continue()
 })
 await page.addInitScript(({scenario,failure})=>window.__IMPORT_STARTUP_SCENARIO__={scenario,failure},{scenario,failure})
 await page.goto(url);await page.locator('input[type=file]').setInputFiles(fixture)
 await page.getByRole('button',{name:'确认导入',exact:true}).click()
 await expect(page.getByRole('button',{name:'暂不安装',exact:true})).toBeVisible({timeout:30_000})
 await page.getByRole('button',{name:'暂不安装',exact:true}).click()
 await page.getByRole('button',{name:/^运\s*行$/,exact:true}).click()
 await expect(page.getByRole('button',{name:'运行所选',exact:true})).toBeVisible()
 await expect(page.getByRole('button',{name:'重新识别',exact:true})).toBeEnabled()
 await expect(page.getByText('正在识别…',{exact:true})).toHaveCount(0,{timeout:5000})
 return {context,page}
}
try{
 {
  const {context,page}=await launch('multi','service')
  await expect(page.getByRole('button',{name:'运行所选',exact:true})).toBeDisabled()
  await page.getByRole('checkbox',{name:'前端',exact:true}).check();await page.getByRole('checkbox',{name:'后端',exact:true}).check()
  await page.getByRole('button',{name:'运行所选',exact:true}).click()
  await expect(page.getByText('npm ci',{exact:true})).toBeVisible()
  await expect(page.getByText('python -m pip install -r requirements.txt',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'暂不安装',exact:true}).click()
  await expect(page.getByText('就绪',{exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'运行所选',exact:true}).click();await page.getByRole('button',{name:/^安\s*装$/,exact:true}).click()
  await expect(page.getByText('部分失败，其他服务仍在运行。',{exact:true})).toBeVisible({timeout:6000})
  await expect(page.getByText('就绪',{exact:true})).toHaveCount(1)
  await page.getByRole('button',{name:/^重\s*试$/,exact:true}).click();await expect(page.getByText('就绪',{exact:true})).toHaveCount(2,{timeout:6000})
  await page.getByRole('button',{name:/^停\s*止$/,exact:true}).first().click();await expect(page.getByText('就绪',{exact:true})).toHaveCount(1)
  await expect(page.getByText('未启动',{exact:true})).toHaveCount(1,{timeout:6000})
  for(const width of [1440,720]){
   await page.setViewportSize({width,height:1000})
   expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
   const rows=await page.locator('.service:visible').evaluateAll(elements=>elements.map(node=>({height:node.getBoundingClientRect().height,gap:getComputedStyle(node).gap})))
   expect(rows.every(row=>row.gap==='12px'&&row.height>100)).toBe(true)
   await page.screenshot({path:path.join(evidence,`services-${width}.png`),fullPage:true})
  }
  result.checks.push({name:'多选、拒绝安装、部分失败、独立重试与停止、宽窄布局',status:'PASS'})
  await context.close()
 }
 {
  const {context,page}=await launch('unidentified')
  await expect(page.getByText('backend · 未识别',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'Agent 识别',exact:true}).click();await page.getByRole('button',{name:'暂不使用',exact:true}).click()
  await expect(page.getByRole('checkbox',{name:'后端',exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'手动配置',exact:true}).click()
  await page.getByRole('button',{name:/^保\s*存$/,exact:true}).click();await expect(page.getByText('填写名称和启动命令。',{exact:true})).toBeVisible()
  await page.getByLabel('名称',{exact:true}).fill('自定义后端');await page.getByLabel('启动命令',{exact:true}).fill('python server.py')
  await page.getByRole('button',{name:/^保\s*存$/,exact:true}).click();await expect(page.getByRole('checkbox',{name:'自定义后端',exact:true})).toBeVisible()
  await expect(page.getByText('就绪',{exact:true})).toHaveCount(0)
  result.checks.push({name:'局部未识别、拒绝 Agent 后手动配置、保存不自动执行',status:'PASS'});await context.close()
 }
 {
  const {context,page}=await launch('unidentified')
  await page.getByRole('button',{name:'Agent 识别',exact:true}).click()
  await expect(page.getByText('读取 backend 的源码与说明，发送至当前模型；不读取密钥、不安装或运行。',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:/^识\s*别$/,exact:true}).click();await expect(page.getByRole('checkbox',{name:'后端',exact:true})).toBeVisible({timeout:5000})
  await page.getByRole('button',{name:'详情',exact:true}).last().click();await expect(page.getByText(/Agent 推断/)).toBeVisible()
  await expect(page.getByRole('button',{name:'运行所选',exact:true})).toBeDisabled()
  result.checks.push({name:'授权后补充未知范围，推断标记且不自动选择',status:'PASS'});await context.close()
 }
 {
  const {context,page}=await launch('incomplete')
  await expect(page.getByText('扫描不完整：文件数量超限。请缩小目录后重试。',{exact:true})).toBeVisible()
  await expect(page.getByRole('checkbox',{name:'前端',exact:true})).toBeDisabled()
  await expect(page.getByRole('button',{name:'Agent 识别',exact:true})).toHaveCount(0)
  await page.locator('.scope .ant-select-selector').click();await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option-content').filter({hasText:'frontend'}).click()
  await page.getByRole('checkbox',{name:'前端',exact:true}).check();await expect(page.getByRole('button',{name:'运行所选',exact:true})).toBeEnabled()
  await expect(page.getByRole('checkbox',{name:'后端',exact:true})).toHaveCount(0)
  result.checks.push({name:'不完整不伪装未识别、不触发 Agent，手选目录优先',status:'PASS'});await context.close()
 }
 {
  const {context,page}=await launch('multi','install')
  await page.getByRole('checkbox',{name:'前端',exact:true}).check();await page.getByRole('button',{name:'运行所选',exact:true}).click();await page.getByRole('button',{name:/^安\s*装$/,exact:true}).click()
  await expect(page.getByText('依赖安装失败：下载中断。',{exact:true})).toBeVisible({timeout:5000});await expect(page.getByText('就绪',{exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'运行所选',exact:true}).click();await page.getByRole('button',{name:/^安\s*装$/,exact:true}).click()
  await expect(page.getByText('就绪',{exact:true})).toHaveCount(1,{timeout:6000})
  result.checks.push({name:'安装失败明确显示、重新确认后只继续所选服务',status:'PASS'});await context.close()
 }
 {
  const {context,page}=await launch('multi')
  await page.getByRole('checkbox',{name:'前端',exact:true}).check()
  await page.getByRole('button',{name:'运行所选',exact:true}).click()
  await page.getByRole('button',{name:/^安\s*装$/,exact:true}).click()
  await page.getByRole('button',{name:/^取\s*消$/,exact:true}).click()
  await expect(page.getByText('已取消。',{exact:true})).toBeVisible({timeout:5000})
  await page.waitForTimeout(1200)
  await expect(page.getByText('就绪',{exact:true})).toHaveCount(0)
  await expect(page.getByText('未启动',{exact:true})).toHaveCount(2)
  result.checks.push({name:'安装中取消，迟到完成不启动服务',status:'PASS'});await context.close()
 }
 expect(result.forbiddenRequests).toEqual([]);expect(result.errors).toEqual([]);result.status='PASS'
}catch(error){result.status='FAIL';result.error=error.message;throw error}
finally{await writeFile(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({status:result.status,evidence}));await browser.close()}
