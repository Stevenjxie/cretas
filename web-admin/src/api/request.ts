/**
 * Axios 请求封装
 * 统一处理请求拦截、响应拦截、错误处理
 * 注意：不在顶层导入 element-plus，避免循环依赖
 */
import axios, { AxiosInstance, AxiosError, InternalAxiosRequestConfig, AxiosRequestConfig, AxiosResponse } from 'axios';
import { ApiResponse, ApiError } from '@/types/api';
import type { ApiData } from '@/types/api';
import type { User } from '@/types/auth';

// 动态导入 ElMessage，避免循环依赖
// Apr 18 2026 UX 优化: error 类 toast 默认 3s 闪过对业务流程错误 (库存不足/并发冲突/
// 批次未分配) 太短 — 用户需时间看清具体原因 + 去依赖流程处理. 改成 duration: 0 (sticky,
// 必须用户手动关) + showClose: true. warning/success/info 保持 3s.
// Apr 28 2026 (Bug C): dedup identical error messages within a short window.
// qa-prompt v2.4 Phase 6 caught: 3 init APIs failing simultaneously emit
// 3 identical "服务暂时不可用,请稍后重试 (后端未就绪)" toasts. User sees
// noise. Dedupe by exact message+type within 2s window.
const _toastSeen: Map<string, number> = new Map();
const _TOAST_DEDUP_MS = 2000;

const showMessage = async (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'error') => {
  // Dedup error/warning toasts only — success/info usually deliberate.
  if (type === 'error' || type === 'warning') {
    const key = `${type}::${message}`;
    const now = Date.now();
    const last = _toastSeen.get(key);
    if (last !== undefined && now - last < _TOAST_DEDUP_MS) {
      _toastSeen.set(key, now);
      return; // suppress duplicate within window
    }
    _toastSeen.set(key, now);
    // Periodic cleanup to avoid unbounded map growth.
    if (_toastSeen.size > 50) {
      for (const [k, t] of _toastSeen) {
        if (now - t > _TOAST_DEDUP_MS * 2) _toastSeen.delete(k);
      }
    }
  }

  const { ElMessage } = await import('element-plus');
  ElMessage({
    message,
    type,
    duration: type === 'error' ? 0 : 3000,
    showClose: type === 'error',
  });
};

// Apr 18 2026 UX 进阶: 3 渠道错误呈现
//   severity=BLOCKING  → ElMessageBox.alert (必须点确定才能继续, 阻塞)
//   actionHint != null → ElNotification (带 "去处理" 按钮; 点击 pulse hintTarget)
//   default            → showMessage (方案 A sticky toast)
const pulseHintTarget = (label: string) => {
  if (!label) return;
  // Find buttons whose visible text or aria-label matches the hint target label.
  const all = Array.from(document.querySelectorAll<HTMLElement>('button, [role="button"], [role="tab"]'));
  const target = all.find(el => {
    const txt = (el.innerText || el.textContent || '').trim();
    return txt === label || txt.startsWith(label) || el.getAttribute('aria-label') === label;
  });
  if (!target) return;
  target.classList.add('cretas-pulse-hint');
  target.scrollIntoView({ block: 'center', behavior: 'smooth' });
  setTimeout(() => target.classList.remove('cretas-pulse-hint'), 5000);
};

const showRichError = async (
  message: string,
  opts: { actionHint?: string | null; severity?: string | null; hintTarget?: string | null }
) => {
  const el = await import('element-plus');
  const { ElMessageBox, ElNotification } = el;
  if (opts.severity === 'BLOCKING') {
    // Modal alert — must click 确定 to dismiss. Used for critical errors
    // (RBAC denial, destructive-confirm, critical business-rule violation).
    try {
      await ElMessageBox.alert(
        `${message}${opts.actionHint ? '\n\n提示: ' + opts.actionHint : ''}`,
        '操作被拒绝',
        { type: 'error', confirmButtonText: '我知道了' }
      );
    } catch { /* user closed */ }
    if (opts.hintTarget) pulseHintTarget(opts.hintTarget);
    return;
  }
  if (opts.actionHint) {
    // Rich notification with action button.
    const n = ElNotification({
      title: '操作无法完成',
      message: `${message}\n\n${opts.actionHint}`,
      type: 'error',
      duration: 0,
      showClose: true,
      onClick: () => { if (opts.hintTarget) pulseHintTarget(opts.hintTarget); },
    });
    if (opts.hintTarget) {
      // Also pulse immediately so user sees where to go.
      setTimeout(() => pulseHintTarget(opts.hintTarget as string), 300);
    }
    void n;
    return;
  }
  // Default: sticky toast (方案 A).
  return showMessage(message, 'error');
};

type KeepResponseConfig<D = unknown> = AxiosRequestConfig<D> & { _keepResponse: true };
type ResponseEnvelope = { success: boolean; data?: unknown; message?: string; code?: string | number };
type RequestResult<T> = T extends ResponseEnvelope ? T : ApiResponse<T>;

type UnwrappedAxiosInstance = Omit<AxiosInstance,
  'request' | 'get' | 'delete' | 'head' | 'options' | 'post' | 'put' | 'patch'
> & {
  <T = ApiData, D = unknown>(config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  <T = ApiData, R = RequestResult<T>, D = unknown>(config: AxiosRequestConfig<D>): Promise<R>;
  request<T = ApiData, D = unknown>(config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  request<T = ApiData, R = RequestResult<T>, D = unknown>(config: AxiosRequestConfig<D>): Promise<R>;
  get<T = ApiData, D = unknown>(url: string, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  get<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, config?: AxiosRequestConfig<D>): Promise<R>;
  delete<T = ApiData, D = unknown>(url: string, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  delete<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, config?: AxiosRequestConfig<D>): Promise<R>;
  head<T = ApiData, D = unknown>(url: string, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  head<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, config?: AxiosRequestConfig<D>): Promise<R>;
  options<T = ApiData, D = unknown>(url: string, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  options<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, config?: AxiosRequestConfig<D>): Promise<R>;
  post<T = ApiData, D = unknown>(url: string, data: D | undefined, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  post<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<R>;
  put<T = ApiData, D = unknown>(url: string, data: D | undefined, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  put<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<R>;
  patch<T = ApiData, D = unknown>(url: string, data: D | undefined, config: KeepResponseConfig<D>): Promise<AxiosResponse<T>>;
  patch<T = ApiData, R = RequestResult<T>, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<R>;
};

// 创建 axios 实例
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/mobile',
  // P0-8 (Apr 20): 30s→120s default. Big-data analysis (12K+ rows enrich / LLM insights)
  // exceed 30s, cascading abort. Per-call 600s overrides stay for upload/confirm.
  timeout: 120000,
  withCredentials: true, // Send HttpOnly cookies with every request
  headers: {
    'Content-Type': 'application/json',
    'X-Client-Type': 'web' // Tells backend to use cookie-based auth
  }
}) as UnwrappedAxiosInstance;

// 延迟获取 router（避免循环依赖）
const getRouter = async () => {
  const router = await import('@/router');
  return router.default;
};

// 请求拦截器
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // Add Authorization header from localStorage token
    const token = localStorage.getItem('cretas_access_token');
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }

    // FormData 时删除 Content-Type，让浏览器自动设置 multipart/form-data
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type'];
    }

    return config;
  },
  (error: AxiosError) => {
    console.error('Request error:', error);
    return Promise.reject(error);
  }
);

// Token 刷新状态
let isRefreshing = false;
let failedQueue: Array<{
  resolve: () => void;
  reject: (error: Error) => void;
}> = [];

const processQueue = (error: Error | null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve();
    }
  });
  failedQueue = [];
};

// 响应拦截器
request.interceptors.response.use(
  (response: AxiosResponse) => {
    const data = response.data;

    // Opt-out: blob downloads needing custom response headers (e.g. X-Cache-Hit,
    // X-Gold-Materialized-At from /api/smartbi/.../revenue-report/generate) must
    // bypass the envelope unwrap below. Callers set `_keepResponse: true` to
    // receive the raw AxiosResponse — preserves both `.data` (blob) and
    // `.headers` (with backend-set custom headers).
    const cfg0 = response.config as { _keepResponse?: boolean };
    if (cfg0._keepResponse) {
      return response;
    }

    // Defensive: reject HTML responses that leaked through (routing misconfiguration).
    // When an API URL hits a non-proxied path, Vite SPA fallback returns index.html
    // with 200 OK. Without this guard, axios returns {success:true, data:"<HTML>"}
    // and downstream code silently iterates characters of the HTML string.
    // See R20-F2: workflow-designer rendered 766 empty 📦 tiles for this reason.
    if (typeof data === 'string' && data.trimStart().startsWith('<')) {
      const url = response.config?.url || '(unknown)';
      const msg = `API 路由错误: ${url} 返回了 HTML (期望 JSON). 检查后端 @RequestMapping 或 nginx 代理规则.`;
      showMessage(msg, 'error');
      return Promise.reject(new ApiError(msg, 'ROUTING_ERROR'));
    }

    // 如果响应已经是标准格式
    if (data && typeof data.success === 'boolean') {
      if (!data.success) {
        // Respect _silent on success=false too (Apr 21 2026): previously
        // this branch unconditionally fired a red toast even when the
        // caller set _silent=true (e.g. workflow-designer optional
        // state-machine fetch → new factory with no SM got a toast
        // "状态机配置不存在" on every page mount).
        const cfg = response.config as { _silent?: boolean };
        const rich = (data as Record<string, string | null>);
        if (!cfg._silent) {
          showRichError(data.message || '操作失败', {
            actionHint: rich.actionHint,
            severity: rich.severity,
            hintTarget: rich.hintTarget,
          });
        }
        return Promise.reject(new ApiError(data.message, rich.errorCode || data.code));
      }
      return data;
    }

    // 包装为标准格式
    return {
      success: true,
      data: data,
      message: 'OK'
    };
  },
  async (error: AxiosError<ApiResponse>) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean;
      _silent?: boolean;
      _handledErrorCodes?: string[];
    };
    const status = error.response?.status;

    // Apr 20 Bug BR-01 fix: 用户点浏览器回退/路由切换时, 进行中的 axios 请求会被 abort,
    // 抛 AxiosError code=ERR_CANCELED, message='canceled'. 之前默认路径会走 showMessage
    // 弹 "canceled" toast, 用户困惑. 正确做法是静默 reject (调用方通常 await with
    // .catch 或 Promise.allSettled, 不会 surface 给用户).
    if (axios.isCancel?.(error) || error.code === 'ERR_CANCELED' || error.message === 'canceled') {
      return Promise.reject(error);
    }

    // 401 未授权 - 尝试刷新 token via cookie
    if (status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // 如果正在刷新，将请求加入队列
        return new Promise<void>((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        })
          .then(() => {
            // Cookie is already refreshed by the server, just retry
            return request(originalRequest);
          })
          .catch((err) => Promise.reject(err));
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // Refresh token is in an HttpOnly cookie scoped to the refresh path.
        // The server reads it from the cookie and sets updated cookies in the response.
        const response = await axios.post<ApiResponse<{ accessToken: string; refreshToken: string }>>(
          `${import.meta.env.VITE_API_BASE_URL || '/api/mobile'}/auth/refresh`,
          {},
          {
            withCredentials: true,
            headers: { 'X-Client-Type': 'web' }
          }
        );

        if (response.data.success) {
          // Update localStorage fallback token
          const newToken = (response.data.data as Record<string, string>)?.accessToken
            || (response.data.data as Record<string, string>)?.token;
          if (newToken) {
            localStorage.setItem('cretas_access_token', newToken);
          }
          processQueue(null);
          return request(originalRequest);
        } else {
          throw new Error('Token refresh failed');
        }
      } catch (refreshError) {
        processQueue(refreshError as Error);

        // 刷新失败，清除本地状态并跳转登录
        localStorage.removeItem('cretas_user');

        // 延迟跳转，避免循环依赖
        setTimeout(async () => {
          const router = await getRouter();
          router.push('/login');
        }, 0);

        showMessage('登录已过期，请重新登录', 'error');
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    // 403 禁止访问 — always blocking (per user 进阶方向 2)
    if (status === 403) {
      const backendMsg = error.response?.data?.message || '您没有权限执行此操作';
      const rich = (error.response?.data as unknown as Record<string, string | null>) || {};
      if (!originalRequest._silent) {
        showRichError(backendMsg, {
          actionHint: rich.actionHint,
          severity: rich.severity || 'BLOCKING',
          hintTarget: rich.hintTarget,
        });
      }
      return Promise.reject(new ApiError('权限不足', 'FORBIDDEN', 403));
    }

    // 404 Spring 默认 NoResourceFoundException 的"请求资源不存在"对用户太神秘,
    // 常见根因其实是: (a) 后端该接口没上线 / (b) URL 前端拼错 / (c) 权限 interceptor 已 reject
    // 给出明确提示 + 路径,让用户/开发都能立刻定位 (Apr 18 2026 bug #50)。
    if (status === 404) {
      const backendMsg = error.response?.data?.message || '';
      const method = String(originalRequest?.method || '').toUpperCase();
      const url = originalRequest?.url || '';
      const isSpringDefault = /资源不存在|no\s*resource|not\s*found/i.test(backendMsg);
      const friendly = isSpringDefault || !backendMsg
        ? `请求的接口不存在 (${method} ${url})。可能是后端未上线该功能,或当前账号无权访问。`
        : backendMsg;
      // Sprint 12 Phase D (#266): business-level 404 (e.g. 指标不存在) carries actionHint +
      // hintTarget — render rich notification with next-action button instead of plain toast.
      // This fixes audit A9 silent-failure: 指标 404 was 4-位一体 incomplete (no next-action).
      // Generic Spring 404s (no actionHint) fall through to the existing friendly showMessage.
      const rich404 = (error.response?.data as unknown as Record<string, string | null>) || {};
      if (!originalRequest._silent) {
        if (rich404.actionHint) {
          showRichError(friendly, {
            actionHint: rich404.actionHint,
            severity: rich404.severity || 'warning',
            hintTarget: rich404.hintTarget,
          });
        } else {
          showMessage(friendly, 'error');
        }
      }
      return Promise.reject(new ApiError(friendly, 'NOT_FOUND', 404));
    }

    // 410 Gone — Phase 2A SmartBI Java→Python 迁移完成 (PR #205 + chat 8 audit PR #214 §5.1, 2026-05-09).
    // 23 个 SmartBI Analysis endpoint 在 Java 端 stub 成 410, body 含
    // `SMARTBI_MIGRATED: endpoint moved to Python {NEW_PATH}` 英文消息.
    // 正常 75 个客户 factory 走 nginx regex 路由到 Python 返 200; 此 410 只在
    // (a) F999 测试 factory / (b) nginx 漏配 factory / (c) 直连后端 dev 调试 触发.
    // 把 raw 英文 message 转成友好中文 + dev console 警告便于排查.
    if (status === 410) {
      const rawMsg = (error.response?.data?.message as string | undefined) || '';
      const isMigrated = rawMsg.startsWith('SMARTBI_MIGRATED:');
      if (isMigrated) {
        // dev console hint — surface nginx routing gap if any
        console.warn('[SMARTBI_MIGRATED] backend 410 — nginx may not route this path to Python:', rawMsg);
      }
      if (!originalRequest._silent) {
        showMessage(
          isMigrated
            ? '该功能已迁移升级，请刷新页面重试。如反复出现请联系运维。'
            : (rawMsg || '该资源已下线 (410)'),
          'warning'  // warning level (3s auto-dismiss) vs error (sticky)
        );
      }
      return Promise.reject(new ApiError(
        isMigrated ? '该功能已迁移升级' : (rawMsg || '410 Gone'),
        isMigrated ? 'SMARTBI_MIGRATED' : 'GONE',
        410
      ));
    }

    // 其他错误 — Apr 19 2026 Bug #58: 5xx/网络断开时 axios 默认 error.message 是英文
    // "Request failed with status code 502",用户看不懂。映射为中文 friendly 文案,
    // 特别对 502/503/504 (nginx 网关 / upstream 未就绪 / 超时) 给出具体指引。
    const rawMessage = error.response?.data?.message as string | undefined;
    const isAxiosDefault = !!error.message && /^Request failed with status code/i.test(error.message);
    let message: string;
    if (rawMessage) {
      message = rawMessage;
    } else if (status === 502 || status === 504) {
      message = `服务暂时不可用,请稍后重试 (${status === 502 ? '后端未就绪' : '网关超时'})`;
    } else if (status === 503) {
      message = '服务繁忙,请稍后重试';
    } else if (status === 500) {
      message = '服务器内部错误,请联系管理员';
    } else if (typeof status === 'number' && status >= 500) {
      message = `服务异常 (${status}),请稍后重试`;
    } else if (isAxiosDefault || !error.message) {
      message = '网络请求失败,请检查网络连接';
    } else {
      message = error.message;
    }
    // FK_BLOCK 防呆导航 (fool-proof-design Rule 5: dead-end → 导航)
    // 后端 DELETE FK violation 命中 FK_MODULE_MAP → status=409 + errorCode=FK_BLOCK
    // + message=中文 + actionHint=前往XX + hintTarget=targetRoute
    // 用 ElMessageBox.confirm 询问用户是否跳转，确认则带 _returnTo 参数导航
    const richAll = (error.response?.data as unknown as Record<string, string | null>) || {};
    if (status === 409 && richAll.errorCode === 'FK_BLOCK' && !originalRequest._silent) {
      const fkMessage = (error.response?.data?.message as string) || '无法删除: 该数据被其他记录引用';
      const fkHint = richAll.actionHint || '请先处理引用该数据的相关记录';
      const targetRoute = richAll.hintTarget;
      try {
        const { ElMessageBox } = await import('element-plus');
        await ElMessageBox.confirm(
          `${fkMessage}\n\n${fkHint}`,
          '删除被阻止',
          {
            confirmButtonText: '前往处理',
            cancelButtonText: '稍后处理',
            distinguishCancelAndClose: true,
            type: 'warning',
          }
        );
        // 用户确认 → 跳转目标模块，携带 _returnTo 参数
        if (targetRoute) {
          const router = await getRouter();
          const currentRoute = router.currentRoute.value;
          const returnTo = encodeURIComponent(currentRoute.fullPath);
          const separator = targetRoute.includes('?') ? '&' : '?';
          router.push(`${targetRoute}${separator}_returnTo=${returnTo}`);
        }
      } catch {
        // 用户选择「稍后处理」或关闭弹窗 — 静默
      }
      return Promise.reject(new ApiError(fkMessage, 'FK_BLOCK', 409));
    }

    // T144 MATERIAL_UOM_UNCONFIGURED 防呆导航 (fool-proof-design Rule 5: dead-end → 导航).
    // 注: 原料称重入库, 库存校验以称重批次单位(kg)为口径, BOM 克(g)↔kg 自动换算 →
    // 称重原料不再触发此 409. 现在此 409 = BOM 单位与库存单位维度不可换算 (e.g. 个 vs kg)
    // 的真实配置错误 (安全网), 引导用户核对/修正物料单位配置 (原料管理页).
    // 权限门控: 仅当用户可访问 warehouse 模块时才提供跳转 (镜像 T136 canAccess pattern).
    if (status === 409 && richAll.errorCode === 'MATERIAL_UOM_UNCONFIGURED' && !originalRequest._silent) {
      const uomMessage = (error.response?.data?.message as string)
        || '原料 BOM 单位与库存单位无法换算，无法校验库存';
      const uomHint = richAll.actionHint || '请核对该原料 BOM 配方单位与入库称重单位是否同一计量维度';
      try {
        const { useAuthStore } = await import('@/store/modules/auth');
        const { canAccessModule } = await import('@/utils/permission');
        const authUser = useAuthStore().user as User | null;
        const canConfig = canAccessModule(authUser, 'warehouse');
        const { ElMessageBox } = await import('element-plus');
        if (canConfig) {
          await ElMessageBox.confirm(
            `${uomMessage}\n\n${uomHint}`,
            '原料单位无法换算',
            {
              confirmButtonText: '去核对单位配置',
              cancelButtonText: '稍后处理',
              distinguishCancelAndClose: true,
              type: 'warning',
            }
          );
          // 用户确认 → 跳转原料管理页, 携带 _returnTo 便于回到原页面
          const router = await getRouter();
          const currentRoute = router.currentRoute.value;
          const returnTo = encodeURIComponent(currentRoute.fullPath);
          router.push(`/warehouse/material-types?_returnTo=${returnTo}`);
        } else {
          // 无权限配置 → 仅显示信息 + 提示联系管理员 (不给 dead-end 跳转)
          await ElMessageBox.alert(
            `${uomMessage}\n\n${uomHint}\n\n(需要仓库管理权限配置, 请联系管理员)`,
            '原料单位无法换算',
            { confirmButtonText: '我知道了', type: 'warning' }
          );
        }
      } catch {
        // 用户选择「稍后处理」或关闭弹窗 — 静默
      }
      return Promise.reject(new ApiError(uomMessage, 'MATERIAL_UOM_UNCONFIGURED', 409, uomHint));
    }

    // 409 differentiation (R24 P2 real-window finding):
    //   - Optimistic lock 409: GlobalExceptionHandler emits {code:409, message:"数据已被其他用户修改..."}
    //     with NO actionHint. Callers (suppliers/customers/sales-orders/DynamicModulePage)
    //     catch status===409 and ElMessageBox.alert their own "刷新列表" dialog. Interceptor
    //     toast would be redundant.
    //   - Business 409: BusinessException(409, ...).withHint(...) emits {code:409, message:..,
    //     actionHint:.., hintTarget:..}. R18+R21+R23 invariants (DRAFT/CANCELLED/PRE_FINANCE
    //     SO/PO blocked from /finance/receivable, /finance/payable, /finance/invoices/request,
    //     /finance/payments/record, etc.) all use this path. Pre-R24 the blanket-suppress
    //     swallowed actionHint so users got NO feedback — captured by R24 real-window test
    //     on /finance/invoices/request with CONFIRMED SO (toast log empty, network 409).
    // Apr 24 LOW-1 fix was too broad; this re-narrows the suppression to vanilla optimistic
    // lock only (no actionHint).
    const rich = (error.response?.data as unknown as Record<string, string | null>) || {};
    const isVanillaOptimisticLock = status === 409 && !rich.actionHint && !rich.hintTarget;
    const isCallerHandledConflict = status === 409
      && typeof rich.errorCode === 'string'
      && originalRequest._handledErrorCodes?.includes(rich.errorCode) === true;
    // Allow callers to suppress error toast via _silent config flag
    if (!originalRequest._silent && !isVanillaOptimisticLock && !isCallerHandledConflict) {
      showRichError(message, {
        actionHint: rich.actionHint,
        severity: rich.severity,
        hintTarget: rich.hintTarget,
      });
    }

    return Promise.reject(new ApiError(
      message,
      rich.errorCode || error.response?.data?.code,
      status,
      rich.actionHint || null,
    ));
  }
);

export default request;

// 便捷方法
//
// Tier 1 vue-tsc cleanup (2026-05-09): default T = ApiData (alias for `any`) so
// untyped calls don't poison call sites with `unknown`. Callers should still
// supply explicit `<ResponseType>` for new code; default exists ONLY as the
// historical baseline for ~800 legacy call sites that never typed their
// responses. Continues PR #240 cleanup. See also PageData / ListData type
// helpers in @/types/api for paginated responses.
//
// IMPORTANT: This is NOT a license to skip typing — new call sites should pass
// explicit type parameters. The default exists to unblock vue-tsc CI rollout
// without 800+ targeted edits in one PR.

export const get = <T = ApiData>(url: string, config?: object): Promise<ApiResponse<T>> => {
  return request.get(url, config);
};

export const post = <T = ApiData>(url: string, data?: object, config?: object): Promise<ApiResponse<T>> => {
  return request.post(url, data, config);
};

export const put = <T = ApiData>(url: string, data?: object, config?: object): Promise<ApiResponse<T>> => {
  return request.put(url, data, config);
};

export const del = <T = ApiData>(url: string, config?: object): Promise<ApiResponse<T>> => {
  return request.delete(url, config);
};

// Admin API 便捷方法 — 绕过 /api/mobile baseURL，直接使用绝对路径
// 用于 /api/admin/* 等非 /api/mobile 前缀的后端接口
const adminBaseURL = import.meta.env.VITE_API_BASE_URL ? '' : '';
export const adminGet = <T = ApiData>(url: string, config?: object): Promise<ApiResponse<T>> => {
  return request.get(url, { ...config, baseURL: adminBaseURL });
};
export const adminPost = <T = ApiData>(url: string, data?: object, config?: object): Promise<ApiResponse<T>> => {
  return request.post(url, data, { ...config, baseURL: adminBaseURL });
};
export const adminPut = <T = ApiData>(url: string, data?: object): Promise<ApiResponse<T>> => {
  return request.put(url, data, { baseURL: adminBaseURL });
};
export const adminDel = <T = ApiData>(url: string): Promise<ApiResponse<T>> => {
  return request.delete(url, { baseURL: adminBaseURL });
};
