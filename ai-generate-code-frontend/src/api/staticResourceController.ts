/* eslint-disable */
// @ts-ignore
import request from '@/request'

import * as API from './types'

/** 此处后端没有提供注释 GET /static/${param0}/&#42;&#42; */
export function staticDeployKeyUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.StaticDeployKeyUsingGetParams
  options?: { [key: string]: unknown }
}) {
  const { deployKey: param0, ...queryParams } = params

  return request<string>(`/static/${param0}/**`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}
