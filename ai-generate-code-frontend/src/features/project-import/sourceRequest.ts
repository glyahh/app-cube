const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function key(ownerId: string) {
  if (!/^[1-9]\d*$/.test(ownerId)) throw new Error('请先登录。')
  return `app-cube:import-source-request:${ownerId}`
}
// 只保存找回标识；仓库、凭据和清单仍由登录用户的后端任务核实。
export function pendingSourceRequest(ownerId: string): string | null {
  const value = sessionStorage.getItem(key(ownerId))
  if (value !== null && !uuid.test(value)) throw new Error('来源记录无效，请保留当前页面。')
  return value
}
export function beginSourceRequest(ownerId: string): string {
  if (pendingSourceRequest(ownerId)) throw new Error('请先完成或取消当前来源。')
  const value = crypto.randomUUID()
  sessionStorage.setItem(key(ownerId), value)
  return value
}
export function finishSourceRequest(ownerId: string, requestId: string) {
  if (pendingSourceRequest(ownerId) === requestId) sessionStorage.removeItem(key(ownerId))
}
