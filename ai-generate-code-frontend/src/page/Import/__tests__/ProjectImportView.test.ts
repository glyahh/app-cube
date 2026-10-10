import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectImportView from '../ProjectImportView.vue'
import { UserLoginStore } from '@/stores/UserLogin'
import { submitFolderImport, saveImportPreferences, loadImportPreferences } from '@/features/project-import/service'

vi.mock('@/features/project-import/service', () => ({ submitFolderImport: vi.fn(), saveImportPreferences: vi.fn(), loadImportPreferences:vi.fn() }))
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.mocked(loadImportPreferences).mockReset().mockResolvedValue({skipDependencies:false,skipBuildOutputs:false,includeSensitive:false})
  vi.mocked(submitFolderImport).mockReset().mockRejectedValue(new Error('导入服务尚未开通，请稍后重试。'))
  vi.mocked(saveImportPreferences).mockReset().mockRejectedValue(new Error('偏好保存服务尚未开通，请稍后重试。'))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })

async function openForm() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const user = UserLoginStore()
  user.setLoginUser({ id: 'user-1', userName: '开发者', userRole: 'user' })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/project/import', name: 'project-import', component: ProjectImportView },
    { path: '/app/:id/chat', name: 'app-chat', component: { template: '<div>工作台</div>' } },
    { path: '/user/login', component: { template: '<div>登录</div>' } },
  ] })
  await router.push('/project/import')
  wrapper = mount(ProjectImportView, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  return { router, user }
}

async function choose(paths = ['src/App.vue', '.env', 'node_modules/pkg/index.js']) {
  const input = wrapper!.get('input[type="file"]')
  const files = paths.map(path => {
    const file = new File(['test'], path.split('/').pop()!)
    Object.defineProperty(file, 'webkitRelativePath', { value: `客户管理台/${path}` })
    return file
  })
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  return files
}

async function click(name: string) {
  const button = wrapper!.findAll('button').find(button => button.text().replace(/\s/g, '') === name)
  expect(button, `button ${name}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}

describe('formal folder import frontend', () => {
  it('reads native handles including empty directories and binary files', async () => {
    await openForm()
    const binary = new File([new Uint8Array([0, 255])], 'binary.bin')
    const empty = { name: '中文 空目录', kind: 'directory', async *values() {} }
    const root = { name: '原生工程', kind: 'directory', async *values() {
      yield empty
      yield { name: 'binary.bin', kind: 'file', getFile: async () => binary }
    } }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: vi.fn().mockResolvedValue(root) })
    try {
      await click('选择文件夹')
      expect(wrapper!.get('[data-testid="included-count"]').text()).toBe('2')
      await click('确认导入')
      const request = vi.mocked(submitFolderImport).mock.calls[0]![0]
      expect(request.files.map(entry => entry.metadata)).toEqual([
        { path: '中文 空目录', size: 0, directory: true }, { path: 'binary.bin', size: 2, directory: false },
      ])
      expect(request.files[0]!.file.size).toBe(0)
      expect(request.files[1]!.file).toBe(binary)
    } finally { Reflect.deleteProperty(window, 'showDirectoryPicker') }
  })
  it('retains dependency files by default and excludes sensitive files from the frozen submission', async () => {
    const { router } = await openForm()
    const files = await choose()
    expect(wrapper!.get('[data-testid="included-count"]').text()).toBe('2')
    await click('确认导入')
    const request = vi.mocked(submitFolderImport).mock.calls[0]![0]
    expect(request.ownerId).toBe('user-1')
    expect(request.appName).toBe('客户管理台')
    // 清单包含排除文件；真实适配器只上传服务端确认的 included 字节。
    expect(request.files.map(entry => entry.metadata.path)).toEqual(['src/App.vue', '.env', 'node_modules/pkg/index.js'])
    expect(request.files[0]!.file).toBe(files[0])
    expect(wrapper!.text()).toContain('导入服务尚未开通')
    expect(router.currentRoute.value.path).toBe('/project/import')
    await click('重新导入')
    expect(submitFolderImport).toHaveBeenCalledTimes(2)
  })

  it('does not claim that default preferences were saved after a rejected request', async () => {
    await openForm()
    await click('保存为默认偏好')
    expect(wrapper!.text()).toContain('偏好保存服务尚未开通')
    expect(wrapper!.text()).not.toContain('偏好已保存')
  })

  it('cancellation blocks a late successful response and preserves the source for retry', async () => {
    const { router } = await openForm()
    await choose()
    let resolve!: (value: { appId: string; taskId: string }) => void
    vi.mocked(submitFolderImport).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await click('确认导入')
    await click('取消导入')
    expect(vi.mocked(submitFolderImport).mock.calls[0]![1].aborted).toBe(true)
    resolve({ appId: '42', taskId: 'task-42' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/project/import')
    expect(wrapper!.get('#import-name').element).toHaveProperty('value', '客户管理台')
  })

  it('changing accounts clears files and blocks the former account result', async () => {
    const { user, router } = await openForm()
    await choose()
    let resolve!: (value: { appId: string; taskId: string }) => void
    vi.mocked(submitFolderImport).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await click('确认导入')
    user.setLoginUser({ id: 'user-2', userName: '另一用户', userRole: 'user' })
    await flushPromises()
    resolve({ appId: '42', taskId: 'task-42' })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/project/import')
    expect(wrapper!.get('#import-name').element).toHaveProperty('value', '')
    expect(wrapper!.find('[data-testid="included-count"]').exists()).toBe(false)
  })

  it('only routes a successful eligible result without autoSend or initial prompts', async () => {
    const { router } = await openForm()
    await choose()
    vi.mocked(submitFolderImport).mockResolvedValueOnce({ appId: '42', taskId: 'task-42' })
    await click('确认导入')
    expect(router.currentRoute.value.fullPath).toBe('/app/42/chat')
  })

  it('rejects a preview application result in the formal route', async () => {
    const { router } = await openForm()
    await choose()
    vi.mocked(submitFolderImport).mockResolvedValueOnce({ appId: 'preview-app-42', taskId: 'preview-task-42' })
    await click('确认导入')
    expect(router.currentRoute.value.path).toBe('/project/import')
    expect(wrapper!.text()).toContain('导入结果无效')
  })

  it('an empty new selection removes the former manifest', async () => {
    await openForm()
    await choose()
    await choose([])
    expect(wrapper!.text()).toContain('未发现文件')
    expect(wrapper!.find('[data-testid="included-count"]').exists()).toBe(false)
  })
})
