import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/perf',
  timeout: 30_000,
  use: { browserName: 'chromium', headless: true, baseURL: 'http://127.0.0.1:4174' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4174',
    url: 'http://127.0.0.1:4174/perf/long-code.html',
    reuseExistingServer: true,
  },
})
