import axios, { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const elementMocks = vi.hoisted(() => ({
  message: vi.fn(),
  notification: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: elementMocks.message,
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
  ElNotification: elementMocks.notification,
}));

vi.mock('@/router', () => ({
  default: { push: vi.fn(), currentRoute: { value: { fullPath: '/' } } },
}));

import request from '../request';

describe('401 handling separates a failed login from an expired session', () => {
  let refreshSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.clearAllMocks();
    elementMocks.notification.mockReturnValue({ close: vi.fn() });
    // 只有 refresh 走裸 axios.post; request 实例的调用不经过它。
    refreshSpy = vi.spyOn(axios, 'post').mockRejectedValue(new Error('no refresh cookie'));
  });

  it('surfaces the server credential message for a failed login and never attempts a token refresh', async () => {
    const promise = request.post(
      '/auth/unified-login',
      { username: 'MOCK_REST', password: '123456' },
      { adapter: rejecting401('用户名或密码错误', '请检查用户名和密码后重试', 'password') } as never,
    );

    await expect(promise).rejects.toBeTruthy();
    await waitForSurface(() => surfacedText().length > 0);

    expect(refreshSpy).not.toHaveBeenCalled();
    expect(surfacedText()).toContain('用户名或密码错误');
    expect(surfacedText()).toContain('请检查用户名和密码后重试');
    expect(surfacedText()).not.toContain('登录已过期');
  });

  it('still attempts a refresh for a non-auth 401 and reports the expired session', async () => {
    // request.ts 的 toast 去重是 module 级 Map + 2s 窗口, 同一条文案在窗口内只弹一次。
    // 不等满窗口, 本用例的断言就会依赖"前一个用例有没有弹过同一句" —— 那是顺序耦合的假信号。
    await flushToastDedup();

    const promise = request.get('/F006/dashboard/summary', {
      adapter: rejecting401('未授权', null, null),
    } as never);

    await expect(promise).rejects.toBeTruthy();
    await waitForSurface(() => surfacedText().length > 0);

    expect(refreshSpy).toHaveBeenCalledTimes(1);
    expect(surfacedText()).toContain('登录已过期');
  });

  it('clears the stale access token when the refresh fails', async () => {
    localStorage.setItem('cretas_access_token', 'stale-token');
    localStorage.setItem('cretas_user', '{"id":1}');

    const promise = request.get('/F006/dashboard/summary', {
      adapter: rejecting401('未授权', null, null),
    } as never);

    await expect(promise).rejects.toBeTruthy();
    // 清理发生在 refresh 失败的 catch 里, 与 toast 同步 —— 等清理落地即可, 不必等 toast
    // (同一句文案会被 2s 去重窗口吞掉, 等它只会白等满超时)。
    await waitForSurface(() => localStorage.getItem('cretas_user') === null);

    expect(localStorage.getItem('cretas_user')).toBeNull();
    expect(localStorage.getItem('cretas_access_token')).toBeNull();
  });
});

function rejecting401(
  message: string,
  actionHint: string | null,
  hintTarget: string | null,
): AxiosAdapter {
  return async (config) => {
    const response: AxiosResponse = {
      status: 401,
      statusText: 'Unauthorized',
      headers: new AxiosHeaders(),
      config,
      data: {
        success: false,
        code: 401,
        message,
        actionHint,
        severity: 'error',
        hintTarget,
        data: null,
      },
    };
    throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}

function surfacedText(): string {
  return [...elementMocks.message.mock.calls, ...elementMocks.notification.mock.calls]
    .map((call) => JSON.stringify(call[0]))
    .join(' ');
}

// 提示是 interceptor 里 fire-and-forget 的异步分支 (`await import('element-plus')`),
// 断言前必须等它落地 — 见 request.workflowConflict.spec.ts 里同一条踩坑记录。
// _TOAST_DEDUP_MS = 2000 (request.ts)。多给 100ms 余量。
async function flushToastDedup(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 2100));
}

async function waitForSurface(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}
