<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button, Form, Input, message, Modal, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { UserChatHistoryItemVO } from '@/api'
import { chatHistoryDeleteByAppIdUsingPost, chatHistoryMyUsingPost } from '@/api'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isLogin = computed(() => !!userLoginStore.userLogin?.id)

const loading = ref(false)
const deletingAppId = ref<number | null>(null)
const tableData = ref<UserChatHistoryItemVO[]>([])
const pagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条对话记录`,
})

const searchForm = ref<{
  messageType?: string
  appId?: number
  appName?: string
}>({
  messageType: '',
  appId: undefined,
  appName: '',
})

function formatDateTime(value?: string) {
  if (!value) return '-'
  try {
    const date = new Date(value)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
      date.getDate(),
    ).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(
      date.getMinutes(),
    ).padStart(2, '0')}`
  } catch {
    return value
  }
}

function truncateMessage(text?: string, maxLen = 80) {
  if (!text) return '-'
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text
}

async function loadData() {
  if (!isLogin.value) {
    message.warning('请先登录')
    router.push('/user/login')
    return
  }
  loading.value = true
  try {
    const res = await chatHistoryMyUsingPost({
      body: {
        pageNum: pagination.value.current,
        pageSize: pagination.value.pageSize,
        messageType: searchForm.value.messageType || undefined,
        appId: searchForm.value.appId,
        appName: searchForm.value.appName || undefined,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      tableData.value = res.data.data.records || []
      pagination.value.total = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取对话列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取对话列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function handleTableChange(pag: any) {
  pagination.value.current = pag.current
  pagination.value.pageSize = pag.pageSize
  void loadData()
}

function handleSearch() {
  pagination.value.current = 1
  const raw = searchForm.value.appName?.trim() || ''
  if (/^\d+$/.test(raw)) {
    searchForm.value.appId = parseInt(raw, 10)
    searchForm.value.appName = undefined
  } else {
    searchForm.value.appId = undefined
    searchForm.value.appName = raw || undefined
  }
  void loadData()
}

function resetSearch() {
  searchForm.value = {
    messageType: '',
    appId: undefined,
    appName: '',
  }
  pagination.value.current = 1
  void loadData()
}

function goAppChat(record: UserChatHistoryItemVO) {
  if (record.appId != null) {
    router.push({ name: 'app-chat', params: { id: String(record.appId) } })
  }
}

function handleDelete(record: UserChatHistoryItemVO) {
  const appId = record.appId
  if (appId == null) return
  Modal.confirm({
    title: '确认删除',
    content: `确定删除「${record.appName || '未知应用'}」的全部对话记录？删除后不可恢复。`,
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      deletingAppId.value = appId
      try {
        const res = await chatHistoryDeleteByAppIdUsingPost({ params: { appId } })
        if (res.data.code === 0 || res.data.code === 20000) {
          message.success('对话记录已删除')
          void loadData()
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch (e) {
        console.error(e)
        message.error('删除失败，请稍后重试')
      } finally {
        deletingAppId.value = null
      }
    },
  })
}

const columns: ColumnsType<UserChatHistoryItemVO> = [
  {
    title: '消息类型',
    dataIndex: 'messageType',
    key: 'messageType',
    width: 110,
    align: 'center',
    customRender: ({ text }: { text?: string }) => {
      const val = text || '-'
      const color = val.toLowerCase() === 'user' ? 'blue' : 'green'
      return h(Tag, { color }, () => val)
    },
  },
  {
    title: '应用 ID',
    dataIndex: 'appId',
    key: 'appId',
    width: 110,
    align: 'center',
  },
  {
    title: '应用名称',
    dataIndex: 'appName',
    key: 'appName',
    width: 180,
    ellipsis: true,
  },
  {
    title: '消息内容',
    dataIndex: 'message',
    key: 'message',
    width: 350,
    customRender: ({ text, record }: { text?: string; record: UserChatHistoryItemVO }) => {
      const type = (record.messageType || '').toLowerCase()

      // 错误消息
      if (type === 'error') {
        return h('span', { style: 'color: #999;' }, '消息出错啦')
      }

      // AI 消息：摘要 + NL 两行
      if (type === 'ai') {
        const summary = record.summaryText || 'nothing'
        const nl = record.naturalLanguage
          ? record.naturalLanguage.length > 80
            ? record.naturalLanguage.slice(0, 80) + '…'
            : record.naturalLanguage
          : 'nothing'
        return h('div', { style: 'line-height: 1.8;' }, [
          h('div', { style: 'font-size: 12px; color: #999; margin-bottom: 2px;' }, summary),
          h('div', { style: 'color: #666; font-size: 13px;' }, nl),
        ])
      }

      // 用户消息：80 字截断
      return h('span', truncateMessage(text, 80))
    },
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 170,
    customRender: ({ text }: { text?: string }) => formatDateTime(text),
  },
  {
    title: '上次修改时间',
    dataIndex: 'updateTime',
    key: 'updateTime',
    width: 170,
    customRender: ({ text }: { text?: string }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    fixed: 'right',
    width: 210,
    align: 'center',
    customRender: ({ record }: { record: UserChatHistoryItemVO }) => {
      const isDeleting = deletingAppId.value === record.appId
      return h('div', { style: 'display: flex; gap: 8px; justify-content: center;' }, [
        h(
          Button,
          {
            size: 'small',
            type: 'primary',
            onClick: () => goAppChat(record),
          },
          () => '查看对话',
        ),
        h(
          Button,
          {
            size: 'small',
            danger: true,
            loading: isDeleting,
            disabled: isDeleting,
            onClick: () => handleDelete(record),
          },
          () => '删除对话',
        ),
      ])
    },
  },
]

onMounted(async () => {
  await userLoginStore.fetchLoginUser()
  await loadData()
})
</script>

<template>
  <main class="user-chat-container">
    <div class="user-chat-bg" aria-hidden="true" />
    <div class="user-chat-wrapper">
      <div class="user-chat-header">
        <h1 class="user-chat-title">查看历史</h1>
      </div>

      <section class="search-bar">
        <Form layout="inline">
          <Form.Item label="消息类型">
            <Input
              v-model:value="searchForm.messageType"
              placeholder="user / assistant"
              allow-clear
              style="width: 140px"
            />
          </Form.Item>
          <Form.Item label="应用 ID/名称">
            <Input
              v-model:value="searchForm.appName"
              placeholder="数字 ID 或名称关键字"
              allow-clear
              style="width: 160px"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" @click="handleSearch">查询</Button>
              <Button @click="resetSearch">重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </section>

      <div class="table-wrapper">
        <Table
          :columns="columns"
          :data-source="tableData"
          :loading="loading"
          :pagination="pagination"
          :scroll="{ x: 1400 }"
          row-key="id"
          @change="handleTableChange"
        />
      </div>
    </div>
  </main>
</template>

<style scoped>
.user-chat-container {
  position: relative;
  min-height: calc(100vh - 64px - 48px);
  padding: 40px 20px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.user-chat-bg {
  position: fixed;
  top: 64px;
  left: 0;
  right: 0;
  bottom: 48px;
  z-index: 0;
  background-image: url('@/picture/admin/image.png');
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  opacity: 0.65;
}

.user-chat-wrapper {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1600px;
  background: var(--bg-card, rgba(255, 255, 255, 0.72));
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 28px 32px 36px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.35);
  transition: box-shadow 0.2s ease;
}

.user-chat-header {
  text-align: center;
  margin-bottom: 20px;
}

.user-chat-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-base, #0f172a);
  margin: 0;
  letter-spacing: -0.3px;
}

.user-chat-subtitle {
  margin: 0;
  color: var(--text-secondary, #666);
}

.search-bar {
  margin-bottom: 20px;
}

.search-bar :deep(.ant-form-item) {
  margin-bottom: 0;
}

.search-bar :deep(.ant-input) {
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.search-bar :deep(.ant-input:focus) {
  border-color: #4096ff;
  box-shadow: 0 0 0 2px rgba(64, 150, 255, 0.1);
}

.table-wrapper {
  overflow: hidden;
}

.table-wrapper :deep(.ant-table-wrapper) {
  border-radius: 12px;
  overflow: hidden;
}

.table-wrapper :deep(.ant-table) {
  border-radius: 12px;
  background: transparent;
}

.table-wrapper :deep(.ant-table-thead > tr > th) {
  background: var(--bg-soft, rgba(250, 250, 252, 0.85));
  font-weight: 600;
  font-size: 13px;
  color: var(--text-secondary, #475569);
  border-bottom: 1px solid var(--border-color, #e9edf4);
  padding: 12px 16px;
}

.table-wrapper :deep(.ant-table-tbody > tr > td) {
  border-bottom: 1px solid var(--border-color, #f0f2f5);
  padding: 14px 16px;
  transition: background 0.15s ease;
}

.table-wrapper :deep(.ant-table-tbody > tr:hover > td) {
  background: var(--bg-soft, rgba(240, 244, 248, 0.6));
}

.table-wrapper :deep(.ant-table-tbody > tr:last-child > td) {
  border-bottom: none;
}

.table-wrapper :deep(.ant-table-pagination) {
  margin: 16px 0 4px !important;
}

.table-wrapper :deep(.ant-btn-primary) {
  box-shadow: none;
}

.table-wrapper :deep(.ant-btn-dangerous) {
  font-size: 13px;
}

@media (max-width: 1200px) {
  .user-chat-wrapper {
    padding: 24px 16px 32px;
  }
}

@media (max-width: 768px) {
  .user-chat-container {
    padding: 20px 10px;
    min-height: calc(100vh - 56px - 48px);
  }

  .user-chat-bg {
    top: 56px;
  }

  .user-chat-wrapper {
    padding: 20px 12px 28px;
  }

  .search-bar :deep(.ant-form) {
    flex-wrap: wrap;
    gap: 12px;
  }
}
</style>
