import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/types/api';

const elementMocks = vi.hoisted(() => ({
  message: vi.fn(),
  notification: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: elementMocks.message,
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
  ElNotification: elementMocks.notification,
}));

import request from '../request';

describe('request workflow conflict ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    elementMocks.notification.mockReturnValue({ close: vi.fn() });
  });

  it('suppresses the global surface only for an editor-owned workflow conflict and preserves its errorCode', async () => {
    const payload = { items: [{ materialTypeId: 'RAW-1', shortage: 3, unit: 'kg' }] };
    const promise = request.put('/F006/product-process-workflows/PT-A/draft', {}, {
      _handledErrorCodes: ['PRODUCT_PROCESS_WORKFLOW_CONFLICT'],
      adapter: rejectingAdapter(
        409,
        'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
        'reload or copy',
        payload,
      ),
    } as never);

    await expect(promise).rejects.toEqual(expect.objectContaining<ApiError>({
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
      status: 409,
      data: payload,
    }));
    await settleAsyncSurface();

    expect(elementMocks.notification).not.toHaveBeenCalled();
  });

  it('suppresses the generic JPA optimistic-lock surface only when the workflow editor owns its semantic code', async () => {
    const promise = request.put('/F006/product-process-workflows/PT-A/draft', {}, {
      _handledErrorCodes: [
        'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
        'OPTIMISTIC_LOCK_CONFLICT',
      ],
      adapter: rejectingAdapter(409, 'OPTIMISTIC_LOCK_CONFLICT', 'refresh latest data'),
    } as never);

    await expect(promise).rejects.toEqual(expect.objectContaining<ApiError>({
      code: 'OPTIMISTIC_LOCK_CONFLICT',
      status: 409,
      actionHint: 'refresh latest data',
    }));
    await settleAsyncSurface();

    expect(elementMocks.notification).not.toHaveBeenCalled();
  });

  it.each([
    [409, 'UNRELATED_RICH_CONFLICT'],
    [422, 'PRODUCT_PROCESS_WORKFLOW_CONFLICT'],
  ])('retains the global rich surface for status %s and errorCode %s', async (status, errorCode) => {
    const promise = request.put('/test', {}, {
      _handledErrorCodes: ['PRODUCT_PROCESS_WORKFLOW_CONFLICT'],
      adapter: rejectingAdapter(status, errorCode, 'take the documented next action'),
    } as never);

    await expect(promise).rejects.toBeInstanceOf(ApiError);
    await waitForSurface(() => elementMocks.notification.mock.calls.length > 0);

    expect(elementMocks.notification).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining(`Request failed with ${errorCode}`),
    }));
  });
});

function rejectingAdapter(
  status: number,
  errorCode: string,
  actionHint: string,
  payload?: unknown,
): AxiosAdapter {
  return async (config) => {
    const response: AxiosResponse = {
      status,
      statusText: 'Rejected',
      headers: new AxiosHeaders(),
      config,
      data: {
        success: false,
        code: status,
        errorCode,
        message: `Request failed with ${errorCode}`,
        actionHint,
        severity: 'warning',
        data: payload,
      },
    };
    throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}

// 全局错误提示是 interceptor 里 fire-and-forget 的异步分支 (showRichError 内部
// `await import('element-plus')`), 断言前必须等它落地。
//
// 2026-08-04: 原实现是写死的 `setTimeout(50ms)` —— CI 上解析一次动态 import 超过 50ms
// 是常事, 于是"该弹的没弹"变成 `Number of calls: 0` 随机红 (实测挂过 PR #2275)。
// 正例改成轮询到条件成立: 命中即返回 (通常一两个 tick), 超时预算给足。
async function waitForSurface(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

// 反例 (断言"没有弹") 没有可等的正向信号, 只能等满预算 —— 等太短会变成**假绿**
// (提示其实弹了, 只是断言跑在它前面), 那比红更糟。故意给到 50ms 的 10 倍。
async function settleAsyncSurface(timeoutMs = 500): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, timeoutMs));
}
