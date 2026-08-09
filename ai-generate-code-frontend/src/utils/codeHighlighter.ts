export const HIGHLIGHT_BYTE_THRESHOLD = 65_536
export const VIRTUAL_LINE_THRESHOLD = 2_000

export interface HighlightedCode {
  html: string
  lines: string[]
}

interface CachedHighlight extends HighlightedCode {
  source: string
  weight: number
}

const cache = new Map<string, CachedHighlight>()
let cacheWeight = 0
const CACHE_LIMIT = 8
const CACHE_WEIGHT_LIMIT = 12 * 1024 * 1024

export function codeByteLength(text: string): number {
  return new TextEncoder().encode(text).byteLength
}

export function codeHash(text: string): string {
  // FNV-1a is sufficient here because the complete source is also retained
  // in the cache entry and compared before a cached value is used.
  let hash = 2166136261
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16)
}

function cacheKey(code: string, language: string): string {
  return `${language.toLowerCase()}:${codeByteLength(code)}:${codeHash(code)}`
}

export function getCachedHighlight(code: string, language: string): HighlightedCode | undefined {
  const key = cacheKey(code, language)
  const cached = cache.get(key)
  if (!cached || cached.source !== code) return undefined
  // Refresh LRU order. The content hash is only a lookup accelerator; this
  // check prevents a rare 32-bit hash collision from changing rendered code.
  cache.delete(key)
  cache.set(key, cached)
  return { html: cached.html, lines: cached.lines }
}

export function setCachedHighlight(code: string, language: string, value: HighlightedCode): void {
  const key = cacheKey(code, language)
  const weight = codeByteLength(code) + value.html.length
  const previous = cache.get(key)
  if (previous) cacheWeight -= previous.weight
  cache.delete(key)
  cache.set(key, { ...value, source: code, weight })
  cacheWeight += weight
  while (cache.size > CACHE_LIMIT || cacheWeight > CACHE_WEIGHT_LIMIT) {
    const first = cache.keys().next().value as string | undefined
    if (!first) break
    const item = cache.get(first)
    cache.delete(first)
    if (item) cacheWeight -= item.weight
  }
}

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function decodeHtmlEntitiesOnce(text: string): string {
  return text
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}

export function maybeDecodePreEscapedHtml(raw: string, language: string): string {
  const lang = (language || 'text').toLowerCase()
  if (!(lang === 'html' || lang === 'xml' || lang === 'vue')) return raw
  const hasEntities = /&(lt|gt|amp|quot|#39);/.test(raw)
  if (!hasEntities || /[<>]/.test(raw)) return raw
  let out = raw
  for (let i = 0; i < 2; i += 1) {
    const decoded = decodeHtmlEntitiesOnce(out)
    if (decoded === out) break
    out = decoded
  }
  return out
}

function withStashedTokens(
  source: string,
  builders: Array<{ re: RegExp; className: string }>,
): { text: string; restore: (s: string) => string } {
  const stash: string[] = []
  let result = source
  builders.forEach(({ re, className }) => {
    result = result.replace(re, (match) => {
      const id = stash.length
      stash.push(`<span class="${className}">${match}</span>`)
      return `__TOK_${id}__`
    })
  })
  return { text: result, restore: (s) => s.replace(/__TOK_(\d+)__/g, (_, index) => stash[Number(index)] ?? '') }
}

function withStashedHighlightedSpans(source: string): { text: string; restore: (s: string) => string } {
  const stash: string[] = []
  const text = source.replace(/<span class="code-[^"]+">[\s\S]*?<\/span>/g, (match) => {
    const id = stash.length
    stash.push(match)
    return `__SPAN_${id}__`
  })
  return { text, restore: (s) => s.replace(/__SPAN_(\d+)__/g, (_, index) => stash[Number(index)] ?? '') }
}

function highlightJsLike(raw: string): string {
  const escaped = escapeHtml(raw)
  const { text, restore } = withStashedTokens(escaped, [
    { re: /\/\*[\s\S]*?\*\//g, className: 'code-comment' },
    { re: /\/\/[^\r\n]*/g, className: 'code-comment' },
    { re: /(["'`])(?:\\.|(?!\1)[\s\S])*?\1/g, className: 'code-string' },
  ])
  const primary = text
    .replace(/\b(const|let|var|function|return|if|else|switch|case|break|default|for|while|do|continue|import|from|export|class|extends|new|this|async|await|try|catch|finally|throw|typeof|instanceof|in|of|static|get|set|super|null|undefined|true|false)\b/g, '<span class="code-keyword">$1</span>')
    .replace(/\b(\d+(?:\.\d+)?)\b/g, '<span class="code-number">$1</span>')
  const { text: safeText, restore: restoreSpans } = withStashedHighlightedSpans(primary)
  return restore(restoreSpans(safeText
    .replace(/([=+\-*/%<>!&|^~?:]+)/g, '<span class="code-operator">$1</span>')
    .replace(/\b([A-Za-z_$][\w$]*)(?=\s*\()/g, '<span class="code-function">$1</span>')))
}

function highlightCss(raw: string): string {
  const escaped = escapeHtml(raw)
  const { text, restore } = withStashedTokens(escaped, [
    { re: /\/\*[\s\S]*?\*\//g, className: 'code-comment' },
    { re: /(["'])(?:\\.|(?!\1)[\s\S])*?\1/g, className: 'code-string' },
  ])
  return restore(text
    .replace(/([.#][\w-]+)/g, '<span class="code-selector">$1</span>')
    .replace(/(@[\w-]+)/g, '<span class="code-keyword">$1</span>')
    .replace(/([\w-]+)(\s*:)/g, '<span class="code-property">$1</span>$2')
    .replace(/(:\s*)([^;}{\n]+)(;?)/g, '$1<span class="code-value">$2</span>$3')
    .replace(/\b(\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|s|ms|deg)?)\b/g, '<span class="code-number">$1</span>'))
}

function highlightHtml(raw: string): string {
  const escaped = escapeHtml(raw)
  return escaped
    .replace(/&lt;!--[\s\S]*?--&gt;/g, '<span class="code-comment">$&</span>')
    .replace(/(&lt;\/?)([\w-]+)([\s\S]*?)(&gt;)/g, (_, prefix, tag, attrs, suffix) => {
      const safeAttrs = String(attrs).replace(/([:@\w-]+)(\s*=\s*)(["'])([\s\S]*?)(\3)/g, '<span class="code-attr">$1</span>$2$3<span class="code-string">$4</span>$5')
      return `${prefix}<span class="code-tag">${tag}</span>${safeAttrs}${suffix}`
    })
}

function highlightVue(raw: string): string {
  const blockRe = /(<script\b[^>]*>)([\s\S]*?)(<\/script>)|(<style\b[^>]*>)([\s\S]*?)(<\/style>)/gi
  let cursor = 0
  let out = ''
  let match: RegExpExecArray | null
  while ((match = blockRe.exec(raw)) !== null) {
    if (match.index > cursor) out += highlightHtml(raw.slice(cursor, match.index))
    if (match[1] != null) out += highlightHtml(match[1]) + highlightJsLike(match[2] ?? '') + highlightHtml(match[3] ?? '')
    else out += highlightHtml(match[4] ?? '') + highlightCss(match[5] ?? '') + highlightHtml(match[6] ?? '')
    cursor = match.index + match[0].length
  }
  return out + (cursor < raw.length ? highlightHtml(raw.slice(cursor)) : '')
}

export function highlightCode(raw: string, language = 'text'): string {
  const code = maybeDecodePreEscapedHtml(raw, language)
  const lang = language.toLowerCase()
  if (lang === 'vue') return highlightVue(code)
  if (['javascript', 'js', 'typescript', 'ts'].includes(lang)) return highlightJsLike(code)
  if (['css', 'scss', 'less'].includes(lang)) return highlightCss(code)
  if (lang === 'html' || lang === 'xml') return highlightHtml(code)
  return escapeHtml(code)
}

export function highlightCodeLines(raw: string, language = 'text'): HighlightedCode {
  // Highlight lines independently so a virtualized line never contains an
  // unclosed span whose matching tag lives outside the mounted viewport.
  const lines = raw.split(/\r?\n/).map((line) => highlightCode(line, language))
  return { html: lines.join('\n'), lines }
}
