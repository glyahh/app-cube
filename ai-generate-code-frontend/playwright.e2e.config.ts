import { defineConfig } from '@playwright/test'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(frontendDir, '..')
const backendEnv: Record<string, string> = {}
for (const [key, value] of Object.entries(process.env)) {
  if (value !== undefined) backendEnv[key] = value
}
Object.assign(backendEnv, {
  E2E_EMPTY_CONFIG: path.join(repoRoot, 'target', 'e2e-empty.properties'),
  E2E_PYTHON:
    process.env.E2E_PYTHON ||
    (process.platform === 'win32'
      ? path.join(repoRoot, 'agent', '.venv', 'Scripts', 'python.exe')
      : '/usr/bin/python3'),
  E2E_AGENT_STUB: path.join(repoRoot, 'src', 'test', 'resources', 'agent-stub.py'),
  E2E_WORKSPACE_ROOT: path.join(os.tmpdir(), 'app-cube-e2e', String(process.pid), 'code_output'),
  E2E_DEPLOY_ROOT: path.join(os.tmpdir(), 'app-cube-e2e', String(process.pid), 'code_deploy'),
})
for (const key of [
  'AGENT_MODEL_API_KEY',
  'DEEPSEEK_API_KEY',
  'DASHSCOPE_API_KEY',
  'AGENT_SUMMARIZER_API_KEY',
  'AGENT_POSTGRES_DSN',
]) {
  delete backendEnv[key]
}

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['junit', { outputFile: 'test-results/e2e.xml' }],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    browserName: 'chromium',
    headless: true,
    baseURL: 'http://127.0.0.1:4175',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command:
        'java -jar target/glyahh-ai-generate-code-0.0.1-SNAPSHOT.jar ' +
        '--spring.profiles.active=e2e --spring.config.additional-location=file:./src/test/resources/',
      cwd: repoRoot,
      env: backendEnv,
      url: 'http://127.0.0.1:8124/api/user/get/login',
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 4175',
      cwd: frontendDir,
      url: 'http://127.0.0.1:4175',
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
})
