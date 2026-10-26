import { computed, ref, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import * as api from './api'
import type {
  AgentChangeCandidate,
  AgentComposerSettings,
  AgentFileDiff,
  AgentModelOption,
  AgentReviewDecisionEvent,
  AgentToolDecisionEvent,
} from './types'

/**
 * 会话授权状态与接口调用。
 *
 * <p>导入应用读取权限会话与候选审阅；普通应用只读取应用级模型偏好。
 * 组件只负责展示，布局由调用方决定。
 */
export function useAgentSession(input: {
  appId: Ref<string>
  streaming: Ref<boolean>
}) {
  const session = ref<api.SessionSettings | null>(null)
  const options = ref<AgentModelOption[]>([])
  const tool = ref<api.ToolPermission | null>(null)
  const review = ref<api.CandidateReview | null>(null)
  const state = ref('LOADING')
  const saving = ref(false)
  const deciding = ref(false)
  const error = ref('')
  const merged = ref(0)
  /** 服务端表明这是导入应用：模式与模型同属一份会话；否则两者分表存储。 */
  const isImportApp = ref(false)
  /** 普通应用的沙箱档位；默认工作区可写。 */
  const sandboxMode = ref<'READ_ONLY' | 'WORKSPACE_WRITE'>('WORKSPACE_WRITE')
  /** 普通应用的授权模式 revision，与模型偏好的 revision 相互独立。 */
  const modePolicyRevision = ref<string>('')
  let disposed = false
  let timer: ReturnType<typeof setTimeout> | undefined

  const settings = computed<AgentComposerSettings>(() => ({
    mode: session.value?.mode ?? 'MANUAL',
    modelId: session.value?.profileId ?? '',
    effort: session.value?.effort ?? null,
  }))

  const candidate = computed<AgentChangeCandidate | null>(() =>
    review.value
      ? {
          applicationMode: isImportApp.value ? 'STAGED' : 'APPLIED',
          rollbackAvailable: !review.value.body.changes.some((change) => change.truncated),
          id: review.value.editId,
          revision: String(review.value.revision),
          reviewHash: review.value.hash,
          highRisk: review.value.risk === 'HIGH',
          files: review.value.body.changes.map((change) => ({
            path: change.path,
            change: change.before === null ? 'ADDED' : change.after === null ? 'DELETED' : 'MODIFIED',
            highRisk: review.value?.risk === 'HIGH',
            binary: false,
          })),
        }
      : null,
  )

  const diffs = computed<Record<string, AgentFileDiff>>(() =>
    Object.fromEntries(
      (review.value?.body.changes ?? []).map((change) => {
        // 超限文件没有正文：直接拼差异会得到一片空白，用户会以为「没改动」。
        const text = change.truncated
          ? '（文件过大，未保留完整差异；拒绝回滚会因此失败，请手动核对）'
          : [
              ...(change.beforeText ?? '').split('\n').map((line) => `- ${line}`),
              ...(change.afterText ?? '').split('\n').map((line) => `+ ${line}`),
            ].join('\n')
        const lines = text.split('\n').length
        return [
          change.path,
          { status: 'loaded', text, startLine: 1, endLine: lines, totalLines: lines },
        ]
      }),
    ),
  )

  const toolRequest = computed(() =>
    tool.value
      ? {
          ...tool.value,
          allowSimilar: isImportApp.value || tool.value.action !== 'execute',
          action: tool.value.display.title,
          object: tool.value.display.object,
          reason: tool.value.display.reason,
          detailTitle: '实际操作',
          revision: String(tool.value.revision),
        }
      : null,
  )

  /** 导入：未就绪或出错都阻塞；普通：只有保存中才阻塞，读取失败回落全局配置。 */
  const blocked = computed(() =>
    isImportApp.value
      ? !session.value || !!error.value || state.value !== 'IDLE' || saving.value || deciding.value
      : saving.value,
  )

  async function refresh() {
    // 导入应用有会话状态接口；普通应用只有待决票据与最近审阅，两者取数路径不同。
    if (!isImportApp.value) {
      const [pending, latest] = await Promise.all([
        api.projectPending(input.appId.value),
        api.projectLatestReview(input.appId.value),
      ])
      if (disposed) return
      tool.value = pending
      // 后端给的是变更类型，前端按 before/after 是否为空推断；这里换算成同义形状。
      review.value = latest && latest.status === 'WAITING'
        ? {
            editId: latest.reviewId,
            revision: latest.revision,
            hash: latest.reviewHash,
            risk: latest.risk,
            status: latest.status,
            body: {
              changes: latest.body.changes.map((change) => ({
                path: change.path,
                before: change.change === 'ADDED' ? null : (change.beforeText ?? ''),
                after: change.change === 'DELETED' ? null : (change.afterText ?? ''),
                beforeText: change.beforeText ?? null,
                afterText: change.afterText ?? null,
                truncated: change.truncated === true,
              })),
            },
          }
        : null
      return
    }
    const current = await api.active(input.appId.value)
    const pending = current.permissionId
      ? await api.permission(input.appId.value, current.permissionId)
      : null
    if (disposed) return
    const wasWaiting = state.value === 'WAITING_REVIEW'
    state.value = current.state
    tool.value = pending
    review.value = current.review ?? null
    if (wasWaiting && current.state === 'IDLE' && current.outcome === 'SUCCEEDED') merged.value += 1
  }

  async function poll() {
    try {
      await refresh()
    } catch (cause) {
      if (!disposed) error.value = (cause as Error).message
    }
    if (!disposed && !error.value) timer = setTimeout(poll, 2000)
  }

  async function load() {
    error.value = ''
    state.value = 'LOADING'
    try {
      // 1. 模型目录与偏好对所有应用统一；授权模式普通应用也由本接口返回。
      const config = await api.modelConfig(input.appId.value)
      if (disposed) return
      options.value = config.models.filter((model) => model.configured)
      sandboxMode.value = (config.sandboxMode as typeof sandboxMode.value) ?? 'WORKSPACE_WRITE'
      // 2. 以目录为准解析选中项：偏好里的 profile 失效时回落到默认模型，
      //    力度为空时取该模型支持的第一档，避免下拉渲染成空白。
      const preferred = options.value.find((model) => model.id === config.preference.profileId)
      const fallback = options.value.find((model) => model.id === 'default') ?? options.value[0]
      const selected = preferred ?? fallback
      session.value = {
        mode: config.mode ?? 'MANUAL',
        profileId: selected?.id ?? '',
        effort: config.preference.effort ?? selected?.efforts[0] ?? null,
        // policyRevision 始终指模型偏好的 revision；模式的 revision 单独存。
        policyRevision: config.preference.revision ?? '',
      }
      // config.mode 为 null 表示导入应用：它有独立的设置接口，需要拉取会话与待决定项。
      isImportApp.value = config.mode === null
      modePolicyRevision.value = config.policyRevision ?? ''
      if (config.mode === null) {
        await refresh()
        const restored = await api.settings(input.appId.value)
        if (disposed) return
        session.value = restored
      }
      // 两条链路都要轮询待决票据：普通应用靠它发现被门禁拦下的动作。
      await poll()
      state.value = 'IDLE'
    } catch (cause) {
      if (disposed) return
      error.value = (cause as Error).message
      state.value = 'ERROR'
    }
  }

  async function saveSettings(next: AgentComposerSettings) {
    if (!session.value || saving.value || input.streaming.value || state.value !== 'IDLE') return
    saving.value = true
    try {
      if (isImportApp.value) {
        // 导入应用：模式与模型同属一份服务端会话。
        const result = await api.save(input.appId.value, session.value, next)
        if (!disposed) session.value = result
        return
      }
      // 普通应用：模式与模型分别存两张表，各有独立 revision；只提交真正变化的那个。
      if (next.mode !== session.value.mode) {
        const saved = await api.saveEditMode(input.appId.value, modePolicyRevision.value || null, next.mode,
          sandboxMode.value)
        if (disposed) return
        modePolicyRevision.value = saved.policyRevision
        session.value = { ...session.value, mode: saved.mode }
      }
      const modelChanged = next.modelId !== session.value.profileId || next.effort !== session.value.effort
      if (modelChanged) {
        const savedModel = await api.saveProjectModel(
          input.appId.value,
          session.value.policyRevision || null,
          next.modelId,
          next.effort,
        )
        if (!disposed) {
          session.value = {
            ...session.value,
            profileId: savedModel.profileId ?? '',
            effort: savedModel.effort,
            policyRevision: savedModel.revision ?? '',
          }
        }
      }
    } catch (cause) {
      message.error((cause as Error).message)
    } finally {
      saving.value = false
    }
  }

  async function decideTool(event: AgentToolDecisionEvent) {
    if (!tool.value || deciding.value || event.requestId !== tool.value.id) return
    if (event.actionHash !== tool.value.actionHash) return
    deciding.value = true
    try {
      // 普通应用走自己的许可入口；导入应用沿用导入链路。
      if (isImportApp.value) {
        await api.decideTool(input.appId.value, tool.value, event.decision)
      } else {
        await api.decideProjectTool(input.appId.value, tool.value, event.decision)
      }
      await refresh()
    } catch (cause) {
      message.error((cause as Error).message)
    } finally {
      deciding.value = false
    }
  }

  async function decideReview(event: AgentReviewDecisionEvent) {
    if (!review.value || deciding.value || event.reviewId !== review.value.editId) return
    if (event.reviewHash !== review.value.hash) return
    deciding.value = true
    try {
      if (isImportApp.value) {
        await api.decideReview(review.value, event.decision)
      } else {
        // 普通应用的审阅以 reviewId 为主键，与导入的 editId 不是同一个东西。
        const current = await api.projectReview(input.appId.value, review.value.editId)
        await api.decideProjectReview(input.appId.value, current, event.decision)
      }
      await refresh()
    } catch (cause) {
      message.error((cause as Error).message)
    } finally {
      deciding.value = false
    }
  }

  function dispose() {
    disposed = true
    if (timer) clearTimeout(timer)
  }

  return {
    session, options, tool, review, state, saving, deciding, error, merged,
    settings, candidate, diffs, toolRequest, blocked,
    isImportApp,
    load, saveSettings, decideTool, decideReview, dispose,
  }
}