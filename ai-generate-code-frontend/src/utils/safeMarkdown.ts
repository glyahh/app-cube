import { Marked } from 'marked'

const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;')
  .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const markdown = new Marked({ breaks: false, renderer: {
  html({ text }) { return escape(text) },
  link({ href, tokens }) {
    const label = this.parser.parseInline(tokens)
    // 禁止脚本、data URL 与协议相对链接，不通过 HTML 属性拼接用户输入。
    if (!/^(https?:\/\/|\/(?!\/)|#)/i.test(href) || /[\u0000-\u0020]/.test(href)) return label
    return `<a href="${escape(href)}" rel="noopener noreferrer">${label}</a>`
  },
  image({ text }) { return escape(text) },
} })

export function renderSafeMarkdown(text: string): string {
  return markdown.parse(text ?? '', { async: false }) as string
}
