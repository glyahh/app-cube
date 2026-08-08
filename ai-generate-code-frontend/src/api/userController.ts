/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 POST /user/add */
export function userAddUsingPost({
  body,
  options,
}: {
  body: API.UserAddRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseLong>('/user/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/delete */
export function userOpenApiDeleteUsingPost({
  body,
  options,
}: {
  body: API.DeleteRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/user/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/get */
export function userGetUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.UserGetUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseUser>('/user/get', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/get/login */
export function userGetLoginUsingGet({
  options,
}: {
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseLoginUserVO>('/user/get/login', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/get/vo */
export function userGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.UserGetVoUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseUserVO>('/user/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/getInfo/${param0} */
export function userGetInfoIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.UserGetInfoIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { id: param0, ...queryParams } = params;

  return request<API.User>(`/user/getInfo/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/list */
export function userListUsingGet({
  options,
}: {
  options?: { [key: string]: unknown };
}) {
  return request<API.User[]>('/user/list', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/list/page/vo */
export function userListPageVoUsingPost({
  body,
  options,
}: {
  body: API.UserQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageUserVO>('/user/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/login */
export function userLoginUsingPost({
  body,
  options,
}: {
  body: API.UserLoginRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseLoginUserVO>('/user/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/logout */
export function userLogoutUsingPost({
  options,
}: {
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/user/logout', {
    method: 'POST',
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /user/page */
export function userPageUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.UserPageUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.PageUser>('/user/page', {
    method: 'GET',
    params: {
      ...params,
      page: undefined,
      ...params['page'],
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/register */
export function userRegisterUsingPost({
  body,
  options,
}: {
  body: API.UserRegisterRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseLong>('/user/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 DELETE /user/remove/${param0} */
export function userRemoveIdUsingDelete({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.UserRemoveIdUsingDeleteParams;
  options?: { [key: string]: unknown };
}) {
  const { id: param0, ...queryParams } = params;

  return request<boolean>(`/user/remove/${param0}`, {
    method: 'DELETE',
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/save */
export function userSaveUsingPost({
  body,
  options,
}: {
  body: API.User;
  options?: { [key: string]: unknown };
}) {
  return request<boolean>('/user/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /user/update */
export function userUpdateUsingPut({
  body,
  options,
}: {
  body: API.User;
  options?: { [key: string]: unknown };
}) {
  return request<boolean>('/user/update', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /user/update */
export function userUpdateUsingPost({
  body,
  options,
}: {
  body: API.UserUpdateRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/user/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 用户个性化配置 VO */
interface UserPersonalizationVO {
  appStyle?: string;
  answerStyle?: string;
}

/** 用户个性化配置更新请求 */
interface UserPersonalizationUpdateRequest {
  appStyle?: string;
  answerStyle?: string;
}

/** 获取当前登录用户的个性化配置 GET /user/personalization */
export function userPersonalizationGetUsingGet(options?: Record<string, any>) {
  return request<API.BaseResponseUserPersonalizationVO>('/user/personalization', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 保存当前登录用户的个性化配置 PUT /user/personalization */
export function userPersonalizationPutUsingPut({
  body,
  options,
}: {
  body: UserPersonalizationUpdateRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/user/personalization', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}
