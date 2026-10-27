<template>
  <div class="loop-market">
    <div class="loop-market-header">
      <div class="loop-market-header-left">
        <h1>Loop 市场</h1>
        <p>发现精选与公开 Loop，为你的应用注入强大技能</p>
      </div>
      <a-button type="primary" class="create-loop-btn" @click="goCreate">
        <template #icon>
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <line
              x1="7"
              y1="1"
              x2="7"
              y2="13"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
            <line
              x1="1"
              y1="7"
              x2="13"
              y2="7"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
          </svg>
        </template>
        创作我的 Loop
      </a-button>
    </div>

    <a-tabs v-model:activeKey="activeTab" class="loop-tabs">
      <a-tab-pane key="good" tab="精选 Loop">
        <div v-if="goodLoading" class="loop-loading">
          <a-spin />
        </div>
        <div v-else-if="goodList.length === 0" class="loop-empty">
          <a-empty description="暂无精选 Loop">
            <template #image>
              <svg width="120" height="120" viewBox="0 0 120 120" fill="none">
                <rect
                  x="20"
                  y="30"
                  width="80"
                  height="60"
                  rx="8"
                  stroke="currentColor"
                  stroke-width="2"
                  fill="none"
                  opacity="0.3"
                />
                <circle
                  cx="60"
                  cy="55"
                  r="12"
                  stroke="currentColor"
                  stroke-width="2"
                  fill="none"
                  opacity="0.3"
                />
                <line
                  x1="42"
                  y1="78"
                  x2="78"
                  y2="78"
                  stroke="currentColor"
                  stroke-width="2"
                  opacity="0.2"
                />
              </svg>
            </template>
          </a-empty>
        </div>
        <div v-else class="loop-grid">
          <div
            v-for="loop in goodList"
            :key="loop.id"
            class="loop-card"
            @click="viewDetail(loop.id)"
          >
            <div class="loop-card-icon">
              <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
                <circle
                  cx="24"
                  cy="24"
                  r="20"
                  stroke="var(--primary-color)"
                  stroke-width="1.5"
                  fill="none"
                  opacity="0.3"
                />
                <path
                  d="M18 28l6-8 6 8"
                  stroke="var(--primary-color)"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  fill="none"
                />
                <circle cx="24" cy="20" r="2" fill="var(--primary-color)" opacity="0.6" />
              </svg>
            </div>
            <div class="loop-card-body">
              <h3 class="loop-card-title">{{ loop.loopName }}</h3>
              <p class="loop-card-desc">{{ loop.description || '暂无描述' }}</p>
              <div class="loop-card-meta">
                <span class="loop-card-author">
                  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                    <circle
                      cx="6"
                      cy="4"
                      r="2.5"
                      stroke="currentColor"
                      stroke-width="1"
                      fill="none"
                    />
                    <path
                      d="M2 11c0-2.5 1.79-4.5 4-4.5s4 2 4 4.5"
                      stroke="currentColor"
                      stroke-width="1"
                      fill="none"
                    />
                  </svg>
                  用户 {{ loop.userId }}
                </span>
                <span v-if="loop.priority >= 99" class="loop-card-badge">精选</span>
              </div>
            </div>
            <div class="loop-card-actions">
              <a-button type="primary" size="small" ghost @click.stop="importToMyLoop(loop.id)">
                导入到我的 Loop
              </a-button>
              <a-button size="small" @click.stop="viewDetail(loop.id)">详情</a-button>
            </div>
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="explore" tab="探索">
        <div class="explore-toolbar">
          <a-input-search
            v-model:value="exploreSearchText"
            placeholder="搜索公开 Loop..."
            allow-clear
            @search="loadExplore"
            @press-enter="loadExplore"
          />
        </div>
        <div v-if="exploreLoading" class="loop-loading">
          <a-spin />
        </div>
        <div v-else-if="exploreList.length === 0" class="loop-empty">
          <a-empty :description="exploreSearchText ? '未找到匹配的 Loop' : '暂无公开 Loop'" />
        </div>
        <div v-else class="loop-grid">
          <div
            v-for="loop in exploreList"
            :key="loop.id"
            class="loop-card"
            @click="viewDetail(loop.id)"
          >
            <div class="loop-card-icon">
              <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
                <circle
                  cx="24"
                  cy="24"
                  r="20"
                  stroke="var(--primary-color)"
                  stroke-width="1.5"
                  fill="none"
                  opacity="0.3"
                />
                <path
                  d="M18 28l6-8 6 8"
                  stroke="var(--primary-color)"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  fill="none"
                />
                <circle cx="24" cy="20" r="2" fill="var(--primary-color)" opacity="0.6" />
              </svg>
            </div>
            <div class="loop-card-body">
              <h3 class="loop-card-title">{{ loop.loopName }}</h3>
              <p class="loop-card-desc">{{ loop.description || '暂无描述' }}</p>
              <div class="loop-card-meta">
                <span class="loop-card-tag">{{ loop.visibility }}</span>
                <span class="loop-card-tag loop-card-tag-source">{{ loop.sourceType }}</span>
              </div>
            </div>
            <div class="loop-card-actions">
              <a-button type="primary" size="small" ghost @click.stop="importToMyLoop(loop.id)">
                导入到我的 Loop
              </a-button>
              <a-button size="small" @click.stop="viewDetail(loop.id)">详情</a-button>
            </div>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  Button as AButton,
  Empty as AEmpty,
  InputSearch as AInputSearch,
  Spin as ASpin,
  TabPane as ATabPane,
  Tabs as ATabs,
  message,
} from 'ant-design-vue'
import {
  loopGoodListPageVoUsingPost,
  loopMarketImportUsingPost,
  loopPublicListPageVoUsingPost,
} from '@/api/loopController'

const router = useRouter()

// Tab 切换
const activeTab = ref('good')

// 精选列表
const goodList = ref<any[]>([])
const goodLoading = ref(false)

// 探索列表
const exploreList = ref<any[]>([])
const exploreLoading = ref(false)
const exploreSearchText = ref('')

// 跳转创作页
function goCreate() {
  router.push('/loop/create')
}

// 加载精选 Loop
const loadGood = async () => {
  goodLoading.value = true
  try {
    const res = await loopGoodListPageVoUsingPost({
      body: { pageNum: 1, pageSize: 20 },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      goodList.value = res.data.data || []
    } else {
      message.error(res.data.message || '加载精选 Loop 失败')
    }
  } catch (e) {
    console.error('加载精选 Loop 失败', e)
  } finally {
    goodLoading.value = false
  }
}

// 加载探索列表
const loadExplore = async () => {
  exploreLoading.value = true
  try {
    const res = await loopPublicListPageVoUsingPost({
      body: {
        pageNum: 1,
        pageSize: 20,
        searchText: exploreSearchText.value || undefined,
      },
    })
    if (res.data.code === 0 || res.data.code === 20000) {
      exploreList.value = res.data.data || []
    } else {
      message.error(res.data.message || '加载探索列表失败')
    }
  } catch (e) {
    console.error('加载探索列表失败', e)
  } finally {
    exploreLoading.value = false
  }
}

// 从市场导入到我的 Loop（不走应用绑定）
const importToMyLoop = async (loopId: number) => {
  try {
    // 调用 /loop/market/import 克隆到用户个人库
    const res = await loopMarketImportUsingPost({ params: { loopId } })
    if (res.data.code === 0 || res.data.code === 20000) {
      message.success('已导入到「我的 Loop」，可在创建应用时绑定使用')
    } else {
      message.error(res.data.message || '导入失败')
    }
  } catch (e: any) {
    if (e?.toString()?.includes('404')) {
      message.warning('导入功能暂不可用，请稍后重试')
    } else {
      message.error('导入失败，请稍后重试')
    }
    console.error('导入 Loop 失败', e)
  }
}

// 查看详情
const viewDetail = (loopId: number) => {
  router.push({ path: `/loop/${loopId}/edit` })
}

onMounted(() => {
  loadGood()
})
</script>

<style scoped>
.loop-market {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.loop-market-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 16px;
}

.loop-market-header-left h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 8px;
  color: var(--text-base, #1a1a1a);
}

.loop-market-header-left p {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

.create-loop-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.create-loop-btn svg {
  vertical-align: -2px;
}

.loop-tabs {
  margin-top: 16px;
}

.loop-loading {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.loop-empty {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

/* 卡片网格 */
.loop-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* 卡片 */
.loop-card {
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition:
    box-shadow 0.3s ease,
    transform 0.3s ease;
  display: flex;
  flex-direction: column;
}

.loop-card:hover {
  box-shadow: 0 0 12px var(--primary-color);
  transform: translateY(-2px);
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

/* 图标占位 */
.loop-card-icon {
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-mute, rgba(0, 0, 0, 0.03));
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}

/* 卡片正文 */
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

/* 元信息 */
.loop-card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary, #999);
}

.loop-card-author {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary, #999);
  font-size: 12px;
}

.loop-card-author svg {
  opacity: 0.5;
}

.loop-card-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: linear-gradient(135deg, #f59e0b, #d97706);
  color: #fff;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.5;
}

.loop-card-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--bg-mute, rgba(0, 0, 0, 0.05));
  color: var(--text-secondary, #666);
  font-size: 11px;
  line-height: 1.5;
  text-transform: lowercase;
}

.loop-card-tag-source {
  background: var(--primary-1, rgba(24, 144, 255, 0.1));
  color: var(--primary-color, #1890ff);
}

/* 操作区 */
.loop-card-actions {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--border-color, #e5e7eb);
}

/* 探索工具栏 */
.explore-toolbar {
  margin-bottom: 20px;
  max-width: 400px;
}

/* 响应式 */
@media (max-width: 768px) {
  .loop-market {
    padding: 16px;
  }

  .loop-market-header {
    flex-direction: column;
    gap: 12px;
  }

  .loop-market-header-left h1 {
    font-size: 20px;
  }

  .loop-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 375px) {
  .loop-market {
    padding: 12px;
  }

  .loop-card-actions {
    flex-direction: column;
  }
}
</style>
