<template>
  <a-popover v-model:open="visible" trigger="click" placement="top">
    <template #content>
      <div v-if="loopList.length === 0" class="picker-empty">
        <p>暂无 Loop，前往市场添加</p>
        <a-button type="link" @click="goToMarket">前往 Loop 市场</a-button>
      </div>
      <div v-else class="picker-list">
        <!-- 单选模式：可取消的自定义按钮 -->
        <template v-if="singleSelect">
          <div v-for="loop in loopList" :key="loop.id" class="picker-item">
            <a-button
              size="small"
              :type="selectedSingleId === String(loop.id) ? 'primary' : 'default'"
              class="picker-radio-btn"
              @click="onSingleSelect(loop)"
            >
              {{ loop.loopName }}
            </a-button>
            <span class="picker-desc">{{ loop.description }}</span>
          </div>
          <!-- 已选中的 Loop compiledPrompt 预览 -->
          <div v-if="selectedSingleLoop" class="picker-prompt-preview">
            <div class="picker-preview-label">已选 Loop 注入内容预览</div>
            <pre class="picker-preview-text">{{
              selectedSingleLoop.compiledPrompt || '(无注入内容)'
            }}</pre>
          </div>
        </template>
        <!-- 多选模式：checkbox（默认） -->
        <template v-else>
          <a-checkbox-group v-model:value="selectedIds">
            <div v-for="loop in loopList" :key="loop.id" class="picker-item">
              <a-checkbox :value="loop.id">{{ loop.loopName }}</a-checkbox>
              <span class="picker-desc">{{ loop.description }}</span>
            </div>
          </a-checkbox-group>
        </template>
      </div>
    </template>
    <div class="loop-pill-wrap">
      <a-button
        class="loop-pill"
        :type="
          singleSelect
            ? selectedSingleId
              ? 'primary'
              : 'default'
            : selectedIds.length > 0
              ? 'primary'
              : 'default'
        "
      >
        <span class="loop-pill-text">
          Loop{{
            singleSelect
              ? selectedSingleLoop
                ? ' · 已选'
                : ''
              : selectedIds.length > 0
                ? ' · ' + selectedIds.length
                : ''
          }}
        </span>
      </a-button>
      <span v-if="showBeta" class="loop-pill-beta">beta</span>
    </div>
  </a-popover>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Checkbox as ACheckbox,
  CheckboxGroup as ACheckboxGroup,
  Popover as APopover,
  message,
} from 'ant-design-vue'
import { loopMyListPageVoUsingPost, loopGetVoUsingGet } from '@/api/loopController'

interface SelectedLoopInfo {
  id: string
  loopName: string
  compiledPrompt: string
}

const props = withDefaults(
  defineProps<{
    singleSelect?: boolean
    showBeta?: boolean
  }>(),
  {
    singleSelect: false,
    showBeta: false,
  },
)

const router = useRouter()
const visible = ref(false)
const loopList = ref<any[]>([])
const selectedIds = ref<string[]>([])
const selectedSingleId = ref<string>('')
const selectedSingleLoop = ref<SelectedLoopInfo | null>(null)
let selectionRequestId = 0

const emit = defineEmits<{
  (e: 'change', ids: string[]): void
  (e: 'singleChange', info: SelectedLoopInfo | null): void
}>()

watch(selectedIds, (val) => emit('change', val), { deep: true })

async function onSingleSelect(loop: any) {
  const loopId = String(loop.id)

  // 再次点击已选中的 Loop → 取消选择
  if (selectedSingleId.value === loopId) {
    selectionRequestId += 1
    selectedSingleId.value = ''
    selectedSingleLoop.value = null
    emit('singleChange', null)
    return
  }

  const requestId = ++selectionRequestId
  const initialSelection: SelectedLoopInfo = {
    id: loopId,
    loopName: loop.loopName,
    compiledPrompt: '',
  }
  selectedSingleId.value = loopId
  selectedSingleLoop.value = initialSelection
  emit('singleChange', initialSelection)

  // 获取完整 LoopVO 以拿到 compiledPrompt
  try {
    const res = await loopGetVoUsingGet({ params: { id: loop.id } })
    if (requestId !== selectionRequestId || selectedSingleId.value !== loopId) return

    const detailedSelection: SelectedLoopInfo = {
      ...initialSelection,
      compiledPrompt:
        (res.data.code === 0 || res.data.code === 20000) && res.data.data
          ? res.data.data.compiledPrompt || ''
          : '',
    }
    selectedSingleLoop.value = detailedSelection
    emit('singleChange', detailedSelection)
  } catch (e) {
    if (requestId !== selectionRequestId || selectedSingleId.value !== loopId) return
    console.error('获取 Loop 详情失败', e)
  }
}

function goToMarket() {
  visible.value = false
  router.push('/loop')
}

onMounted(async () => {
  try {
    const res = await loopMyListPageVoUsingPost({
      body: { pageNum: 1, pageSize: 50 },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      loopList.value = res.data.data
    }
  } catch (e) {
    console.error('加载 Loop 列表失败', e)
  }
})

defineExpose({ selectedIds, selectedSingleId, selectedSingleLoop })
</script>

<style scoped>
.picker-empty {
  text-align: center;
  padding: 16px;
  min-width: 200px;
}
.picker-list {
  max-height: 300px;
  overflow-y: auto;
  min-width: 220px;
}
.picker-item {
  padding: 6px 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.picker-radio-btn {
  font-size: 12px;
  border-radius: 8px;
}
.picker-desc {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  margin-left: 0;
  width: 100%;
  padding-left: 4px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}
.picker-prompt-preview {
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: rgba(15, 23, 42, 0.04);
  border: 1px solid rgba(15, 23, 42, 0.08);
}
.picker-preview-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary, #6b7280);
  margin-bottom: 4px;
}
.picker-preview-text {
  font-size: 11px;
  line-height: 1.5;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-base, #0f172a);
  margin: 0;
}
.loop-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 36px;
  border-radius: 999px;
  padding: 0 12px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  background: var(--bg-card, rgba(255, 255, 255, 0.68));
  color: var(--text-base, #0f172a);
  cursor: pointer;
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    border-color 0.16s ease-out;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
  font-size: 12px;
  font-weight: 600;
}
.loop-pill:hover {
  transform: translateY(-1px);
  border-color: rgba(59, 130, 246, 0.35);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.12);
}
.loop-pill-text {
  position: relative;
  z-index: 1;
}
.loop-pill-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
}
.loop-pill-beta {
  position: absolute;
  top: -6px;
  right: -6px;
  padding: 1px 5px;
  border-radius: 999px;
  font-size: 9px;
  font-weight: 800;
  line-height: 1.3;
  text-transform: uppercase;
  color: #d97706;
  background: rgba(245, 158, 11, 0.18);
  border: 1px solid rgba(245, 158, 11, 0.3);
  pointer-events: none;
}
</style>
