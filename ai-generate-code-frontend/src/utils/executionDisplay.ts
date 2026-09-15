const OMITTED = '部分内部执行信息已省略'

/** 只清理展示副本；流式尾部暂存可能尚未到齐的占位句。 */
export function cleanExecutionText(text: string, streaming = false, showPlaceholder = false): string {
  if (!text) return ''
  let visible = text.split(OMITTED).join('')
  if (streaming) {
    for (let length = OMITTED.length - 1; length > 0; length--) {
      if (visible.endsWith(OMITTED.slice(0, length))) {
        visible = visible.slice(0, -length)
        break
      }
    }
  }
  // 仅在明确启用占位提示时，原文非空但清洗后全空才给出占位提示，避免影响普通文本和思维链过滤
  if (showPlaceholder && !visible.trim() && text.trim()) return '（内容已被安全过滤）'
  return visible
}

/** 摘要已包含在详情中时只显示详情，不跨阶段去重。 */
export function thinkingLines(summary: string, details: string[]): string[] {
  const lines = [...new Set(details.flatMap(detail => detail.split('\n')).map(line => line.trim()).filter(Boolean))]
  const title = summary.trim()
  return title && !lines.some(line => line.includes(title)) ? [title, ...lines] : lines
}
