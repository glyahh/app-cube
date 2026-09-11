<template>
  <div class="loop-create">
    <a-row :gutter="24">
      <!-- 左侧：5 步流程图 -->
      <a-col :xs="24" :md="8" class="loop-left-col">
        <div class="flow-diagram">
          <div
            v-for="(step, idx) in templateSteps"
            :key="step.key"
            class="flow-node"
            :class="{ active: currentStep === idx }"
            @click="currentStep = idx"
          >
            <div class="flow-node-index">
              <svg v-if="step.icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path :d="step.icon" />
              </svg>
              <span v-else>{{ idx + 1 }}</span>
            </div>
            <div class="flow-node-label">{{ step.label }}</div>
          </div>
        </div>
      </a-col>

      <!-- 右侧：编辑区 -->
      <a-col :xs="24" :md="16" class="loop-right-col">
        <div class="edit-header">
          <h2>{{ isEditMode ? '编辑 Loop' : '创建 Loop' }}</h2>
          <span class="edit-step-hint">第 {{ currentStep + 1 }} 步：{{ currentStepLabel }}</span>
        </div>

        <a-form layout="vertical" class="loop-form">
          <a-row :gutter="16">
            <a-col :span="12">
              <a-form-item label="Loop 名称" required>
                <a-input
                  v-model:value="form.loopName"
                  :maxlength="128"
                  placeholder="给技能起个名字"
                  show-count
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="可见性">
                <a-radio-group v-model:value="form.visibility">
                  <a-radio value="public">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px;margin-right:4px">
                      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" /><circle cx="12" cy="12" r="3" />
                    </svg>
                    公开
                  </a-radio>
                  <a-radio value="private">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px;margin-right:4px">
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" /><path d="M7 11V7a5 5 0 0110 0v4" />
                    </svg>
                    私有
                  </a-radio>
                </a-radio-group>
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="简介">
            <a-textarea
              v-model:value="form.description"
              :rows="2"
              :maxlength="512"
              placeholder="简短的技能说明"
              show-count
            />
          </a-form-item>

          <a-divider class="step-divider" />

          <!-- 当前步骤编辑区 -->
          <div
            v-for="(step, idx) in form.steps"
            :key="step.key"
          >
            <a-form-item
              v-if="idx === currentStep"
              :label="`${step.label}`"
              class="step-content-item"
            >
              <a-textarea
                v-model:value="step.content"
                :rows="8"
                :placeholder="step.placeholder || ''"
              />
            </a-form-item>
          </div>

          <!-- 步骤预览（小提示） -->
          <div class="steps-preview">
            <span
              v-for="(step, idx) in form.steps"
              :key="step.key"
              class="step-dot"
              :class="{ filled: step.content.trim(), active: idx === currentStep }"
              :title="step.label + (step.content.trim() ? ' (已填写)' : ' (空')"
              @click="currentStep = idx"
            >
              {{ idx + 1 }}
            </span>
          </div>

          <a-form-item class="form-actions">
            <a-space>
              <a-button type="primary" @click="handleSave" :loading="saving">
                <template #icon>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z" />
                    <polyline points="17,21 17,13 7,13 7,21" /><polyline points="7,3 7,8 15,8" />
                  </svg>
                </template>
                保存
              </a-button>
              <a-button @click="handleCancel">
                <template #icon>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
                  </svg>
                </template>
                取消
              </a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Button as AButton,
  Col as ACol,
  Divider as ADivider,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal,
  Radio as ARadio,
  RadioGroup as ARadioGroup,
  Row as ARow,
  Space as ASpace,
  Textarea as ATextarea,
  message,
} from 'ant-design-vue'
import { loopAddUsingPost } from '@/api/loopController'
import { loopGetVoById, loopUpdateById } from '@/api/longIdApi'

const props = defineProps<{
  editId?: string
}>()

const router = useRouter()
const route = useRoute()

const resolvedEditId = computed(() => props.editId ?? (route.params.id as string) ?? undefined)
const isEditMode = computed(() => !!resolvedEditId.value)

const saving = ref(false)
const currentStep = ref(0)

const currentStepLabel = computed(() => templateSteps[currentStep.value]?.label ?? '')

/** 5 步模板：每个步骤附带 SVG path 图标 */
const templateSteps = [
  { key: 'role', label: '角色设定', icon: 'M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2M12 3a4 4 0 100 8 4 4 0 000-8z',
    placeholder: '你扮演…\n例如：你是一个资深的 Vue 前端工程师' },
  { key: 'context', label: '背景上下文', icon: 'M9 12h6M9 16h6M12 2a10 10 0 110 20 10 10 0 010-20z',
    placeholder: '补充背景信息…\n例如：用户需要生成一个响应式管理后台' },
  { key: 'constraints', label: '约束与边界', icon: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z',
    placeholder: '行为限制、输出规范…\n例如：- 使用 Vue 3 + TypeScript\n- 响应式布局\n- 输出简洁' },
  { key: 'workflow', label: '执行步骤', icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 012-2h2a2 2 0 012 2M9 5h6M9 12l2 2 4-4',
    placeholder: '1. 分析需求\n2. 设计数据流\n3. 生成核心代码\n4. 优化与打包' },
  { key: 'output', label: '输出格式', icon: 'M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8',
    placeholder: '返回 Markdown 格式结果\n或：生成可直接运行的 HTML 文件' },
]

const form = reactive<{
  loopName: string
  description: string
  visibility: string
  steps: { key: string; label: string; content: string; placeholder: string }[]
}>({
  loopName: '',
  description: '',
  visibility: 'private',
  steps: templateSteps.map((s) => ({
    key: s.key,
    label: s.label,
    content: '',
    placeholder: s.placeholder,
  })),
})

// 编译 workflowJson（仅非空步骤）
const buildWorkflowJson = () => {
  const steps = form.steps
    .filter((s) => s.content.trim())
    .map((s) => ({
      key: s.key,
      label: s.label,
      content: s.content.trim(),
    }))
  return {
    templateId: 'standard_v1',
    steps,
  }
}

// 保存
const handleSave = async () => {
  if (!form.loopName.trim()) {
    message.warning('请输入 Loop 名称')
    return
  }

  // 检查是否有未填写的步骤
  const emptySteps = form.steps.filter((s) => !s.content.trim())
  if (emptySteps.length > 0) {
    try {
      await new Promise<void>((resolve, reject) => {
        Modal.confirm({
          title: '部分步骤未填写',
          content: `你只填写了 ${form.steps.length - emptySteps.length}/${form.steps.length} 步，空的步骤（${emptySteps.map(s => s.label).join('、')}）将被跳过，仅保存已填写内容。确定保存？`,
          okText: '确定保存',
          cancelText: '继续编辑',
          onOk: () => resolve(),
          onCancel: () => reject(new Error('cancelled')),
        })
      })
    } catch {
      return
    }
  }

  saving.value = true
  try {
    const payload = {
      loopName: form.loopName.trim(),
      description: form.description.trim(),
      visibility: form.visibility,
      workflowJson: JSON.stringify(buildWorkflowJson()),
    }

    if (isEditMode.value) {
      const res = await loopUpdateById(resolvedEditId.value, payload)
      if (res.data.code === 0 || res.data.code === 20000) {
        message.success('更新成功')
      } else {
        message.error(res.data.message || '更新失败')
        return
      }
    } else {
      const res = await loopAddUsingPost({ body: payload })
      if (res.data.code === 0 || res.data.code === 20000) {
        message.success('创建成功')
      } else {
        message.error(res.data.message || '创建失败')
        return
      }
    }
    router.push('/user/loops')
  } catch (e) {
    console.error('保存失败', e)
    message.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// 取消
const handleCancel = () => {
  router.push('/user/loops')
}

// 编辑模式：加载已有 Loop 数据
onMounted(async () => {
  if (resolvedEditId.value) {
    try {
      const res = await loopGetVoById(resolvedEditId.value)
      if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
        const data = res.data.data
        form.loopName = data.loopName || ''
        form.description = data.description || ''
        form.visibility = data.visibility || 'private'

        // 解析 workflowJson 回填 steps
        if (data.workflowJson) {
          try {
            const parsed = JSON.parse(data.workflowJson)
            if (parsed.steps && Array.isArray(parsed.steps)) {
              parsed.steps.forEach((step: any) => {
                const match = form.steps.find((s) => s.key === step.key)
                if (match) match.content = step.content || ''
              })
            }
          } catch (e) {
            console.warn('workflowJson 解析失败', e)
          }
        }
      } else {
        message.error(res.data.message || '加载 Loop 失败')
        router.push('/user/loops')
      }
    } catch (e) {
      console.error('加载 Loop 失败', e)
      message.error('加载失败，请稍后重试')
      router.push('/user/loops')
    }
  }
})
</script>

<style scoped>
.loop-create {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
  min-height: calc(100vh - 140px);
}

/* ===== 左侧：流程图 ===== */
.loop-left-col {
  position: sticky;
  top: 80px;
  align-self: flex-start;
}

.flow-diagram {
  padding: 20px 16px;
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
}

.flow-node {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  cursor: pointer;
  border-radius: 8px;
  position: relative;
  transition: background var(--transition-duration, 0.2s) ease,
              border-color var(--transition-duration, 0.2s) ease;
  border: 1px solid transparent;
}

.flow-node:hover {
  background: var(--color-primary-light, #e6f4ff);
}

.flow-node.active {
  background: var(--color-primary-bg, #f0f5ff);
  border-color: var(--color-primary, #1677ff);
}

.flow-node-index {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-primary, #1677ff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
  transition: background var(--transition-duration, 0.2s) ease;
}

.flow-node-index svg {
  width: 14px;
  height: 14px;
  color: #fff;
}

.flow-node.active .flow-node-index {
  background: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.2);
}

.flow-node-label {
  font-size: 14px;
  color: var(--text-base, #1f1f1f);
  font-weight: 500;
}

/* ===== 右侧：编辑区 ===== */
.loop-right-col {
  min-height: 400px;
}

.edit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.edit-header h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-base, #1f1f1f);
  margin: 0;
}

.edit-step-hint {
  font-size: 13px;
  color: var(--text-secondary, #666);
  background: var(--color-primary-bg, #f0f5ff);
  padding: 4px 12px;
  border-radius: 12px;
}

.loop-form {
  background: var(--bg-card, #ffffff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  padding: 24px;
}

.step-divider {
  margin: 16px 0;
}

.step-content-item {
  margin-bottom: 12px;
}

/* 步骤指示点 */
.steps-preview {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  padding: 8px 0;
  justify-content: center;
}

.step-dot {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  background: var(--bg-mute, #f0f0f0);
  color: var(--text-secondary, #666);
  border: 1px solid var(--border-color, #e8e8e8);
  transition: all var(--transition-duration, 0.2s) ease;
}

.step-dot.filled {
  background: var(--color-primary, #1677ff);
  color: #fff;
  border-color: var(--color-primary, #1677ff);
}

.step-dot.active {
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.3);
}

.step-dot:hover {
  transform: scale(1.15);
}

.form-actions {
  margin-bottom: 0;
  padding-top: 8px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .loop-create {
    padding: 16px;
  }

  .loop-left-col {
    position: static;
    margin-bottom: 16px;
  }

  .flow-diagram {
    display: flex;
    flex-wrap: wrap;
    gap: 0;
    padding: 16px;
    justify-content: center;
  }

  .flow-node {
    flex-direction: column;
    gap: 4px;
    padding: 8px;
    min-width: 60px;
    align-items: center;
  }

  .flow-node-label {
    font-size: 11px;
    text-align: center;
  }

  .loop-form {
    padding: 16px;
  }

  .edit-header {
    flex-direction: column;
    gap: 8px;
    align-items: flex-start;
  }
}

@media (max-width: 375px) {
  .loop-create {
    padding: 12px;
  }

  .flow-diagram {
    padding: 12px 8px;
  }

  .flow-node {
    min-width: 50px;
    padding: 6px 4px;
  }

  .flow-node-index {
    width: 24px;
    height: 24px;
    font-size: 11px;
  }

  .loop-form {
    padding: 12px;
  }
}
</style>
