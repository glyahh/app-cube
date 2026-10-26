import { describe, expect, it } from 'vitest'
import { hasControlCharacter } from '../controlChars'

describe('hasControlCharacter', () => {
  it('拒绝 NUL、换行和 DEL，保留普通可见字符', () => {
    expect(hasControlCharacter('a\u0000b')).toBe(true)
    expect(hasControlCharacter('a\nb')).toBe(true)
    expect(hasControlCharacter('a\u007fb')).toBe(true)
    expect(hasControlCharacter('normal/path-1')).toBe(false)
    expect(hasControlCharacter('中文路径')).toBe(false)
  })

  it('按调用方阈值判断空格边界', () => {
    expect(hasControlCharacter('a b')).toBe(false)
    expect(hasControlCharacter('a b', 0x20)).toBe(true)
  })
})
