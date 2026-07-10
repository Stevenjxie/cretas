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
    const promise = request.put('/F006/product-process-workflows/PT-A/draft', {}, {
      _handledErrorCodes: ['PRODUCT_PROCESS_WORKFLOW_CONFLICT'],
      adapter: rejectingAdapter(409, 'PRODUCT_PROCESS_WORKFLOW_CONFLICT', 'reload or copy'),
    } as never);

    await expect(promise).rejects.toEqual(expect.objectContaining<ApiError>({
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
      status: 409,
    }));
    await flushAsyncImports();

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
    await flushAsyncImports();

    expect(elementMocks.notification).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining(`Request failed with ${errorCode}`),
    }));
  });
});

function rejectingAdapter(
  status: number,
  errorCode: string,
  actionHint: string,
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
      },
    };
    throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}

async function flushAsyncImports(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 50));
}
