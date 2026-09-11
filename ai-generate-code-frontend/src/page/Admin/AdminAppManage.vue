<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import { Table, Button, Modal, Form, Input, InputNumber, Tag, Space, message } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { AppVO, AppQueryRequest } from '@/api'
import { appAdminListPageVoUsingPost, appAdminOpenApiDeleteUsingPost, appAdminUpdateUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

const loading = ref(false)
const tableData = ref<AppVO[]>([])
const pagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 个应用`,
})

const searchForm = ref<Pick<AppQueryRequest, 'appName' | 'userId' | 'priority'>>({
  appName: '',
  userId: undefined,
  priority: undefined,
})

const editModalVisible = ref(false)
const editFormRef = ref()
const editForm = ref({
  id: undefined as number | undefined,
  appName: '',
  cover: '',
  priority: undefined as number | undefined,
})

const deleteModalVisible = ref(false)
const deletingId = ref<number | null>(null)

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

async function loadData() {
  if (!isAdmin.value) {
    message.error('您没有权限访问此页面')
    router.push('/')
    return
  }
  loading.value = true
  try {
    const res = await appAdminListPageVoUsingPost({
      body: {
        pageNum: pagination.value.current,
        pageSize: pagination.value.pageSize,
        appName: searchForm.value.appName || undefined,
        userId: searchForm.value.userId,
        priority: searchForm.value.priority,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      tableData.value = res.data.data.records || []
      pagination.value.total = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '获取应用列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取应用列表失败，请稍后重试')
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
    appName: '',
    userId: undefined,
    priority: undefined,
  }
  pagination.value.current = 1
  void loadData()
}

function openEditModal(record: AppVO) {
  editForm.value = {
    id: record.id,
    appName: record.appName || '',
    cover: record.cover || '',
    priority: record.priority,
  }
  editModalVisible.value = true
}

function closeEditModal() {
  editModalVisible.value = false
  editFormRef.value?.resetFields()
}

async function handleEditSubmit() {
  await editFormRef.value?.validate()
  loading.value = true
  try {
    const res = await appAdminUpdateUsingPost({
      body: {
        id: editForm.value.id,
        appName: editForm.value.appName,
        cover: editForm.value.cover || undefined,
        priority: editForm.value.priority,
      },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('保存成功')
      closeEditModal()
      await loadData()
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

function openDeleteModal(record: AppVO) {
  deletingId.value = record.id ?? null
  deleteModalVisible.value = true
}

function closeDeleteModal() {
  deleteModalVisible.value = false
  deletingId.value = null
}

async function handleDeleteConfirm() {
  if (!deletingId.value) return
  loading.value = true
  try {
    const res = await appAdminOpenApiDeleteUsingPost({
      body: { id: deletingId.value },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('删除成功')
      closeDeleteModal()
      await loadData()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch (e) {
    console.error(e)
    message.error('删除失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function handleGood(record: AppVO) {
  if (!record.id) return
  loading.value = true
  try {
    const res = await appAdminUpdateUsingPost({
      body: {
        id: record.id,
        appName: record.appName,
        cover: record.cover,
        priority: 99,
      },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('已设为精选应用')
      await loadData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch (e) {
    console.error(e)
    message.error('操作失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function goEdit(record: AppVO) {
  if (!record.id) return
  router.push({ name: 'app-edit', params: { id: record.id } })
}

const columns: ColumnsType<AppVO> = [
  {
    title: 'ID',
    dataIndex: 'id',
    key: 'id',
    width: 80,
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
    title: '创建人',
    key: 'user',
    width: 140,
    customRender: ({ record }: { record: AppVO }) => {
      const userName = record.userVO?.userName || record.userVO?.userAccount || '-'
      return h('span', userName)
    },
  },
  {
    title: '优先级',
    dataIndex: 'priority',
    key: 'priority',
    width: 90,
    align: 'center',
    customRender: ({ text }: { text?: number }) => {
      const val = text ?? 0
      if (val >= 99) {
        return h(Tag, { color: 'gold' }, () => `精选 (${val})`)
      }
      return h('span', val)
    },
  },
  {
    title: '生成类型',
    dataIndex: 'codeGenType',
    key: 'codeGenType',
    width: 120,
  },
  {
    title: '部署标识',
    dataIndex: 'deployKey',
    key: 'deployKey',
    ellipsis: true,
  },
  {
    title: '部署时间',
    dataIndex: 'deployedTime',
    key: 'deployedTime',
    width: 170,
    customRender: ({ text }: { text?: string }) => formatDateTime(text),
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
    width: 220,
    align: 'center',
    customRender: ({ record }: { record: AppVO }) =>
      h(Space, { size: 'small' }, () => [
        h(
          Button,
          {
            size: 'small',
            type: 'default',
            onClick: () => goEdit(record),
          },
          () => '编辑',
        ),
        h(
          Button,
          {
            size: 'small',
            onClick: () => openDeleteModal(record),
            danger: true,
          },
          () => '删除',
        ),
        h(
          Button,
          {
            size: 'small',
            type: 'primary',
            onClick: () => handleGood(record),
          },
          () => '精选',
        ),
      ]),
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
        <h1 class="admin-title">应用管理</h1>
        <p class="admin-subtitle">管理通过 AI 生成的所有应用</p>
      </div>

      <section class="search-bar">
        <Form layout="inline">
          <Form.Item label="应用名称">
            <Input
              v-model:value="searchForm.appName"
              placeholder="按名称模糊搜索"
              allow-clear
              style="width: 200px"
            />
          </Form.Item>
          <Form.Item label="用户 ID">
            <InputNumber
              v-model:value="searchForm.userId"
              :min="1"
              style="width: 140px"
            />
          </Form.Item>
          <Form.Item label="优先级">
            <InputNumber
              v-model:value="searchForm.priority"
              style="width: 120px"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" @click="handleSearch">
                查询
              </Button>
              <Button @click="resetSearch">
                重置
              </Button>
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
          bordered
        />
      </div>
    </div>

    <Modal
      v-model:open="editModalVisible"
      title="编辑应用信息"
      :confirm-loading="loading"
      width="600px"
      :mask-closable="false"
      @ok="handleEditSubmit"
      @cancel="closeEditModal"
    >
      <Form
        ref="editFormRef"
        :model="editForm"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 16 }"
        layout="horizontal"
      >
        <Form.Item
          label="应用名称"
          name="appName"
          :rules="[{ required: true, message: '请输入应用名称' }]"
        >
          <Input v-model:value="editForm.appName" />
        </Form.Item>
        <Form.Item label="封面 URL" name="cover">
          <Input v-model:value="editForm.cover" />
        </Form.Item>
        <Form.Item label="优先级" name="priority">
          <InputNumber
            v-model:value="editForm.priority"
            :min="0"
            style="width: 100%"
          />
        </Form.Item>
      </Form>
    </Modal>

    <Modal
      v-model:open="deleteModalVisible"
      title="确认删除"
      ok-text="确认删除"
      cancel-text="取消"
      ok-type="danger"
      :confirm-loading="loading"
      @ok="handleDeleteConfirm"
      @cancel="closeDeleteModal"
    >
      <p>确定要删除该应用吗？此操作不可恢复。</p>
    </Modal>
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
