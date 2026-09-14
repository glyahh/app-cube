import { describe, expect, it } from 'vitest'
import { cleanExecutionText, thinkingLines } from '../executionDisplay'

describe('execution display filtering', () => {
  const omitted = '部分内部执行信息已省略'
  it('holds every split prefix until the placeholder is complete', () => {
    for (let i = 1; i <= omitted.length; i++) {
      expect(cleanExecutionText('保留内容' + omitted.slice(0, i), true)).toBe('保留内容')
    }
    expect(cleanExecutionText('保留' + omitted + '文字')).toBe('保留文字')
    expect(cleanExecutionText('部分正常内容', true)).toBe('部分正常内容')
    expect(cleanExecutionText('部分', false)).toBe('部分')
  })
  it('deduplicates only within one stage', () => {
    expect(thinkingLines('检查完毕', ['检查完毕', '标签正常', '标签正常'])).toEqual(['检查完毕', '标签正常'])
    expect(thinkingLines('检查完毕', ['检查完毕，标签正常'])).toEqual(['检查完毕，标签正常'])
    expect(thinkingLines('检查完毕', ['标签正常'])).toEqual(['检查完毕', '标签正常'])
  })
})
