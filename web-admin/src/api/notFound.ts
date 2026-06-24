import { isAxiosError } from 'axios';

/**
 * 判定一个被 reject 的错误是否表示「资源不存在 (404)」。
 *
 * <p>本项目后端常用 `ApiResponse.error(404, msg)` 返回 **HTTP 200 + body code 404**
 * (而非真 HTTP 404)。`request.ts` 拦截器把 `success:false` body 转成
 * `new ApiError(message, data.code)` —— `code` 来自后端 `Integer code` → 运行时是**数字 404**
 * (ApiError 的 TS 类型虽标 `string`)。因此识别 404 必须兼容:
 *   - ApiError.code === 数字 404 (body-code, 最常见)
 *   - ApiError.code === 字符串 '404' / 'NOT_FOUND'
 *   - ApiError.status === 404
 *   - 真 HTTP 404 (axios error, response.status === 404)
 *
 * 漏掉数字 body-code 会让「资源不存在」误落到通用错误分支 (audit R4 实证: U7 缺 BOM 引导失效).
 */
export function isNotFoundError(err: unknown): boolean {
  const code = (err as { code?: unknown })?.code;
  const status = (err as { status?: unknown })?.status;
  if (code === 404 || code === '404' || code === 'NOT_FOUND') return true;
  if (status === 404 || status === '404') return true;
  if (isAxiosError(err) && err.response?.status === 404) return true;
  return false;
}
