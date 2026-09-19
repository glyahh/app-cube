<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Select as ASelect,
  SelectOption as ASelectOption,
  Slider as ASlider,
  Switch as ASwitch,
} from 'ant-design-vue'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'
import { useAppearanceStore, CODE_THEME_OPTIONS, DEFAULT_CODE_TYPE_OPTIONS } from '@/stores/appearance'
import { useExecutionPreference } from '@/stores/executionPreference'
import { message } from 'ant-design-vue'

const router = useRouter()
const store = useAppearanceStore()
const execution = useExecutionPreference()
async function saveExecutionPreference(value: boolean | string | number) {
  try { await execution.save(value === true) }
  catch { message.error('保存失败，请重试') }
}
async function saveStartupFailureAlert(value: boolean | string | number) {
  try { await execution.saveAlertOnStartupFailure(value === true) }
  catch { message.error('保存失败，请重试') }
}
async function saveRuntimeFailureAlert(value: boolean | string | number) {
  try { await execution.saveAlertOnRuntimeFailure(value === true) }
  catch { message.error('保存失败，请重试') }
}
async function saveAutoModelProxyFallback(value: boolean | string | number) {
  try { await execution.saveAutoModelProxyFallback(value === true) }
  catch { message.error('保存失败，请重试') }
}
async function saveAutoNameProject(value: boolean | string | number) {
  try { await execution.saveAutoNameProject(value === true) }
  catch { message.error('保存失败，请重试') }
}
const collapseMotionDesc = ref(true) // 减少动画说明列表默认折叠

const previewCode = `function hello() {
  const msg = "你好，世界！";
  console.log(msg);
  document.body.append(msg);
}

hello();`

/** 简约的 JS 语法高亮 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const highlightedPreview = computed(() => {
  const escaped = escapeHtml(previewCode)
  return escaped
    .replace(/(\/\/[^\r\n]*)/g, '<span class="code-comment">$1</span>')
    .replace(/(["'`])(?:\\.|(?!\1)[\s\S])*?\1/g, '<span class="code-string">$&</span>')
    .replace(
      /\b(const|let|var|function|return|if|else|switch|case|break|default|for|while|do|continue|import|from|export|class|extends|new|this|async|await|try|catch|finally|throw|null|undefined|true|false)\b/g,
      '<span class="code-keyword">$1</span>',
    )
    .replace(/\b(\d+(?:\.\d+)?)\b/g, '<span class="code-number">$1</span>')
    .replace(/([=+\-*/%<>!&|^~?:]+)/g, '<span class="code-operator">$1</span>')
    .replace(/\b([A-Za-z_$][\w$]*)(?=\s*\()/g, '<span class="code-function">$1</span>')
})

function goBack() {
  router.back()
}
</script>

<template>
  <div class="appearance-page">
    <!-- 顶部栏 -->
    <div class="page-header">
      <button class="back-btn" @click="goBack">
        <ArrowLeftOutlined />
        <span>返回</span>
      </button>
      <h1 class="page-title">外观设置</h1>
      <p class="page-desc">自定义界面颜色、字体、布局和生成偏好</p>
    </div>

    <section class="appearance-section">
      <div class="section-header">对话展示</div>
      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title" id="execution-preference-label">展开思考过程</span>
          <span class="row-desc">按账号保存，立即同步当前与历史消息；仅控制详情展开，不改变已记录内容。</span>
        </div>
        <a-switch :checked="execution.expanded" :loading="execution.saving"
          aria-labelledby="execution-preference-label" @change="saveExecutionPreference" />
      </div>
    </section>

    <section class="appearance-section">
      <div class="section-header">运行与异常提示</div>
      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title" id="startup-failure-alert-label">启动异常时弹窗提示</span>
          <span class="row-desc">应用启动失败（进程异常退出）时弹出提示，可选择让 AI 诊断修复。默认开启。</span>
        </div>
        <a-switch :checked="execution.alertOnStartupFailure" :loading="execution.saving"
          aria-labelledby="startup-failure-alert-label" @change="saveStartupFailureAlert" />
      </div>
      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title" id="runtime-failure-alert-label">运行异常时弹窗提示</span>
          <span class="row-desc">应用运行期间出现未处理异常时弹出提示。默认关闭，避免偶发错误频繁打扰；错误仍会记录在运行日志里。</span>
        </div>
        <a-switch :checked="execution.alertOnRuntimeFailure" :loading="execution.saving"
          aria-labelledby="runtime-failure-alert-label" @change="saveRuntimeFailureAlert" />
      </div>
      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title" id="model-proxy-fallback-label">代理连接失败时自动直连</span>
          <span class="row-desc">仅代理无法建立连接时重试当前模型调用；成功后该应用后续固定直连。默认关闭。</span>
        </div>
        <a-switch :checked="execution.autoModelProxyFallback" :loading="execution.saving"
          aria-labelledby="model-proxy-fallback-label" @change="saveAutoModelProxyFallback" />
      </div>
    </section>
    <!-- A. 基础颜色 -->
    <section class="appearance-section">
      <div class="section-header">基础颜色</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">外观模式</span>
          <span class="row-desc">选择浅色、深色或跟随系统</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.colorMode"
            style="width: 140px"
            @change="(v) => store.colorMode = v as 'system' | 'light' | 'dark'"
          >
            <a-select-option value="system">跟随系统</a-select-option>
            <a-select-option value="light">浅色</a-select-option>
            <a-select-option value="dark">深色</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">主题强调色</span>
          <span class="row-desc">应用于按钮、链接和选中项</span>
        </div>
        <div class="row-control row-control--color">
          <input
            v-model="store.primaryColor"
            class="native-color-picker"
            type="color"
            aria-label="主题强调色"
          />
          <a-button size="small" @click="store.primaryColor = store.DEFAULTS.primaryColor">重置</a-button>
        </div>
      </div>
    </section>

    <!-- B. 字体 -->
    <section class="appearance-section">
      <div class="section-header">字体</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">界面字号</span>
          <span class="row-desc">{{ store.fontSize }}px — 控制全局正文字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="12"
            :max="18"
            :step="1"
            :value="store.fontSize"
            style="width: 160px"
            @update:value="(v) => store.fontSize = Number(v)"
          />
          <span class="slider-value">{{ store.fontSize }}px</span>
          <a-button size="small" @click="store.fontSize = store.DEFAULTS.fontSize">重置</a-button>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码字号</span>
          <span class="row-desc">{{ store.codeFontSize }}px — 代码块和终端字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="11"
            :max="16"
            :step="1"
            :value="store.codeFontSize"
            style="width: 160px"
            @update:value="(v) => store.codeFontSize = Number(v)"
          />
          <span class="slider-value">{{ store.codeFontSize }}px</span>
          <a-button size="small" @click="store.codeFontSize = store.DEFAULTS.codeFontSize">重置</a-button>
        </div>
      </div>

      <!-- 代码字号预览 -->
      <div class="appearance-row appearance-row--code-preview">
        <pre class="code-preview"
          :style="{ fontSize: store.codeFontSize + 'px' }"
        ><code v-html="highlightedPreview"></code></pre>
      </div>
    </section>

    <!-- C. 界面密度 -->
    <section class="appearance-section">
      <div class="section-header">界面密度</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">紧凑模式</span>
          <span class="row-desc">减小内边距和间距，显示更多内容</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.compactMode" @change="(v) => store.compactMode = Boolean(v)" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">减少动画</span>
          <span class="row-desc">关闭界面切换和内容加载的过渡动画</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.reducedMotion" @change="(v) => store.reducedMotion = Boolean(v)" />
        </div>
      </div>

      <!-- 减少动画详情与演示 -->
      <div class="appearance-row appearance-row--motion-detail">
        <div class="motion-detail">
          <div
            class="motion-detail__label motion-detail__label--clickable"
            @click="collapseMotionDesc = !collapseMotionDesc"
          >
            <span class="motion-detail__arrow" :class="{ 'motion-detail__arrow--expanded': !collapseMotionDesc }">▶</span>
            关闭后以下动画将被抑制：
          </div>
          <div v-show="!collapseMotionDesc" class="motion-detail__collapse-wrap">
            <ul class="motion-detail__list">
              <li>页面切换过渡（背景色、文字色）</li>
              <li>元素展开与折叠（代码块、工具卡片）</li>
              <li>加载脉冲（工具调用、打字指示器、工作流步骤）</li>
              <li>装饰循环（首页漂浮、光束旋转、渐变位移）</li>
              <li>hover 位移与阴影过渡</li>
            </ul>
          </div>
          <div class="motion-demo">
            <div class="motion-demo__header">对比演示</div>
            <div class="motion-demo__body">
              <div class="motion-demo__dot" />
              <span class="motion-demo__text">{{ store.reducedMotion ? '动画已关闭 — 静态无脉冲' : '动画运行中 — 蓝色圆点规律脉冲' }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- D. 生成与聊天偏好 -->
    <section class="appearance-section">
      <div class="section-header">生成与聊天偏好</div>
      <div class="appearance-row">
        <div class="row-label"><span id="auto-name-label" class="row-title">自动命名项目</span></div>
        <div class="row-control">
          <a-switch :checked="execution.autoNameProject" :loading="execution.saving" :disabled="!execution.ready"
            aria-labelledby="auto-name-label" @change="saveAutoNameProject" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">默认代码类型</span>
          <span class="row-desc">创建新应用时的默认生成类型</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.defaultCodeType"
            style="width: 140px"
            @change="(v) => store.defaultCodeType = v as 'auto' | 'html' | 'multi_file' | 'vue_project'"
          >
            <a-select-option
              v-for="opt in DEFAULT_CODE_TYPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">启用工作流生成</span>
          <span class="row-desc">BETA — 逻辑更严谨、图片更贴切</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.workflowEnabled" @change="(v) => store.workflowEnabled = Boolean(v)" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码块主题</span>
          <span class="row-desc">代码语法高亮的配色方案</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.codeTheme"
            style="width: 120px"
            @change="(v) => store.codeTheme = v as 'default' | 'high-contrast' | 'soft'"
          >
            <a-select-option
              v-for="opt in CODE_THEME_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">工具卡片默认折叠</span>
          <span class="row-desc">写入/修改文件卡片默认收起</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.toolCardCollapsed" @change="(v) => store.toolCardCollapsed = Boolean(v)" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">预览面板默认展开</span>
          <span class="row-desc">打开对话时显示 iframe 预览</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.previewExpanded" @change="(v) => store.previewExpanded = Boolean(v)" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">流式输出平滑滚动</span>
          <span class="row-desc">新内容自动滚动到底部</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.smoothScroll" @change="(v) => store.smoothScroll = Boolean(v)" />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.appearance-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
  gap: var(--spacing-md, 16px);
}

/* 顶部栏 */
.page-header {
  margin-bottom: 24px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  border: none;
  background: none;
  color: var(--color-primary, #1677ff);
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 12px;
}

.back-btn:hover {
  opacity: 0.8;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--text-base, #1f1f1f);
}

.page-desc {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

/* 分组卡片 */
.appearance-section {
  background: var(--bg-card, #fff);
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-card, 0 2px 8px rgba(0,0,0,0.06));
  overflow: hidden;
  margin-bottom: var(--spacing-md, 16px);
}

.section-header {
  padding: var(--spacing-sm, 8px) var(--spacing-md, 16px);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-secondary, #666);
  background: var(--bg-section-header, #f0f5ff);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

/* 设置行 */
.appearance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm, 8px) var(--spacing-md, 16px);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.appearance-row:last-child {
  border-bottom: none;
}

.row-label {
  flex: 1;
  min-width: 0;
  padding-right: 16px;
}

.row-title {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-base, #1f1f1f);
  margin-bottom: 2px;
}

.row-desc {
  display: block;
  font-size: 12px;
  color: var(--text-secondary, #666);
  line-height: 1.4;
}

.row-control {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-control--slider {
  min-width: 220px;
}

.row-control--color {
  gap: 6px;
}

.native-color-picker {
  width: 32px;
  height: 24px;
  padding: 2px;
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 4px;
  background: var(--bg-card, #fff);
  cursor: pointer;
}

.slider-value {
  min-width: 32px;
  font-size: 13px;
  color: var(--text-secondary, #666);
  text-align: center;
}

/* 减少动画详情与演示 */
.appearance-row--motion-detail {
  flex-direction: column;
  align-items: stretch;
  padding: var(--spacing-sm, 8px) var(--spacing-md, 16px);
}

.motion-detail__label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary, #666);
  margin-bottom: 6px;
}

.motion-detail__list {
  margin: 0;
  padding: 0 0 0 16px;
  font-size: 12px;
  color: var(--text-muted, #999);
  line-height: 1.8;
  list-style-type: disc;
}

.motion-demo {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: var(--radius-sm, 6px);
  background: var(--bg-soft, #f5f7fa);
}

.motion-demo__header {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted, #999);
  margin-bottom: 8px;
}

.motion-demo__body {
  display: flex;
  align-items: center;
  gap: 10px;
}

.motion-demo__dot {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: var(--color-primary, #1677ff);
  animation: motion-demo-pulse 1.2s ease-in-out infinite;
}

@keyframes motion-demo-pulse {
  0%, 100% { opacity: 0.4; transform: scale(0.9); }
  50% { opacity: 1; transform: scale(1.15); }
}

.motion-demo__text {
  font-size: 13px;
  color: var(--text-base, #1f1f1f);
}

/* ---------- 代码字号预览 ---------- */
.appearance-row--code-preview {
  padding: var(--spacing-sm, 8px) var(--spacing-md, 16px);
  display: flex;
  align-items: stretch;
}

.code-preview {
  flex: 1;
  margin: 0;
  padding: 10px 14px;
  font-family: var(--code-font-family, Consolas, Monaco, "Courier New", monospace);
  line-height: 1.7;
  color: var(--code-text, #d4d4d4);
  background: var(--code-bg, #1e1e1e);
  border-radius: var(--radius-sm, 6px);
  white-space: pre;
  tab-size: 2;
}

.code-preview code {
  font-family: inherit;
  font-size: inherit;
  color: inherit;
  background: none;
}

.code-preview :deep(.code-keyword) { color: var(--code-keyword, #569cd6); font-weight: 500; }
.code-preview :deep(.code-string) { color: var(--code-string, #e6b450); }
.code-preview :deep(.code-comment) { color: var(--code-comment, #7f8ea3); font-style: italic; }
.code-preview :deep(.code-number) { color: var(--code-number, #c4b5fd); }
.code-preview :deep(.code-operator) { color: var(--code-operator, #f38ba8); }
.code-preview :deep(.code-function) { color: var(--code-function, #7ee787); }

/* ---------- 减少动画折叠 ---------- */
.motion-detail__label--clickable {
  cursor: pointer;
  user-select: none;
  transition: color 150ms ease;
}

.motion-detail__label--clickable:hover {
  color: var(--color-primary, #1677ff);
}

.motion-detail__arrow {
  display: inline-block;
  font-size: 10px;
  margin-right: 4px;
  transition: transform 150ms ease;
  transform: rotate(0deg);
}

.motion-detail__arrow--expanded {
  transform: rotate(90deg);
}

.motion-detail__collapse-wrap {
  overflow: hidden;
  transition: max-height 200ms ease, opacity 150ms ease;
}

/* ---------- 响应式 ---------- */
@media (max-width: 576px) {
  .appearance-page {
    padding: 16px;
  }

  .appearance-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .row-label {
    padding-right: 0;
  }

  .row-control {
    width: 100%;
  }

  .row-control--slider {
    min-width: unset;
    width: 100%;
  }

  .appearance-row--code-preview {
    padding: 4px var(--spacing-md, 16px);
  }
}
</style>
