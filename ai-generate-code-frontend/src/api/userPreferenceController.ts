/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 GET /user/preferences */
export function userPreferencesUsingGet({
  options,
}: {
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePreference>('/user/preferences', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 PUT /user/preferences */
export function userPreferencesUsingPut({
  body,
  options,
}: {
  body: API.Preference;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePreference>('/user/preferences', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}
