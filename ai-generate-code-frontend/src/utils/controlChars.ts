/** 控制字符检查；单独实现是为了让安全校验不依赖容易误配的正则。 */
export function hasControlCharacter(value: string, maxCode = 0x1f): boolean {
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0
    if (code <= maxCode || code === 0x7f) return true
  }
  return false
}
