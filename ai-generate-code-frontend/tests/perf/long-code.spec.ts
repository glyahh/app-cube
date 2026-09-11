import { expect, test } from '@playwright/test'

function calculateCls(entries: Array<{ value: number; startTime: number }>): number {
  let maximum = 0
  let windowValue = 0
  let windowStart = 0
  let previous = 0
  for (const entry of entries) {
    if (windowValue === 0 || entry.startTime - previous > 1_000 || entry.startTime - windowStart > 5_000) {
      windowValue = entry.value
      windowStart = entry.startTime
    } else {
      windowValue += entry.value
    }
    previous = entry.startTime
    maximum = Math.max(maximum, windowValue)
  }
  return maximum
}

for (const viewport of [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
]) {
  for (const bytes of [100_000, 300_000]) {
    test(`${viewport.name} ${bytes / 1000}KB stream stays responsive with CLS at or below 0.10`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto(`/perf/long-code.html?bytes=${bytes}`)
    const input = page.locator('#input')
    await input.fill('typing during generation')
    await page.locator('.code-block').evaluate((element) => {
      element.scrollTop = element.scrollHeight
      element.dispatchEvent(new Event('scroll'))
    })
    await expect(page.locator('body')).toHaveAttribute('data-done', 'true', { timeout: 20_000 })
    await expect(input).toHaveValue('typing during generation')
    const maxLongTask = await page.evaluate(() => Math.max(0, ...(window.__perf?.longTasks ?? [])))
    expect(maxLongTask).toBeLessThan(50)
    const layoutShifts = await page.evaluate(() => window.__perf?.layoutShifts ?? [])
    expect(calculateCls(layoutShifts), JSON.stringify(layoutShifts, null, 2)).toBeLessThanOrEqual(0.1)
    const workflowBottomGap = await page.locator('.workflow-steps-list').evaluate((element) => (
      element.scrollHeight - element.scrollTop - element.clientHeight
    ))
    expect(workflowBottomGap).toBeLessThanOrEqual(1)
    if (bytes === 300_000) {
      await expect.poll(() => page.locator('.code-line').count()).toBeLessThan(150)
    }
    })
  }
}
