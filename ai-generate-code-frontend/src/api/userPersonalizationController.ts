/* eslint-disable */
// @ts-ignore
import request from '@/request'

import * as API from './types'

/** 此处后端没有提供注释 GET /user/personalization */
export function userPersonalizationUsingGet({ options }: { options?: { [key: string]: unknown } }) {
  return request<API.BaseResponseUserPersonalizationVO>('/user/personalization', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 PUT /user/personalization */
export function userPersonalizationUsingPut({
  body,
  options,
}: {
  body: API.UserPersonalizationUpdateRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/user/personalization', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
