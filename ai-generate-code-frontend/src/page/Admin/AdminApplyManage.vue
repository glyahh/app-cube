<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Button,
  Modal as AModal,
  Space as ASpace,
  Space,
  Table as ATable,
  Table,
  Tabs as ATabs,
  Tabs,
  Tag as ATag,
  Tag,
  Textarea as ATextarea,
  message,
} from 'ant-design-vue'
import type { ApplyVO } from '@/api'
import { appApplyListPendingUsingPost, appApplyAgreeUsingPost, appApplyRejectUsingPost } from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'
import {
  loopAdminApplyListUsingPost,
  loopAdminApplyApproveUsingPost,
  loopAdminApplyRejectUsingPost,
} from '@/api/loopController'

const router = useRouter()
const userLoginStore = UserLoginStore()

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

const activeTab = ref('app-apply')

const loading = ref(false)
const applies = ref<ApplyVO[]>([])
const activeIndex = ref(0)

// ===== Loop 申请 =====
const loopApplyLoading = ref(false)
const loopApplyList = ref<any[]>([])
const loopApplyPagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条`,
})

const loopApplyColumns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 80, align: 'center' as const },
  { title: 'Loop ID', dataIndex: 'loopId', key: 'loopId', width: 100, align: 'center' as const },
  { title: '用户', dataIndex: 'userId', key: 'userId', width: 100, align: 'center' as const },
  { title: '理由', dataIndex: 'applyReason', key: 'applyReason', ellipsis: true, width: 200 },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 90,
    align: 'center' as const,
    customRender: ({ text }: { text?: number }) => {
      if (text === 1) return h(Tag, { color: 'green' }, () => '已通过')
      if (text === 2) return h(Tag, { color: 'red' }, () => '已拒绝')
      return h(Tag, { color: 'orange' }, () => '待审核')
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
    title: '操作',
    key: 'action',
    width: 180,
    align: 'center' as const,
    customRender: ({ record }: { record: any }) => {
      if (record.status !== 0) return h('span', { style: { color: '#999' } }, '已处理')
      return h(Space, { size: 'small' }, () => [
        h(Button, { size: 'small', type: 'primary', onClick: () => approveLoop(record) }, () => '通过'),
        h(Button, { size: 'small', onClick: () => rejectLoop(record) }, () => '拒绝'),
      ])
    },
  },
]

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

async function loadLoopApplyList() {
  loopApplyLoading.value = true
  try {
    const res = await loopAdminApplyListUsingPost({
      body: { pageNum: loopApplyPagination.value.current, pageSize: loopApplyPagination.value.pageSize },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      loopApplyList.value = res.data.data
      loopApplyPagination.value.total = res.data.data.length || 0
    } else {
      message.error(res.data.message || '获取 Loop 申请列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取 Loop 申请列表失败，请稍后重试')
  } finally {
    loopApplyLoading.value = false
  }
}

function handleLoopApplyTableChange(pag: any) {
  loopApplyPagination.value.current = pag.current
  loopApplyPagination.value.pageSize = pag.pageSize
  void loadLoopApplyList()
}

async function approveLoop(record: any) {
  loopApplyLoading.value = true
  try {
    const res = await loopAdminApplyApproveUsingPost({ params: { applyId: record.id } })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('已通过，Loop 已上架精选')
      await loadLoopApplyList()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch (e) {
    console.error(e)
    message.error('操作失败，请稍后重试')
  } finally {
    loopApplyLoading.value = false
  }
}

async function rejectLoop(record: any) {
  loopApplyLoading.value = true
  try {
    const res = await loopAdminApplyRejectUsingPost({
      params: { applyId: record.id, reviewRemark: '管理员拒绝' },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('已拒绝该申请')
      await loadLoopApplyList()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch (e) {
    console.error(e)
    message.error('操作失败，请稍后重试')
  } finally {
    loopApplyLoading.value = false
  }
}
// ===== /Loop 申请 =====

// 拒绝弹窗
const rejectModalVisible = ref(false)
const rejectReviewRemark = ref('')
const rejectApplyReason = ref('')

const activeApply = computed<ApplyVO | null>(() => {
  if (!applies.value.length) return null
  return applies.value[activeIndex.value] ?? applies.value[0] ?? null
})

const detailLoading = ref(false)

function formatOperate(apply: ApplyVO): string {
  if (apply.operate === 2) return '申请成为管理员'
  if (apply.operate === 1) return '申请应用成为精选'
  return '其他申请'
}

function formatIdLabel(apply: ApplyVO): string {
  if (apply.operate === 2) {
    return `用户 ID：${apply.userId ?? '-'}`
  }
  return `App ID：${apply.appId ?? '-'}`
}

async function loadPending() {
  if (!isAdmin.value) {
    message.error('您没有权限访问用户请求页')
    router.push('/')
    return
  }
  loading.value = true
  try {
    const res = await appApplyListPendingUsingPost({})
    if ((res.data.code === 0 || res.data.code === 20000) && Array.isArray(res.data.data)) {
      applies.value = res.data.data
      activeIndex.value = 0
    } else {
      message.error(res.data.message || '获取申请列表失败')
    }
  } catch (e) {
    console.error(e)
    message.error('获取申请列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function selectApply(index: number) {
  activeIndex.value = index
}

async function handleDecision(type: 'approve' | 'reject') {
  const apply = activeApply.value
  if (!apply) return

  if (type === 'reject') {
    rejectReviewRemark.value = ''
    rejectApplyReason.value = apply.reason ?? ''
    rejectModalVisible.value = true
    return
  }

  AModal.confirm({
    title: '确认同意该请求？',
    content: '该操作可能会影响权限或应用展示位，请谨慎确认。',
    okText: '确认同意',
    okType: 'primary',
    cancelText: '再想想',
    async onOk() {
      detailLoading.value = true
      try {
        const res = await appApplyAgreeUsingPost({ body: { applyId: apply.applyId } })
        if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
          message.success('已同意该请求')
          await loadPending()
        } else {
          message.error(res.data.message || '操作失败')
        }
      } catch (e) {
        console.error(e)
        message.error('操作失败，请稍后重试')
      } finally {
        detailLoading.value = false
      }
    },
  })
}

function closeRejectModal() {
  rejectModalVisible.value = false
  rejectReviewRemark.value = ''
  rejectApplyReason.value = ''
}

async function submitReject() {
  const apply = activeApply.value
  if (!apply?.applyId) return
  const remark = rejectReviewRemark.value?.trim()
  if (!remark) {
    message.warning('请填写审核备注（拒绝理由）')
    return
  }
  detailLoading.value = true
  try {
    const res = await appApplyRejectUsingPost({
      body: {
        applyId: apply.applyId,
        reviewRemark: remark,
        applyReason: rejectApplyReason.value?.trim() || undefined,
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
      message.success('已拒绝该请求')
      closeRejectModal()
      await loadPending()
    } else {
      message.error(res.data.message || '拒绝失败')
    }
  } catch (e) {
    console.error(e)
    message.error('操作失败，请稍后重试')
  } finally {
    detailLoading.value = false
  }
}

function handleQuickLocate() {
  const apply = activeApply.value
  if (!apply) return

  if (apply.operate === 2 && apply.userId != null) {
    // 管理员申请：跳到用户管理并按 userId 搜索
    router.push({
      path: '/admin/users',
      query: { userId: String(apply.userId), fromApply: '1' },
    })
  } else if (apply.operate === 1 && apply.appId != null) {
    // 精选申请：跳到该应用的代码生成（对话）页面
    router.push({
      name: 'app-chat',
      params: { id: String(apply.appId) },
    })
  }
}

onMounted(() => {
  void loadPending()
  void loadLoopApplyList()
})
</script>

<template>
  <div class="apply-page-with-tabs">
    <Tabs v-model:activeKey="activeTab" type="card" class="apply-tabs">
      <Tabs.TabPane key="app-apply" tab="应用申请">
        <div class="apply-manage-page">
          <section class="left-column">
            <h2 class="column-title">待处理请求</h2>
            <p class="column-subtitle">
              点击左侧列表中的用户或应用，右侧将展示详细信息。
            </p>
            <div class="apply-list" v-loading="loading">
              <div
                v-if="!applies.length"
                class="empty-tip"
              >
                暂无待处理的用户请求。
              </div>
              <button
                v-for="(item, index) in applies"
                v-else
                :key="`${item.userId}-${item.appId}-${index}`"
                type="button"
                class="apply-item"
                :class="{ active: index === activeIndex }"
                @click="selectApply(index)"
              >
                <div class="avatar-circle">
                  <span>{{ (item.userId ?? 'U').toString().slice(-2) }}</span>
                </div>
                <div class="apply-meta">
                  <div class="apply-id">
                    {{ formatIdLabel(item) }}
                  </div>
                  <div class="apply-type">
                    {{ formatOperate(item) }}
                  </div>
                </div>
              </button>
            </div>
          </section>

          <section class="right-column" v-loading="detailLoading">
            <div v-if="activeApply" class="detail-card">
              <header class="detail-header">
                <div class="detail-title-block">
                  <div class="detail-tag">
                    {{ formatOperate(activeApply) }}
                  </div>
                  <div class="detail-ids">
                    <span>用户 ID：{{ activeApply.userId ?? '-' }}</span>
                    <span v-if="activeApply.appId">App ID：{{ activeApply.appId }}</span>
                  </div>
                </div>
              </header>

              <main class="detail-body">
                <div class="detail-section">
                  <h3 class="section-title">申请理由</h3>
                  <div class="reason-box">
                    {{ activeApply.reason || '用户未填写具体理由。' }}
                  </div>
                </div>
              </main>

              <footer class="detail-footer">
                <span class="footer-hint">请谨慎处理用户权限与应用展示相关的请求。</span>
                <div class="footer-actions">
                  <a-button @click="handleQuickLocate">
                    一键查找
                  </a-button>
                  <a-button danger @click="handleDecision('reject')">
                    拒绝
                  </a-button>
                  <a-button type="primary" @click="handleDecision('approve')">
                    同意
                  </a-button>
                </div>
              </footer>
            </div>
            <div v-else class="detail-empty">
              请选择左侧列表中的一条请求查看详情。
            </div>
          </section>

          <a-modal
            v-model:open="rejectModalVisible"
            title="拒绝该请求"
            ok-text="确认拒绝"
            cancel-text="取消"
            :confirm-loading="detailLoading"
            @ok="submitReject"
            @cancel="closeRejectModal"
          >
            <div class="reject-form">
              <div class="reject-field">
                <label class="reject-label">审核备注（拒绝理由）<span class="required">*</span></label>
                <a-textarea
                  v-model:value="rejectReviewRemark"
                  placeholder="请填写拒绝原因，将作为审核备注反馈给用户"
                  :rows="3"
                  allow-clear
                />
              </div>
              <div class="reject-field">
                <label class="reject-label">申请理由（可修改）</label>
                <a-textarea
                  v-model:value="rejectApplyReason"
                  placeholder="可在此修改用户填写的申请理由，留空则保持原样"
                  :rows="3"
                  allow-clear
                />
                <p class="reject-hint">管理员可修改用户原本的申请理由文字，修改后将保存到该条申请记录中。</p>
              </div>
            </div>
          </a-modal>
        </div>
      </Tabs.TabPane>
      <Tabs.TabPane key="loop-apply" tab="Loop 申请">
        <div class="loop-apply-section">
          <div class="loop-apply-header">
            <h2>Loop 精选申请</h2>
            <p class="loop-apply-subtitle">审核用户提交的 Loop 精选申请</p>
          </div>
          <Table
            :dataSource="loopApplyList"
            :columns="loopApplyColumns"
            :loading="loopApplyLoading"
            :pagination="loopApplyPagination"
            rowKey="id"
            bordered
            @change="handleLoopApplyTableChange"
          />
        </div>
      </Tabs.TabPane>
    </Tabs>
  </div>
</template>

<style scoped>
.apply-manage-page {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 20px;
  height: calc(100vh - 64px - 80px);
  min-height: 560px;
}

.left-column,
.right-column {
  background: var(--bg-card, #ffffff);
  border-radius: 18px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
  padding: 18px 18px 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.column-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 6px;
}

.column-subtitle {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.apply-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
}

.apply-item {
  width: 100%;
  border: none;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 12px;
  cursor: pointer;
  transition: background-color 0.15s ease, transform 0.1s ease;
}

.apply-item:hover {
  background-color: rgba(148, 163, 184, 0.18);
  transform: translateY(-1px);
}

.apply-item.active {
  background-color: rgba(59, 130, 246, 0.1);
}

.avatar-circle {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: linear-gradient(135deg, #4f46e5, #22c55e);
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

.apply-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.apply-id {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.apply-type {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.empty-tip {
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
  padding: 16px 4px;
}

.detail-card {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.detail-header {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}

.detail-title-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  background: rgba(59, 130, 246, 0.08);
  color: #1d4ed8;
  font-weight: 500;
}

.detail-ids {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 13px;
  color: var(--text-secondary, #4b5563);
}

.detail-body {
  flex: 1;
  padding: 14px 0 10px;
  overflow-y: auto;
}

.detail-section + .detail-section {
  margin-top: 12px;
}

.section-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-base, #111827);
}

.reason-box {
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--bg-soft, #f9fafb);
  border: 1px solid var(--border-color, #e5e7eb);
  font-size: 13px;
  color: var(--text-base, #374151);
  min-height: 72px;
  white-space: pre-wrap;
}

.detail-footer {
  padding-top: 10px;
  border-top: 1px solid var(--border-color, #e5e7eb);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.footer-hint {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.footer-actions {
  display: flex;
  gap: 8px;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
}

.reject-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.reject-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.reject-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-base, #374151);
}

.reject-label .required {
  color: #ef4444;
  margin-left: 2px;
}

.reject-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  line-height: 1.4;
}

@media (max-width: 960px) {
  .apply-manage-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .left-column {
    order: 1;
  }

  .right-column {
    order: 2;
  }
}

/* ===== Tabs 页面容器 ===== */
.apply-page-with-tabs {
  max-width: 1600px;
  margin: 0 auto;
  padding: 24px 20px;
}

.apply-tabs {
  background: transparent;
}

.apply-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 16px;
}

.apply-tabs :deep(.ant-tabs-tab) {
  font-size: 15px;
  padding: 8px 20px;
}

/* ===== Loop 申请 ===== */
.loop-apply-section {
  background: var(--bg-card, #fff);
  border-radius: 18px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
  padding: 24px;
}

.loop-apply-header {
  margin-bottom: 20px;
}

.loop-apply-header h2 {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 6px;
}

.loop-apply-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
}

@media (max-width: 768px) {
  .apply-page-with-tabs {
    padding: 16px 10px;
  }

  .loop-apply-section {
    padding: 16px;
  }
}
</style>
