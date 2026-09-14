import { describe, expect, it } from 'vitest'
import { renderSafeMarkdown } from '../safeMarkdown'

describe('safeMarkdown', () => {
  it('escapes raw HTML and disallows script links', () => {
    const html = renderSafeMarkdown('<img src=x onerror=alert(1)>\n\n[x](javascript:alert(1))')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href="javascript:')
  })
  it('keeps legitimate paragraphs, links, and fenced code', () => {
    const html = renderSafeMarkdown('一段\n\n\n\n二段\n\n[作品](/static/demo)\n\n```js\na\n\nb\n```')
    expect(html).not.toContain('<br>')
    expect(html).toContain('href="/static/demo"')
    expect(html).toContain('a\n\nb')
  })
})
