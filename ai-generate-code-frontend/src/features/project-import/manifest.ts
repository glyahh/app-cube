export interface FileMetadata {
  path: string
  size: number
  directory?: boolean
}

export interface ImportOptions {
  skipDependencies: boolean
  skipBuildOutputs: boolean
  includeSensitive: boolean
}

export const defaultOptions = (): ImportOptions => ({
  skipDependencies: false,
  skipBuildOutputs: false,
  includeSensitive: false,
})

// 这是浏览器文件清单上限，不是拟议后端上传额度。
export const importFileLimit = 10_000

export function validateFiles(files: FileMetadata[]): string | null {
  if (!files.length) return '未发现文件，请选择非空文件夹。'
  if (files.length > importFileLimit) return `最多选择 ${importFileLimit} 个文件，未截断清单，请缩小范围后重试。`
  const paths = new Set<string>()
  for (const file of files) {
    const parts = file.path.split('/')
    if (
      parts.some((part) => !part || part === '.' || part === '..') ||
      /[\\:\u0000-\u001f\u007f]/.test(file.path) ||
      !Number.isSafeInteger(file.size) || file.size < 0 || (file.directory === true && file.size !== 0)
    ) return '文件路径或大小无效，请重新选择文件夹。'
    if (paths.has(file.path)) return '发现重复文件路径，未生成导入清单。'
    paths.add(file.path)
  }
  return null
}

/** 仅按文件名提示敏感范围；真实后端仍需独立校验，不能据此开放模型读取。 */
export function isSensitivePath(path: string): boolean {
  const name = path.split('/').pop()!.toLowerCase()
  if (/^\.env(?:\.|$)/.test(name)) return !/\.(?:example|sample|template)$/.test(name)
  return /^application-local(?:-[\w-]+)?\.(?:ya?ml|properties)$/.test(name) ||
    /\.(?:pem|key|p12|pfx|jks|keystore)$/.test(name) ||
    /^(?:id_rsa|id_ed25519|credentials(?:\.json)?|\.npmrc|\.pypirc|\.netrc)$/.test(name)
}

export function createManifest(files: FileMetadata[], options: ImportOptions) {
  const included: FileMetadata[] = []
  const excluded: Array<FileMetadata & { reason: string }> = []
  for (const file of files) {
    const directories = file.path.split('/').slice(0, file.directory ? undefined : -1)
    const reason = !options.includeSensitive && isSensitivePath(file.path) ? '敏感配置' :
      options.skipDependencies && directories.some((part) => ['node_modules', '.venv', 'venv'].includes(part)) ? '依赖目录' :
      options.skipBuildOutputs && directories.some((part) => ['dist', 'target', 'build'].includes(part)) ? '构建产物' : ''
    if (reason) excluded.push({ ...file, reason })
    else included.push(file)
  }
  return { included, excluded, totalBytes: included.reduce((sum, file) => sum + file.size, 0) }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}
