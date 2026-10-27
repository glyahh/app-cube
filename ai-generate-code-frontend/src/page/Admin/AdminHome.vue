<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  Select,
  message,
  Space,
  Image,
  Tag,
} from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  userListPageVoUsingPost,
  userOpenApiDeleteUsingPost,
  userUpdateUsingPost,
} from '@/api/userController'
import type { UserVO, UserUpdateRequest } from '@/api/types'
import { UserLoginStore } from '@/stores/UserLogin'

const router = useRouter()
const route = useRoute()
const userLoginStore = UserLoginStore()

// 权限检查
const isAdmin = computed(() => {
  return userLoginStore.userLogin?.userRole === 'admin'
})

// 表格数据
const tableData = ref<UserVO[]>([])
const loading = ref(false)
const pagination = ref({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条记录`,
})

// 修改对话框
const editModalVisible = ref(false)
const editingUser = ref<UserVO | null>(null)
const editFormRef = ref()
const editForm = ref<UserUpdateRequest>({
  id: undefined,
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: '',
})

// 删除确认对话框
const deleteModalVisible = ref(false)
const deletingUserId = ref<number | null>(null)

// 搜索状态
const searchState = ref<{
  activeField: 'id' | 'userAccount' | null
  idValue: string
  userAccountValue: string
  hoverField: 'id' | 'userAccount' | null
}>({
  activeField: null,
  idValue: '',
  userAccountValue: '',
  hoverField: null,
})

// 原始数据（用于搜索过滤）
const originalTableData = ref<UserVO[]>([])

// 搜索处理函数
function handleSearch(field: 'id' | 'userAccount', value: string) {
  if (field === 'id') {
    searchState.value.idValue = value
  } else {
    searchState.value.userAccountValue = value
  }

  // 如果没有搜索值，恢复原始数据
  if (!searchState.value.idValue && !searchState.value.userAccountValue) {
    tableData.value = originalTableData.value
    return
  }

  // 执行搜索过滤
  let filtered = [...originalTableData.value]

  if (searchState.value.idValue) {
    filtered = filtered.filter((item) => String(item.id || '').includes(searchState.value.idValue))
  }

  if (searchState.value.userAccountValue) {
    filtered = filtered.filter((item) =>
      (item.userAccount || '')
        .toLowerCase()
        .includes(searchState.value.userAccountValue.toLowerCase()),
    )
  }

  tableData.value = filtered
}

// 激活搜索
function activateSearch(field: 'id' | 'userAccount') {
  searchState.value.activeField = field
  if (field === 'id') {
    searchState.value.idValue = ''
  } else {
    searchState.value.userAccountValue = ''
  }
}

// 取消搜索
function deactivateSearch() {
  if (!searchState.value.idValue && !searchState.value.userAccountValue) {
    searchState.value.activeField = null
    searchState.value.hoverField = null
    tableData.value = originalTableData.value
  } else {
    // 如果另一个字段有值，切换到那个字段的激活状态
    if (searchState.value.idValue && !searchState.value.userAccountValue) {
      searchState.value.activeField = 'id'
    } else if (searchState.value.userAccountValue && !searchState.value.idValue) {
      searchState.value.activeField = 'userAccount'
    }
  }
}

// 格式化时间
function formatDateTime(dateTimeStr?: string): string {
  if (!dateTimeStr) return '-'
  const date = new Date(dateTimeStr)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 获取用户角色显示文本
function getRoleText(role?: string): string {
  return role === 'admin' ? '管理员' : '普通用户'
}

// 获取用户角色标签颜色
function getRoleTagColor(role?: string): string {
  return role === 'admin' ? 'red' : 'blue'
}

// 加载用户列表
async function loadUserList() {
  if (!isAdmin.value) {
    message.error('您没有权限访问此页面')
    router.push('/')
    return
  }

  loading.value = true
  try {
    const res = await userListPageVoUsingPost({
      body: {
        pageNum: pagination.value.current,
        pageSize: pagination.value.pageSize,
      },
    })

    if (res.data.code === 0 || res.data.code === 20000) {
      if (res.data.data) {
        originalTableData.value = res.data.data.records || []
        // 如果有搜索条件，应用过滤
        if (searchState.value.idValue || searchState.value.userAccountValue) {
          handleSearch('id', searchState.value.idValue)
        } else {
          tableData.value = originalTableData.value
        }
        pagination.value.total = res.data.data.totalRow || 0
      }
    } else {
      message.error(res.data.message || '获取用户列表失败')
    }
  } catch (error) {
    console.error('加载用户列表失败:', error)
    message.error('加载用户列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

// 处理分页变化
function handleTableChange(pag: any) {
  pagination.value.current = pag.current
  pagination.value.pageSize = pag.pageSize
  loadUserList()
}

// 根据路由 query 一键定位用户（来自“用户请求”页）
function applyQuickLocateFromRoute() {
  const userIdFromQuery = route.query.userId as string | undefined
  if (userIdFromQuery) {
    searchState.value.activeField = 'id'
    searchState.value.idValue = userIdFromQuery
    handleSearch('id', userIdFromQuery)
  }
}

// 打开修改对话框
function openEditModal(user: UserVO) {
  editingUser.value = user
  editForm.value = {
    id: user.id,
    userName: user.userName || '',
    userAvatar: user.userAvatar || '',
    userProfile: user.userProfile || '',
    userRole: user.userRole || '',
  }
  editModalVisible.value = true
}

// 关闭修改对话框
function closeEditModal() {
  editModalVisible.value = false
  editingUser.value = null
  editForm.value = {
    id: undefined,
    userName: '',
    userAvatar: '',
    userProfile: '',
    userRole: '',
  }
  editFormRef.value?.resetFields()
}

// 提交修改
async function handleEditSubmit() {
  try {
    await editFormRef.value?.validate()

    loading.value = true
    const res = await userUpdateUsingPost({
      body: editForm.value,
    })

    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('修改成功')
      closeEditModal()
      await loadUserList()
    } else {
      message.error(res.data.message || '修改失败')
    }
  } catch (error: any) {
    if (error && typeof error === 'object' && 'errorFields' in error) {
      // 表单验证错误
      return
    }
    console.error('修改用户失败:', error)
    message.error('修改用户失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

// 打开删除确认对话框
function openDeleteModal(user: UserVO) {
  deletingUserId.value = user.id || null
  deleteModalVisible.value = true
}

// 关闭删除确认对话框
function closeDeleteModal() {
  deleteModalVisible.value = false
  deletingUserId.value = null
}

// 确认删除
async function handleDeleteConfirm() {
  if (!deletingUserId.value) return

  loading.value = true
  try {
    const res = await userOpenApiDeleteUsingPost({
      body: {
        id: deletingUserId.value,
      },
    })

    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('删除成功')
      closeDeleteModal()
      await loadUserList()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch (error) {
    console.error('删除用户失败:', error)
    message.error('删除用户失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

// 表格列定义
const columns: ColumnsType<UserVO> = [
  {
    title: () => {
      const isActive = searchState.value.activeField === 'id'
      const isHover = searchState.value.hoverField === 'id'

      if (isActive) {
        return h(
          'div',
          {
            class: 'search-header-active',
            style: {
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '100%',
              height: '100%',
              padding: '0 4px',
            },
          },
          [
            h(Input, {
              value: searchState.value.idValue,
              placeholder: '输入ID搜索',
              size: 'small',
              style: {
                width: '100%',
              },
              onInput: (e: Event) => {
                const value = (e.target as HTMLInputElement).value
                handleSearch('id', value)
                if (!value && !searchState.value.userAccountValue) {
                  deactivateSearch()
                }
              },
              onBlur: () => {
                if (!searchState.value.idValue && !searchState.value.userAccountValue) {
                  deactivateSearch()
                }
              },
              onPressEnter: () => {
                handleSearch('id', searchState.value.idValue)
              },
              autofocus: true,
            }),
          ],
        )
      }

      if (isHover) {
        return h(
          Button,
          {
            type: 'text',
            size: 'small',
            class: 'search-trigger-btn search-trigger-id',
            onClick: () => activateSearch('id'),
            onMouseenter: () => {
              searchState.value.hoverField = 'id'
            },
            onMouseleave: () => {
              searchState.value.hoverField = null
            },
            style: {
              width: '100%',
              height: '100%',
              border: 'none',
              background: 'transparent',
              fontWeight: 500,
              color: '#1890ff',
              transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
              padding: '4px 4px',
            },
          },
          () => 'ID搜索',
        )
      }

      return h(
        'div',
        {
          class: 'search-header-default',
          onMouseenter: () => {
            searchState.value.hoverField = 'id'
          },
          onMouseleave: () => {
            searchState.value.hoverField = null
          },
          style: {
            transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
            cursor: 'pointer',
            width: '100%',
            height: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          },
        },
        'ID',
      )
    },
    dataIndex: 'id',
    key: 'id',
    width: 80,
    align: 'center',
  },
  {
    title: () => {
      const isActive = searchState.value.activeField === 'userAccount'
      const isHover = searchState.value.hoverField === 'userAccount'

      if (isActive) {
        return h(
          'div',
          {
            class: 'search-header-active',
            style: {
              display: 'flex',
              alignItems: 'center',
              width: '100%',
              height: '100%',
              padding: '0 4px',
            },
          },
          [
            h(Input, {
              value: searchState.value.userAccountValue,
              placeholder: '输入账号搜索',
              size: 'small',
              style: {
                width: '100%',
              },
              onInput: (e: Event) => {
                const value = (e.target as HTMLInputElement).value
                handleSearch('userAccount', value)
                if (!value && !searchState.value.idValue) {
                  deactivateSearch()
                }
              },
              onBlur: () => {
                if (!searchState.value.userAccountValue && !searchState.value.idValue) {
                  deactivateSearch()
                }
              },
              onPressEnter: () => {
                handleSearch('userAccount', searchState.value.userAccountValue)
              },
              autofocus: true,
            }),
          ],
        )
      }

      if (isHover) {
        return h(
          Button,
          {
            type: 'text',
            size: 'small',
            class: 'search-trigger-btn',
            onClick: () => activateSearch('userAccount'),
            onMouseenter: () => {
              searchState.value.hoverField = 'userAccount'
            },
            onMouseleave: () => {
              searchState.value.hoverField = null
            },
            style: {
              width: '100%',
              height: '100%',
              border: 'none',
              background: 'transparent',
              fontWeight: 500,
              color: '#1890ff',
              transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
              padding: '4px 8px',
            },
          },
          () => '点击根据账号搜索',
        )
      }

      return h(
        'div',
        {
          class: 'search-header-default',
          onMouseenter: () => {
            searchState.value.hoverField = 'userAccount'
          },
          onMouseleave: () => {
            searchState.value.hoverField = null
          },
          style: {
            transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
            cursor: 'pointer',
            width: '100%',
            height: '100%',
            display: 'flex',
            alignItems: 'center',
          },
        },
        '账号',
      )
    },
    dataIndex: 'userAccount',
    key: 'userAccount',
    width: 150,
    ellipsis: true,
  },
  {
    title: '用户名',
    dataIndex: 'userName',
    key: 'userName',
    width: 150,
    ellipsis: true,
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
    key: 'userAvatar',
    width: 120,
    align: 'center',
    customRender: ({ record }: { record: UserVO }) => {
      if (record.userAvatar) {
        return h(Image, {
          src: record.userAvatar,
          alt: '头像',
          width: 80,
          height: 80,
          style: {
            objectFit: 'cover',
            borderRadius: '8px',
          },
          preview: true,
        })
      }
      return h('span', { style: { color: '#999' } }, '暂无头像')
    },
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
    key: 'userProfile',
    width: 200,
    ellipsis: {
      showTitle: false,
    },
    customRender: ({ text }: { text?: string }) => {
      return text || h('span', { style: { color: '#999' } }, '暂无简介')
    },
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
    key: 'userRole',
    width: 120,
    align: 'center',
    customRender: ({ text }: { text?: string }) => {
      return h(Tag, { color: getRoleTagColor(text) }, () => getRoleText(text))
    },
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
    customRender: ({ text }: { text?: string }) => {
      return formatDateTime(text)
    },
  },
  {
    title: '操作',
    key: 'action',
    width: 120,
    align: 'center',
    fixed: 'right',
    customRender: ({ record }: { record: UserVO }) => {
      return h(
        Space,
        {
          direction: 'vertical',
          size: 'small',
          style: { width: '100%' },
        },
        () => [
          h(
            Button,
            {
              size: 'small',
              onClick: () => openEditModal(record),
              style: {
                width: '100%',
                backgroundColor: '#FFD700',
                borderColor: '#FFD700',
                color: '#000',
              },
              class: 'edit-btn',
            },
            () => '修改',
          ),
          h(
            Button,
            {
              size: 'small',
              onClick: () => openDeleteModal(record),
              style: {
                width: '100%',
                backgroundColor: '#DC143C',
                borderColor: '#DC143C',
                color: '#fff',
              },
              class: 'delete-btn',
            },
            () => '删除',
          ),
        ],
      )
    },
  },
]

// 组件挂载时检查权限并加载数据
onMounted(async () => {
  await userLoginStore.fetchLoginUser()
  if (!isAdmin.value) {
    message.error('您没有权限访问此页面')
    router.push('/')
    return
  }
  await loadUserList()
  applyQuickLocateFromRoute()
})
</script>

<template>
  <main class="admin-home-container">
    <div class="admin-home-bg" aria-hidden="true" />
    <div class="admin-content-wrapper">
      <div class="admin-header">
        <h1 class="admin-title">用户管理</h1>
        <p class="admin-subtitle">管理系统用户信息</p>
      </div>

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

    <!-- 修改用户对话框 -->
    <Modal
      v-model:open="editModalVisible"
      title="修改用户信息"
      :confirm-loading="loading"
      @ok="handleEditSubmit"
      @cancel="closeEditModal"
      width="600px"
      :mask-closable="false"
    >
      <Form
        ref="editFormRef"
        :model="editForm"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 16 }"
        layout="horizontal"
      >
        <Form.Item
          label="用户名"
          name="userName"
          :rules="[{ required: true, message: '请输入用户名' }]"
        >
          <Input v-model:value="editForm.userName" placeholder="请输入用户名" />
        </Form.Item>

        <Form.Item label="头像URL" name="userAvatar">
          <Input v-model:value="editForm.userAvatar" placeholder="请输入头像URL" />
        </Form.Item>

        <Form.Item label="简介" name="userProfile">
          <Input.TextArea
            v-model:value="editForm.userProfile"
            placeholder="请输入用户简介"
            :rows="4"
          />
        </Form.Item>

        <Form.Item
          label="用户角色"
          name="userRole"
          :rules="[{ required: true, message: '请选择用户角色' }]"
        >
          <Select v-model:value="editForm.userRole" placeholder="请选择用户角色">
            <Select.Option value="user">普通用户</Select.Option>
            <Select.Option value="admin">管理员</Select.Option>
          </Select>
        </Form.Item>
      </Form>
    </Modal>

    <!-- 删除确认对话框 -->
    <Modal
      v-model:open="deleteModalVisible"
      title="确认删除"
      @ok="handleDeleteConfirm"
      @cancel="closeDeleteModal"
      :confirm-loading="loading"
      ok-text="确认删除"
      cancel-text="取消"
      ok-type="danger"
    >
      <p>确定要删除该用户吗？此操作不可恢复。</p>
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
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.3));
}

.admin-header {
  text-align: center;
  margin-bottom: 40px;
}

.admin-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--text-base, #1a1a1a);
  margin: 0 0 12px 0;
}

.admin-subtitle {
  font-size: 16px;
  color: var(--text-secondary, #666);
  margin: 0;
}

.table-wrapper {
  overflow: hidden;
}

:deep(.ant-table) {
  font-size: 14px;
}

:deep(.ant-table-thead > tr > th) {
  background: var(--bg-soft, #fafafa);
  font-weight: 600;
  color: var(--text-base, #1a1a1a);
  border-bottom: 2px solid var(--border-color, #e8e8e8);
}

:deep(.ant-table-tbody > tr > td) {
  border-bottom: 1px solid var(--border-color, #f0f0f0);
}

:deep(.ant-table-tbody > tr:hover > td) {
  background: var(--bg-soft, #f5f7fa);
}

:deep(.ant-pagination) {
  margin-top: 24px;
  text-align: center;
}

:deep(.edit-btn) {
  background-color: #ffd700 !important;
  border-color: #ffd700 !important;
  color: #000 !important;
  box-shadow: 0 4px 12px rgba(255, 215, 0, 0.4);
  transition: all 0.3s ease;
}

:deep(.edit-btn:hover) {
  background-color: #ffeb3b !important;
  border-color: #ffeb3b !important;
  color: #000 !important;
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(255, 215, 0, 0.6);
}

:deep(.delete-btn) {
  background-color: #dc143c !important;
  border-color: #dc143c !important;
  color: #fff !important;
  box-shadow: 0 4px 12px rgba(220, 20, 60, 0.4);
  transition: all 0.3s ease;
}

:deep(.delete-btn:hover) {
  background-color: #ff0000 !important;
  border-color: #ff0000 !important;
  color: #fff !important;
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(220, 20, 60, 0.6);
}

:deep(.ant-tag) {
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 12px;
  border: none;
}

:deep(.ant-image) {
  display: inline-block;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

/* 搜索功能样式 */
:deep(.search-header-default) {
  animation: fadeIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

:deep(.search-header-default:hover) {
  background: rgba(24, 144, 255, 0.08);
  border-radius: 4px;
}

:deep(.search-trigger-btn) {
  animation: slideIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

:deep(.search-trigger-btn:hover) {
  background: rgba(24, 144, 255, 0.12) !important;
  transform: scale(1.02);
}

:deep(.search-trigger-id) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
}

:deep(.search-header-active) {
  animation: expandIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

:deep(.search-header-active .ant-input) {
  animation: inputFadeIn 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateX(-10px) scale(0.95);
  }

  to {
    opacity: 1;
    transform: translateX(0) scale(1);
  }
}

@keyframes expandIn {
  from {
    opacity: 0;
    transform: scale(0.9);
    max-width: 0;
  }

  to {
    opacity: 1;
    transform: scale(1);
    max-width: 100%;
  }
}

@keyframes inputFadeIn {
  from {
    opacity: 0;
    width: 0;
  }

  to {
    opacity: 1;
    width: 100%;
  }
}

@media (max-width: 1200px) {
  .admin-content-wrapper {
    padding: 30px 20px;
  }

  .admin-title {
    font-size: 28px;
  }

  :deep(.ant-table) {
    font-size: 13px;
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
    padding: 20px 15px;
    border-radius: 16px;
  }

  .admin-header {
    margin-bottom: 30px;
  }

  .admin-title {
    font-size: 24px;
  }

  .admin-subtitle {
    font-size: 14px;
  }

  :deep(.ant-table) {
    font-size: 12px;
  }

  :deep(.ant-table-thead > tr > th) {
    padding: 8px 4px;
    font-size: 12px;
  }

  :deep(.ant-table-tbody > tr > td) {
    padding: 8px 4px;
    font-size: 12px;
  }

  :deep(.ant-pagination) {
    margin-top: 16px;
  }

  :deep(.ant-pagination-item),
  :deep(.ant-pagination-prev),
  :deep(.ant-pagination-next) {
    min-width: 28px;
    height: 28px;
    line-height: 26px;
  }
}

@media (max-width: 480px) {
  .admin-home-container {
    padding: 15px 8px;
  }

  .admin-content-wrapper {
    padding: 15px 10px;
    border-radius: 12px;
  }

  .admin-title {
    font-size: 20px;
  }

  .admin-subtitle {
    font-size: 12px;
  }

  :deep(.ant-table) {
    font-size: 11px;
  }

  :deep(.ant-table-thead > tr > th),
  :deep(.ant-table-tbody > tr > td) {
    padding: 6px 2px;
    font-size: 11px;
  }

  :deep(.edit-btn),
  :deep(.delete-btn) {
    font-size: 11px;
    padding: 2px 8px;
  }
}
</style>
