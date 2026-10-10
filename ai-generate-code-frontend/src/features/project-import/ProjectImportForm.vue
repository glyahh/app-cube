<script setup lang="ts">
import { ref } from 'vue'
import { Alert as AAlert, Button as AButton, Checkbox as ACheckbox, Input as AInput, Progress as AProgress, Steps as ASteps, Tag as ATag } from 'ant-design-vue'
import { formatBytes, type FileMetadata, type ImportOptions, createManifest } from './manifest'
const props = defineProps<{
  files: FileMetadata[]; folderName: string; manifest: ReturnType<typeof createManifest>
  state: 'select' | 'review' | 'importing' | 'succeeded' | 'failed' | 'cancelled'
  busy: boolean; canSubmit: boolean; error: string; notice: string
  stageLabel?: string; progress?: number; metadataPreview?: boolean
}>()
const appName = defineModel<string>('appName', { required: true })
const options = defineModel<ImportOptions>('options', { required: true })
const picker = ref<HTMLInputElement>()
const emit = defineEmits<{
  selectFolder: [event: Event]; selectDirectory: [handle: FileSystemDirectoryHandle]; failure: [message: string]; savePreferences: []; startImport: []; cancelImport: []; resetImport: []
}>()
async function chooseFolder() {
  if (props.metadataPreview) { picker.value?.click(); return }
  const choose = (window as Window & { showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle> }).showDirectoryPicker
  if (!choose) { emit('failure', '当前浏览器不支持完整目录读取，请使用 Chromium。'); return }
  try { emit('selectDirectory', await choose.call(window)) }
  catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') return
    emit('failure', '无法读取文件夹，请检查目录权限。')
  }
}
</script>

<template>
<main class="project-import-form">
        <div class="page-heading">
          <h1>导入项目</h1>
        </div>

        <section class="import-card" aria-label="项目导入">
          <a-steps class="flow-steps" size="small" :responsive="false" :current="state === 'select' ? 0 : state === 'succeeded' ? 2 : 1"
            :items="[{ title: '选择来源' }, { title: '确认范围' }, { title: '导入结果' }]" />

            <div class="import-grid">
              <section class="source-panel" aria-labelledby="source-title">
                <h2 id="source-title">工程来源</h2>
                <div v-if="$slots['source-switch']" class="source-switch"><slot name="source-switch" /></div>
                <slot name="source"><div class="folder-picker">
                  <svg aria-hidden="true" viewBox="0 0 48 48" class="folder-icon"><path d="M5 14a4 4 0 0 1 4-4h11l5 5h14a4 4 0 0 1 4 4v18a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4Z" /><path d="M5 20h38" /></svg>
                  <strong v-if="folderName">{{ folderName }}</strong>
                  <a-button :disabled="busy" @click="chooseFolder">{{ files.length ? '重新选择' : '选择文件夹' }}</a-button>
                  <input ref="picker" type="file" webkitdirectory multiple hidden aria-label="选择工程文件夹" :disabled="busy" @change="emit('selectFolder', $event)" />
                </div></slot>
                <label class="field-label" for="import-name">新应用名称</label>
                <a-input id="import-name" v-model:value="appName" :disabled="!files.length || busy" :aria-invalid="!!files.length && (!appName.trim() || appName.trim().length > 80)" />
                <p v-if="files.length && !appName.trim()" class="field-error" role="alert">请输入应用名称。</p>
                <p v-else-if="appName.trim().length > 80" class="field-error" role="alert">名称最多 80 个字符。</p>
              </section>

              <section class="range-panel" aria-labelledby="range-title">
                <div class="section-heading">
                  <h2 id="range-title">导入范围</h2>
                  <details class="filter-help">
                    <summary>过滤规则</summary>
                    <div class="help-content">
                      <p>依赖：node_modules、.venv、venv</p>
                      <p>产物：dist、target、build</p>
                      <p>默认排除敏感配置，保留示例。</p>
                      <p>仅本次生效；保存偏好后沿用。</p>
                    </div>
                  </details>
                </div>
                <div class="filter-controls">
                  <a-checkbox v-model:checked="options.skipDependencies" :disabled="busy">跳过依赖目录</a-checkbox>
                  <a-checkbox v-model:checked="options.skipBuildOutputs" :disabled="busy">跳过构建产物</a-checkbox>
                  <a-checkbox v-model:checked="options.includeSensitive" :disabled="busy">本次包含敏感配置</a-checkbox>
                </div>
                <a-alert v-if="options.includeSensitive" class="sensitive-alert" type="warning" show-icon message="可能包含密钥；此选择不授权模型读取。" />
                <div class="preference-row"><a-button size="small" :disabled="busy" @click="emit('savePreferences')">保存为默认偏好</a-button></div>
                <div v-if="files.length" class="summary" aria-label="导入摘要">
                  <div><strong data-testid="included-count">{{ manifest.included.length }}</strong><span>拟导入文件</span></div>
                  <div><strong data-testid="total-bytes">{{ formatBytes(manifest.totalBytes) }}</strong><span>拟导入大小</span></div>
                  <div><strong data-testid="excluded-count">{{ manifest.excluded.length }}</strong><span>排除文件</span></div>
                </div>
                <a-alert v-if="files.length && !manifest.included.length" type="warning" message="当前范围没有可导入文件，请调整选项或重新选择。" />
                <details v-if="files.length" class="file-details">
                  <summary>查看清单详情（{{ files.length }}）</summary>
                  <div class="file-list" tabindex="0" aria-label="文件清单详情">
                    <div v-for="file in manifest.included" :key="file.path" class="file-row"><span>{{ file.path }}</span><small>{{ file.directory ? '目录' : formatBytes(file.size) }}</small><a-tag color="blue">拟导入</a-tag></div>
                    <div v-for="file in manifest.excluded" :key="file.path" class="file-row excluded"><span>{{ file.path }}</span><small>{{ formatBytes(file.size) }}</small><a-tag>{{ file.reason }}</a-tag></div>
                  </div>
                </details>
              </section>
            </div>
            <div v-if="error" class="status-block"><a-alert type="error" show-icon :message="error" role="alert" /></div>
            <div v-if="busy" class="status-block" role="status" aria-live="polite">
              <div class="status-heading"><strong>{{ stageLabel }}</strong></div>
              <a-progress v-if="progress !== undefined" :percent="progress" />
            </div>
            <div v-if="!error && (state === 'failed' || state === 'cancelled')" class="status-block" role="alert">
              <a-alert :type="state === 'failed' ? 'error' : 'warning'" show-icon
                :message="state === 'failed' ? '导入失败，请重试。' : '导入已取消。'" />
            </div>
            <footer class="import-footer">
              <div class="footer-actions">
                <a-button v-if="busy" @click="emit('cancelImport')">取消导入</a-button>
                <a-button v-else :disabled="!files.length && !error" @click="emit('resetImport')">清空</a-button>
                <a-button type="primary" :disabled="!canSubmit" :loading="busy" @click="emit('startImport')">{{ state === 'failed' || state === 'cancelled' ? '重新导入' : '确认导入' }}</a-button>
              </div>
            </footer>
        </section>

        <a-alert v-if="notice" class="notice" show-icon type="info" :message="notice" role="status" />
      </main>
</template>

<style scoped>
.project-import-form { max-width: 1024px; padding: 32px 24px 48px; margin: auto; color: var(--text-base); }
.footer-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.page-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 24px; }
h1 { font-size: 26px; font-weight: 650; letter-spacing: -.03em; margin: 0; color: var(--text-base); }
h2 { font-size: 17px; font-weight: 600; margin: 0 0 20px; color: var(--text-base); }
p { color: var(--text-secondary); line-height: 1.7; margin: 0; }
.import-card { border: 1px solid var(--border-color); border-radius: var(--radius-lg); background: var(--bg-card); box-shadow: var(--shadow-card); overflow: hidden; }
.flow-steps { padding: 20px 24px; border-bottom: 1px solid var(--border-color); }
.import-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); }
.source-panel, .range-panel { min-width: 0; padding: 24px; }
.source-panel { border-right: 1px solid var(--border-color); }
.source-switch { margin-bottom:20px; }
.folder-picker { min-height: 176px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 24px 16px; background: var(--bg-soft); border: 1px dashed var(--border-color-hover); border-radius: var(--radius-md); text-align: center; }
.folder-picker strong { display: block; overflow-wrap: anywhere; }
.folder-icon { display: block; width: 40px; height: 40px; fill: var(--color-primary-light); stroke: var(--color-primary); stroke-width: 1.7; }
.field-label { display: block; font-size: 13px; font-weight: 550; margin: 24px 0 8px; }
.field-error { color: #cf1322; font-size: 12px; margin-top: 6px; }
.filter-controls { display: flex; flex-direction: column; gap: 16px; }
.sensitive-alert { margin: 8px 0 16px; }
.preference-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 20px; }
.summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; background: var(--bg-soft); border-radius: var(--radius-md); padding: 20px 16px; margin: 24px 0 16px; }
.summary > div { display: flex; flex-direction: column; justify-content: center; gap: 6px; }
.summary strong { display: block; font-size: 21px; font-weight: 650; line-height: 1.25; white-space: nowrap; }
.summary span { color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
.section-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 20px; }
.section-heading h2 { margin: 0; }
.filter-help { position: relative; }
.filter-help summary { padding: 0; white-space: nowrap; }
.help-content { position: absolute; z-index: 2; top: 28px; right: 0; width: 280px; max-width: calc(100vw - 72px); padding: 12px 16px; background: var(--bg-card); border: 1px solid var(--border-color); border-radius: var(--radius-md); box-shadow: var(--shadow-popup); font-size: 13px; }
.help-content p + p { margin-top: 8px; }
summary { cursor: pointer; font-size: 13px; color: var(--text-secondary); padding: 12px 0; }
.file-list { max-height: 260px; overflow: auto; padding-right: 6px; }
.file-row { display: grid; grid-template-columns: minmax(0, 1fr) 65px 85px; gap: 8px; align-items: center; font-size: 12px; padding: 8px 0; border-bottom: 1px solid var(--border-color); }
.file-row > span { overflow-wrap: anywhere; line-height: 1.5; }
.file-row small { color: var(--text-secondary); text-align: right; line-height: 1.5; }
.file-row :deep(.ant-tag) { display: inline-flex; align-items: center; justify-content: center; min-height: 24px; margin: 0; line-height: 1.5; }
.excluded { color: var(--text-secondary); }
.status-block { padding: 0 24px 20px; }
.status-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.import-footer { padding: 20px 24px; border-top: 1px solid var(--border-color); display: flex; align-items: center; justify-content: flex-end; gap: 16px; }
.notice { margin-top: 20px; }
@media (max-width: 760px) {
  .project-import-form { padding: 24px 16px; }
  .page-heading { align-items: flex-start; flex-direction: column; gap: 8px; }
  h1 { font-size: 24px; }
  .flow-steps { padding: 20px; }
  .flow-steps :deep(.ant-steps-item-title) { font-size: 12px; }
  .flow-steps :deep(.ant-steps-item-icon) { margin-inline-end: 4px; }
  .import-grid { grid-template-columns: minmax(0, 1fr); }
  .source-panel, .range-panel { padding: 24px 20px; }
  .source-panel { border-right: none; border-bottom: 1px solid var(--border-color); }
  .import-footer { padding: 20px; flex-direction: column; align-items: stretch; }
  .footer-actions { justify-content: flex-end; }
  .status-block { padding: 0 20px 20px; }
  .summary { padding: 16px 12px; gap: 6px; }
  .summary strong { font-size: 18px; }
}


</style>
