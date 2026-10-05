import { describe, expect, it } from 'vitest'
import {
  createManifest, defaultOptions, formatBytes, isSensitivePath, previewFileLimit,
  sampleFiles, validateFiles,
} from '../manifest'

describe('文件夹导入预览元数据（非真实上传验收）', () => {
  it('默认保留依赖、产物、示例和二进制，排除本地敏感配置', () => {
    const manifest = createManifest(sampleFiles, defaultOptions())
    expect(manifest.included).toHaveLength(10)
    expect(manifest.excluded.map((file) => file.path)).toEqual(['.env', 'backend/application-local.yml'])
    expect(manifest.totalBytes).toBe(15511)
    expect(manifest.included.map((file) => file.path)).toContain('node_modules/vue/package.json')
    expect(manifest.included.map((file) => file.path)).toContain('.venv/pyvenv.cfg')
    expect(manifest.included.map((file) => file.path)).toContain('dist/index.html')
    expect(manifest.included.map((file) => file.path)).toContain('target/classes/App.class')
  })

  it('仅按明确选项排除依赖或产物，不修改原清单', () => {
    const options = { ...defaultOptions(), skipDependencies: true, skipBuildOutputs: true }
    const manifest = createManifest(sampleFiles, options)
    expect(manifest.included).toHaveLength(6)
    expect(manifest.totalBytes).toBe(11697)
    expect(manifest.excluded).toHaveLength(6)
    expect(sampleFiles).toHaveLength(12)
    expect(createManifest(sampleFiles, defaultOptions()).included).toHaveLength(10)
  })

  it('显式包含敏感配置，只改变本次范围', () => {
    expect(createManifest(sampleFiles, { ...defaultOptions(), includeSensitive: true }).included).toHaveLength(12)
    expect(defaultOptions().includeSensitive).toBe(false)
    expect(isSensitivePath('.env.example')).toBe(false)
    expect(isSensitivePath('secrets/private.key')).toBe(true)
    expect(isSensitivePath('backend/application-local-a.yml')).toBe(true)
  })

  it('接受中文、空格、空文件与二进制元数据', () => {
    expect(validateFiles(sampleFiles)).toBeNull()
    expect(validateFiles([{ path: '目录/空 文件.png', size: 0 }])).toBeNull()
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
  })

  it.each(['../escape', '/absolute', 'C:/project', 'folder\\file', 'folder/./file', 'a//b', 'a\u0000b'])(
    '拒绝非法路径 %s', (path) => expect(validateFiles([{ path, size: 1 }])).not.toBeNull(),
  )

  it('空、重复、非法大小与超限清单明确拒绝，不截断后报成功', () => {
    expect(validateFiles([])).not.toBeNull()
    expect(validateFiles([{ path: 'a', size: 0 }, { path: 'a', size: 1 }])).not.toBeNull()
    expect(validateFiles([{ path: 'a', size: -1 }])).not.toBeNull()
    expect(validateFiles([{ path: 'a', size: NaN }])).not.toBeNull()
    expect(validateFiles(Array.from({ length: previewFileLimit + 1 }, (_, index) => ({ path: `${index}`, size: 0 })))).toContain('未截断')
    const excluded = createManifest([{ path: '.env', size: 5 }], defaultOptions())
    expect(excluded.included).toHaveLength(0)
    expect(excluded.totalBytes).toBe(0)
  })
})
