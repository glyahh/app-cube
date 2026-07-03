<template>
  <div class="my-loop">
    <!-- 顶栏 -->
    <div class="my-loop-header">
      <div class="my-loop-header-left">
        <h1>我的 Loop</h1>
        <p>管理你的技能集合，导入或创作专属 Loop</p>
      </div>
      <div class="my-loop-actions">
        <a-button type="primary" @click="handleCreate">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <line x1="7" y1="1" x2="7" y2="13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              <line x1="1" y1="7" x2="13" y2="7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
            </svg>
          </template>
          创建 Loop
        </a-button>
        <a-button @click="showImportModal = true">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M7 1v8M3 6l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              <line x1="2" y1="11" x2="12" y2="11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
            </svg>
          </template>
          导入 Skill
        </a-button>
      </div>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="my-loop-loading">
      <a-spin tip="加载中..." />
    </div>

    <!-- 空状态 -->
    <div v-else-if="myList.length === 0" class="my-loop-empty">
      <a-empty>
        <template #image>
          <svg width="120" height="120" viewBox="0 0 120 120" fill="none">
            <rect x="30" y="25" width="60" height="70" rx="10" stroke="currentColor" stroke-width="1.5" fill="none" opacity="0.25" />
            <line x1="45" y1="50" x2="75" y2="50" stroke="currentColor" stroke-width="1.5" opacity="0.2" />
            <line x1="45" y1="60" x2="75" y2="60" stroke="currentColor" stroke-width="1.5" opacity="0.2" />
            <line x1="45" y1="70" x2="65" y2="70" stroke="currentColor" stroke-width="1.5" opacity="0.2" />
            <circle cx="60" cy="40" r="8" stroke="var(--primary-color)" stroke-width="1.5" fill="none" opacity="0.4" />
            <line x1="60" y1="36" x2="60" y2="44" stroke="var(--primary-color)" stroke-width="1.5" opacity="0.4" />
            <line x1="56" y1="40" x2="64" y2="40" stroke="var(--primary-color)" stroke-width="1.5" opacity="0.4" />
          </svg>
        </template>
        <template #description>
          <p>暂无 Loop</p>
          <span class="empty-hint">点击上方「创建 Loop」或「导入 Skill」开始</span>
        </template>
        <a-button type="primary" @click="handleCreate">创建第一个 Loop</a-button>
      </a-empty>
    </div>

    <!-- 卡片网格 -->
    <div v-else class="loop-grid">
      <div
        v-for="loop in myList"
        :key="loop.id"
        class="loop-card"
        @click="handleEdit(loop.id)"
      >
        <!-- 卡片图标区 -->
        <div class="loop-card-icon">
          <svg width="40" height="40" viewBox="0 0 40 40" fill="none">
            <rect x="8" y="8" width="24" height="24" rx="6" stroke="var(--primary-color)" stroke-width="1.5" fill="none" opacity="0.3" />
            <path d="M14 20l4 4 8-8" stroke="var(--primary-color)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" fill="none" />
          </svg>
        </div>
        <!-- 卡片正文 -->
        <div class="loop-card-body">
          <h3 class="loop-card-title">{{ loop.loopName }}</h3>
          <p class="loop-card-desc">{{ loop.description || '暂无简介' }}</p>
          <div class="loop-card-tags">
            <a-tag :color="sourceTypeColor(loop.sourceType)" class="loop-tag">
              {{ loop.sourceType === 'imported' ? '导入' : '创作' }}
            </a-tag>
            <a-tag :color="loop.visibility === 'public' ? 'green' : 'default'" class="loop-tag">
              {{ loop.visibility === 'public' ? '公开' : '私有' }}
            </a-tag>
          </div>
        </div>
        <!-- 底部操作 -->
        <div class="loop-card-footer" @click.stop>
          <template v-if="loop.priority >= 99">
            <a-tag color="gold" class="featured-tag">已精选</a-tag>
          </template>
          <template v-else>
            <a-button type="link" size="small" @click="handleApplyGood(loop)">
              申请精选
            </a-button>
          </template>
          <a-button type="link" size="small" danger @click="handleDelete(loop)">
            删除
          </a-button>
        </div>
      </div>
    </div>

    <!-- 导入弹窗 -->
    <a-modal
      v-model:visible="showImportModal"
      title="导入 Skill / Loop"
      :confirm-loading="importing"
      ok-text="导入"
      @ok="handleImport"
    >
      <a-tabs v-model:activeKey="importTabKey" size="small">
        <a-tab-pane key="paste" tab="粘贴内容">
          <div class="import-tip">
            <p>粘贴 <code>.md</code> 或 <code>.json</code> 格式内容，自动识别并转换：</p>
            <pre class="import-example">---
name: 我的技能
description: 一个示例技能
visibility: public
---

## 角色设定
你是一个 XX 专家

## 约束与边界
- 输出简洁</pre>
          </div>
          <a-textarea
            v-model:value="importContent"
            :rows="8"
            placeholder="在此粘贴 .md 或 .json 格式内容..."
            :maxlength="10000"
            show-count
          />
        </a-tab-pane>
        <a-tab-pane key="file" tab="上传文件">
          <div class="import-tip">
            <p>支持 <code>.md</code> 和 <code>.json</code> 文件，上传后自动识别格式并转换。</p>
          </div>
          <a-upload-dragger
            :before-upload="handleFileSelect"
            accept=".md,.json"
            :show-upload-list="false"
          >
            <p class="ant-upload-drag-icon">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12" />
              </svg>
            </p>
            <p class="ant-upload-text">点击或拖拽文件到此区域上传</p>
            <p class="ant-upload-hint">支持 .md 和 .json 文件</p>
          </a-upload-dragger>
        </a-tab-pane>
      </a-tabs>
    </a-modal>

    <!-- 申请精选确认弹窗 -->
    <a-modal
      v-model:visible="showApplyModal"
      title="申请精选"
      ok-text="提交申请"
      :confirm-loading="applying"
      @ok="handleSubmitApply"
    >
      <p>申请将 <strong>{{ applyingLoop?.loopName }}</strong> 加入精选市场。</p>
      <a-textarea
        v-model:value="applyReason"
        :rows="3"
        placeholder="请简要说明申请理由（选填）"
        :maxlength="512"
        show-count
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  loopMyListPageVoUsingPost,
  loopOpenApiDeleteUsingPost,
  loopApplyUsingPost,
  loopOpenApiImportUsingPost,
} from '@/api/loopController'

const router = useRouter()

// 数据
const myList = ref<any[]>([])
const loading = ref(false)

// 导入弹窗
const showImportModal = ref(false)
const importContent = ref('')
const importing = ref(false)
const importTabKey = ref('paste')

// 申请弹窗
const showApplyModal = ref(false)
const applying = ref(false)
const applyingLoop = ref<any>(null)
const applyReason = ref('')

// 标签颜色映射
const sourceTypeColor = (type: string): string => {
  return type === 'imported' ? 'purple' : 'blue'
}

// 创建 Loop
const handleCreate = () => {
  router.push('/loop/create')
}

// 编辑 Loop
const handleEdit = (id: number) => {
  router.push(`/loop/${id}/edit`)
}

// 删除 Loop
const handleDelete = async (loop: any) => {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除「${loop.loopName}」吗？删除后不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await loopOpenApiDeleteUsingPost({ params: { id: loop.id } })
        if (res.data.code === 0 || res.data.code === 20000) {
          message.success('删除成功')
          myList.value = myList.value.filter((item: any) => item.id !== loop.id)
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch (e) {
        console.error('删除失败', e)
      }
    },
  })
}

// 打开申请精选弹窗
const handleApplyGood = (loop: any) => {
  applyingLoop.value = loop
  applyReason.value = ''
  showApplyModal.value = true
}

// 提交精选申请
const handleSubmitApply = async () => {
  if (!applyingLoop.value) return
  applying.value = true
  try {
    const res = await loopApplyUsingPost({
      params: {
        loopId: applyingLoop.value.id,
        reason: applyReason.value || undefined,
      },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('申请已提交，等待管理员审核')
      showApplyModal.value = false
    } else {
      message.error(res.data.message || '申请失败')
    }
  } catch (e) {
    console.error('申请失败', e)
  } finally {
    applying.value = false
  }
}

// 文件上传预处理（读文件内容后调后端接口）
const handleFileSelect = async (file: File): Promise<boolean> => {
  const text = await file.text()
  if (!text.trim()) {
    message.warning('文件内容为空')
    return false
  }
  importing.value = true
  try {
    const res = await loopOpenApiImportUsingPost({ body: text.trim() })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('导入成功')
      showImportModal.value = false
      await loadMyList()
    } else {
      message.error(res.data.message || '导入失败')
    }
  } catch (e) {
    console.error('文件导入失败', e)
    message.error('文件导入失败，请检查格式')
  } finally {
    importing.value = false
  }
  return false // 阻止默认上传行为
}

// 文本导入（粘贴内容）
const handleImport = async () => {
  if (!importContent.value.trim()) {
    message.warning('请粘贴要导入的内容')
    return
  }
  importing.value = true
  try {
    const res = await loopOpenApiImportUsingPost({ body: importContent.value.trim() })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('导入成功')
      showImportModal.value = false
      importContent.value = ''
      await loadMyList()
    } else {
      message.error(res.data.message || '导入失败')
    }
  } catch (e) {
    console.error('导入失败', e)
  } finally {
    importing.value = false
  }
}

// 加载列表
const loadMyList = async () => {
  loading.value = true
  try {
    const res = await loopMyListPageVoUsingPost({
      body: { pageNum: 1, pageSize: 50 },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      myList.value = res.data.data || []
    } else {
      message.error(res.data.message || '加载失败')
    }
  } catch (e) {
    console.error('加载我的 Loop 失败', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadMyList()
})
</script>

<style scoped>
.my-loop {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

/* 顶栏 */
.my-loop-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
  gap: 16px;
}

.my-loop-header-left h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--text-base, #1a1a1a);
}

.my-loop-header-left p {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

.my-loop-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.my-loop-actions .ant-btn svg {
  vertical-align: -2px;
}

/* 加载态 */
.my-loop-loading {
  display: flex;
  justify-content: center;
  padding: 80px 0;
}

/* 空状态 */
.my-loop-empty {
  display: flex;
  justify-content: center;
  padding: 48px 0;
}

.empty-hint {
  color: var(--text-secondary, #999);
  font-size: 13px;
}

/* 卡片网格 */
.loop-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}

/* 卡片 */
.loop-card {
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.3s ease, transform 0.3s ease;
  display: flex;
  flex-direction: column;
}

.loop-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
  border-color: var(--primary-color, #1890ff);
}

/* 动画降级 */
@media (prefers-reduced-motion: reduce) {
  .loop-card {
    transition: none;
  }

  .loop-card:hover {
    transform: none;
  }
}

/* 图标区 */
.loop-card-icon {
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-mute, rgba(0, 0, 0, 0.03));
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}

/* 正文 */
.loop-card-body {
  padding: 16px;
  flex: 1;
}

.loop-card-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 8px;
  color: var(--text-base, #1a1a1a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.loop-card-desc {
  font-size: 13px;
  color: var(--text-secondary, #666);
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

/* 标签 */
.loop-card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.loop-tag {
  font-size: 11px;
  line-height: 1.5;
  border-radius: 4px;
}

/* 底栏 */
.loop-card-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  padding: 10px 16px;
  border-top: 1px solid var(--border-color, #e5e7eb);
}

.featured-tag {
  font-size: 12px;
  line-height: 1.5;
}

/* 导入弹窗 */
.import-tip {
  margin-bottom: 16px;
}

.import-tip p {
  margin: 0 0 8px;
  color: var(--text-secondary, #666);
  font-size: 13px;
}

.import-tip code {
  background: var(--bg-mute, rgba(0, 0, 0, 0.05));
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}

.import-example {
  background: var(--bg-mute, rgba(0, 0, 0, 0.04));
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  color: var(--text-base, #1a1a1a);
  white-space: pre-wrap;
  word-break: break-all;
}

/* 限制上传拖拽区高度，避免超出弹窗白底 */
.my-loop :deep(.ant-upload-drag) {
  max-height: 160px;
  overflow-y: auto;
}
.my-loop :deep(.ant-upload-drag .ant-upload-drag-icon) {
  margin-bottom: 4px;
}
.my-loop :deep(.ant-upload-drag p.ant-upload-text) {
  margin-bottom: 4px;
}

/* 响应式 */
@media (max-width: 768px) {
  .my-loop {
    padding: 16px;
  }

  .my-loop-header {
    flex-direction: column;
    gap: 12px;
  }

  .my-loop-header-left h1 {
    font-size: 20px;
  }

  .my-loop-actions {
    width: 100%;
  }

  .my-loop-actions .ant-btn {
    flex: 1;
  }

  .loop-grid {
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  }
}

@media (max-width: 375px) {
  .my-loop {
    padding: 12px;
  }

  .loop-grid {
    grid-template-columns: 1fr;
  }
}
</style>
