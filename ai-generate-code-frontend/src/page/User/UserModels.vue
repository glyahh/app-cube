<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Checkbox as ACheckbox,
  CheckboxGroup as ACheckboxGroup,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputNumber as AInputNumber,
  InputPassword as AInputPassword,
  Modal,
  Select as ASelect,
  message,
} from 'ant-design-vue'
import {
  ApiOutlined,
  PlusOutlined,
  SettingOutlined,
  SkinOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import myAxios from '@/request'

interface Provider {
  id: string
  name: string
  baseUrl: string
  model: string
  models: string[]
  contextWindow: number
  maxOutputTokens: number
  efforts: string[]
  configured: boolean
  isDefault: boolean
}

interface Config {
  defaultId: string | null
  providers: Provider[]
}

const router = useRouter()
const config = ref<Config>({ defaultId: null, providers: [] })
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const fetchingModels = ref(false)
const open = ref(false)
const editing = ref<Provider | null>(null)
const availableModels = ref<string[]>([])
const manualModel = ref('')

const form = reactive({
  id: '',
  name: '',
  baseUrl: '',
  model: '',
  models: [] as string[],
  apiKey: '',
  contextWindow: 128_000,
  maxOutputTokens: 8_192,
  efforts: [] as string[],
  makeDefault: false,
})

watch(
  () => form.models,
  (models) => {
    if (!models.includes(form.model)) {
      form.model = models[0] ?? ''
    }
  },
  { deep: true },
)

async function call<T>(path: string, method = 'get', data?: unknown): Promise<T> {
  const response = await myAxios.request<{ code: number; message?: string; data: T }>({
    url: path,
    method,
    data,
  })
  if (response.data.code !== 20000) {
    throw new Error(response.data.message || '操作失败，请稍后重试。')
  }
  return response.data.data
}

async function load() {
  loading.value = true
  try {
    config.value = await call<Config>('/model-config/providers')
  } catch (cause) {
    message.error((cause as Error).message || '加载模型配置失败。')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.id = ''
  form.name = ''
  form.baseUrl = ''
  form.model = ''
  form.models = []
  form.apiKey = ''
  form.contextWindow = 128_000
  form.maxOutputTokens = 8_192
  form.efforts = []
  form.makeDefault = false
  availableModels.value = []
  manualModel.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  open.value = true
}

function openEdit(provider: Provider) {
  editing.value = provider
  form.id = provider.id
  form.name = provider.name
  form.baseUrl = provider.baseUrl
  form.model = provider.model
  form.models = [...provider.models]
  form.apiKey = ''
  form.contextWindow = provider.contextWindow
  form.maxOutputTokens = provider.maxOutputTokens
  form.efforts = [...provider.efforts]
  form.makeDefault = false
  availableModels.value = [...provider.models]
  manualModel.value = ''
  open.value = true
}

function applyPreset(kind: 'deepseek' | 'opencode-go') {
  if (kind === 'deepseek') {
    form.name = 'DeepSeek 官方'
    form.baseUrl = 'https://api.deepseek.com'
    form.model = 'deepseek-chat'
    form.models = ['deepseek-chat']
    availableModels.value = ['deepseek-chat', 'deepseek-reasoner']
    form.contextWindow = 128_000
    form.maxOutputTokens = 8_192
    form.efforts = []
  } else {
    form.name = 'OpenCode Go'
    form.baseUrl = 'https://opencode.ai/inference/go/v1'
    form.model = 'deepseek-v4.1-flash'
    form.models = ['deepseek-v4.1-flash']
    availableModels.value = ['deepseek-v4.1-flash', 'deepseek-v4-flash']
    form.contextWindow = 200_000
    form.maxOutputTokens = 32_000
    form.efforts = ['low', 'high']
  }
  form.makeDefault = false
}

async function fetchModels() {
  if (!form.baseUrl.trim()) {
    message.warning('请先填写 Base URL。')
    return
  }
  fetchingModels.value = true
  try {
    const result = await call<{ models: string[]; message: string }>('/model-config/models', 'post', {
      id: form.id || undefined,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined,
    })
    const merged = [...new Set([...result.models, ...form.models])]
    availableModels.value = merged
    if (!form.models.length && form.model && merged.includes(form.model)) {
      form.models = [form.model]
    }
    if (result.models.length) {
      message.success(result.message)
    } else {
      message.warning(result.message)
    }
  } catch (cause) {
    message.error((cause as Error).message || '获取模型列表失败。')
  } finally {
    fetchingModels.value = false
  }
}

function addManualModel() {
  const model = manualModel.value.trim()
  if (!model) {
    return
  }
  if (!availableModels.value.includes(model)) {
    availableModels.value.push(model)
  }
  if (!form.models.includes(model)) {
    form.models.push(model)
  }
  if (!form.model) {
    form.model = model
  }
  manualModel.value = ''
}

async function save() {
  if (!form.name.trim() || !form.baseUrl.trim() || !form.models.length) {
    message.warning('请填写名称、Base URL，并至少勾选一个模型。')
    return
  }
  if (!form.models.includes(form.model)) {
    form.model = form.models[0] ?? ''
  }
  saving.value = true
  try {
    config.value = await call<Config>('/model-config/providers', 'post', {
      id: form.id || undefined,
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      models: form.models,
      apiKey: form.apiKey.trim() || undefined,
      contextWindow: form.contextWindow,
      maxOutputTokens: form.maxOutputTokens,
      efforts: form.efforts,
      makeDefault: form.makeDefault,
    })
    open.value = false
    message.success('已保存模型配置；新请求将使用新配置，运行中的任务保持旧配置。')
  } catch (cause) {
    message.error((cause as Error).message || '保存失败。')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  if (!form.baseUrl.trim() || !form.model.trim()) {
    message.warning('请填写 Base URL 和模型 ID。')
    return
  }
  if (!form.apiKey.trim() && !form.id) {
    message.warning('请填写 API Key。')
    return
  }
  testing.value = true
  try {
    const result = await call<{ ok: boolean; message: string }>('/model-config/test', 'post', {
      id: form.id || undefined,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      apiKey: form.apiKey.trim() || undefined,
    })
    if (result.ok) {
      message.success(result.message)
    } else {
      message.error(result.message)
    }
  } catch (cause) {
    message.error((cause as Error).message || '测试连接失败。')
  } finally {
    testing.value = false
  }
}

async function setDefault(provider: Provider) {
  try {
    config.value = await call<Config>(`/model-config/providers/${provider.id}/default`, 'post')
    message.success('已切换默认模型；新请求将使用新配置。')
  } catch (cause) {
    message.error((cause as Error).message || '切换默认模型失败。')
  }
}

function confirmDelete(provider: Provider) {
  Modal.confirm({
    title: `删除 ${provider.name}`,
    content: '删除后该模型的 Key 会从 .env 中移除，此操作不可撤销。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        config.value = await call<Config>(`/model-config/providers/${provider.id}`, 'delete')
        message.success('已删除模型。')
      } catch (cause) {
        message.error((cause as Error).message || '删除失败。')
      }
    },
  })
}

onMounted(load)
</script>

<template>
  <div class="settings-page">
    <aside class="settings-sidebar">
      <h2 class="sidebar-title">
        <SettingOutlined class="title-icon" />
        设置
      </h2>
      <nav class="sidebar-nav">
        <button class="nav-item" @click="router.push('/user/settings')">
          <UserOutlined class="nav-icon" />
          个人设置
        </button>
        <button class="nav-item" @click="router.push('/user/appearance')">
          <SkinOutlined class="nav-icon" />
          外观设置
        </button>
        <button class="nav-item active">
          <ApiOutlined class="nav-icon" />
          模型
        </button>
      </nav>
    </aside>

    <main class="models-workbench">
      <section class="model-panel">
        <h3 class="panel-title">模型</h3>

        <div v-if="loading" class="loading-text">正在读取模型配置…</div>

        <div v-else class="provider-list">
          <div v-if="!config.providers.length" class="empty-providers">
            还没有配置模型。添加你自己的提供商并保存后即可使用。
          </div>
          <div
            v-for="provider in config.providers"
            :key="provider.id"
            :class="['provider-card', { 'is-default': provider.isDefault }]"
          >
            <div class="provider-main">
              <div class="provider-title">
                <span :class="['status-dot', { ok: provider.configured }]" />
                <strong>{{ provider.name }}</strong>
              </div>
            </div>
            <div class="provider-actions">
              <AButton size="small" @click="openEdit(provider)">
                {{ provider.configured ? '编辑' : '填写 Key' }}
              </AButton>
              <AButton v-if="!provider.isDefault" size="small" @click="setDefault(provider)">
                设为默认
              </AButton>
              <AButton
                v-if="!provider.isDefault || config.providers.length === 1"
                size="small"
                danger
                @click="confirmDelete(provider)"
              >
                删除
              </AButton>
            </div>
          </div>

          <button class="add-provider" @click="openCreate">
            <PlusOutlined />
            添加模型提供商
          </button>
        </div>
      </section>
    </main>

    <Modal
      v-model:open="open"
      :title="editing ? '编辑模型提供商' : '添加模型提供商'"
      width="560px"
      :mask-closable="false"
      destroy-on-close
    >
      <AForm layout="vertical" class="provider-form">
        <div v-if="!editing" class="preset-row">
          <span class="preset-label">常用预设</span>
          <AButton size="small" @click="applyPreset('deepseek')">DeepSeek 官方</AButton>
          <AButton size="small" @click="applyPreset('opencode-go')">OpenCode Go</AButton>
        </div>
        <AFormItem label="名称">
          <AInput v-model:value="form.name" placeholder="例如 DeepSeek 官方" />
        </AFormItem>
        <AFormItem label="Base URL">
          <AInput v-model:value="form.baseUrl" placeholder="https://api.example.com/v1" />
        </AFormItem>
        <AFormItem label="模型">
          <div class="model-toolbar">
            <AButton size="small" :loading="fetchingModels" @click="fetchModels">一键获取模型列表</AButton>
            <span class="model-hint">填写 Key 后获取，按需勾选</span>
          </div>
          <ACheckboxGroup v-if="availableModels.length" v-model:value="form.models" class="model-checkboxes">
            <ACheckbox v-for="model in availableModels" :key="model" :value="model">
              {{ model }}
            </ACheckbox>
          </ACheckboxGroup>
          <div v-else class="model-empty">还没有模型，先获取或手动添加。</div>
          <div class="manual-model">
            <AInput v-model:value="manualModel" placeholder="手动添加模型 ID" @press-enter="addManualModel" />
            <AButton size="small" @click="addManualModel">添加</AButton>
          </div>
        </AFormItem>
        <AFormItem v-if="form.models.length" label="默认模型">
          <ASelect
            v-model:value="form.model"
            :options="form.models.map((model) => ({ label: model, value: model }))"
          />
        </AFormItem>
        <AFormItem label="API Key">
          <AInputPassword
            v-model:value="form.apiKey"
            :placeholder="editing?.configured ? '已配置，留空保持不变' : '填写提供商的 API Key'"
          />
        </AFormItem>
        <div class="form-grid">
          <AFormItem label="上下文窗口">
            <AInputNumber v-model:value="form.contextWindow" :min="1024" :max="100000000" style="width: 100%" />
          </AFormItem>
          <AFormItem label="最大输出">
            <AInputNumber v-model:value="form.maxOutputTokens" :min="1" :max="1000000" style="width: 100%" />
          </AFormItem>
        </div>
        <AFormItem label="思考力度">
          <ACheckboxGroup v-model:value="form.efforts" :options="['low', 'medium', 'high', 'xhigh', 'max']" />
        </AFormItem>
        <AFormItem v-if="!editing?.isDefault" label="设为默认模型">
          <AButton
            size="small"
            :type="form.makeDefault ? 'primary' : 'default'"
            @click="form.makeDefault = !form.makeDefault"
          >
            {{ form.makeDefault ? '将设为默认' : '设为默认' }}
          </AButton>
        </AFormItem>
      </AForm>

      <template #footer>
        <div class="modal-footer">
          <AButton :loading="testing" @click="testConnection">测试连接</AButton>
          <div class="footer-right">
            <AButton @click="open = false">取消</AButton>
            <AButton type="primary" :loading="saving" @click="save">保存</AButton>
          </div>
        </div>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.settings-page {
  display: flex;
  gap: 32px;
  max-width: 1000px;
  margin: 0 auto;
  min-height: 520px;
}

.settings-sidebar {
  flex-shrink: 0;
  width: 220px;
  background: var(--bg-card, #fff);
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
}

.sidebar-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 20px;
  font-size: 18px;
  font-weight: 600;
}

.title-icon {
  color: var(--color-primary, #c17f59);
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 12px 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.nav-item:hover {
  background: rgba(193, 127, 89, 0.08);
  color: var(--text-base, #1a1a1a);
}

.nav-item.active {
  background: rgba(193, 127, 89, 0.14);
  color: #c17f59;
  font-weight: 500;
}

.nav-icon {
  font-size: 16px;
  opacity: 0.85;
}

.models-workbench {
  flex: 1;
  min-width: 0;
}

.model-panel {
  background: var(--bg-card, #fff);
  border-radius: 12px;
  padding: 28px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
}

.panel-title {
  margin: 0 0 18px;
  font-size: 20px;
}

.loading-text {
  padding: 24px 0;
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
}

.empty-providers {
  padding: 28px 20px;
  border: 1px dashed var(--border-color, rgba(0, 0, 0, 0.16));
  border-radius: 12px;
  color: var(--text-secondary, rgba(0, 0, 0, 0.6));
  text-align: center;
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.provider-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.1));
  border-radius: 12px;
  background: var(--bg-card, #fff);
}

.provider-card.is-default {
  border-color: rgba(193, 127, 89, 0.45);
}

.provider-main {
  min-width: 0;
}

.provider-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 15px;
}

.status-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #ff4d4f;
}

.status-dot.ok {
  background: #52c41a;
}

.provider-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.add-provider {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 18px;
  border: 1px dashed var(--border-color, rgba(0, 0, 0, 0.2));
  border-radius: 12px;
  background: transparent;
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
  font-size: 14px;
  cursor: pointer;
}

.add-provider:hover {
  border-color: var(--color-primary, #c17f59);
  color: var(--color-primary, #c17f59);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.preset-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.preset-label {
  color: var(--text-secondary, rgba(0, 0, 0, 0.55));
  font-size: 13px;
}

.model-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.model-hint {
  color: var(--text-secondary, rgba(0, 0, 0, 0.55));
  font-size: 12px;
}

.model-checkboxes {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 180px;
  margin-bottom: 8px;
  overflow-y: auto;
}

.model-empty {
  margin-bottom: 8px;
  color: var(--text-secondary, rgba(0, 0, 0, 0.55));
  font-size: 12px;
}

.manual-model {
  display: flex;
  gap: 8px;
}

.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer-right {
  display: flex;
  gap: 8px;
}

@media (max-width: 768px) {
  .settings-page {
    flex-direction: column;
    gap: 16px;
  }

  .settings-sidebar {
    width: auto;
  }

  .sidebar-nav {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .provider-card {
    align-items: flex-start;
    flex-direction: column;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
