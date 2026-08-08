/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 POST /chatHistory/admin */
export function chatHistoryAdminUsingPost({
  body,
  options,
}: {
  body: API.ChatHistoryQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageChatHistory>('/chatHistory/admin', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /chatHistory/app/${param0} */
export function chatHistoryAppAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryAppAppIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseAppChatHistoryPageVO>(
    `/chatHistory/app/${param0}`,
    {
      method: 'GET',
      params: {
        // size has a default value: 10
        size: '10',
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /chatHistory/deleteByAppId/${param0} */
export function chatHistoryDeleteByAppIdAppIdUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryDeleteByAppIdAppIdUsingPostParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseBoolean>(
    `/chatHistory/deleteByAppId/${param0}`,
    {
      method: 'POST',
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /chatHistory/export/${param0} */
export function chatHistoryOpenApiExportAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryOpenApiExportAppIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseListChatHistoryVO>(
    `/chatHistory/export/${param0}`,
    {
      method: 'GET',
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /chatHistory/myChatHistory */
export function chatHistoryMyChatHistoryUsingPost({
  body,
  options,
}: {
  body: API.ChatHistoryQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageUserChatHistoryItemVO>(
    '/chatHistory/myChatHistory',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /chatHistory/roundCount/${param0} */
export function chatHistoryRoundCountAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryRoundCountAppIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseInteger>(`/chatHistory/roundCount/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /chatHistory/save */
export function chatHistorySaveUsingPost({
  body,
  options,
}: {
  body: API.ChatHistory;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/chatHistory/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 兼容旧页面使用的自动生成函数名，实际请求路径保持与后端 OpenAPI 一致。 */
export const chatHistoryMyUsingPost = chatHistoryMyChatHistoryUsingPost;
export const chatHistoryDeleteByAppIdUsingPost = chatHistoryDeleteByAppIdAppIdUsingPost;
