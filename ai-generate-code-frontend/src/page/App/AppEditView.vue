<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  message,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Button as AButton,
  Card as ACard,
} from 'ant-design-vue'
import type { AppVO, AppUpdateRequest, AppAdminUpdateRequest } from '@/api'
import { appGetVoUsingGet, appAdminGetVoUsingGet, appUpdateUsingPost, appAdminUpdateUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'

const route = useRoute()
const router = useRouter()
const userLoginStore = UserLoginStore()

/**
 * 路由中的应用 ID（始终以字符串形式拿到）
 */
const appIdStr = computed(() => String(route.params.id ?? ''))

/**
 * 使用 BigInt 处理雪花 ID，保证数值运算精度
 * 注意：如果路由参数不是合法数字字符串，BigInt 构造会抛错，这里用 try/catch 兜底。
 */
const appIdBigInt = computed<bigint | null>(() => {
  try {
    return BigInt(appIdStr.value)
  } catch {
    return null
  }
})

/**
 * 发送给后端时使用的 ID（字符串形式，后端用 Long.parseLong 解析）
 */
const appIdForRequest = computed(() => appIdBigInt.value?.toString() ?? appIdStr.value)

const appInfo = ref<AppVO | null>(null)
const loading = ref(false)

const formRef = ref()
const form = ref<{
  appName: string
  cover: string
  priority: number | undefined
}>({
  appName: '',
  cover: '',
  priority: undefined,
})

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

function isSuccess(code: number | undefined): boolean {
  return code === 0 || code === 20000
}

async function loadDetail() {
  if (!appIdBigInt.value) {
    message.error('应用 ID 异常')
    router.replace('/')
    return
  }
  loading.value = true
  try {
    const fn = isAdmin.value ? appAdminGetVoUsingGet : appGetVoUsingGet
    const res = await fn({ params: { id: appIdForRequest.value } } as any)
    if (isSuccess(res.data.code) && res.data.data) {
      const data = res.data.data
      appInfo.value = data
      form.value = {
        appName: data.appName || '',
        cover: data.cover || '',
        priority: data.priority,
      }
    } else {
      message.error(res.data.message || '获取应用信息失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取应用信息失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  if (!appIdBigInt.value) return
  await formRef.value?.validate()
  loading.value = true
  try {
    const commonFields = {
      id: appIdForRequest.value as any,
      appName: form.value.appName,
      cover: form.value.cover || undefined,
    }
    const res = isAdmin.value
      ? await appAdminUpdateUsingPost({
          body: { ...commonFields, priority: form.value.priority } as AppAdminUpdateRequest,
        })
      : await appUpdateUsingPost({ body: commonFields as any as AppUpdateRequest })

    if (isSuccess(res.data.code)) {
      message.success('保存成功')
      router.back()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } catch (e) {
    console.error(e)
    message.error('保存失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadDetail()
})
</script>

<template>
  <main class="app-edit-page">
    <ACard class="edit-card" :loading="loading">
      <div class="edit-header">
        <h1>应用信息设置</h1>
        <p>
          {{
            isAdmin
              ? '管理员可修改应用名称、封面和优先级'
              : '可修改应用名称与封面（封面用于“我的应用”中的卡片展示）'
          }}
        </p>
      </div>

      <a-form ref="formRef" :model="form" layout="vertical" class="edit-form">
        <a-form-item label="应用名称" name="appName" :rules="[{ required: true, message: '请输入应用名称' }]">
          <AInput v-model:value="form.appName" placeholder="请输入应用名称" />
        </a-form-item>

        <a-form-item label="应用封面 URL" name="cover">
          <AInput
            v-model:value="form.cover"
            placeholder="可选，展示在列表和预览中的封面图，支持图片地址或渐变背景等"
          />
        </a-form-item>

        <a-form-item v-if="isAdmin" label="优先级" name="priority">
          <AInput v-model:value="(form.priority as any)" placeholder="可选，数值越大展示越靠前，精选可设置为 99" />
        </a-form-item>

        <div class="edit-actions">
          <AButton @click="router.back()">
            取消
          </AButton>
          <AButton type="primary" :loading="loading" @click="handleSubmit">
            保存
          </AButton>
        </div>
      </a-form>
    </ACard>
  </main>
</template>

<style scoped>
.app-edit-page {
  display: flex;
  justify-content: center;
}

.edit-card {
  width: 100%;
  max-width: 720px;
}

.edit-header h1 {
  margin: 0 0 8px 0;
  font-size: 22px;
  font-weight: 600;
}

.edit-header p {
  margin: 0 0 16px 0;
  color: var(--text-secondary, #6b7280);
  font-size: 13px;
}

.edit-form {
  margin-top: 8px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
