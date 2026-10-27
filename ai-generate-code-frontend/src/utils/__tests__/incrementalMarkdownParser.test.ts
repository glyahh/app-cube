import { describe, expect, it } from 'vitest'

import { IncrementalMarkdownParser } from '../incrementalMarkdownParser'
import { parseMarkdownWithCode } from '../markdownParser'

describe('IncrementalMarkdownParser', () => {
  it('matches full parsing when input arrives in 1-8 character chunks', () => {
    const source =
      '说明\n### src/App.vue\n```vue\n<template>\n  <main>Hello</main>\n</template>\n```\n完成'
    const parser = new IncrementalMarkdownParser()

    for (let index = 0; index < source.length; ) {
      const width = (index % 8) + 1
      parser.append(source.slice(index, index + width))
      index += width
    }
    const actual = parser.finish()

    expect(actual.map(({ id: _id, status: _status, ...block }) => block)).toEqual(
      parseMarkdownWithCode(source),
    )
    expect(actual.every((block) => block.status === 'complete')).toBe(true)
  })

  it('keeps a fence split across chunks active until the closing line arrives', () => {
    const parser = new IncrementalMarkdownParser()
    parser.append('```ty')
    parser.append('pescript\nconst answer = ')
    const active = parser.getBlocks()

    expect(active).toHaveLength(1)
    expect(active[0]).toMatchObject({ type: 'code', language: 'typescript', status: 'active' })
    expect(active[0]?.content).toContain('const answer =')

    parser.append('42\n``')
    parser.append('`\n')
    const complete = parser.finish()
    expect(complete[0]).toMatchObject({ type: 'code', language: 'typescript', status: 'complete' })
    expect(complete[0]?.content).toBe('const answer = 42')
  })

  it('handles CRLF, longer fences, language info, and file labels', () => {
    const source =
      '### src/main.ts\r\n\r\n    ````typescript strict\r\nlet ready = true\r\n    ````\r\n'
    const parser = new IncrementalMarkdownParser()
    parser.append(source.slice(0, 17))
    parser.append(source.slice(17))
    const [block] = parser.finish()

    expect(block).toMatchObject({
      type: 'code',
      language: 'typescript',
      fileLabel: 'src/main.ts',
      content: 'let ready = true',
      status: 'complete',
    })
  })

  it('freezes completed blocks and only replaces the active tail', () => {
    const parser = new IncrementalMarkdownParser()
    parser.append('intro\n```js\nconst a = 1\n```\nnext')
    const before = parser.getBlocks()
    expect(before).toHaveLength(3)
    expect(before[0]?.status).toBe('complete')
    expect(before[1]?.status).toBe('complete')
    expect(before[2]?.status).toBe('active')

    parser.append(' text')
    const after = parser.getBlocks()
    expect(after[0]).toBe(before[0])
    expect(after[1]).toBe(before[1])
    expect(after[2]).not.toBe(before[2])
  })

  it('rebuilds from scratch when update receives a non-append snapshot', () => {
    const parser = new IncrementalMarkdownParser()
    parser.update('old')
    const blocks = parser.update('```html\n<div />\n```')

    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toMatchObject({ type: 'code', language: 'html', content: '<div />' })
  })
})
