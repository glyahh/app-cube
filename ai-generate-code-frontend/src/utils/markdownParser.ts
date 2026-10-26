/**
 * Markdown 代码块解析工具
 * 用于将 AI 返回的 markdown 格式文本解析成代码块和普通文本片段
 *
 * 结束围栏必须与 CommonMark 一致：独占一行（行前最多 3 个空格），仅由反引号与行尾空白组成。
 * 这样不会把源码里出现的连续 ```（如字符串 "```"）误当作围栏结束。
 */

export interface TextSegment {
  type: 'text' | 'code'
  content: string
  language?: string // 代码块的语言类型（如 'css', 'javascript', 'html' 等）
  /** 工作流等多文件场景：`### 文件名` 独占区时并入代码块展示，不单独当正文 */
  fileLabel?: string
}

/** `### path` 且整段 textBefore 仅含该行与空白时，解析为下一代码块的展示文件名 */
const ONLY_FILE_HEADING_RE = /^[\s\r\n]*###\s+(\S+)\s*[\s\r\n]*$/

function fileLabelFromTextBeforeFence(textBefore: string): string | undefined {
  if (!textBefore || !textBefore.trim()) return undefined
  const m = textBefore.match(ONLY_FILE_HEADING_RE)
  return m?.[1]
}

/** 行首可选 0–3 空格 + ``` + info，整行以换行结束 */
const OPEN_FENCE_LINE_RE = /(?:^|[\r\n])( {0,3})(`{3,})([^\r\n]*)\r?\n/

/**
 * 判断一行是否为「闭合围栏行」：缩进后反引号串长度 >= minRun，且其后仅有空白
 */
/**
 * 围栏代码块正文：去掉闭合围栏行之前的尾部空白。
 * 避免模型在 content 末尾多带 `\n` 时，UI 在最后一行代码下多出一行空行。
 */
export function normalizeFenceCodeContent(content: string): string {
  return content.replace(/\s+$/, '')
}

export function isClosingFenceLine(lineRaw: string, minRun: number): boolean {
  const line = lineRaw.replace(/\r$/, '')
  let i = 0
  while (i < line.length && i < 4 && line[i] === ' ') i++

  let ticks = 0
  while (i + ticks < line.length && line[i + ticks] === '`') ticks++
  i += ticks
  if (ticks < minRun) return false

  while (i < line.length) {
    if (line[i] !== ' ' && line[i] !== '\t') return false
    i++
  }
  return true
}

/**
 * 从 text[from] 起按行扫描，找到第一行闭合围栏；返回该行起始下标与消费结束下标（含围栏行后的换行）
 */
export function findClosingFenceLineRange(
  text: string,
  from: number,
  minFenceRun: number,
): { closeLineStart: number; consumeEnd: number } | null {
  const n = text.length
  let pos = from

  while (pos < n) {
    const lineEndIdx = text.indexOf('\n', pos)
    const end = lineEndIdx === -1 ? n : lineEndIdx
    let line = text.slice(pos, end)
    if (line.endsWith('\r')) line = line.slice(0, -1)

    if (isClosingFenceLine(line, minFenceRun)) {
      const consumeEnd = lineEndIdx === -1 ? n : lineEndIdx + 1
      return { closeLineStart: pos, consumeEnd }
    }
    if (lineEndIdx === -1) break
    pos = lineEndIdx + 1
  }
  return null
}

/**
 * 流式工具写入 buffer：在整块代码 buffer 内查找「行对齐」的结束围栏（与 parseMarkdownWithCode 规则一致）
 */
export function findLineAlignedClosingFenceInBuffer(
  buffer: string,
  minBackticks = 3,
): { codeEnd: number; consumeEnd: number } | null {
  const found = findClosingFenceLineRange(buffer, 0, minBackticks)
  if (!found) return null
  return { codeEnd: found.closeLineStart, consumeEnd: found.consumeEnd }
}

/**
 * 解析包含代码块的 markdown 文本
 * 支持格式：```语言名\n代码内容\n```（结束 ``` 必须独占一行）
 * 支持流式输出：只有开始围栏、尚无结束围栏时，将剩余内容视为未完成代码块
 */
export function parseMarkdownWithCode(text: string): TextSegment[] {
  const segments: TextSegment[] = []
  let cursor = 0
  const n = text.length

  while (cursor < n) {
    const slice = text.slice(cursor)
    const m = slice.match(OPEN_FENCE_LINE_RE)
    if (!m || m.index === undefined) {
      const rest = text.slice(cursor)
      if (rest.trim()) {
        segments.push({ type: 'text', content: rest })
      }
      break
    }

    const relIdx = m.index
    const absOpenMatchStart = cursor + relIdx
    const openFullLen = m[0].length
    const absOpenEnd = absOpenMatchStart + openFullLen
    const fenceRunLen = (m[2] ?? '```').length
    const info = (m[3] ?? '').trim()
    const language = (info.split(/\s+/)[0] || 'text').toLowerCase()

    let fileLabel: string | undefined
    if (absOpenMatchStart > cursor) {
      const textBefore = text.slice(cursor, absOpenMatchStart)
      const headingOnly = fileLabelFromTextBeforeFence(textBefore)
      if (headingOnly) {
        fileLabel = headingOnly
      } else if (textBefore.trim()) {
        segments.push({ type: 'text', content: textBefore })
      }
    }

    const contentStart = absOpenEnd
    const close = findClosingFenceLineRange(text, contentStart, fenceRunLen)

    if (!close) {
      // opening 前的文本已在上方处理，这里只追加未完成代码块
      const afterOpen = text.slice(contentStart)
      segments.push({
        type: 'code',
        content: afterOpen,
        language: language,
        ...(fileLabel ? { fileLabel } : {}),
      })
      break
    }

    const codeRaw = text.slice(contentStart, close.closeLineStart)
    const codeContent = normalizeFenceCodeContent(codeRaw)

    segments.push({
      type: 'code',
      content: codeContent,
      language: language,
      ...(fileLabel ? { fileLabel } : {}),
    })

    cursor = close.consumeEnd
  }

  // 兜底：末尾短小“代码收尾”并入上一代码块（与旧逻辑一致，处理极端截断）
  if (segments.length >= 2) {
    const last = segments[segments.length - 1]
    const prev = segments[segments.length - 2]
    if (prev && last && prev.type === 'code' && last.type === 'text' && isCodeTail(last.content)) {
      const tail = last.content.trimEnd()
      prev.content = `${prev.content}\n${tail}`
      segments.pop()
    }
  }

  if (segments.length === 0) {
    segments.push({
      type: 'text',
      content: text,
    })
  }

  // 强兜底：把“疑似泄漏出的代码文本”并回前一个代码块，避免白底文本溢出
  return mergeLeakedCodeLikeText(segments)
}

/**
 * 判断一段文本是否更像“代码结尾”而不是普通说明文本
 */
function isCodeTail(raw: string): boolean {
  if (!raw) return false
  const t = raw.trim()
  if (!t) return false

  const lines = t.split(/\r?\n/)
  if (lines.length > 10) return false

  const chineseMatch = t.match(/[\u4e00-\u9fa5]/g)
  if (chineseMatch && chineseMatch.length > 10) {
    return false
  }

  let codeLikeLines = 0
  let nonEmptyLines = 0

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue
    nonEmptyLines += 1

    const startsWithComment =
      trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*')
    const startsWithClosingTag = trimmed.startsWith('</')
    const endsWithCodePunct = /[;{}>\]]$/.test(trimmed)

    if (startsWithComment || startsWithClosingTag || endsWithCodePunct) {
      codeLikeLines += 1
    }
  }

  if (nonEmptyLines === 0) return false

  return codeLikeLines / nonEmptyLines >= 0.6
}

/**
 * 把紧跟在代码块后的“代码样文本”并回代码块：
 * 目标是兜住模型输出中围栏异常导致的泄漏，避免代码区外出现大段白底文本。
 */
function mergeLeakedCodeLikeText(segments: TextSegment[]): TextSegment[] {
  if (!segments.length) return segments
  const out: TextSegment[] = []

  for (const seg of segments) {
    const prev = out[out.length - 1]
    if (
      prev &&
      prev.type === 'code' &&
      seg.type === 'text' &&
      isLikelyLeakedCodeText(seg.content)
    ) {
      const leak = seg.content.trimEnd()
      prev.content = `${prev.content}\n${leak}`
      continue
    }
    out.push(seg)
  }
  return out
}

/**
 * 比 isCodeTail 更激进的判定：
 * - 允许更长行数
 * - 一旦出现高置信代码特征（关键字/DOM API/闭合标签/大量符号）即归并
 */
function isLikelyLeakedCodeText(raw: string): boolean {
  if (!raw) return false
  const text = raw.trim()
  if (!text) return false

  const lines = text.split(/\r?\n/)
  if (lines.length > 200) return false

  const chineseMatch = text.match(/[\u4e00-\u9fa5]/g)
  if (chineseMatch && chineseMatch.length > 40) return false

  const strongSignal =
    /(^|\n)\s*(const|let|var|function|class|if|for|while|switch|return|import|export)\b/.test(
      text,
    ) ||
    /document\.(querySelector|getElementById|createElement|addEventListener)\b/.test(text) ||
    /(^|\n)\s*<\/?[a-zA-Z][^>]*>\s*$/.test(text) ||
    /=>/.test(text)
  if (strongSignal) return true

  let nonEmpty = 0
  let codeLike = 0
  for (const line of lines) {
    const s = line.trim()
    if (!s) continue
    nonEmpty++
    const startsCode =
      /^(\/\/|\/\*|\*|<\/|\.?[#\w-]+\s*\{|const\b|let\b|var\b|if\s*\(|for\s*\(|while\s*\(|return\b)/.test(
        s,
      )
    const endsCode = /[;{}>\],)]$/.test(s)
    const hasOps = /[=<>:+\-*/]/.test(s)
    if (startsCode || endsCode || hasOps) codeLike++
  }
  if (nonEmpty === 0) return false
  return codeLike / nonEmpty >= 0.5
}
