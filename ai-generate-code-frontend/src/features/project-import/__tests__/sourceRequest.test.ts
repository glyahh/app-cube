import { beforeEach, expect, it } from 'vitest'
import { pendingSourceRequest, beginSourceRequest, finishSourceRequest } from '../sourceRequest'
beforeEach(() => sessionStorage.clear())
it('标识按用户隔离，仅存 UUID，不允许覆盖未完成来源', () => {
  const id = beginSourceRequest('12')
  expect(pendingSourceRequest('12')).toBe(id)
  expect(pendingSourceRequest('13')).toBeNull()
  expect(sessionStorage.getItem('app-cube:import-source-request:12')).toBe(id)
  expect(() => beginSourceRequest('12')).toThrow('取消')
  expect(() => beginSourceRequest('0')).toThrow('登录')
})
it('迟到的旧请求不能删除新用户或新来源记录', () => {
  const old = beginSourceRequest('12'),
    other = beginSourceRequest('13')
  finishSourceRequest('12', other)
  expect(pendingSourceRequest('12')).toBe(old)
  finishSourceRequest('12', old)
  const next = beginSourceRequest('12')
  finishSourceRequest('12', old)
  expect(pendingSourceRequest('12')).toBe(next)
  expect(pendingSourceRequest('13')).toBe(other)
})
it('损坏记录明确失败，不当成可重复创建的新请求', () => {
  sessionStorage.setItem('app-cube:import-source-request:12', 'bad')
  expect(() => pendingSourceRequest('12')).toThrow('无效')
  expect(() => beginSourceRequest('12')).toThrow('无效')
  expect(sessionStorage.getItem('app-cube:import-source-request:12')).toBe('bad')
})
