/* eslint-disable */
// @ts-ignore
import request from '@/request'

import * as API from './types'

/** 此处后端没有提供注释 POST /app/loop/add */
export function appLoopAddUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppLoopAddUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/app/loop/add', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/loop/bind */
export function appLoopBindUsingPost({
  params,
  body,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppLoopBindUsingPostParams
  body: API.AppLoopBindUsingPostBody
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/app/loop/bind', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    params: {
      ...params,
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/loop/list/vo */
export function appLoopListVoUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppLoopListVoUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListMapStringObject>('/app/loop/list/vo', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/loop/remove */
export function appLoopRemoveUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppLoopRemoveUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/app/loop/remove', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
