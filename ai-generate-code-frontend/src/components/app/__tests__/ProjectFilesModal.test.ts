import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectFilesModal from '../ProjectFilesModal.vue'

const mocks = vi.hoisted(() => ({
  loadTree: vi.fn(),
  loadContent: vi.fn(),
  save: vi.fn(),
  buildDownloadUrl: vi.fn(() => '/api/app/1/files/download?path=logo.png'),
  isConflict: vi.fn(() => false),
}))

vi.mock('@/utils/projectFilesApi', () => ({
  loadProjectTree: mocks.loadTree,
  loadProjectFileContent: mocks.loadContent,
  saveProjectFile: mocks.save,
  buildProjectFileDownloadUrl: mocks.buildDownloadUrl,
  isProjectFileConflict: mocks.isConflict,
  PROJECT_FILE_CONFLICT_CODE: 40900,
}))

const TEXT_ENTRY = {
  name: 'index.html',
  path: 'index.html',
  type: 'file' as const,
  size: 12,
  isText: true,
  updatedAt: 1,
}

const TEXT_CONTENT = {
  path: 'index.html',
  isText: true,
  size: 12,
  content: '<h1>a</h1>',
  version: 'v1',
  updatedAt: 1,
  language: 'html',
}

// a-modal 通过 Teleport 挂到 body，所以断言走 document 查询而不是 wrapper.find
let wrapper: ReturnType<typeof mount> | undefined

async function mountModal(props: Record<string, unknown> = {}) {
  wrapper = mount(ProjectFilesModal, {
    props: { open: true, appId: '1', ...props },
    attachTo: document.body,
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

function q<T extends Element = Element>(selector: string): T | null {
  return document.querySelector<T>(selector)
}

/** 选中唯一文件并把内容改脏 */
async function makeDirty() {
  const node = q('.ant-tree-node-content-wrapper')
  if (!node) {
    const modal = q('.ant-modal')
    const body = q('.ant-modal-body')
    throw new Error(
      `树节点未渲染。modal=${!!modal} modalBody=${!!body} bodyHTML=${(body?.innerHTML ?? '').slice(0, 600)}`,
    )
  }
  node.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()

  const textarea = q<HTMLTextAreaElement>('textarea.pf-editor')
  if (!textarea) throw new Error('编辑器未渲染')
  textarea.value = '<h1>changed</h1>'
  textarea.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

function saveButton(): HTMLButtonElement | null {
  return q<HTMLButtonElement>('.pf-viewer-head button')
}

describe('ProjectFilesModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.loadTree.mockResolvedValue([TEXT_ENTRY])
    mocks.loadContent.mockResolvedValue(TEXT_CONTENT)
    mocks.save.mockResolvedValue({ ...TEXT_CONTENT, content: '<h1>changed</h1>', version: 'v2' })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('改脏后可以保存，保存成功会清掉未保存标记', async () => {
    await mountModal()
    await makeDirty()

    expect(q('.pf-dirty')).not.toBeNull()
    expect(saveButton()?.disabled).toBe(false)

    saveButton()?.click()
    await flushPromises()

    expect(mocks.save).toHaveBeenCalledWith(
      expect.objectContaining({
        path: 'index.html',
        content: '<h1>changed</h1>',
        baseVersion: 'v1',
      }),
    )
    expect(q('.pf-dirty')).toBeNull()
  })

  it('AI 正在写入该项目时，即使改脏了也不能保存', async () => {
    await mountModal({ generating: true })
    await makeDirty()

    expect(q('.pf-dirty')).not.toBeNull()
    expect(saveButton()?.disabled).toBe(true)
    expect(document.body.textContent).toContain('AI 正在写入')
  })

  it('只读访问时不能保存', async () => {
    await mountModal({ readOnly: true })
    await makeDirty()

    expect(saveButton()?.disabled).toBe(true)
    expect(document.body.textContent).toContain('只读访问')
  })

  it('版本冲突时保留草稿，不覆盖磁盘内容', async () => {
    mocks.save.mockRejectedValueOnce(Object.assign(new Error('文件已被修改'), { code: 40900 }))
    mocks.isConflict.mockReturnValueOnce(true)

    await mountModal()
    await makeDirty()
    saveButton()?.click()
    await flushPromises()

    // 草稿还在、脏标记还在 —— 用户可以重新读取后再决定怎么合
    expect(q('.pf-dirty')).not.toBeNull()
    expect(q<HTMLTextAreaElement>('textarea.pf-editor')?.value).toBe('<h1>changed</h1>')
  })

  it('二进制文件不提供编辑器，只给基本信息与下载', async () => {
    mocks.loadTree.mockResolvedValue([
      { name: 'logo.png', path: 'logo.png', type: 'file', size: 11, isText: false, updatedAt: 1 },
    ])
    mocks.loadContent.mockResolvedValue({
      path: 'logo.png',
      isText: false,
      size: 11,
      content: null,
      updatedAt: 1,
    })

    await mountModal()
    q('.ant-tree-node-content-wrapper')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(q('textarea.pf-editor')).toBeNull()
    expect(q('.pf-binary-name')?.textContent).toBe('logo.png')
    expect(q('.pf-binary-meta')?.textContent).toContain('11 B')
  })

  it('有未保存草稿时关闭窗口会先要一个明确选择', async () => {
    await mountModal()
    await makeDirty()

    wrapper!.findComponent({ name: 'AModal' }).vm.$emit('cancel')
    await flushPromises()

    expect(q('.pf-confirm-bar')).not.toBeNull()
    // antd 会给两个汉字的按钮插空格（"取 消"），断言前去掉空白
    const labels = Array.from(document.querySelectorAll('.pf-confirm-bar button')).map((b) =>
      (b.textContent ?? '').replace(/\s+/g, ''),
    )
    expect(labels.join('|')).toContain('保存并关闭')
    expect(labels.join('|')).toContain('放弃修改')
    expect(labels.join('|')).toContain('取消')
    // 没有静默关闭
    expect(wrapper!.emitted('update:open')).toBeUndefined()
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
async function edit(value: string) {
  const textarea = q<HTMLTextAreaElement>('textarea.pf-editor')!
  textarea.value = value
  textarea.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}
async function select(index: number) {
  document
    .querySelectorAll('.ant-tree-node-content-wrapper')
    [index]!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
}

describe('F06/F07 asynchronous editor identity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.loadTree.mockResolvedValue([TEXT_ENTRY, { ...TEXT_ENTRY, name: 'b.txt', path: 'b.txt' }])
    mocks.loadContent.mockResolvedValue(TEXT_CONTENT)
    mocks.save.mockResolvedValue({ ...TEXT_CONTENT, content: '<h1>changed</h1>', version: 'v2' })
  })
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('keeps edits made while saving and advances only the saved baseline/version', async () => {
    const pending = deferred<typeof TEXT_CONTENT>()
    mocks.save.mockReturnValueOnce(pending.promise)
    await mountModal()
    await makeDirty()
    saveButton()!.click()
    await flushPromises()
    await edit('newer draft')
    pending.resolve({ ...TEXT_CONTENT, content: '<h1>changed</h1>', version: 'v2' })
    await flushPromises()
    expect(q<HTMLTextAreaElement>('textarea.pf-editor')!.value).toBe('newer draft')
    expect(q('.pf-dirty')).not.toBeNull()
    mocks.save.mockResolvedValueOnce({ ...TEXT_CONTENT, content: 'newer draft', version: 'v3' })
    saveButton()!.click()
    await flushPromises()
    expect(mocks.save).toHaveBeenLastCalledWith(
      expect.objectContaining({ content: 'newer draft', baseVersion: 'v2' }),
    )
    expect(q('.pf-dirty')).toBeNull()
  })

  it('save-and-close does not discard edits typed during the save', async () => {
    const pending = deferred<typeof TEXT_CONTENT>()
    mocks.save.mockReturnValueOnce(pending.promise)
    await mountModal()
    await makeDirty()
    wrapper!.findComponent({ name: 'AModal' }).vm.$emit('cancel')
    await flushPromises()
    q<HTMLButtonElement>('.pf-confirm-bar button')!.click()
    await flushPromises()
    await edit('keep me')
    pending.resolve({ ...TEXT_CONTENT, content: '<h1>changed</h1>', version: 'v2' })
    await flushPromises()
    expect(wrapper!.emitted('update:open')).toBeUndefined()
    expect(q<HTMLTextAreaElement>('textarea.pf-editor')!.value).toBe('keep me')
    expect(q('.pf-dirty')).not.toBeNull()
  })

  it('late A response cannot replace B text/version/save target', async () => {
    const a = deferred<typeof TEXT_CONTENT>()
    const b = deferred<typeof TEXT_CONTENT>()
    mocks.loadContent.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    await mountModal()
    await select(0)
    await select(1)
    b.resolve({ ...TEXT_CONTENT, path: 'b.txt', content: 'B', version: 'vb' })
    await flushPromises()
    a.resolve({ ...TEXT_CONTENT, content: 'A' })
    await flushPromises()
    expect(q('.pf-path')!.textContent).toBe('b.txt')
    expect(q<HTMLTextAreaElement>('textarea.pf-editor')!.value).toBe('B')
    await edit('B edited')
    saveButton()!.click()
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledWith(
      expect.objectContaining({ path: 'b.txt', content: 'B edited', baseVersion: 'vb' }),
    )
  })

  it.each(['close', 'application'])('ignores a pending read after %s changes', async (kind) => {
    const pending = deferred<typeof TEXT_CONTENT>()
    mocks.loadContent.mockReturnValueOnce(pending.promise)
    await mountModal()
    await select(0)
    await wrapper!.setProps(kind === 'close' ? { open: false } : { appId: '2' })
    pending.resolve(TEXT_CONTENT)
    await flushPromises()
    expect(q('textarea.pf-editor')).toBeNull()
    expect(q('.pf-path')).toBeNull()
  })

  it('ignores saved file A response after moving to file B', async () => {
    const pending = deferred<typeof TEXT_CONTENT>()
    mocks.save.mockReturnValueOnce(pending.promise)
    await mountModal()
    await makeDirty()
    saveButton()!.click()
    await flushPromises()
    // Return draft to old baseline so the UI permits selecting another file.
    await edit(TEXT_CONTENT.content)
    mocks.loadContent.mockResolvedValueOnce({
      ...TEXT_CONTENT,
      path: 'b.txt',
      content: 'B',
      version: 'vb',
    })
    await select(1)
    pending.resolve({ ...TEXT_CONTENT, content: '<h1>changed</h1>', version: 'v2' })
    await flushPromises()
    expect(q('.pf-path')!.textContent).toBe('b.txt')
    expect(q<HTMLTextAreaElement>('textarea.pf-editor')!.value).toBe('B')
    expect(q('.pf-dirty')).toBeNull()
  })
})
