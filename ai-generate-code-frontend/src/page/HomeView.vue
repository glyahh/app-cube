<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  message,
  Modal as AModal,
  Input as AInput,
  Select as ASelect,
  Button as AButton,
  Card as ACard,
  Pagination as APagination,
  Empty as AEmpty,
  Tag as ATag,
  Tooltip as ATooltip,
} from 'ant-design-vue'
import type { AppVO } from '@/api'
import {
  appAddUsingPost,
  appMyListPageVoUsingPost,
  appGoodListPageVoUsingPost,
  appDeployUsingPost,
  appUndeployUsingPost,
  appOpenApiDeleteUsingPost,
} from '@/api/appController'
import { UserLoginStore } from '@/stores/UserLogin'
import { normalizeDeployUrl } from '@/utils/deployUrl'
import AppGenPipelineBadge from '@/components/app/AppGenPipelineBadge.vue'
import { isAppWorkflowBetaFromApp, syncGenModeStorageForApp } from '@/utils/appGenPipeline'
import { useAppearanceStore } from '@/stores/appearance'
import LoopPickerTrigger from '@/components/loop/LoopPickerTrigger.vue'

const router = useRouter()
const userLoginStore = UserLoginStore()

const prompt = ref('')
const creating = ref(false)

const appearanceStore = useAppearanceStore()
const workflowBetaEnabled = ref(appearanceStore.workflowEnabled)
const workflowBetaTooltip = '该功能处于测试状态，可能不稳定'

const selectedLoopId = ref<string>('')
const selectedLoopCompiledPrompt = ref<string>('')
const loopPickerRef = ref<InstanceType<typeof LoopPickerTrigger> | null>(null)
function onLoopChange(ids: string[]) {
  // 单选模式下不使用此事件
}
function onLoopSingleChange(info: { id: string; loopName: string; compiledPrompt: string } | null) {
  if (info) {
    selectedLoopId.value = info.id
    selectedLoopCompiledPrompt.value = info.compiledPrompt || ''
    // 选中 Loop 时自动关闭工作流生成
    workflowBetaEnabled.value = false
  } else {
    selectedLoopId.value = ''
    selectedLoopCompiledPrompt.value = ''
  }
}

function handleWorkflowToggle() {
  if (selectedLoopId.value) {
    message.warning('暂不支持该功能，请先取消 Loop 选择')
    return
  }
  workflowBetaEnabled.value = !workflowBetaEnabled.value
}
const workflowAdvantageTips = ['逻辑更严谨', '图片更贴切', '代码质量检测']

const deployModalVisible = ref(false)
const deployUrl = ref('')

/**
 * 判断接口响应是否成功。
 * 证据：部分网关/序列化会把 code 变成字符串，严格 === 20000 会失败，导致下线后不刷新列表、已部署态残留。
 */
function isSuccess(code: unknown): boolean {
  if (code === null || code === undefined) return false
  const n = Number(code)
  if (Number.isNaN(n)) return false
  return n === 0 || n === 20000
}

/** 任意代码类型部署后，后端会写入 deployKey / deployedTime */
function isAppDeployed(app: AppVO | undefined): boolean {
  if (!app) return false
  const key = app.deployKey
  // 注意：deployedTime 在部分后端实现里可能作为“历史部署时间”保留，或由于 null 更新策略未落库，
  // 导致下线后刷新列表仍然带着旧的 deployedTime。为了避免首页误显示“已部署”，这里只以 deployKey 为准。
  return key !== undefined && key !== null && String(key).trim() !== ''
}

const DEFAULT_BG_OBJECT_KEY = '/default/myAppBackground/appBackground.png'

function normalizeOssAssetUrl(raw: unknown, ossOrigin: string): string {
  const input = String(raw ?? '').trim()
  if (!input) return ''
  if (input.startsWith('http://') || input.startsWith('https://')) return input
  if (input.startsWith('//')) return `${window.location.protocol}${input}`
  // 兼容旧数据：可能只存了 OSS objectKey（例如：/screenshots/xxx.jpg 或 screenshots/xxx.jpg）
  if (ossOrigin) {
    const key = input.startsWith('/') ? input.slice(1) : input
    return `${ossOrigin}/${key}`
  }
  // 兼容：可能只存了“host/path”（例如 bucket.endpoint/objectKey），无协议
  if (/^[^/]+\.[^/]+\/.+$/.test(input)) {
    try {
      return new URL(`https://${input}`).toString()
    } catch {
      // ignore
    }
  }
  // 没拿到 ossOrigin 时，兜底返回原值（避免直接报错；但可能仍无法访问）
  return input
}

const ossOrigin = computed(() => {
  // 优先使用构建时注入的 env 变量，避免首屏无 cover 数据时推断失败
  const envOrigin = (import.meta.env.VITE_OSS_ORIGIN as string | undefined)?.trim()
  if (envOrigin) return envOrigin.replace(/\/$/, '')
  // 备用：从已有 cover 数据中反推 origin
  const all = [...(goodApps.value.records ?? []), ...(myApps.value.records ?? [])]
  const cover = all.find(
    (x) => x.cover && typeof x.cover === 'string' && String(x.cover).trim() !== '',
  )?.cover
  if (!cover) return ''
  const c = String(cover).trim()
  try {
    if (c.startsWith('http://') || c.startsWith('https://')) return new URL(c).origin
    if (c.startsWith('//')) return new URL(`${window.location.protocol}${c}`).origin
    if (/^[^/]+\.[^/]+\/.+$/.test(c)) return new URL(`https://${c}`).origin
  } catch {
    // ignore
  }
  return ''
})

const defaultBgUrl = computed(() => {
  if (!ossOrigin.value) return DEFAULT_BG_OBJECT_KEY
  return `${ossOrigin.value}${DEFAULT_BG_OBJECT_KEY}`
})

function appCoverUrl(app: AppVO): string {
  return normalizeOssAssetUrl(app.cover, ossOrigin.value)
}

function cardThumbUrl(app: AppVO): string {
  // 未部署的卡片：统一走默认封面，避免空白。
  // 已部署：优先使用 cover（若存在），否则回退默认封面。
  if (!isAppDeployed(app)) return defaultBgUrl.value
  return appCoverUrl(app) || defaultBgUrl.value
}

function handleCoverImgError(e: Event) {
  const img = e.target as HTMLImageElement | null
  if (!img) return
  img.src = defaultBgUrl.value
}

function cardBgUrl(app: AppVO): string {
  const coverUrl = appCoverUrl(app)
  // 仅对“未部署”的卡片强制使用默认背景，避免出现不可访问的 cover。
  if (!isAppDeployed(app)) return defaultBgUrl.value
  return coverUrl || defaultBgUrl.value
}

const quickPrompts = [
  {
    label: '博客',
    value: '帮我做一个个人博客主页，包含文章列表和关于我页面',
  },
  {
    label: '记账',
    value: '帮我做一个简单记账工具，可以按分类统计支出',
  },
  {
    label: '待办',
    value: '帮我做一个待办任务管理网站，可以添加、勾选和删除待办事项',
  },
  {
    label: '游戏',
    value: '帮我做一个简单的网页小游戏，例如点击消除类游戏',
  },
]

type AppListState = {
  records: AppVO[]
  total: number
  pageNum: number
  pageSize: number
  loading: boolean
  keyword: string
  isWorkflow?: number
  isDeployed?: number
  codeType?: string
}

const myApps = ref<AppListState>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 20,
  loading: false,
  keyword: '',
  isWorkflow: undefined,
  isDeployed: undefined,
  codeType: undefined,
})

const goodApps = ref<AppListState>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 20,
  loading: false,
  keyword: '',
})

const isLogin = computed(() => !!userLoginStore.userLogin?.id)

function formatDateTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function buildAppNameFromPrompt(text: string): string {
  const trimmed = text.trim()
  if (!trimmed) return '我的应用'
  const firstLine = (trimmed.split('\n')[0] || trimmed).trim()
  return firstLine.slice(0, 18) || '我的应用'
}

async function handleCreateApp() {
  const content = prompt.value.trim()
  if (!content) {
    message.warning('请先输入一句话描述你要生成的应用')
    return
  }

  if (!isLogin.value) {
    message.warning('请先登录后再创建应用')
    router.push({
      path: '/user/login',
      query: { redirect: '/' },
    })
    return
  }

  creating.value = true
  try {
    // 不在此处注入 compiledPrompt 到 initPrompt，避免重复注入。
    // 注入由后端 LoopInjectService 在首次发送时通过 loopId 参数自动完成。
    const res = await appAddUsingPost({
      body: {
        appName: buildAppNameFromPrompt(content),
        initPrompt: content,
        isBeta: workflowBetaEnabled.value ? 1 : 0,
        // TODO: openapi2ts 生成后移除 as any，loopIds 字段由后端 AppAddRequest 提供
        loopIds: selectedLoopId.value ? [selectedLoopId.value] : undefined,
      } as any,
    })
    if (isSuccess(res.data.code) && res.data.data) {
      const appId = res.data.data
      message.success('应用创建成功，正在进入对话页面')
      // 添加 autoSend 标识，表示从首页创建，需要自动提交初始提示词
      const query: Record<string, string> = {
        autoSend: 'true',
        genMode: workflowBetaEnabled.value ? 'workflow' : 'legacy',
      }
      // 如果勾选了 Loop，传递 loopId 到聊天页
      if (selectedLoopId.value) {
        query.loopId = selectedLoopId.value
      }
      router.push({
        name: 'app-chat',
        params: { id: appId },
        query,
      })
    } else {
      message.error(res.data.message || '创建应用失败')
    }
  } catch (e) {
    console.error(e)
    message.error('创建应用失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

function handleQuickPrompt(item: { label: string; value: string }) {
  if (creating.value) return
  // 只填充文案，不直接创建应用，交由用户点击“开始生成”
  prompt.value = item.value
}

/** 将关键字解析为 id 或 appName 查询参数 */
function keywordToQuery(keyword: string): { id?: any; appName?: string } {
  const kw = keyword.trim()
  if (!kw) return {}
  return /^\d+$/.test(kw) ? { id: kw } : { appName: kw }
}

/** 去掉当前引用上的部署字段（下线后立刻反馈） */
function clearDeployedFieldsOnApp(app: AppVO) {
  app.deployKey = undefined
  app.deployedTime = undefined
}

/**
 * 在「我的应用」当前列表里按应用 id 清除部署字段（必须用 String 比 id，避免 Snowflake 精度丢失）。
 * 证据：loadMyApps 会整体替换 records 为新对象；仅改下线前的 app 引用无效，必须在刷新后再对当前行清一次。
 */
function clearDeployedFieldsOnAppByStableId(id: string | number | undefined | null) {
  if (id === undefined || id === null) return
  const sid = String(id)
  for (const row of myApps.value.records) {
    if (row.id != null && String(row.id) === sid) {
      row.deployKey = undefined
      row.deployedTime = undefined
      break
    }
  }
}

async function loadMyApps() {
  if (!isLogin.value) {
    myApps.value.records = []
    myApps.value.total = 0
    return
  }
  myApps.value.loading = true
  try {
    const res = await appMyListPageVoUsingPost({
      body: {
        pageNum: myApps.value.pageNum,
        pageSize: myApps.value.pageSize,
        ...keywordToQuery(myApps.value.keyword),
        isWorkflow: myApps.value.isWorkflow ?? undefined,
        isDeployed: myApps.value.isDeployed ?? undefined,
        codeGenType: myApps.value.codeType ?? undefined,
      },
    })
    if (isSuccess(res.data.code) && res.data.data) {
      myApps.value.records = res.data.data.records || []
      myApps.value.total = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '加载我的应用失败')
    }
  } catch {
    message.error('加载我的应用失败，请稍后重试')
  } finally {
    myApps.value.loading = false
  }
}

async function loadGoodApps() {
  goodApps.value.loading = true
  try {
    const res = await appGoodListPageVoUsingPost({
      body: {
        pageNum: goodApps.value.pageNum,
        pageSize: goodApps.value.pageSize,
        ...keywordToQuery(goodApps.value.keyword),
      },
    })
    if (isSuccess(res.data.code) && res.data.data) {
      goodApps.value.records = res.data.data.records || []
      goodApps.value.total = res.data.data.totalRow || 0
    } else {
      message.error(res.data.message || '加载精选应用失败')
    }
  } catch {
    message.error('加载精选应用失败，请稍后重试')
  } finally {
    goodApps.value.loading = false
  }
}

function handleMyPageChange(page: number, pageSize: number) {
  const pageSizeChanged = pageSize !== myApps.value.pageSize
  myApps.value.pageNum = pageSizeChanged ? 1 : page
  myApps.value.pageSize = pageSize
  void loadMyApps()
}

function handleGoodPageChange(page: number, pageSize: number) {
  const pageSizeChanged = pageSize !== goodApps.value.pageSize
  goodApps.value.pageNum = pageSizeChanged ? 1 : page
  goodApps.value.pageSize = pageSize
  void loadGoodApps()
}

function goChat(app: AppVO) {
  if (!app.id) return
  syncGenModeStorageForApp(app.id, app.isBeta)
  router.push({
    name: 'app-chat',
    params: { id: app.id },
    query: isAppWorkflowBetaFromApp(app) ? { genMode: 'workflow' } : {},
  })
}

function goEdit(app: AppVO) {
  if (!app.id) return
  router.push({ name: 'app-edit', params: { id: app.id } })
}

async function handleDeploy(app: AppVO) {
  if (!app.id) return
  const hide = message.loading('正在部署应用...', 0)
  try {
    const res = await appDeployUsingPost({
      body: { appId: app.id },
    })
    hide()
    if (isSuccess(res.data.code) && res.data.data) {
      // debug 证据：部署成功但无法访问时，优先在前端归一化 URL，规避”缺协议/缺端口”问题。
      deployUrl.value = normalizeDeployUrl(res.data.data)
      deployModalVisible.value = true
      message.success('部署成功')
      void loadMyApps()
    } else {
      message.error(res.data.message || '部署失败')
    }
  } catch (e) {
    hide()
    console.error(e)
    message.error('部署失败，请稍后重试')
  }
}

async function handleUndeploy(app: AppVO) {
  if (!app.id) return
  const appName = app.appName || '该应用'
  AModal.confirm({
    title: '确认下线',
    content: `是否下线部署的应用「${appName}」？`,
    okText: '下线',
    okType: 'primary',
    cancelText: '取消',
    async onOk() {
      const hide = message.loading('正在下线应用...', 0)
      try {
        const res = await appUndeployUsingPost({
          body: { appId: app.id as any },
        })
        hide()
        const undeployData: unknown = res.data?.data
        const undeployOk =
          isSuccess(res.data?.code) &&
          (undeployData === true ||
            (typeof undeployData === 'string' && undeployData.toLowerCase() === 'true'))
        if (undeployOk) {
          clearDeployedFieldsOnApp(app)
          message.success('下线成功')
          await loadMyApps()
          // 新列表行是接口新对象；再按 id 清一次，避免替换引用后仍显示已部署
          clearDeployedFieldsOnAppByStableId(app.id)
        } else {
          // 未成功下线时统一提示”好像还没有部署哦”
          message.error('好像还没有部署哦')
        }
      } catch (e) {
        hide()
        console.error(e)
        message.error('下线失败，请稍后重试')
      }
    },
  })
}

async function handleDeployCopy() {
  if (!deployUrl.value) return
  try {
    await navigator.clipboard.writeText(deployUrl.value)
    message.success('已复制访问地址到剪贴板')
  } catch (e) {
    console.error(e)
    message.error('复制失败，请手动复制')
  }
}

function handleDeployVisit() {
  if (!deployUrl.value) return
  // debug 证据：若 URL 格式异常，明确给出提示，避免用户点了没反应。
  if (!/^https?:\/\//i.test(deployUrl.value)) {
    message.error('部署地址格式异常，请复制地址后手动检查')
    return
  }
  window.open(deployUrl.value, '_blank')
}

function handleDeployModalClose() {
  deployModalVisible.value = false
}

async function handleDeleteApp(app: AppVO) {
  if (!app.id) return
  const appName = app.appName || '该应用'
  AModal.confirm({
    title: '确认删除',
    content: `确定要删除「${appName}」吗？删除后将无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const res = await appOpenApiDeleteUsingPost({
          body: { id: app.id as any },
        })
        if (isSuccess(res.data.code)) {
          message.success('删除成功')
          await loadMyApps()
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch (e) {
        console.error(e)
        message.error('删除失败，请稍后重试')
      }
    },
  })
}

// 已登录用户首屏自动加载“我的应用”，登录状态变化时自动刷新
watch(
  () => userLoginStore.userLogin?.id,
  (id) => {
    if (id) {
      myApps.value.pageNum = 1
      void loadMyApps()
    } else {
      myApps.value.records = []
      myApps.value.total = 0
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadGoodApps()
})
</script>

<template>
  <main class="home-page">
    <AModal
      v-model:open="deployModalVisible"
      title="部署成功"
      :footer="null"
      centered
      @cancel="handleDeployModalClose"
    >
      <div class="deploy-modal">
        <p class="deploy-modal-text">应用已成功部署，访问地址如下：</p>
        <AInput readonly :value="deployUrl" class="deploy-modal-input" />
        <div class="deploy-modal-actions">
          <AButton type="primary" @click="handleDeployVisit"> 一键访问 </AButton>
          <AButton @click="handleDeployCopy"> 复制地址 </AButton>
        </div>
      </div>
    </AModal>
    <section class="hero-section">
      <div class="hero-bg" />
      <div class="hero-content">
        <div class="hero-title">
          <div class="hero-main-text">
            <span class="hero-main-text-inner">Start Vibe Coding~</span>
          </div>
        </div>

        <div class="prompt-card-border">
          <div class="prompt-card-beam" aria-hidden="true" />
          <div class="prompt-card">
            <AInput.TextArea
              v-model:value="prompt"
              class="prompt-input"
              :rows="3"
              placeholder="使用应用魔方创建一个小工具，例如：旅行计划记录网站……"
            />
            <div class="prompt-footer">
              <div class="prompt-hint">支持自然语言描述，越具体效果越好</div>
              <div class="prompt-actions">
                <!-- Loop 按钮（带 beta 标志） -->
                <LoopPickerTrigger
                  ref="loopPickerRef"
                  :single-select="true"
                  :show-beta="true"
                  @single-change="onLoopSingleChange"
                />
                <!-- 工作流生成按钮（? 帮助按钮在里面，Loop 选择时禁用，去掉 beta 标志） -->
                <ATooltip
                  :title="selectedLoopId ? '已选择 Loop 时暂不支持工作流生成' : workflowBetaTooltip"
                  placement="top"
                >
                  <button
                    type="button"
                    class="workflow-beta-btn"
                    :class="{
                      'workflow-beta-btn--active': workflowBetaEnabled && !selectedLoopId,
                      'workflow-beta-btn--disabled': !!selectedLoopId,
                    }"
                    @click="handleWorkflowToggle"
                  >
                    <span class="workflow-beta-btn-icon" aria-hidden="true" />
                    <span class="workflow-beta-btn-text">工作流生成</span>
                    <span class="workflow-beta-btn-help">
                      <ATooltip placement="top" :mouseenter-delay="0.3">
                        <template #title>
                          <div class="workflow-help-tooltip">
                            <div
                              v-for="tip in workflowAdvantageTips"
                              :key="tip"
                              class="workflow-help-tooltip-item"
                            >
                              {{ tip }}
                            </div>
                          </div>
                        </template>
                        <span class="workflow-beta-help-icon">?</span>
                      </ATooltip>
                    </span>
                  </button>
                </ATooltip>
                <AButton type="primary" shape="round" :loading="creating" @click="handleCreateApp">
                  开始生成
                </AButton>
                <AButton shape="round" @click="router.push({ name: 'project-import' })"
                  >导入项目</AButton
                >
              </div>
            </div>
          </div>
        </div>
        <div class="prompt-quick-row">
          <AButton
            v-for="item in quickPrompts"
            :key="item.label"
            size="large"
            shape="round"
            class="prompt-quick-btn"
            @click="handleQuickPrompt(item)"
          >
            {{ item.label }}
          </AButton>
        </div>
      </div>
    </section>

    <section class="apps-section apps-section--featured">
      <div class="section-header">
        <h2>精选应用</h2>
      </div>

      <div class="list-toolbar">
        <div class="list-toolbar-search">
          <AInput
            v-model:value="goodApps.keyword"
            placeholder="按名称或 App ID 搜索精选应用"
            allow-clear
            @press-enter="loadGoodApps"
          />
          <AButton type="primary" ghost @click="loadGoodApps"> 搜索 </AButton>
        </div>
      </div>

      <div class="card-grid" v-loading="goodApps.loading">
        <template v-if="goodApps.records.length">
          <ACard
            v-for="app in goodApps.records"
            :key="app.id"
            class="app-card good-app-card"
            :style="{ '--card-bg-image': `url(${cardBgUrl(app)})` }"
            hoverable
            @click="goChat(app)"
          >
            <div class="app-info">
              <div class="app-name-line">
                <span class="app-name">{{ app.appName || '未命名应用' }}</span>
                <ATag color="gold" size="small">精选</ATag>
              </div>
              <div class="app-meta">
                <span>{{ app.userVO?.userName || app.userVO?.userAccount || '匿名用户' }}</span>
              </div>
            </div>
          </ACard>
        </template>
        <template v-else>
          <div class="empty-wrap">
            <AEmpty description="暂时还没有精选应用" />
          </div>
        </template>
      </div>

      <div v-if="goodApps.total > 0" class="pagination-wrap">
        <APagination
          :current="goodApps.pageNum"
          :page-size="goodApps.pageSize"
          :total="goodApps.total"
          :show-size-changer="true"
          :page-size-options="['10', '20', '40', '80']"
          @change="handleGoodPageChange"
          @show-size-change="handleGoodPageChange"
        />
      </div>
    </section>

    <section class="apps-section apps-section--mine">
      <div class="section-header">
        <h2>我的应用</h2>
      </div>

      <div v-if="!isLogin" class="section-login-tip">
        <AEmpty description="登录后即可查看你创建的所有应用" />
      </div>
      <div v-else>
        <div class="list-toolbar">
          <div class="list-toolbar-search">
            <ASelect
              v-model:value="myApps.isWorkflow"
              placeholder="生成类型"
              allow-clear
              style="width: 120px"
              @change="loadMyApps"
            >
              <ASelect.Option :value="null">全部</ASelect.Option>
              <ASelect.Option :value="0">普通生成</ASelect.Option>
              <ASelect.Option :value="1">工作流生成</ASelect.Option>
            </ASelect>
            <ASelect
              v-model:value="myApps.isDeployed"
              placeholder="部署状态"
              allow-clear
              style="width: 120px"
              @change="loadMyApps"
            >
              <ASelect.Option :value="null">全部</ASelect.Option>
              <ASelect.Option :value="0">未部署</ASelect.Option>
              <ASelect.Option :value="1">已部署</ASelect.Option>
            </ASelect>
            <ASelect
              v-model:value="myApps.codeType"
              placeholder="代码类型"
              allow-clear
              style="width: 120px"
              @change="loadMyApps"
            >
              <ASelect.Option :value="null">全部</ASelect.Option>
              <ASelect.Option value="html">HTML</ASelect.Option>
              <ASelect.Option value="multi_file">多文件</ASelect.Option>
              <ASelect.Option value="vue">Vue</ASelect.Option>
            </ASelect>
            <AInput
              v-model:value="myApps.keyword"
              placeholder="按名称或 App ID 搜索我的应用"
              allow-clear
              style="width: 180px"
              @press-enter="loadMyApps"
            />
            <AButton type="primary" ghost @click="loadMyApps"> 搜索 </AButton>
          </div>
        </div>
        <div class="card-grid" v-loading="myApps.loading">
          <template v-if="myApps.records.length">
            <ACard
              v-for="app in myApps.records"
              :key="app.id"
              :class="['app-card', 'my-app-card', { 'my-app-card--deployed': isAppDeployed(app) }]"
              hoverable
              @click="goChat(app)"
            >
              <!-- 背景图层：占满卡片，低不透明度，不阻挡交互 -->
              <div class="my-card-bg-layer" aria-hidden="true">
                <img
                  class="my-card-bg-img"
                  :src="cardBgUrl(app)"
                  alt=""
                  loading="lazy"
                  @error="(e) => ((e.target as HTMLImageElement).src = defaultBgUrl)"
                />
              </div>
              <AppGenPipelineBadge
                class="app-gen-pipeline-badge"
                size="compact"
                :is-beta="app.isBeta"
              />
              <div
                v-if="isAppDeployed(app)"
                class="app-deployed-mark app-deployed-mark--stacked"
                aria-label="已部署"
              >
                <span class="app-deployed-mark__glow" aria-hidden="true" />
                <span class="app-deployed-mark__text">已部署</span>
              </div>
              <div class="app-hover-meta">
                <div v-if="app.createTime" class="app-hover-meta-row">
                  <span class="meta-label">创建时间</span>
                  <span class="meta-value">{{ formatDateTime(app.createTime) }}</span>
                </div>
                <div v-if="app.updateTime" class="app-hover-meta-row">
                  <span class="meta-label">修改时间</span>
                  <span class="meta-value">{{ formatDateTime(app.updateTime) }}</span>
                </div>
              </div>
              <div class="my-app-card__footer">
                <div class="app-info">
                  <div class="app-name-line">
                    <span class="app-name">{{ app.appName || '未命名应用' }}</span>
                    <ATag v-if="app.priority && app.priority >= 99" color="gold" size="small">
                      精选
                    </ATag>
                  </div>
                  <div class="app-meta">
                    <div class="app-meta-type">
                      <span class="app-meta-type-label">生成类型</span>
                      <span class="app-meta-type-tag">
                        {{ app.codeGenType || 'multi_file' }}
                      </span>
                    </div>
                  </div>
                </div>
                <div class="app-actions" @click.stop>
                  <AButton size="small" type="link" danger @click="handleDeleteApp(app)">
                    删除
                  </AButton>
                  <AButton size="small" type="link" @click="goEdit(app)"> 编辑 </AButton>
                  <AButton size="small" type="link" @click="handleDeploy(app)"> 部署 </AButton>
                  <AButton size="small" type="link" @click="handleUndeploy(app)"> 下线 </AButton>
                </div>
              </div>
            </ACard>
          </template>
          <template v-else>
            <div class="empty-wrap">
              <AEmpty description="还没有创建应用，先在上方输入一句话试试吧" />
            </div>
          </template>
        </div>
        <div v-if="myApps.total > 0" class="pagination-wrap">
          <APagination
            :current="myApps.pageNum"
            :page-size="myApps.pageSize"
            :total="myApps.total"
            :show-size-changer="true"
            :page-size-options="['10', '20', '40', '80']"
            @change="handleMyPageChange"
            @show-size-change="handleMyPageChange"
          />
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  gap: 32px;
  min-height: 100vh;
  width: 100%;
  padding: 24px 0 40px;
}

.hero-section {
  position: relative;
  border-radius: 24px;
  overflow: hidden;
  padding: 40px 32px 32px;
  min-height: calc(60vh + 100px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.28;
  background:
    radial-gradient(circle at 0 0, rgba(129, 140, 248, 0.4) 0, transparent 55%),
    radial-gradient(circle at 100% 0, rgba(56, 189, 248, 0.4) 0, transparent 55%);
}

.hero-content {
  position: relative;
  z-index: 1;
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
  text-align: center;
  padding-inline: 24px;
  transform: translateY(-30px);
}

.hero-title {
  margin-bottom: 24px;
}

.hero-main-text {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-base, #0f172a);
}

.hero-main-text-inner {
  display: inline-block;
  padding-inline: 4px;
  text-shadow: 0 8px 16px rgba(148, 163, 184, 0.35);
  animation: heroFloat 3s ease-in-out infinite;
}

/* 两阶段：Phase A = 改版前 borderFlow 快速一整圈并淡出；Phase B = 窄弧霓虹 + bloom + 慢转（抗压 8s/圈） */
.prompt-card-border {
  --beam-duration: 8s;
  --beam-intro-duration: 1.15s;
  --beam-strength: 0.7;
  --beam-ring: 2px;

  margin-top: 8px;
  max-width: 780px;
  margin-left: auto;
  margin-right: auto;
  position: relative;
  isolation: isolate;
  padding: var(--beam-ring);
  border-radius: 22px;
  overflow: visible;
  background: transparent;
}

/* 单独一层裁剪光束，beam 层的 overflow:hidden 不升到 border，下拉可用 */
.prompt-card-beam {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  border-radius: inherit;
  overflow: hidden;
}

/* Phase B：霓虹窄弧（::after 盖在上面播 intro） */
.prompt-card-beam::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 1;
  width: 175%;
  height: 175%;
  opacity: 0;
  transform: translate(-50%, -50%) rotate(0deg);
  transform-origin: center center;
  /* Colorful（贴图）：绿 → 青 → 紫 → 洋红 → 柔黄 */
  background: conic-gradient(
    from 0deg at 50% 50%,
    transparent 0deg 300deg,
    rgba(57, 255, 20, 0.92) 302deg,
    rgba(0, 242, 255, 0.92) 310deg,
    rgba(112, 0, 255, 0.88) 318deg,
    rgba(200, 0, 255, 0.85) 326deg,
    rgba(255, 235, 120, 0.82) 334deg,
    transparent 340deg,
    transparent 360deg
  );
  box-shadow:
    0 0 12px rgba(0, 242, 255, calc(0.32 * var(--beam-strength, 1))),
    0 0 28px rgba(200, 0, 255, calc(0.2 * var(--beam-strength, 1))),
    0 0 44px rgba(57, 255, 20, calc(0.18 * var(--beam-strength, 1))),
    0 0 64px rgba(255, 235, 120, calc(0.06 * var(--beam-strength, 1)));
  animation:
    neonBeamReveal 0.4s ease-out var(--beam-intro-duration) forwards,
    neonBeamRotate var(--beam-duration) linear var(--beam-intro-duration) infinite;
}

@keyframes neonBeamReveal {
  from {
    opacity: 0;
  }

  to {
    opacity: var(--beam-strength);
  }
}

@keyframes neonBeamRotate {
  from {
    transform: translate(-50%, -50%) rotate(0deg);
  }

  to {
    transform: translate(-50%, -50%) rotate(360deg);
  }
}

/* Phase A：改版前整环柔色流光（单层平移对标旧 borderFlow） */
.prompt-card-beam::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  border-radius: inherit;
  pointer-events: none;
  background: linear-gradient(
    120deg,
    rgba(59, 130, 246, 0.42),
    rgba(45, 212, 191, 0.42),
    rgba(244, 114, 182, 0.42),
    rgba(129, 140, 248, 0.42),
    rgba(59, 130, 246, 0.42)
  );
  background-size: 300% 300%;
  background-position: 0% 50%;
  opacity: 1;
  animation:
    beamIntroBorderFlow var(--beam-intro-duration) linear forwards,
    beamIntroFadeOut 0.38s ease-out calc(var(--beam-intro-duration) - 0.38s) forwards;
}

@keyframes beamIntroBorderFlow {
  from {
    background-position: 0% 50%;
  }

  to {
    background-position: 100% 50%;
  }
}

@keyframes beamIntroFadeOut {
  from {
    opacity: 1;
  }

  to {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .prompt-card-beam::after {
    animation: none;
    opacity: 0;
  }

  .prompt-card-beam::before {
    animation: none;
    opacity: calc(var(--beam-strength, 1) * 0.42);
    box-shadow:
      0 0 8px rgba(0, 242, 255, 0.12),
      0 0 18px rgba(200, 0, 255, 0.1);
    transform: translate(-50%, -50%) rotate(28deg);
  }
}

/* 焦点内略减压：稍加长时间、略降强度（不改变布局） */
.prompt-card-border:focus-within {
  --beam-duration: 10s;
}

.prompt-card-border:focus-within .prompt-card-beam::before {
  filter: saturate(0.92);
}

@media (prefers-reduced-motion: reduce) {
  .prompt-card-border:focus-within {
    --beam-duration: 8s;
  }
}

.prompt-card {
  position: relative;
  z-index: 1;
  padding: 16px 16px 12px;
  border-radius: 21px;
  background: var(--bg-card, rgba(255, 255, 255, 0.96));
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.08);
}

.prompt-input :deep(.ant-input) {
  border-radius: 14px;
  border: none;
  background: transparent;
  resize: none;
  box-shadow: none;
}

.prompt-quick-row {
  margin-top: 16px;
  display: flex;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
}

.prompt-quick-btn {
  min-width: 72px;
}

@keyframes heroFloat {
  0% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
  100% {
    transform: translateY(0);
  }
}

.prompt-footer {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.prompt-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.workflow-help-tooltip {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  line-height: 1.5;
}

.workflow-help-tooltip-item::before {
  content: '• ';
}

.workflow-beta-btn {
  height: 36px;
  border-radius: 999px;
  padding: 0 10px 0 8px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  background: var(--bg-card, rgba(255, 255, 255, 0.68));
  color: var(--text-base, #0f172a);
  cursor: pointer;
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    border-color 0.16s ease-out,
    background 0.16s ease-out;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.workflow-beta-btn:hover {
  transform: translateY(-1px);
  border-color: rgba(59, 130, 246, 0.35);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.12);
}

/* 选择 Loop 时禁用工作流生成 */
.workflow-beta-btn--disabled {
  opacity: 0.4;
  cursor: not-allowed;
  filter: grayscale(0.6);
}
.workflow-beta-btn--disabled:hover {
  transform: none;
  border-color: rgba(15, 23, 42, 0.12);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.workflow-beta-btn-icon {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  position: relative;
  background: linear-gradient(135deg, rgba(148, 163, 184, 0.58), rgba(100, 116, 139, 0.46));
  flex: 0 0 18px;
}

.workflow-beta-btn-icon::before {
  content: '';
  position: absolute;
  top: 4px;
  left: 6px;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
  border-left: 7px solid rgba(255, 255, 255, 0.94);
}

.workflow-beta-btn-text {
  font-size: 12px;
  font-weight: 600;
}

/* ? 帮助图标嵌入在工作流按钮内 */
.workflow-beta-btn-help {
  display: inline-flex;
  align-items: center;
  margin-left: 2px;
}
.workflow-beta-help-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  color: var(--text-secondary, rgba(15, 23, 42, 0.5));
  background: rgba(15, 23, 42, 0.06);
  cursor: help;
  transition:
    background 0.12s,
    color 0.12s;
}
.workflow-beta-help-icon:hover {
  background: rgba(59, 130, 246, 0.14);
  color: #3b82f6;
}

.workflow-beta-btn-badge {
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  color: var(--text-secondary, #475569);
  background: rgba(148, 163, 184, 0.2);
}

.workflow-beta-btn--active {
  border-color: rgba(59, 130, 246, 0.48);
  background:
    radial-gradient(circle at 0 0, rgba(129, 140, 248, 0.28), transparent 55%),
    linear-gradient(
      135deg,
      rgba(56, 189, 248, 0.18),
      rgba(45, 212, 191, 0.2),
      rgba(129, 140, 248, 0.18)
    ),
    rgba(255, 255, 255, 0.9);
  box-shadow:
    0 16px 34px rgba(15, 23, 42, 0.14),
    0 0 0 1px rgba(59, 130, 246, 0.22);
}

.workflow-beta-btn--active .workflow-beta-btn-icon {
  background: linear-gradient(135deg, #38bdf8, #818cf8, #2dd4bf);
}

.workflow-beta-btn--active .workflow-beta-btn-badge {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.2);
}

.code-type-picker-wrap {
  position: relative;
}

.code-type-picker-btn {
  height: 36px;
  border-radius: 999px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  background: var(--bg-card, rgba(255, 255, 255, 0.7));
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    border-color 0.16s ease-out;
  color: var(--text-base, #0f172a);
  position: relative;
  overflow: hidden;
}

.code-type-picker-btn::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: 999px;
  background: linear-gradient(
    120deg,
    rgba(59, 130, 246, 0.55),
    rgba(45, 212, 191, 0.45),
    rgba(244, 114, 182, 0.35)
  );
  opacity: 0;
  transition: opacity 0.16s ease-out;
  z-index: 0;
}

.code-type-picker-btn > * {
  position: relative;
  z-index: 1;
}

.code-type-picker-btn:hover {
  transform: translateY(-1px);
  border-color: rgba(59, 130, 246, 0.35);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.12);
}

.code-type-picker-btn:hover::before {
  opacity: 1;
}

.code-type-picker-btn-label {
  font-size: 12px;
  letter-spacing: 0.02em;
  color: var(--text-secondary, rgba(15, 23, 42, 0.72));
}

.code-type-picker-btn-value {
  font-size: 12px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
}

.code-type-picker-btn-caret {
  font-size: 12px;
  color: var(--text-muted, rgba(15, 23, 42, 0.6));
}

.code-type-options {
  position: absolute;
  top: calc(100% + 10px);
  right: -30px;
  z-index: 20;

  width: 120px;
  padding: 10px;
  border-radius: 16px;
  background: var(--bg-card, rgba(255, 255, 255, 0.92));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.14);

  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;

  animation: codeTypePickerPop 160ms ease-out;
}

.code-type-auto-head {
  display: flex;
  justify-content: center;
  margin-bottom: 2px;
}

.code-type-auto-btn {
  width: 100%;
  height: 34px;
  border: 1px solid rgba(37, 99, 235, 0.24);
  border-radius: 999px;
  background:
    radial-gradient(circle at 16% 20%, rgba(191, 219, 254, 0.55), transparent 40%),
    linear-gradient(
      120deg,
      rgba(219, 234, 254, 0.9),
      rgba(186, 230, 253, 0.84),
      rgba(224, 242, 254, 0.88)
    );
  color: var(--color-primary, #1e3a8a);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  box-shadow:
    0 6px 16px rgba(59, 130, 246, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.42);
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    filter 0.16s ease-out;
}

.code-type-auto-btn:hover {
  transform: translateY(-1px);
  filter: saturate(1.06);
  box-shadow:
    0 10px 20px rgba(59, 130, 246, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.55);
}

.code-type-auto-btn.is-active {
  border-color: rgba(37, 99, 235, 0.42);
  background:
    radial-gradient(circle at 16% 20%, rgba(96, 165, 250, 0.35), transparent 40%),
    linear-gradient(
      120deg,
      rgba(191, 219, 254, 0.92),
      rgba(125, 211, 252, 0.88),
      rgba(191, 219, 254, 0.9)
    );
  box-shadow:
    0 12px 26px rgba(37, 99, 235, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.65);
}

.code-type-auto-btn:focus-visible {
  outline: none;
  box-shadow:
    0 0 0 3px rgba(59, 130, 246, 0.22),
    0 12px 24px rgba(37, 99, 235, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.58);
}

.code-type-auto-btn__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--color-primary, #3b82f6);
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.7);
}

.code-type-auto-btn__text {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

@keyframes codeTypePickerPop {
  from {
    opacity: 0;
    transform: translateY(-6px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.code-type-option-btn {
  height: 34px;
  width: 100%;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  background: var(--bg-card, rgba(255, 255, 255, 0.65));
  cursor: pointer;
  transition:
    transform 0.16s ease-out,
    box-shadow 0.16s ease-out,
    background 0.16s ease-out,
    border-color 0.16s ease-out;
  font-weight: 700;
  color: var(--text-base, rgba(15, 23, 42, 0.8));
  letter-spacing: 0.02em;
  display: flex;
  align-items: center;
  justify-content: center;
}

.code-type-option-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 14px 30px rgba(15, 23, 42, 0.14);
  border-color: rgba(59, 130, 246, 0.35);
}

.code-type-option-btn--html {
  --accent: rgba(249, 115, 22, 0.9);
}
.code-type-option-btn--multi_file {
  --accent: rgba(34, 197, 94, 0.92);
}
.code-type-option-btn--vue_project {
  --accent: rgba(59, 130, 246, 0.95);
}

.code-type-option-btn.is-selected {
  background: color-mix(in srgb, var(--accent) 18%, rgba(255, 255, 255, 0.7));
  border-color: color-mix(in srgb, var(--accent) 55%, rgba(15, 23, 42, 0.08));
  color: rgba(15, 23, 42, 0.95);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
}

.code-type-option-btn.is-selected:hover {
  border-color: color-mix(in srgb, var(--accent) 70%, rgba(15, 23, 42, 0.08));
}

.apps-section {
  position: relative;
  overflow: hidden;
  isolation: isolate;
  background: var(--bg-card, rgba(255, 255, 255, 0.95));
  border-radius: 20px;
  padding: 20px 20px 16px;
  box-shadow:
    0 12px 30px rgba(15, 23, 42, 0.05),
    0 4px 12px rgba(15, 23, 42, 0.05),
    0 1px 0 rgba(255, 255, 255, 0.6) inset,
    0 0 0 1px rgba(148, 163, 184, 0.07) inset,
    0 0 0 1px rgba(148, 163, 184, 0.18);
  backdrop-filter: blur(10px) saturate(120%);
  -webkit-backdrop-filter: blur(10px) saturate(120%);
  color: var(--text-base, #0f172a);
}

.apps-section::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -2;
  pointer-events: none;
}

.apps-section::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  opacity: 0.48;
}

.apps-section--featured::before {
  background:
    radial-gradient(circle at 8% 0%, rgba(56, 189, 248, 0.2), transparent 38%),
    radial-gradient(circle at 92% 8%, rgba(129, 140, 248, 0.2), transparent 38%),
    radial-gradient(circle at 48% 120%, rgba(45, 212, 191, 0.16), transparent 44%),
    linear-gradient(
      135deg,
      rgba(248, 250, 252, 0.98),
      rgba(236, 253, 245, 0.94),
      rgba(239, 246, 255, 0.96)
    );
}

.apps-section--featured::after {
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.55), rgba(255, 255, 255, 0.12)),
    repeating-linear-gradient(
      -28deg,
      rgba(59, 130, 246, 0.04) 0 10px,
      rgba(56, 189, 248, 0.02) 10px 20px
    );
}

.apps-section--mine::before {
  background:
    radial-gradient(circle at 12% -6%, rgba(251, 191, 36, 0.18), transparent 36%),
    radial-gradient(circle at 88% 4%, rgba(244, 114, 182, 0.14), transparent 34%),
    radial-gradient(circle at 76% 112%, rgba(99, 102, 241, 0.14), transparent 40%),
    linear-gradient(
      136deg,
      rgba(255, 255, 255, 0.98),
      rgba(254, 242, 242, 0.94),
      rgba(238, 242, 255, 0.95)
    );
}

.apps-section--mine::after {
  background:
    linear-gradient(125deg, rgba(255, 255, 255, 0.6), rgba(255, 255, 255, 0.08)),
    repeating-linear-gradient(
      35deg,
      rgba(236, 72, 153, 0.03) 0 9px,
      rgba(79, 70, 229, 0.02) 9px 18px
    );
}
.section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.section-login-tip {
  padding: 40px 0;
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.list-toolbar-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.app-card {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 260px;
  position: relative;
  overflow: hidden;
  isolation: isolate;
  border-radius: 16px;
  border: 1px solid transparent !important;
  background: var(--bg-card, rgba(255, 255, 255, 0.86));
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(14px) saturate(130%);
  -webkit-backdrop-filter: blur(14px) saturate(130%);
  padding-top: 100px;
  transition:
    border-color 0.16s ease-out,
    box-shadow 0.16s ease-out,
    transform 0.16s ease-out;
}

.app-card::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: var(--card-bg-image, none);
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  opacity: 0.18;
  filter: blur(0.6px) saturate(1.02) contrast(1.04);
  transform: scale(1.03);
  z-index: 0;
  pointer-events: none;
}

/* my-app-card 改用 img 背景层，禁用继承自 .app-card 的 ::before 伪元素背景 */
.my-app-card::before {
  content: none;
}

/* 背景图层：绝对定位占满卡片，低 opacity + 轻 blur，z-index 低于内容 */
.my-card-bg-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
  border-radius: inherit;
}

.my-card-bg-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  opacity: 0.28;
  filter: blur(0.9px) saturate(1.08) contrast(1.06);
  transform: scale(1.04);
  display: block;
  pointer-events: none;
}

.app-cover,
.app-info {
  position: relative;
  z-index: 2;
}

.app-card:hover {
  border-color: rgba(59, 130, 246, 0.55) !important;
  background: var(--bg-card, rgba(255, 255, 255, 0.96));
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.14);
  transform: translateY(-2px);
}

.app-cover {
  width: 56px;
  height: 56px;
  margin: 0 auto 10px;
  position: relative;
  overflow: hidden;
  background: transparent;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.app-cover__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.32;
  filter: saturate(0.95) contrast(0.95) brightness(1.1);
  transform: scale(1.04);
  pointer-events: none;
}

.my-app-card:hover .app-cover__img {
  opacity: 0.32;
}

/* 已部署：墨绿主色 + 内光边，与首页蓝紫 hero 区分，避免「又一坨紫渐变」 */
.my-app-card--deployed {
  border-color: rgba(13, 148, 136, 0.42) !important;
  background: linear-gradient(
    152deg,
    rgba(236, 253, 245, 0.97) 0%,
    rgba(255, 255, 255, 0.92) 42%,
    rgba(240, 253, 250, 0.9) 100%
  );
  box-shadow:
    0 10px 28px rgba(15, 23, 42, 0.07),
    0 0 0 1px rgba(13, 148, 136, 0.1) inset;
}

.my-app-card--deployed:hover {
  border-color: rgba(13, 148, 136, 0.58) !important;
  box-shadow:
    0 14px 34px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(13, 148, 136, 0.16) inset;
}

.app-gen-pipeline-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 4;
}

.my-app-card {
  display: flex;
  flex-direction: column;
  padding-top: 0;
}

.my-app-card :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 248px;
  padding: 10px 12px 12px;
}

.my-app-card__footer {
  position: relative;
  z-index: 2;
  margin-top: auto;
  width: 100%;
}

.my-app-card .app-info {
  flex: 0 0 auto;
}

.my-app-card .app-actions {
  margin-top: 8px;
  padding-bottom: 0;
}

.app-deployed-mark--stacked {
  top: 42px;
}

.app-deployed-mark {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px 11px;
  border-radius: 8px;
  pointer-events: none;
  color: #ecfdf5;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  line-height: 1;
  background: linear-gradient(128deg, #0f766e 0%, #059669 48%, #047857 100%);
  box-shadow:
    0 4px 14px rgba(4, 120, 87, 0.38),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  overflow: hidden;
}

.app-deployed-mark__glow {
  position: absolute;
  inset: -40% -20%;
  background: radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.35), transparent 55%);
  opacity: 0.55;
  pointer-events: none;
}

.app-deployed-mark__text {
  position: relative;
  z-index: 1;
}

.app-info {
  flex: 1;
  position: relative;
  z-index: 2;
}

.good-app-card .app-info {
  margin-top: 10px;
}

.app-name-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  /* 预留出最多两行标题的高度，保证不同长度标题下方按钮对齐 */
  min-height: 3.2em;
}

.app-name {
  font-weight: 600;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.app-meta {
  margin-top: 4px;
  display: flex;
  justify-content: flex-end;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.app-meta-type {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 999px;
  background:
    radial-gradient(circle at 0 0, rgba(148, 163, 184, 0.38), transparent 60%),
    rgba(248, 250, 252, 0.95);
  box-shadow:
    0 6px 14px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(148, 163, 184, 0.4);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.app-meta-type-label {
  font-size: 11px;
  color: var(--text-muted, #9ca3af);
}

.app-meta-type-tag {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-base, #0f172a);
  background: linear-gradient(135deg, #dbeafe, #bbf7d0);
  box-shadow: 0 0 0 1px rgba(59, 130, 246, 0.45);
}

.app-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 16px;
  padding-bottom: 4px;
  position: relative;
  z-index: 2;
}

.my-app-card .app-hover-meta {
  position: absolute;
  inset: 0;
  z-index: 1;
  padding: 10px 12px;
  background: linear-gradient(
    to bottom,
    rgba(15, 23, 42, 0.18),
    rgba(15, 23, 42, 0.25),
    transparent 65%
  );
  opacity: 0;
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: flex-start;
  align-items: center;
  text-align: center;
  transition: opacity 0.18s ease-out;
}

.my-app-card:hover .app-hover-meta {
  opacity: 1;
}

.app-hover-meta-row {
  display: flex;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary, #4b5563);
  justify-content: center;
  width: 100%;
  text-align: center;
}

.meta-label {
  opacity: 0.7;
}

.meta-value {
  font-variant-numeric: tabular-nums;
}

.empty-wrap {
  grid-column: 1 / -1;
  padding: 24px 0;
}

.pagination-wrap {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}

.deploy-modal-text {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--text-secondary, #4b5563);
}

.deploy-modal-input {
  margin-bottom: 12px;
}

.deploy-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@keyframes homeGradientShift {
  0% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0% 50%;
  }
}

@media (max-width: 768px) {
  .hero-section {
    padding: 28px 12px 20px;
  }

  .hero-main-text {
    font-size: 30px;
  }

  .hero-highlight {
    font-size: 20px;
  }

  .apps-section {
    padding: 16px 12px 12px;
  }
}
/* ========== 深色模式覆盖 ========== */
[data-theme='dark'] .workflow-beta-btn--active {
  background:
    radial-gradient(circle at 0 0, rgba(99, 102, 241, 0.35), transparent 55%),
    linear-gradient(
      135deg,
      rgba(56, 189, 248, 0.25),
      rgba(45, 212, 191, 0.25),
      rgba(129, 140, 248, 0.25)
    ),
    var(--bg-card, #1e1e36);
  box-shadow:
    0 16px 34px rgba(0, 0, 0, 0.3),
    0 0 0 1px rgba(99, 102, 241, 0.3);
}

[data-theme='dark'] .workflow-beta-btn-icon {
  background: linear-gradient(
    135deg,
    rgba(56, 189, 248, 0.6),
    rgba(129, 140, 248, 0.5),
    rgba(45, 212, 191, 0.5)
  );
}

[data-theme='dark'] .workflow-beta-btn-badge {
  background: rgba(99, 102, 241, 0.25);
  color: #a5b4fc;
}

[data-theme='dark'] .code-type-auto-btn {
  border-color: rgba(99, 102, 241, 0.3);
  background:
    radial-gradient(circle at 16% 20%, rgba(99, 102, 241, 0.2), transparent 40%),
    linear-gradient(120deg, rgba(30, 30, 54, 0.9), rgba(37, 37, 66, 0.85), rgba(30, 30, 54, 0.88));
  color: #a5b4fc;
  box-shadow:
    0 6px 16px rgba(0, 0, 0, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

[data-theme='dark'] .code-type-auto-btn:hover {
  transform: translateY(-1px);
  box-shadow:
    0 10px 20px rgba(0, 0, 0, 0.25),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  border-color: rgba(99, 102, 241, 0.45);
}

[data-theme='dark'] .code-type-auto-btn.is-active {
  border-color: rgba(129, 140, 248, 0.5);
  background:
    radial-gradient(circle at 16% 20%, rgba(129, 140, 248, 0.25), transparent 40%),
    linear-gradient(120deg, rgba(37, 37, 66, 0.95), rgba(49, 46, 129, 0.85), rgba(37, 37, 66, 0.92));
  box-shadow:
    0 12px 26px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  color: #c7d2fe;
}

[data-theme='dark'] .code-type-option-btn {
  border-color: rgba(148, 163, 184, 0.15);
}

[data-theme='dark'] .code-type-option-btn.is-selected {
  background: color-mix(in srgb, var(--accent) 22%, var(--bg-elevated, #252542));
  border-color: color-mix(in srgb, var(--accent) 50%, rgba(148, 163, 184, 0.15));
  color: var(--text-base, #e0e0e0);
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.3);
}

[data-theme='dark'] .apps-section {
  background: var(--bg-card, #1e1e36);
  box-shadow:
    0 12px 30px rgba(0, 0, 0, 0.2),
    0 4px 12px rgba(0, 0, 0, 0.15),
    0 0 0 1px rgba(148, 163, 184, 0.1) inset;
}

[data-theme='dark'] .apps-section--featured::before {
  background:
    radial-gradient(circle at 8% 0%, rgba(99, 102, 241, 0.18), transparent 38%),
    radial-gradient(circle at 92% 8%, rgba(129, 140, 248, 0.15), transparent 38%),
    radial-gradient(circle at 48% 120%, rgba(45, 212, 191, 0.12), transparent 44%),
    linear-gradient(135deg, rgba(30, 30, 54, 0.98), rgba(26, 26, 46, 0.95), rgba(30, 30, 54, 0.96));
}

[data-theme='dark'] .apps-section--featured::after {
  background:
    linear-gradient(120deg, rgba(99, 102, 241, 0.08), rgba(56, 189, 248, 0.04)),
    repeating-linear-gradient(
      -28deg,
      rgba(99, 102, 241, 0.06) 0 10px,
      rgba(56, 189, 248, 0.03) 10px 20px
    );
}

[data-theme='dark'] .apps-section--mine::before {
  background:
    radial-gradient(circle at 12% -6%, rgba(251, 191, 36, 0.12), transparent 36%),
    radial-gradient(circle at 88% 4%, rgba(244, 114, 182, 0.1), transparent 34%),
    radial-gradient(circle at 76% 112%, rgba(99, 102, 241, 0.1), transparent 40%),
    linear-gradient(136deg, rgba(30, 30, 54, 0.98), rgba(37, 37, 66, 0.95), rgba(30, 30, 54, 0.96));
}

[data-theme='dark'] .apps-section--mine::after {
  background:
    linear-gradient(125deg, rgba(99, 102, 241, 0.06), rgba(236, 72, 153, 0.04)),
    repeating-linear-gradient(
      35deg,
      rgba(236, 72, 153, 0.04) 0 9px,
      rgba(99, 102, 241, 0.03) 9px 18px
    );
}

[data-theme='dark'] .my-app-card--deployed {
  border-color: rgba(13, 148, 136, 0.4) !important;
  background: linear-gradient(
    152deg,
    rgba(30, 30, 54, 0.97) 0%,
    rgba(26, 26, 46, 0.92) 42%,
    rgba(30, 30, 54, 0.95) 100%
  );
  box-shadow:
    0 10px 28px rgba(0, 0, 0, 0.2),
    0 0 0 1px rgba(13, 148, 136, 0.12) inset;
}

[data-theme='dark'] .my-app-card--deployed:hover {
  border-color: rgba(13, 148, 136, 0.55) !important;
  box-shadow:
    0 14px 34px rgba(0, 0, 0, 0.25),
    0 0 0 1px rgba(13, 148, 136, 0.2) inset;
}

[data-theme='dark'] .app-meta-type {
  background:
    radial-gradient(circle at 0 0, rgba(148, 163, 184, 0.25), transparent 60%),
    var(--bg-soft, #16213e);
  box-shadow:
    0 6px 14px rgba(0, 0, 0, 0.2),
    0 0 0 1px rgba(148, 163, 184, 0.2);
}

[data-theme='dark'] .app-meta-type-tag {
  background: linear-gradient(135deg, rgba(96, 165, 250, 0.3), rgba(52, 211, 153, 0.25));
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.3);
  color: var(--text-base, #e0e0e0);
}
</style>
