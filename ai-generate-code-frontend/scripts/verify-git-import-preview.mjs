import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
const evidence = path.resolve('../temp', `git-import-preview-${Date.now()}`)
await mkdir(evidence, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.IMPORT_PREVIEW_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const results = [], forbidden = [], errors = []
page.on('request', request => {
  const url = new URL(request.url())
  if (url.pathname.startsWith('/api/') || !['127.0.0.1', 'localhost'].includes(url.hostname)) forbidden.push(url.origin + url.pathname)
})
page.on('pageerror', error => errors.push(error.message))
async function check(name, action) { await action(); results.push({ name, status: 'PASS' }); console.log(`PASS ${name}`) }
async function addCredential(name) {
  await page.getByRole('button', { name: /^添\s*加$/ }).click()
  const dialog = page.getByRole('dialog', { name: '添加 Token' })
  await expect(page.getByRole('link', { name: '打开 GitHub', exact: true })).not.toBeVisible()
  await dialog.getByRole('button', { name: '获取 Token', exact: true }).click()
  await expect(page.getByRole('link', { name: '打开 GitHub', exact: true })).toHaveAttribute('href', 'https://github.com/settings/personal-access-tokens/new')
  await dialog.getByRole('button', { name: '获取 Token', exact: true }).click()
  await dialog.getByLabel('备注', { exact: true }).fill(name)
  await dialog.getByLabel('Token', { exact: true }).fill('TEST_TOKEN_ONLY_NEVER_A_REAL_SECRET')
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click()
}
let status = 'PASS'
try {
  await page.goto('http://127.0.0.1:5175/import-preview.html#/?source=git')
  await check('单分支与来源校验，选分支后可确认范围', async () => {
    await page.getByLabel('仓库地址').fill('https://wrong.example/a/b')
    await page.getByRole('button', { name: '读取分支', exact: true }).click()
    await expect(page.getByText('请输入对应平台的 HTTPS 仓库地址。')).toBeVisible()
    await page.getByLabel('仓库地址').fill('https://github.com/example/customer-console')
    await page.getByRole('button', { name: '读取分支', exact: true }).click()
    await page.getByLabel('导入分支').click()
    await page.getByTitle('feature/customer', { exact: true }).click()
    await expect(page.getByRole('button', { name: '确认导入', exact: true })).toBeEnabled()
    await expect(page.locator('[data-testid="included-count"]')).toHaveText('6')
  })
  await check('多份凭据不覆盖，Token 不回显', async () => {
    await page.getByRole('button', { name: '管理凭据', exact: true }).click()
    await addCredential('项目读取'); await addCredential('另一个项目')
    await expect(page.locator('.credential-item')).toHaveCount(2)
    expect(await page.locator('body').innerText()).not.toContain('TEST_TOKEN_ONLY')
    await page.getByRole('dialog', { name: '仓库凭据' }).getByRole('button', { name: 'Close', exact: true }).click()
    await page.getByRole('region', { name: '工程来源' }).getByLabel('仓库凭据').click()
    await page.getByTitle('项目读取', { exact: true }).click()
    await page.getByRole('button', { name: '读取分支', exact: true }).click()
    await page.getByLabel('导入分支').click(); await page.getByTitle('main', { exact: true }).click()
  })
  await check('改备注不撤销选分支，替换 Token 撤销旧确认', async () => {
    await page.getByRole('button', { name: '管理凭据', exact: true }).click()
    const row = page.locator('.credential-item').filter({ hasText: '项目读取' })
    await row.getByRole('button', { name: /^备\s*注$/ }).click()
    const note = page.getByRole('dialog', { name: '修改备注' })
    await note.getByLabel('备注').fill('读取配置')
    await note.getByRole('button', { name: /^保\s*存$/ }).click()
    const updated = page.locator('.credential-item').filter({ hasText: '读取配置' })
    await updated.getByRole('button', { name: /^替\s*换$/ }).click()
    const replacement = page.getByRole('dialog', { name: '替换 Token' })
    await expect(replacement.getByText('使用旧 Token 的未完成导入将停止。')).toBeVisible()
    await replacement.getByLabel('Token', { exact: true }).fill('ANOTHER_TEST_VALUE')
    await replacement.getByRole('button', { name: /^替\s*换$/ }).click()
    await page.getByRole('dialog', { name: '仓库凭据' }).getByRole('button', { name: 'Close', exact: true }).click()
    await expect(page.getByRole('button', { name: '确认导入', exact: true })).toBeDisabled()
  })
  await check('桌面与窄屏布局、最小文案和网络隔离', async () => {
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 }); await page.waitForTimeout(250)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
      await page.screenshot({ path: path.join(evidence, `git-${width}.png`), fullPage: true })
    }
    expect(await page.locator('body').innerText()).not.toMatch(/Mock|模拟|待实现/)
    expect(forbidden).toEqual([]); expect(errors).toEqual([])
  })
} catch (error) { status = 'FAIL'; errors.push(error.message); throw error }
finally {
  await writeFile(path.join(evidence, 'result.json'), JSON.stringify({ status, evidence, results, forbidden, errors, backendContract: 'NOT_IMPLEMENTED', realPlatformAcceptance: 'NOT_RUN' }, null, 2))
  await browser.close(); console.log(`EVIDENCE=${evidence}`)
}
