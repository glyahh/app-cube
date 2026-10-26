/* eslint-disable */
// @ts-ignore
import request from '@/request'

import * as API from './types'

/** 此处后端没有提供注释 POST /app/add */
export function appAddUsingPost({
  body,
  options,
}: {
  body: API.AppAddRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseLong>('/app/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/delete */
export function appAdminOpenApiDeleteUsingPost({
  body,
  options,
}: {
  body: API.DeleteRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/admin/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/admin/get/vo */
export function appAdminGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppAdminGetVoUsingGetParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseAppVO>('/app/admin/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/list/page/vo */
export function appAdminListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponsePageAppVO>('/app/admin/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/update */
export function appAdminUpdateUsingPost({
  body,
  options,
}: {
  body: API.AppAdminUpdateRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/admin/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/apply */
export function appApplyUsingPost({
  body,
  options,
}: {
  body: API.UserAppApplyRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/apply', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/apply/agree */
export function appApplyAgreeUsingPost({
  body,
  options,
}: {
  body: API.UserAppApplyHandleRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/apply/agree', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/apply/list/my/history */
export function appApplyListMyHistoryUsingPost({
  options,
}: {
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListApplyHistoryVO>('/app/apply/list/my/history', {
    method: 'POST',
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/apply/list/pending */
export function appApplyListPendingUsingPost({
  options,
}: {
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseListApplyVO>('/app/apply/list/pending', {
    method: 'POST',
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/apply/reject */
export function appApplyRejectUsingPost({
  body,
  options,
}: {
  body: API.UserAppApplyHandleRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/apply/reject', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/delete */
export function appOpenApiDeleteUsingPost({
  body,
  options,
}: {
  body: API.DeleteRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/deploy */
export function appDeployUsingPost({
  body,
  options,
}: {
  body: API.AppDeployRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseString>('/app/deploy', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/download/${param0} */
export function appDownloadAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppDownloadAppIdUsingGetParams
  options?: { [key: string]: unknown }
}) {
  const { appId: param0, ...queryParams } = params

  return request<unknown>(`/app/download/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/get/vo */
export function appGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppGetVoUsingGetParams
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseAppVO>('/app/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/good/list/page/vo */
export function appGoodListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponsePageAppVO>('/app/good/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/my/list/page/vo */
export function appMyListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponsePageAppVO>('/app/my/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/static/project-files/${param0} */
export function appStaticProjectFilesAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppStaticProjectFilesAppIdUsingGetParams
  options?: { [key: string]: unknown }
}) {
  const { appId: param0, ...queryParams } = params

  return request<API.BaseResponseListProjectFileVO>(`/app/static/project-files/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/undeploy */
export function appUndeployUsingPost({
  body,
  options,
}: {
  body: API.AppDeployRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/undeploy', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/update */
export function appUpdateUsingPost({
  body,
  options,
}: {
  body: API.AppUpdateRequest
  options?: { [key: string]: unknown }
}) {
  return request<API.BaseResponseBoolean>('/app/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
