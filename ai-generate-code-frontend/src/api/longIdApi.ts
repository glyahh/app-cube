import request from '@/request'
import { appLoopListVoUsingPost } from '@/api/appLoopController'
import { loopGetVoUsingGet, loopUpdateUsingPost } from '@/api/loopController'
import type { BaseResponseLoopVO, BaseResponseVoid, LoopUpdateRequest } from '@/api/types'

export type LongId = string | number

const asGeneratedNumber = (id: LongId) => id as number

export function appLoopListVoByAppId(appId: LongId) {
  return appLoopListVoUsingPost({
    params: { appId: asGeneratedNumber(appId) },
  })
}

export function appLoopBatchAddByAppId(appId: LongId, loopIds: LongId[]) {
  return request<BaseResponseVoid>('/app/loop/batch/add', {
    method: 'POST',
    params: { appId },
    data: loopIds,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function loopGetVoById(id: LongId) {
  return loopGetVoUsingGet({
    params: { id: asGeneratedNumber(id) },
  }) as ReturnType<typeof request<BaseResponseLoopVO>>
}

export function loopUpdateById(id: LongId, payload: Omit<LoopUpdateRequest, 'id'>) {
  return loopUpdateUsingPost({
    body: { id: asGeneratedNumber(id), ...payload },
  }) as ReturnType<typeof request<BaseResponseVoid>>
}
