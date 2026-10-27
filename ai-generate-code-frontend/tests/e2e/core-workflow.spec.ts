import { expect, test } from '@playwright/test'

function account(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

async function register(request: import('@playwright/test').APIRequestContext, user: string) {
  const response = await request.post('/api/user/register', {
    data: {
      userAccount: user,
      userPassword: 'e2e-password-123',
      checkPassword: 'e2e-password-123',
    },
  })
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe(20000)
}

test('登录、创建应用、离线生成、编辑文件并阻止他人读取', async ({ page, request, playwright }) => {
  const owner = account('e2e_owner')
  const stranger = account('e2e_stranger')
  await register(request, owner)
  await register(request, stranger)

  await page.goto('/user/login')
  await page.locator('.panel-signin input').nth(0).fill(owner)
  await page.locator('.panel-signin input').nth(1).fill('e2e-password-123')
  await page.locator('.panel-signin .primary-btn').click()
  await page.waitForURL('**/main_index')

  await page.goto('/code/generate')
  await page.locator('#app-prompt').fill('生成一个离线验收页面')
  await page.getByRole('button', { name: /开始生成/ }).click()
  await page.waitForURL(/\/app\/[^/]+\/chat/)
  const appId = page.url().match(/\/app\/([^/]+)\/chat/)![1]

  await expect(page.getByText('离线模型替身已生成 index.html')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByRole('button', { name: '项目文件', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '项目文件', exact: true }).click()

  await page.getByText('index.html', { exact: true }).click()
  const editor = page.locator('.pf-editor textarea, textarea.pf-editor')
  await expect(editor).toHaveValue(/E2E fixture/)
  const before = await page.request.get(
    `/api/app/${appId}/files/content?path=${encodeURIComponent('index.html')}`,
  )
  const staleVersion = (await before.json()).data.version

  const edited = '<h1>Edited by E2E</h1><p>persisted</p>'
  await editor.fill(edited)
  await expect(page.locator('.pf-dirty')).toBeVisible()

  const save = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/app/${appId}/files/content`) &&
      response.request().method() === 'POST',
  )
  await page.locator('.pf-viewer').getByRole('button', { name: /保\s*存/ }).click()
  const saveBody = await (await save).json()
  expect(saveBody.code).toBe(20000)
  await expect(page.locator('.pf-dirty')).toHaveCount(0)

  const ownerRead = await page.request.get(
    `/api/app/${appId}/files/content?path=${encodeURIComponent('index.html')}`,
  )
  expect((await ownerRead.json()).data.content).toBe(edited)
  const staleSave = await page.request.post(`/api/app/${appId}/files/content`, {
    data: { path: 'index.html', content: 'stale overwrite', baseVersion: staleVersion },
  })
  expect((await staleSave.json()).code).toBe(40900)

  const strangerSession = await playwright.request.newContext({
    baseURL: 'http://127.0.0.1:4175',
  })
  const login = await strangerSession.post('/api/user/login', {
    data: { userAccount: stranger, userPassword: 'e2e-password-123' },
  })
  expect((await login.json()).code).toBe(20000)
  const forbidden = await strangerSession.get(
    `/api/app/${appId}/files/content?path=${encodeURIComponent('index.html')}`,
  )
  expect((await forbidden.json()).code).toBe(40101)
  await strangerSession.dispose()
})
