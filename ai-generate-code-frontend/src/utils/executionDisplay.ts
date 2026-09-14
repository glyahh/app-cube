const OMITTED = '部分内部执行信息已省略'

/** 只清理展示副本；流式尾部暂存可能尚未到齐的占位句。 */
export function cleanExecutionText(text: string, streaming = false): string {
  let visible = text.split(OMITTED).join('')
  if (streaming) {
    for (let length = OMITTED.length - 1; length > 0; length--) {
      if (visible.endsWith(OMITTED.slice(0, length))) {
        visible = visible.slice(0, -length)
        break
      }
    }
  }
  return visible
}

/** 摘要已包含在详情中时只显示详情，不跨阶段去重。 */
export function thinkingLines(summary: string, details: string[]): string[] {
  const lines = [...new Set(details.flatMap(detail => detail.split('\n')).map(line => line.trim()).filter(Boolean))]
  const title = summary.trim()
  return title && !lines.some(line => line.includes(title)) ? [title, ...lines] : lines
}
