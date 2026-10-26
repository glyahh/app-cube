import { highlightCodeLines } from '@/utils/codeHighlighter'

interface HighlightRequest {
  type: 'highlight' | 'cancel'
  requestId: number
  code?: string
  language?: string
}

const cancelled = new Set<number>()

self.onmessage = (event: MessageEvent<HighlightRequest>) => {
  const request = event.data
  if (request.type === 'cancel') {
    cancelled.add(request.requestId)
    return
  }

  try {
    const result = highlightCodeLines(request.code ?? '', request.language ?? 'text')
    if (cancelled.delete(request.requestId)) return
    const lines = result.lines
    self.postMessage({
      type: 'result',
      requestId: request.requestId,
      lines,
      lineCount: lines.length,
      maxColumns: lines.reduce(
        (max, line) => Math.max(max, line.replace(/<[^>]+>/g, '').length),
        0,
      ),
    })
  } catch (error) {
    if (cancelled.delete(request.requestId)) return
    self.postMessage({
      type: 'error',
      requestId: request.requestId,
      message: error instanceof Error ? error.message : String(error),
    })
  }
}
