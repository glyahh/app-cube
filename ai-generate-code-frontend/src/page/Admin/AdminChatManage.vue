<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import { Table, Form, Input, InputNumber, Tag, Space, message, Button } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { ChatHistory, ChatHistoryQueryRequest } from '@/api'
import { chatHistoryAdminUsingPost } from '@/api/chatHistoryController'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

const loading = ref(false)
const tableData = ref<ChatHistory[]>([])
const pagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条对话记录`,
})

const searchForm = ref<Pick<ChatHistoryQueryRequest, 'messageType' | 'appId' | 'userId'>>({
  messageType: '',
  appId: undefined,
  userId: undefined,
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
  if (!isAdmin.value) {
    message.error('您没有权限访问此页面')
    router.push('/')
    return
  }
  loading.value = true
  try {
    const res = await chatHistoryAdminUsingPost({
      body: {
        pageNum: pagination.value.current,
        pageSize: pagination.value.pageSize,
        messageType: searchForm.value.messageType || undefined,
        appId: searchForm.value.appId,
        userId: searchForm.value.userId,
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
  void loadData()
}

function resetSearch() {
  searchForm.value = {
    messageType: '',
    appId: undefined,
    userId: undefined,
  }
  pagination.value.current = 1
  void loadData()
}

function goAppChat(record: ChatHistory) {
  if (record.appId != null) {
    router.push({ name: 'app-chat', params: { id: String(record.appId) } })
  }
}

const columns: ColumnsType<ChatHistory> = [
  {
    title: 'ID',
    dataIndex: 'id',
    key: 'id',
    width: 100,
    align: 'center',
  },
  {
    title: '消息类型',
    dataIndex: 'messageType',
    key: 'messageType',
    width: 100,
    align: 'center',
    customRender: ({ text }: { text?: string }) => {
      const val = text || '-'
      const color = val.toLowerCase() === 'user' ? 'blue' : 'green'
      return h(Tag, { color }, () => val)
    },
  },
  {
    title: '消息内容',
    dataIndex: 'message',
    key: 'message',
    ellipsis: true,
    customRender: ({ text }: { text?: string }) => truncateMessage(text),
  },
  {
    title: '应用 ID',
    dataIndex: 'appId',
    key: 'appId',
    width: 110,
    align: 'center',
  },
  {
    title: '用户 ID',
    dataIndex: 'userId',
    key: 'userId',
    width: 110,
    align: 'center',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 170,
    customRender: ({ text }: { text?: string }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    fixed: 'right',
    width: 100,
    align: 'center',
    customRender: ({ record }: { record: ChatHistory }) =>
      h(
        Button,
        {
          size: 'small',
          type: 'primary',
          onClick: () => goAppChat(record),
        },
        () => '查看对话',
      ),
  },
]

onMounted(async () => {
  await userLoginStore.fetchLoginUser()
  await loadData()
})
</script>

<template>
  <main class="admin-home-container">
    <div class="admin-home-bg" aria-hidden="true" />
    <div class="admin-content-wrapper">
      <div class="admin-header">
        <h1 class="admin-title">对话管理</h1>
        <p class="admin-subtitle">管理所有应用的 AI 对话历史</p>
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
          <Form.Item label="应用 ID">
            <InputNumber v-model:value="searchForm.appId" :min="1" style="width: 140px" />
          </Form.Item>
          <Form.Item label="用户 ID">
            <InputNumber v-model:value="searchForm.userId" :min="1" style="width: 140px" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" @click="handleSearch"> 查询 </Button>
              <Button @click="resetSearch"> 重置 </Button>
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
          :scroll="{ x: 1200 }"
          row-key="id"
          @change="handleTableChange"
          bordered
        />
      </div>
    </div>
  </main>
</template>

<style scoped>
.admin-home-container {
  position: relative;
  min-height: calc(100vh - 64px - 48px);
  padding: 40px 20px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.admin-home-bg {
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
  opacity: 0.8;
}

.admin-content-wrapper {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1600px;
  background: var(--bg-card, rgba(255, 255, 255, 0.6));
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  padding: 32px 32px 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.3));
}

.admin-header {
  text-align: center;
  margin-bottom: 24px;
}

.admin-title {
  font-size: 30px;
  font-weight: 700;
  margin: 0 0 8px;
}

.admin-subtitle {
  margin: 0;
  color: var(--text-secondary, #666);
}

.search-bar {
  margin-bottom: 16px;
}

.table-wrapper {
  overflow: hidden;
}

@media (max-width: 1200px) {
  .admin-content-wrapper {
    padding: 24px 16px 32px;
  }
}

@media (max-width: 768px) {
  .admin-home-container {
    padding: 20px 10px;
    min-height: calc(100vh - 56px - 48px);
  }

  .admin-home-bg {
    top: 56px;
  }

  .admin-content-wrapper {
    padding: 20px 12px 28px;
  }
}
</style>
