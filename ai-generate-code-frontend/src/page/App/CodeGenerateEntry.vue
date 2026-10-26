<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Button as AButton,
  Checkbox as ACheckbox,
  CheckboxGroup as ACheckboxGroup,
  Tag as ATag,
  Textarea as ATextarea,
  Tooltip as ATooltip,
  message,
} from 'ant-design-vue'
import { ArrowRightOutlined, CheckOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import { appAddUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'
import { useAppearanceStore } from '@/stores/appearance'
import LoopPickerTrigger from '@/components/loop/LoopPickerTrigger.vue'

const router = useRouter()
const route = useRoute()
const userLoginStore = UserLoginStore()
const appearanceStore = useAppearanceStore()

const prompt = ref('')
const creating = ref(false)
const workflowEnabled = ref(appearanceStore.workflowEnabled)
/** 看板功能开关，默认全不选；项目内还可以再改 */
const dashboardFeatures = ref<string[]>([])
const selectedLoopId = ref('')
const isLogin = computed(() => Boolean(userLoginStore.userLogin?.id))
const quickPrompts = [
  { label: '个人博客', value: '做一个个人博客主页，包含文章列表、分类筛选和关于我页面' },
  { label: '记账工具', value: '做一个简洁的记账工具，可以按分类统计每月支出' },
  { label: '待办清单', value: '做一个待办任务管理页面，支持添加、完成和删除任务' },
]

function buildAppNameFromPrompt(text: string): string {
  const firstLine = (text.trim().split('\n')[0] || '').trim()
  return firstLine.slice(0, 18) || '我的生成应用'
}

function handleLoopChange(info: { id: string } | null) {
  selectedLoopId.value = info?.id || ''
  if (selectedLoopId.value) workflowEnabled.value = false
}

function toggleWorkflow() {
  if (selectedLoopId.value) {
    message.info('选择 Loop 后暂不支持工作流生成')
    return
  }
  workflowEnabled.value = !workflowEnabled.value
}

function handlePromptKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    void handleCreate()
  }
}

async function handleCreate() {
  if (creating.value) return

  const content = prompt.value.trim()
  if (!content) {
    message.warning('先描述你想生成的应用')
    return
  }
  if (!isLogin.value) {
    message.warning('请先登录后再创建应用')
    router.push({ path: '/user/login', query: { redirect: route.fullPath } })
    return
  }

  const selection = {
    workflowEnabled: workflowEnabled.value,
    loopId: selectedLoopId.value,
    dashboardFeatures: [...dashboardFeatures.value],
  }

  creating.value = true
  try {
    const res = await appAddUsingPost({
      body: {
        appName: buildAppNameFromPrompt(content),
        initPrompt: content,
        isBeta: selection.workflowEnabled ? 1 : 0,
        loopIds: selection.loopId ? [selection.loopId] : undefined,
        dashboardFeatures:
          selection.dashboardFeatures.length > 0 ? selection.dashboardFeatures : undefined,
      } as any,
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      message.success('应用创建成功，正在进入生成对话')
      router.push({
        name: 'app-chat',
        params: { id: res.data.data },
        query: {
          autoSend: 'true',
          genMode: selection.workflowEnabled ? 'workflow' : 'legacy',
          ...(selection.loopId ? { loopId: selection.loopId } : {}),
        },
      })
    } else {
      message.error(res.data.message || '创建应用失败')
    }
  } catch (error) {
    console.error(error)
    message.error('创建应用失败，请稍后重试')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <main class="generate-page">
    <section class="generate-shell" aria-labelledby="page-title">
      <div class="page-intro page-reveal page-reveal--1">
        <div class="eyebrow"><span class="eyebrow-dot" /> AI CODE STUDIO</div>
        <h1 id="page-title">从一个想法开始。</h1>
        <p class="intro-copy">描述你想做的工具或网站，AI 会帮你完成第一版可运行代码。</p>
      </div>

      <section class="composer-card page-reveal page-reveal--2" aria-labelledby="composer-title">
        <div class="composer-heading">
          <div>
            <span class="section-kicker">创建新应用</span>
            <h2 id="composer-title">从描述开始</h2>
          </div>
          <span class="composer-status"><CheckOutlined /> 随时可编辑</span>
        </div>
        <label class="sr-only" for="app-prompt">应用需求描述</label>
        <ATextarea
          id="app-prompt"
          v-model:value="prompt"
          placeholder=""
          :maxlength="2000"
          :auto-size="{ minRows: 7, maxRows: 14 }"
          class="prompt-input"
          @keydown="handlePromptKeydown"
        />
        <div class="prompt-meta">
          <span>{{ prompt.length }} / 2000</span>
        </div>

        <div class="quick-row">
          <span class="quick-label">快速开始</span
          ><button
            v-for="item in quickPrompts"
            :key="item.label"
            type="button"
            class="quick-chip"
            @click="prompt = item.value"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="control-row">
          <div class="control-group" aria-label="看板功能">
            <span class="control-label">看板</span>
            <ACheckboxGroup v-model:value="dashboardFeatures" :disabled="creating">
              <ACheckbox value="metrics">监控</ACheckbox>
              <ACheckbox value="logs">日志</ACheckbox>
              <ACheckbox value="knowledge">知识图谱</ACheckbox>
            </ACheckboxGroup>
          </div>
          <div class="control-actions">
            <LoopPickerTrigger :single-select="true" @single-change="handleLoopChange" /><ATooltip
              title="选择 Loop 后暂不支持工作流生成"
              placement="top"
              ><button
                type="button"
                class="workflow-chip"
                :class="{ active: workflowEnabled, disabled: !!selectedLoopId }"
                :aria-pressed="workflowEnabled"
                :aria-disabled="!!selectedLoopId"
                @click="toggleWorkflow"
              >
                <ThunderboltOutlined /> 工作流 <ATag color="orange" :bordered="false">Beta</ATag>
              </button></ATooltip
            ><AButton
              type="primary"
              size="large"
              class="generate-button"
              :loading="creating"
              :disabled="creating"
              :aria-busy="creating"
              @click="handleCreate"
              >开始生成<template #icon><ArrowRightOutlined /></template
            ></AButton>
          </div>
        </div>
      </section>

      <div class="support-grid page-reveal page-reveal--3">
        <div class="support-item">
          <span class="support-index">1</span>
          <div>
            <strong>描述目标</strong>
            <p>先写清楚用户和核心功能。</p>
          </div>
        </div>
        <div class="support-item">
          <span class="support-index">2</span>
          <div>
            <strong>开始生成</strong>
            <p>AI 会搭建第一版可运行代码。</p>
          </div>
        </div>
        <div class="support-item">
          <span class="support-index">3</span>
          <div>
            <strong>继续迭代</strong>
            <p>进入对话页继续修改和预览。</p>
          </div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.generate-page {
  min-height: calc(100vh - 64px - 48px);
  padding: 72px 24px 88px;
  background: #f8fafc;
}
.generate-shell {
  width: min(920px, 100%);
  margin: 0 auto;
}
.page-intro {
  max-width: 680px;
  margin-bottom: 36px;
}
.eyebrow,
.section-kicker {
  color: #2563eb;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
.eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
}
.eyebrow-dot,
.workflow-mark {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12);
}
.composer-card {
  padding: 36px;
  border: 1px solid #e2e8f0;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.07);
}
.composer-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}
.composer-heading h2 {
  margin-top: 7px;
  color: #0f172a;
  font-size: 24px;
  line-height: 1.25;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.composer-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
  color: #64748b;
  font-size: 12px;
}
.composer-status :deep(svg) {
  color: #16a34a;
}
.prompt-input.ant-input {
  min-height: 214px;
  padding: 20px;
  border: 1px solid #d8e1ec;
  border-radius: 16px;
  outline: none;
  color: #0f172a;
  font-size: 17px;
  line-height: 1.75;
  caret-color: #2563eb;
  background: linear-gradient(145deg, #ffffff 0%, #f7faff 100%);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 8px 22px rgba(15, 23, 42, 0.035);
  resize: none;
  transition:
    border-color 0.22s ease,
    box-shadow 0.22s ease,
    background-color 0.22s ease;
}
.prompt-input.ant-input::placeholder {
  color: transparent;
}
.prompt-input.ant-input:hover {
  border-color: #93b4e9;
}
.prompt-input.ant-input:focus {
  border-color: #6e9ff0;
  background: #fff;
  box-shadow:
    0 0 0 4px rgba(37, 99, 235, 0.12),
    0 14px 30px rgba(37, 99, 235, 0.08);
}
.prompt-meta {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
  color: #64748b;
  font-size: 12px;
}
.quick-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 24px;
}
.quick-label {
  margin-right: 4px;
  color: #475569;
  font-size: 12px;
}
.quick-chip,
.mode-chip,
.workflow-chip {
  min-height: 36px;
  border: 1px solid #dbe4ee;
  border-radius: 999px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;
}
.quick-chip {
  padding: 0 14px;
  font-size: 12px;
}
.quick-chip:hover,
.mode-chip:hover,
.workflow-chip:hover {
  border-color: #93b4e9;
  color: #1d4ed8;
  background: #f5f8ff;
}
.quick-chip:active,
.mode-chip:active,
.workflow-chip:active {
  transform: scale(0.97);
}
.control-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 32px;
  padding-top: 26px;
  border-top: 1px solid #eef2f7;
}
.control-group,
.control-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.control-label {
  margin-right: 4px;
  color: #475569;
  font-size: 12px;
}
.mode-chip,
.workflow-chip {
  padding: 0 13px;
  font-size: 12px;
}
.mode-chip.active {
  border-color: #7ba7ef;
  color: #1d4ed8;
  background: #eff6ff;
}
.workflow-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.workflow-chip.active {
  border-color: #7dd3c7;
  color: #0f766e;
  background: #f0fdfa;
}
.workflow-chip.disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.generate-button {
  min-width: 136px;
  border-radius: 11px;
  font-weight: 600;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.2);
}
.generate-button:active {
  transform: scale(0.98);
}
.support-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 28px;
  margin-top: 28px;
  padding: 0 8px;
}
.support-item {
  display: flex;
  gap: 12px;
  padding: 0 10px 0 0;
}
.support-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #2563eb;
  background: #eff6ff;
  font-size: 11px;
  font-weight: 700;
}
.support-item strong {
  color: #1e293b;
  font-size: 13px;
  font-weight: 600;
}
.support-item p {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}
.page-reveal {
  animation: pageReveal 0.45s ease-out both;
}
.page-reveal--1 {
  animation-delay: 30ms;
}
.page-reveal--2 {
  animation-delay: 90ms;
}
.page-reveal--3 {
  animation-delay: 150ms;
}
@keyframes pageReveal {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
@media (prefers-reduced-motion: reduce) {
  .page-reveal {
    animation: none;
  }
}
@media (max-width: 760px) {
  .generate-page {
    min-height: calc(100vh - 56px - 48px);
    padding: 40px 16px 56px;
  }
  .composer-card {
    padding: 24px 18px;
  }
  .composer-heading,
  .control-row {
    align-items: stretch;
    flex-direction: column;
  }
  .composer-heading {
    gap: 8px;
  }
  .composer-status {
    padding-top: 0;
  }
  .control-actions {
    justify-content: flex-start;
  }
  .generate-button {
    width: 100%;
  }
  .support-grid {
    grid-template-columns: 1fr;
    gap: 18px;
    padding: 0 2px;
  }
}
</style>
