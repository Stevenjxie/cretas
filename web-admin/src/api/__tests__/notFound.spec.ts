import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { isNotFoundError } from '../notFound';
import { ApiError } from '@/types/api';

describe('isNotFoundError — 兼容 body-code 404 / HTTP 404 (audit R4)', () => {
  it('ApiError 数字 body-code 404 (后端 ApiResponse.error(404) 最常见) → true', () => {
    // request.ts: new ApiError(message, data.code) — data.code 是后端 Integer 404 → 运行时数字
    expect(isNotFoundError(new ApiError('产品未建 BOM 配方', 404 as unknown as string))).toBe(true);
  });

  it('ApiError 字符串 "404" → true', () => {
    expect(isNotFoundError(new ApiError('x', '404'))).toBe(true);
  });

  it("ApiError code 'NOT_FOUND' → true", () => {
    expect(isNotFoundError(new ApiError('x', 'NOT_FOUND'))).toBe(true);
  });

  it('ApiError status 404 → true', () => {
    expect(isNotFoundError(new ApiError('x', 'X', 404))).toBe(true);
  });

  it('真 HTTP 404 axios error → true', () => {
    const e = new AxiosError('Not Found', 'ERR_BAD_REQUEST');
    e.response = { status: 404, data: {}, statusText: 'Not Found', headers: {}, config: { headers: new AxiosHeaders() } };
    expect(isNotFoundError(e)).toBe(true);
  });

  it('业务 500 (数字 code) → false', () => {
    expect(isNotFoundError(new ApiError('服务器错误', 500 as unknown as string))).toBe(false);
  });

  it('其它业务 code → false', () => {
    expect(isNotFoundError(new ApiError('权限不足', 'FORBIDDEN'))).toBe(false);
  });

  it('网络错误 (无 response) → false (不误判为 NO_BOM)', () => {
    const e = new AxiosError('Network Error', 'ERR_NETWORK');
    expect(isNotFoundError(e)).toBe(false);
  });

  it('null / undefined → false', () => {
    expect(isNotFoundError(null)).toBe(false);
    expect(isNotFoundError(undefined)).toBe(false);
  });
});
