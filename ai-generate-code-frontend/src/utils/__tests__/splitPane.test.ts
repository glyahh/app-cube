import { describe, expect, it } from 'vitest'
import {
  calculateSplitRatioWithOffset,
  clampRatio,
  computeGridColumns,
  isChatHidden,
  isPreviewHidden,
  isSplitterVisible,
  isLeftRestoreTriggerVisible,
  isRightRestoreTriggerVisible,
  SPLITTER_TRACK,
} from '../splitPane'

const base = { narrow: false, previewExpanded: true, focusedSide: 'none' as const, ratio: 0.5 }

describe('splitPane', () => {
  describe('clampRatio', () => {
    it('夹住上下限', () => {
      expect(clampRatio(0.01)).toBe(0.15)
      expect(clampRatio(0.99)).toBe(0.85)
      expect(clampRatio(0.4)).toBe(0.4)
    })

    it('非有限值退回等分', () => {
      expect(clampRatio(Number.NaN)).toBe(0.5)
      expect(clampRatio(Number.POSITIVE_INFINITY)).toBe(0.5)
      expect(clampRatio(Number.NEGATIVE_INFINITY)).toBe(0.5)
    })
  })

  describe('computeGridColumns', () => {
    it('两侧在场时按比例分三列，中间留给分隔线', () => {
      expect(computeGridColumns({ ...base, ratio: 0.6 })).toBe(
        `minmax(0, 0.6fr) ${SPLITTER_TRACK}px minmax(0, 0.4fr)`,
      )
    })

    it('比例越界时先夹再算', () => {
      expect(computeGridColumns({ ...base, ratio: 5 })).toBe(
        `minmax(0, 0.85fr) ${SPLITTER_TRACK}px minmax(0, 0.15fr)`,
      )
    })

    it('没开预览时是单列', () => {
      expect(computeGridColumns({ ...base, previewExpanded: false })).toBe('minmax(0, 1fr)')
    })

    it('放大某一侧时是单列', () => {
      expect(computeGridColumns({ ...base, focusedSide: 'chat' })).toBe('minmax(0, 1fr)')
      expect(computeGridColumns({ ...base, focusedSide: 'preview' })).toBe('minmax(0, 1fr)')
    })

    it('窄屏不下发行内样式，交给 media query', () => {
      expect(computeGridColumns({ ...base, narrow: true })).toBeUndefined()
    })
  })

  describe('isSplitterVisible', () => {
    it('两侧都在且未放大时才显示', () => {
      expect(isSplitterVisible(base)).toBe(true)
    })

    it('放大任一侧后隐藏', () => {
      expect(isSplitterVisible({ ...base, focusedSide: 'chat' })).toBe(false)
      expect(isSplitterVisible({ ...base, focusedSide: 'preview' })).toBe(false)
    })

    it('没开预览时隐藏', () => {
      expect(isSplitterVisible({ ...base, previewExpanded: false })).toBe(false)
    })

    it('窄屏隐藏', () => {
      expect(isSplitterVisible({ ...base, narrow: true })).toBe(false)
    })
  })

  describe('面板显隐', () => {
    it('放大预览时藏聊天；窄屏下不隐藏保证上下堆叠可用', () => {
      expect(isChatHidden('preview', false)).toBe(true)
      expect(isChatHidden('preview', true)).toBe(false)
      expect(isChatHidden('chat', false)).toBe(false)
      expect(isChatHidden('none', false)).toBe(false)
    })

    it('放大聊天或没开预览时藏预览；窄屏下受折叠控制但不受放大影响', () => {
      expect(isPreviewHidden(true, 'chat', false)).toBe(true)
      expect(isPreviewHidden(true, 'chat', true)).toBe(false)
      expect(isPreviewHidden(false, 'none', false)).toBe(true)
      expect(isPreviewHidden(false, 'none', true)).toBe(true)
      expect(isPreviewHidden(true, 'none', false)).toBe(false)
      expect(isPreviewHidden(true, 'preview', false)).toBe(false)
    })
  })

  describe('边缘恢复双栏按钮显隐', () => {
    it('纯预览模式下左侧小箭头显示，窄屏、折叠或非纯预览时不显示', () => {
      expect(isLeftRestoreTriggerVisible(false, 'preview', true)).toBe(true)
      expect(isLeftRestoreTriggerVisible(false, 'preview', false)).toBe(false)
      expect(isLeftRestoreTriggerVisible(true, 'preview', true)).toBe(false)
      expect(isLeftRestoreTriggerVisible(false, 'chat', true)).toBe(false)
      expect(isLeftRestoreTriggerVisible(false, 'none', true)).toBe(false)
    })

    it('纯聊天模式或折叠预览时右侧小箭头显示，窄屏或双栏时不显示', () => {
      expect(isRightRestoreTriggerVisible(false, true, 'chat')).toBe(true)
      expect(isRightRestoreTriggerVisible(false, false, 'none')).toBe(true)
      expect(isRightRestoreTriggerVisible(false, false, 'chat')).toBe(true)
      expect(isRightRestoreTriggerVisible(true, true, 'chat')).toBe(false)
      expect(isRightRestoreTriggerVisible(false, true, 'preview')).toBe(false)
      expect(isRightRestoreTriggerVisible(false, true, 'none')).toBe(false)
    })

    it('两侧恢复按钮在任何异常组合下永远互斥，绝不同时出现', () => {
      // 场景：折叠预览且处于聚焦预览模式时，不能两边都为 true
      const left = isLeftRestoreTriggerVisible(false, 'preview', false)
      const right = isRightRestoreTriggerVisible(false, false, 'preview')
      expect(left && right).toBe(false)
      expect(right).toBe(false)
    })
  })

  describe('calculateSplitRatioWithOffset', () => {
    const container = { left: 100, width: 1016 } // availableWidth = 1000

    it('无偏移正中点击返回 0.5', () => {
      // 中心位置：100 + 500 + 8 = 608
      expect(calculateSplitRatioWithOffset(608, container, 0, 16)).toBe(0.5)
    })

    it('带 grabOffset 保证相对位置恒定', () => {
      // 假设在中心偏右 4px 点击，grabOffset = 4
      // 鼠标移动到 708 时，计算：(708 - 4 - 100 - 8) / 1000 = 596 / 1000 = 0.596
      expect(calculateSplitRatioWithOffset(708, container, 4, 16)).toBe(0.596)
    })

    it('超出边界时夹住到 MIN_RATIO 与 MAX_RATIO', () => {
      expect(calculateSplitRatioWithOffset(50, container, 0, 16)).toBe(0.15)
      expect(calculateSplitRatioWithOffset(2000, container, 0, 16)).toBe(0.85)
    })
  })
})


