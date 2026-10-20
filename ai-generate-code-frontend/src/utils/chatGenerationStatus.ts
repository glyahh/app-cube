/** 与后端 ChatHistoryConstant 保持一致 */
export const GENERATION_FAILED_MESSAGE = '[生成失败] 代码生成流异常中断，请重试。'
export const GENERATION_INTERRUPTED_MARKER = '[中断]'
export const GENERATION_INTERRUPTED_USER_MESSAGE = '本次生成已被中断，可继续对话后重试。'

const LEGACY_AI_ERROR_PREFIX = 'AI回复失败'

export type GenerationStatusVariant = 'failed' | 'interrupted'

export type GenerationStatusSegmentInput = {
  kind: 'generation_status'
  variant: GenerationStatusVariant
  message: string
  segmentId?: string
}

type MarkdownLikeSegment = { kind: 'markdown'; content: string }
type SegmentLike = MarkdownLikeSegment | { kind: string; content?: string }

export function isGenerationFailedText(text: string): boolean {
  const t = (text ?? '').trim()
  if (!t) return false
  if (t === GENERATION_FAILED_MESSAGE) return true
  return t.startsWith(LEGACY_AI_ERROR_PREFIX)
}

export function splitInterruptedTail(text: string): { body: string; hasInterrupt: boolean } {
  const raw = text ?? ''
  const marker = GENERATION_INTERRUPTED_MARKER
  const suffix = `\n\n${marker}`
  if (raw.endsWith(suffix) || raw.trimEnd() === marker) {
    const body = raw.endsWith(suffix) ? raw.slice(0, -suffix.length) : raw.replace(/\s*\[中断\]\s*$/, '')
    return { body, hasInterrupt: true }
  }
  return { body: raw, hasInterrupt: false }
}

function stripFailedLineFromMarkdown(content: string): string {
  const lines = (content ?? '').split(/\r?\n/)
  const kept = lines.filter((line) => {
    const t = line.trim()
    if (!t) return true
    if (t === GENERATION_FAILED_MESSAGE) return false
    if (t.startsWith(LEGACY_AI_ERROR_PREFIX)) return false
    if (t === GENERATION_INTERRUPTED_MARKER) return false
    return true
  })
  return kept.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

/**
 * 将助手消息中的失败/中断标记转为 generation_status 卡片，并清理 markdown 中的重复行。
 */
export function injectGenerationStatusSegments<T extends SegmentLike>(
  segments: T[],
  rawText: string,
): Array<T | GenerationStatusSegmentInput> {
  const raw = rawText ?? ''

  if (isGenerationFailedText(raw)) {
    return [
      {
        kind: 'generation_status',
        variant: 'failed',
        message: GENERATION_FAILED_MESSAGE,
      },
    ]
  }

  const { body, hasInterrupt } = splitInterruptedTail(raw)
  const out: Array<T | GenerationStatusSegmentInput> = []

  for (const seg of segments) {
    if (seg.kind !== 'markdown') {
      out.push(seg)
      continue
    }
    const cleaned = stripFailedLineFromMarkdown(seg.content ?? '')
    if (cleaned) {
      out.push({ ...seg, content: cleaned } as T)
    }
  }

  const hasFailedInSegments = out.some(
    (s) => s.kind === 'generation_status' && (s as GenerationStatusSegmentInput).variant === 'failed',
  )
  const bodyTrim = body.trim()
  if (!hasFailedInSegments && bodyTrim && isGenerationFailedText(bodyTrim)) {
    return [
      {
        kind: 'generation_status',
        variant: 'failed',
        message: GENERATION_FAILED_MESSAGE,
      },
    ]
  }

  if (hasInterrupt) {
    out.push({
      kind: 'generation_status',
      variant: 'interrupted',
      message: GENERATION_INTERRUPTED_USER_MESSAGE,
    })
  }

  return out
}
