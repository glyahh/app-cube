import { describe, expect, it } from 'vitest'

import {
  codeByteLength,
  escapeHtml,
  getCachedHighlight,
  highlightCodeLines,
  setCachedHighlight,
} from '../codeHighlighter'

describe('codeHighlighter', () => {
  it('uses UTF-8 byte length for the worker threshold', () => {
    expect(codeByteLength('中')).toBe(3)
  })

  it('escapes unsupported languages without applying JavaScript spans', () => {
    const result = highlightCodeLines('<tag> const value = 1', 'unknown')
    expect(result.html).toBe('&lt;tag&gt; const value = 1')
    expect(result.html).not.toContain('<span')
  })

  it('returns independently balanced line markup', () => {
    const result = highlightCodeLines('const first = 1\nconst second = 2', 'typescript')
    expect(result.lines).toHaveLength(2)
    for (const line of result.lines) {
      expect((line.match(/<span/g) ?? []).length).toBe((line.match(/<\/span>/g) ?? []).length)
    }
  })

  it('reuses cached results only for the exact source', () => {
    const highlighted = { html: escapeHtml('const a = 1'), lines: [escapeHtml('const a = 1')] }
    setCachedHighlight('const a = 1', 'typescript', highlighted)
    expect(getCachedHighlight('const a = 1', 'typescript')).toEqual(highlighted)
    expect(getCachedHighlight('const a = 2', 'typescript')).toBeUndefined()
  })
})
