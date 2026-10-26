import { computed, reactive, ref, toRefs, watch } from 'vue'
import { defineStore } from 'pinia'
import { theme } from 'ant-design-vue'

/** localStorage key */
const STORAGE_KEY = 'glyahh:appearance:v1'

/** 代码块主题 */
export const CODE_THEME_OPTIONS = [
  { label: '默认', value: 'default' },
  { label: '高对比', value: 'high-contrast' },
  { label: '柔和', value: 'soft' },
] as const

/** 默认代码类型 */
export const DEFAULT_CODE_TYPE_OPTIONS = [
  { label: '自动识别', value: 'auto' },
  { label: 'HTML', value: 'html' },
  { label: '多文件', value: 'multi_file' },
  { label: 'Vue 项目', value: 'vue_project' },
] as const

/** 外观设置状态接口 */
export interface AppearanceSettings {
  // A. 基础颜色
  colorMode: 'system' | 'light' | 'dark'
  primaryColor: string

  // B. 字体
  fontSize: number
  codeFontSize: number

  // C. 界面密度
  compactMode: boolean
  reducedMotion: boolean

  // D. 本项目专属
  defaultCodeType: 'auto' | 'html' | 'multi_file' | 'vue_project'
  workflowEnabled: boolean
  codeTheme: 'default' | 'high-contrast' | 'soft'
  toolCardCollapsed: boolean
  previewExpanded: boolean
  smoothScroll: boolean
  /** 聊天/预览左右分栏比例（0.15~0.85），左侧占比 */
  splitRatio: number
}

/** 按分组索引的 key 列表，用于 resetSection */
export const SECTION_KEYS: Record<string, Array<keyof AppearanceSettings>> = {
  colors: ['colorMode', 'primaryColor'],
  fonts: ['fontSize', 'codeFontSize'],
  density: ['compactMode', 'reducedMotion'],
  preferences: [
    'defaultCodeType',
    'workflowEnabled',
    'codeTheme',
    'toolCardCollapsed',
    'previewExpanded',
    'smoothScroll',
    'splitRatio',
  ],
}

/** 默认值：对齐项目现有代码的初值 */
const DEFAULTS: AppearanceSettings = {
  colorMode: 'system',
  primaryColor: '#1677ff',
  fontSize: 15,
  codeFontSize: 13,
  compactMode: false,
  reducedMotion: false,
  defaultCodeType: 'auto',
  workflowEnabled: false,
  codeTheme: 'default',
  toolCardCollapsed: true,
  previewExpanded: true,
  smoothScroll: true,
  splitRatio: 0.5,
}

/** 从 localStorage 加载，缺失字段合并默认值 */
function loadFromStorage(): AppearanceSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw)
    // 合并确保新增字段有默认值（v1→v2 兼容）
    return { ...DEFAULTS, ...parsed }
  } catch {
    return { ...DEFAULTS }
  }
}

/** 持久化到 localStorage */
function saveToStorage(state: AppearanceSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch (e) {
    console.warn('[appearance] 写入 localStorage 失败', e)
  }
}

/**
 * 将设置应用到 DOM（仅纯 DOM 操作，不持有 store 引用）
 * - data-theme / data-compact / data-code-theme 属性
 * - CSS 自定义属性（字号、字体族、强调色）
 */
export function applyToDocument(settings: AppearanceSettings): void {
  const root = document.documentElement

  // 1. 主题模式（不再在这里注册 matchMedia，由 Store 的 init 管理）
  if (settings.colorMode === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.dataset.theme = prefersDark ? 'dark' : 'light'
  } else {
    root.dataset.theme = settings.colorMode
  }

  // 2. 紧凑模式
  root.dataset.compact = settings.compactMode ? 'true' : 'false'

  // 3. 代码块主题
  root.dataset.codeTheme = settings.codeTheme

  // 4. CSS 变量
  root.style.setProperty('--font-size-base', `${settings.fontSize}px`)
  root.style.setProperty('--code-font-size', `${settings.codeFontSize}px`)
  root.style.setProperty('--color-primary', settings.primaryColor)

  // 5. 动画减弱
  root.dataset.reducedMotion = settings.reducedMotion ? 'true' : 'false'
  if (settings.reducedMotion) {
    root.style.setProperty('--transition-duration', '0s')
  } else {
    root.style.removeProperty('--transition-duration')
  }
}

/** 根据当前设置生成 Ant Design ConfigProvider theme 对象 */

export function resolveThemeConfig(settings: {
  isDark: boolean
  primaryColor: string
  fontSize: number
  compactMode: boolean
}): { token: Record<string, any>; algorithm: any } {
  return {
    token: {
      colorPrimary: settings.primaryColor,
      fontSize: settings.fontSize,
      sizeStep: settings.compactMode ? 3 : 6,
      borderRadius: settings.compactMode ? 4 : 6,
      controlHeight: settings.compactMode ? 28 : 36,
      padding: settings.compactMode ? 12 : 20,
      paddingSM: settings.compactMode ? 8 : 14,
      marginXS: settings.compactMode ? 2 : 8,
      marginSM: settings.compactMode ? 6 : 12,
      marginMD: settings.compactMode ? 10 : 18,
    },
    algorithm: settings.isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
  }
}

export const useAppearanceStore = defineStore('appearance', () => {
  const saved = loadFromStorage()

  const state = reactive<AppearanceSettings>({ ...saved })

  /**
   * 响应式系统暗色标记。
   * 当 colorMode === 'system' 时，matchMedia 变化会更新此 ref，
   * 驱动 effectiveColorMode 的 computed 重算 → ConfigProvider 自动跟随。
   */
  const _systemDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)

  /** 实际生效的外观模式：非 'system' 时直接透传；'system' 时跟随系统偏好 */
  const effectiveColorMode = computed(() => {
    if (state.colorMode === 'system') return _systemDark.value ? 'dark' : 'light'
    return state.colorMode
  })

  /** 持久化到 localStorage 并应用到 DOM */
  function persistAndApply() {
    saveToStorage(state)
    applyToDocument(state)
  }

  /** 重置某个分组的全部字段到默认值 */
  function resetSection(section: keyof typeof SECTION_KEYS) {
    const keys = SECTION_KEYS[section]
    if (!keys) return
    keys.forEach((k) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      ;(state as any)[k] = DEFAULTS[k]
    })
    persistAndApply()
  }

  /** matchMedia 系统主题监听器。跟随系统模式时自动更新 DOM + 响应式标记 */
  let mql: MediaQueryList | null = null

  function handleSystemThemeChange() {
    if (!mql) return
    _systemDark.value = mql.matches
    // 仅当 colorMode === 'system' 时才更新 dataset，否则用户的显式选择优先
    if (state.colorMode === 'system') {
      document.documentElement.dataset.theme = mql.matches ? 'dark' : 'light'
    }
  }

  /** 启动时执行一次：localStorage → DOM + 注册 matchMedia 监听 */
  function init() {
    persistAndApply()
    // 注册系统主题变化监听
    mql = window.matchMedia('(prefers-color-scheme: dark)')
    mql.addEventListener('change', handleSystemThemeChange)
  }

  // 自动 watch：用户对 state 的任何更改 → 持久化 + 应用（不响应 _systemDark 的变化）
  watch(
    () => ({ ...state }),
    () => persistAndApply(),
  )

  return {
    ...toRefs(state),
    state,
    effectiveColorMode,
    init,
    resetSection,
    DEFAULTS,
  }
})
