/* eslint-disable */
// @ts-ignore
import request from '@/request'

import * as API from './types'

/** 此处后端没有提供注释 POST /loop/add */
export function loopAddUsingPost({
  body,
  options,
}: {
  body: API.LoopAddRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseLong>('/loop/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/admin/list/page/vo */
export function loopAdminListPageVoUsingPost({
  body,
  options,
}: {
  body: API.LoopQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListLoopVO>('/loop/admin/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/admin/update */
export function loopAdminUpdateUsingPost({
  body,
  options,
}: {
  body: API.LoopUpdateRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/admin/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/apply */
export function loopApplyUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.LoopApplyUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/apply', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/delete */
export function loopOpenApiDeleteUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.LoopOpenApiDeleteUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/delete', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/admin/delete */
export function loopAdminDeleteUsingPost({
  params,
  options,
}: {
  params: API.LoopOpenApiDeleteUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/admin/delete', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /loop/get/vo */
export function loopGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.LoopGetVoUsingGetParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseLoopVO>('/loop/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/good/list/page/vo */
export function loopGoodListPageVoUsingPost({
  body,
  options,
}: {
  body: API.LoopQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListLoopVO>('/loop/good/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/import */
export function loopOpenApiImportUsingPost({
  body,
  options,
}: {
  body: API.LoopOpenApiImportUsingPostBody
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseLong>('/loop/import', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/my/list/page/vo */
export function loopMyListPageVoUsingPost({
  body,
  options,
}: {
  body: API.LoopQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListLoopVO>('/loop/my/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /loop/update */
export function loopUpdateUsingPost({
  body,
  options,
}: {
  body: API.LoopUpdateRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 公开探索 Loop（无 priority 过滤） POST /loop/public/list/page/vo */
export function loopPublicListPageVoUsingPost({
  body,
  options,
}: {
  body: API.LoopQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListLoopVO>('/loop/public/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 管理员分页查询 Loop 申请列表 POST /loop/admin/apply/list */
export function loopAdminApplyListUsingPost({
  body,
  options,
}: {
  body: API.LoopAdminListApplyUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListMapStringObject>('/loop/admin/apply/list', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 管理员通过 Loop 精选申请 POST /loop/admin/apply/approve */
export function loopAdminApplyApproveUsingPost({
  params,
  options,
}: {
  params: API.LoopAdminApplyApproveUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/admin/apply/approve', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 管理员拒绝 Loop 精选申请 POST /loop/admin/apply/reject */
export function loopAdminApplyRejectUsingPost({
  params,
  options,
}: {
  params: API.LoopAdminApplyRejectUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseVoid>('/loop/admin/apply/reject', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 从市场克隆公开 Loop 到当前用户个人库 POST /loop/market/import */
export function loopMarketImportUsingPost({
  params,
  options,
}: {
  params: API.LoopMarketImportUsingPostParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseLong>('/loop/market/import', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
