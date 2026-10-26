<template>
  <div v-if="loopList.length > 0" class="loop-inject-bar">
    <div v-if="loopList.length > 20" class="inject-search">
      <a-input v-model:value="searchText" placeholder="搜索 Loop…" size="small" allow-clear />
    </div>
    <div class="inject-radio-scroll">
      <a-radio-group v-model:value="selectedLoopId" class="inject-radio-group">
        <a-radio-button :value="0">无</a-radio-button>
        <a-radio-button v-for="loop in filteredList" :key="loop.loopId" :value="loop.loopId">
          {{ loop.loopName }}
        </a-radio-button>
      </a-radio-group>
    </div>
    <!-- 改为从「我的 Loop」选择添加，不走市场 -->
    <a-popover v-model:open="addPopoverOpen" trigger="click" placement="topRight">
      <template #content>
        <div class="my-loop-picker">
          <div v-if="myLoopList.length === 0" class="picker-empty">
            <p>暂无 Loop，先去创作或导入</p>
            <a-button type="link" size="small" @click="goCreate">去创作</a-button>
          </div>
          <div v-else class="picker-list">
            <a-checkbox-group v-model:value="selectedAddIds">
              <div v-for="loop in myLoopList" :key="loop.id" class="picker-item">
                <a-checkbox :value="loop.id">{{ loop.loopName }}</a-checkbox>
              </div>
            </a-checkbox-group>
            <a-button
              type="primary"
              size="small"
              block
              :loading="adding"
              @click="handleAddToApp"
              class="picker-add-btn"
            >
              添加到当前应用
            </a-button>
          </div>
        </div>
      </template>
      <a-button type="link" size="small" class="inject-add-btn"> + 添加 </a-button>
    </a-popover>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Checkbox as ACheckbox,
  CheckboxGroup as ACheckboxGroup,
  Input as AInput,
  Popover as APopover,
  RadioButton as ARadioButton,
  RadioGroup as ARadioGroup,
  message,
} from 'ant-design-vue'
import { loopMyListPageVoUsingPost } from '@/api/loopController'
import { appLoopBatchAddByAppId, appLoopListVoByAppId, type LongId } from '@/api/longIdApi'

const router = useRouter()

const props = defineProps<{
  appId: LongId
}>()

const emit = defineEmits<{
  (e: 'select', loopId: LongId): void
}>()

// 已有绑定
const loopList = ref<any[]>([])
const selectedLoopId = ref<LongId>(0)
const searchText = ref('')

// 「我的 Loop」添加弹窗
const addPopoverOpen = ref(false)
const myLoopList = ref<any[]>([])
const selectedAddIds = ref<LongId[]>([])
const adding = ref(false)

const filteredList = computed(() => {
  if (!searchText.value) return loopList.value
  const q = searchText.value.toLowerCase()
  return loopList.value.filter((l: any) => l.loopName?.toLowerCase().includes(q))
})

watch(selectedLoopId, (val) => emit('select', val))

function goCreate() {
  router.push('/loop/create')
}

// 从「我的 Loop」选择批量添加到当前应用
async function handleAddToApp() {
  if (selectedAddIds.value.length === 0) {
    message.warning('请至少选择一个 Loop')
    return
  }
  adding.value = true
  try {
    // 使用 bulk request 调用批量添加端点
    const res = await appLoopBatchAddByAppId(props.appId, selectedAddIds.value)
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('批量添加成功')
      addPopoverOpen.value = false
      selectedAddIds.value = []
      // 刷新列表
      const listRes = await appLoopListVoByAppId(props.appId)
      if ((listRes.data.code === 0 || listRes.data.code === 20000) && listRes.data.data) {
        loopList.value = listRes.data.data
      }
    } else {
      message.error(res.data.message || '批量添加失败')
    }
  } catch (e) {
    console.error('批量添加 Loop 失败', e)
    message.error('添加失败，请稍后重试')
  } finally {
    adding.value = false
  }
}

onMounted(async () => {
  try {
    // 加载当前应用已绑定的 Loop
    const res = await appLoopListVoByAppId(props.appId)
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      loopList.value = res.data.data
    }
    // 同时加载「我的 Loop」供添加选择
    const myRes = await loopMyListPageVoUsingPost({
      body: { pageNum: 1, pageSize: 50 },
    })
    if ((myRes.data.code === 0 || myRes.data.code === 20000) && myRes.data.data) {
      myLoopList.value = myRes.data.data
    }
  } catch (e) {
    console.error('加载 Loop 列表失败', e)
  }
})
</script>

<style scoped>
.loop-inject-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--border-color, rgba(15, 23, 42, 0.08));
  flex-wrap: wrap;
}
.inject-search {
  width: 160px;
  flex-shrink: 0;
}
.inject-radio-scroll {
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: thin;
}
.inject-radio-group {
  display: inline-flex;
  gap: 4px;
  white-space: nowrap;
}
.inject-add-btn {
  flex-shrink: 0;
}
.my-loop-picker {
  min-width: 240px;
  max-height: 360px;
  overflow-y: auto;
}
.picker-empty {
  text-align: center;
  padding: 16px;
}
.picker-list {
  padding: 4px 0;
}
.picker-item {
  padding: 4px 0;
}
.picker-add-btn {
  margin-top: 8px;
}
</style>
