import { parseMarkdownWithCode, type TextSegment } from './markdownParser'

export interface MarkdownRenderBlock extends TextSegment {
  id: string
  status: 'active' | 'complete'
}

function isClosed(source: string, block: TextSegment): boolean {
  if (block.type !== 'code') return source.endsWith('\n') || source.length === 0
  const language = block.language ?? 'text'
  const marker = source.lastIndexOf('```')
  if (marker < 0) return false
  return source.slice(marker).trim().startsWith('```') && source.trimEnd().endsWith('```')
}

export class IncrementalMarkdownParser {
  private source = ''
  private blocks: MarkdownRenderBlock[] = []
  private sequence = 0

  append(chunk: string): MarkdownRenderBlock[] {
    if (!chunk) return this.blocks
    this.source += chunk
    return this.rebuild(false)
  }

  update(snapshot: string): MarkdownRenderBlock[] {
    if (snapshot === this.source) return this.blocks
    if (!snapshot.startsWith(this.source)) {
      this.source = ''
      this.blocks = []
    }
    this.source = snapshot
    return this.rebuild(false)
  }

  finish(): MarkdownRenderBlock[] {
    return this.rebuild(true)
  }

  getBlocks(): MarkdownRenderBlock[] {
    return this.blocks
  }

  private rebuild(forceComplete: boolean): MarkdownRenderBlock[] {
    const normalized = this.source.replace(/(^|\r?\n) {4,}(`{3,})/g, '$1$2')
    const parsed = parseMarkdownWithCode(normalized)
    const activeIndex = forceComplete ? -1 : parsed.length - 1
    const next: MarkdownRenderBlock[] = []
    parsed.forEach((segment, index) => {
      const previous = this.blocks[index]
      const complete = forceComplete || index < activeIndex || isClosed(this.source, segment)
      const same = previous && previous.type === segment.type && previous.content === segment.content &&
        previous.language === segment.language && previous.fileLabel === segment.fileLabel && previous.status === (complete ? 'complete' : 'active')
      next.push(same ? previous : {
        ...segment,
        id: previous?.id ?? `md-block-${this.sequence++}`,
        status: complete ? 'complete' : 'active',
      })
    })
    this.blocks = next
    return next
  }
}
