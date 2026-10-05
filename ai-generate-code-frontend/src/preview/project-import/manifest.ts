export * from '@/features/project-import/manifest'
export { importFileLimit as previewFileLimit } from '@/features/project-import/manifest'
import type { FileMetadata } from '@/features/project-import/manifest'

// 明确标记的虚构元数据，不代表真实仓库、上传结果或内容版本。
export const sampleFiles: FileMetadata[] = [
  { path: 'package.json', size: 726 },
  { path: 'src/App.vue', size: 1842 },
  { path: 'src/页面/欢迎 页面.vue', size: 935 },
  { path: 'public/logo.png', size: 8104 },
  { path: 'README.md', size: 0 },
  { path: 'node_modules/vue/package.json', size: 2516 },
  { path: '.venv/pyvenv.cfg', size: 203 },
  { path: 'dist/index.html', size: 682 },
  { path: 'target/classes/App.class', size: 413 },
  { path: '.env', size: 122 },
  { path: '.env.example', size: 90 },
  { path: 'backend/application-local.yml', size: 287 },
]
