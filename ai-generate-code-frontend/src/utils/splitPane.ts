/**
 * 聊天 / 预览分栏的布局计算。
 *
 * 抽成纯函数是为了能直接断言：这些规则不碰 DOM，只描述「在什么状态下用几列」。
 * 组件里只负责把结果绑到 style / v-show 上。
 */

/** 被放大的一侧；none 表示按比例分栏 */
export type FocusedSide = 'none' | 'chat' | 'preview'

/** 分隔线占的列宽，同时充当两侧面板的间距（所以 .main-content 不再用 gap） */
export const SPLITTER_TRACK = 16

/** 单侧最小占比：夹到 0.15~0.85，避免某一侧被拖到没法用 */
export const MIN_RATIO = 0.15
export const MAX_RATIO = 0.85

export interface SplitLayoutInput {
  /** 窄屏时布局交给 media query，行内样式会盖掉它，所以不下发 */
  narrow: boolean
  previewExpanded: boolean
  focusedSide: FocusedSide
  /** 左侧占比 */
  ratio: number
}

/** 把比例夹进合法区间；非有限值退回等分 */
export function clampRatio(ratio: number): number {
  if (!Number.isFinite(ratio)) return 0.5
  return Math.min(MAX_RATIO, Math.max(MIN_RATIO, ratio))
}

/**
 * 计算 .main-content 的 grid-template-columns。
 *
 * 返回 undefined 表示不下发行内样式，交由样式表决定（窄屏）。
 * 返回单列时，另一侧靠 display:none 让出轨道 —— grid 不会让两个子项重叠。
 */
export function computeGridColumns(input: SplitLayoutInput): string | undefined {
  if (input.narrow) return undefined
  if (!input.previewExpanded || input.focusedSide !== 'none') {
    return 'minmax(0, 1fr)'
  }
  const left = clampRatio(input.ratio)
  // 1 - 0.85 是 0.15000000000000002，直接拼进 CSS 很难看，先舍到 4 位
  const right = Math.round((1 - left) * 10000) / 10000
  return `minmax(0, ${left}fr) ${SPLITTER_TRACK}px minmax(0, ${right}fr)`
}

/** 只有两侧同时在场、且没有放大任何一侧时才画分隔线 */
export function isSplitterVisible(input: SplitLayoutInput): boolean {
  return input.previewExpanded && input.focusedSide === 'none' && !input.narrow
}

/** 放大预览时藏起聊天；窄屏下不隐藏以保证上下堆叠可用 */
export function isChatHidden(focusedSide: FocusedSide, narrow = false): boolean {
  if (narrow) return false
  return focusedSide === 'preview'
}

/** 没开预览、或放大聊天时藏起预览；窄屏下不受 focusedSide 影响 */
export function isPreviewHidden(
  previewExpanded: boolean,
  focusedSide: FocusedSide,
  narrow = false,
): boolean {
  if (!previewExpanded) return true
  if (narrow) return false
  return focusedSide === 'chat'
}

/**
 * 只显示预览区（聊天被隐藏）时，在屏幕左边缘显示恢复双栏的小箭头。
 * 窄屏下不展示，且必须同时满足预览展开与聚焦预览。
 */
export function isLeftRestoreTriggerVisible(
  narrow: boolean,
  focusedSide: FocusedSide,
  previewExpanded = true,
): boolean {
  return !narrow && previewExpanded && focusedSide === 'preview'
}

/**
 * 只显示聊天区（预览被隐藏或折叠）时，在屏幕右边缘显示恢复双栏的小箭头。
 * 窄屏下不展示；并且与聚焦预览模式严格互斥。
 */
export function isRightRestoreTriggerVisible(
  narrow: boolean,
  previewExpanded: boolean,
  focusedSide: FocusedSide,
): boolean {
  return !narrow && focusedSide !== 'preview' && (!previewExpanded || focusedSide === 'chat')
}

/**
 * 根据鼠标视口 X 坐标与容器几何信息计算左侧分栏比例。
 * 考虑分割线自身宽度与抓取点偏移，保证鼠标拖拽时像素级精准跟手，避免跳变与系统偏差。
 */
export function calculateSplitRatioWithOffset(
  clientX: number,
  containerRect: { left: number; width: number },
  grabOffset = 0,
  trackWidth = SPLITTER_TRACK,
): number {
  const availableWidth = containerRect.width - trackWidth
  if (availableWidth <= 0) return 0.5
  const leftWidth = clientX - grabOffset - containerRect.left - trackWidth / 2
  const raw = leftWidth / availableWidth
  return clampRatio(raw)
}

