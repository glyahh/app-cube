import {
  findClosingFenceLineRange,
  normalizeFenceCodeContent,
  parseMarkdownWithCode,
} from '@/utils/markdownParser'

/**
 * 工具执行结果里：从全文提取「[工具调用] 写入文件」块
 * （结束围栏为行对齐，避免与文件内 ``` 冲突）
 */
export function extractToolWriteFileBlocksFromText(text: string): Array<{
  filePath: string
  lang?: string
  content: string
}> {
  const out: Array<{ filePath: string; lang?: string; content: string }> = []
  if (!text) return out

  const headerRe = /\[工具调用\]\s*写入文件\s+([^\r\n]+)\s*\r?\n/g
  let searchFrom = 0

  while (searchFrom < text.length) {
    headerRe.lastIndex = searchFrom
    const hm = headerRe.exec(text)
    if (!hm) break

    const filePath = (hm[1] ?? '').trim()
    if (!filePath) {
      searchFrom = hm.index + 1
      continue
    }

    const afterHeader = hm.index + hm[0].length
    const tail = text.slice(afterHeader)
    // 后端输出：header 后会先给一行「文件内容:」，再输出代码围栏
    // 因此这里允许 header 后的空行 + 可选「文件内容:」行，再匹配代码围栏
    const openM = tail.match(
      /^(?:\s*\r?\n)*(?:\s*文件内容:\s*\r?\n)?( {0,3})(`{3,})([^\r\n]*)\r?\n/,
    )
    if (!openM) {
      searchFrom = hm.index + 1
      continue
    }

    const fenceLen = (openM[2] ?? '```').length
    const lang = ((openM[3] ?? '').trim().split(/\s+/)[0] || undefined) as string | undefined
    const contentStart = afterHeader + openM[0].length
    const close = findClosingFenceLineRange(text, contentStart, fenceLen)
    if (!close) {
      searchFrom = hm.index + 1
      continue
    }

    const content = normalizeFenceCodeContent(text.slice(contentStart, close.closeLineStart))
    out.push({ filePath, lang, content })

    searchFrom = close.consumeEnd
  }

  return out
}

/**
 * 工具执行结果里：从全文提取「[工具调用] 修改文件」块的“替换后”内容
 * 用于历史回放时更新“生成文件”回显列表。
 */
export function extractToolModifyFileNewContentBlocksFromText(
  text: string,
): Array<{
  filePath: string
  content: string
}> {
  const out: Array<{ filePath: string; content: string }> = []
  if (!text) return out

  const headerRe = /\[工具调用\]\s*修改文件\s+([^\r\n]+)\s*\r?\n/g
  let searchFrom = 0

  while (searchFrom < text.length) {
    headerRe.lastIndex = searchFrom
    const hm = headerRe.exec(text)
    if (!hm) break

    const filePath = (hm[1] ?? '').trim()
    if (!filePath) {
      searchFrom = hm.index + 1
      continue
    }

    const afterHeader = hm.index + hm[0].length
    const tail = text.slice(afterHeader)

    const afterMarkerIdx = tail.indexOf('替换后:')
    if (afterMarkerIdx < 0) {
      searchFrom = hm.index + 1
      continue
    }

    const afterSection = tail.slice(afterMarkerIdx)
    const segments = parseMarkdownWithCode(afterSection)
    const firstCode = segments.find((s) => s.type === 'code')
    if (!firstCode) {
      searchFrom = hm.index + 1
      continue
    }

    out.push({ filePath, content: firstCode.content })
    searchFrom = afterHeader + afterMarkerIdx + afterSection.length
  }

  return out
}

