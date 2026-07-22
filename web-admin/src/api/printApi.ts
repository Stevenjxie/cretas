/**
 * Web-admin client for the 5 PDF print endpoints shipped by Sprint 1 PR #659 +
 * tightened by Sprint1-Fix-K2 (PR #671 — RBAC + price masking).
 *
 * Backend contract (PrintController):
 *   GET /api/mobile/{factoryId}/print/sales-order/{id}        @RequirePermission(sales:read | sales:read_write)
 *   GET /api/mobile/{factoryId}/print/purchase-order/{id}     @RequirePermission(procurement:read | procurement:read_write)
 *   GET /api/mobile/{factoryId}/print/quotation/{id}          @RequirePermission(sales:read | sales:read_write)
 *   GET /api/mobile/{factoryId}/print/production-task/{id}    @RequirePermission(production:read | production:read_write)
 *   GET /api/mobile/{factoryId}/print/material-requisition/{id}
 *                                                             @RequirePermission(procurement:read | procurement:read_write |
 *                                                                                warehouse:read | warehouse:read_write)
 *   Response: application/pdf bytes
 *   Errors:   403 if role missing module:read; 502 if upstream Python printer down.
 *
 * Implementation note: uses request's `_keepResponse: true` opt-out so the
 * blob body and HTTP status are both available (the default interceptor
 * unwraps `.data.data` which would discard both).
 */

import type { AxiosError, AxiosRequestConfig, AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import request from './request';

export type PrintDocType =
  | 'sales-order'
  | 'purchase-order'
  | 'quotation'
  | 'production-task'
  | 'material-requisition'
  | 'purchase-receipt'
  | 'production-work-order'         // SP12: 生产工单 (PrintController)
  | 'consolidated-material-requisition' // SP12: 汇总领料单 (PrintController)
  | 'batching-sheet';               // 六扇门: 配料单 (按锅配料, PrintController)

export interface PrintOptions {
  /** Optional query overrides forwarded as ?key=value (Day-6 MVP placeholder). */
  overrides?: Record<string, string>;
  /** Override the auto-generated download filename (without `.pdf` suffix). */
  fileName?: string;
}

/** Type extension for the request opt-out flag (defined in api/request.ts response interceptor). */
type BlobRequestConfig = AxiosRequestConfig & { _keepResponse?: boolean };

const DEFAULT_FILENAMES: Record<PrintDocType, string> = {
  'sales-order': '销售订单',
  'purchase-order': '采购订单',
  quotation: '报价单',
  'production-task': '生产任务单',
  'material-requisition': '领料单',
  'purchase-receipt': '收货单',
  'production-work-order': '生产工单',
  'consolidated-material-requisition': '汇总领料单',
  'batching-sheet': '配料单',
};

async function downloadPdf(
  factoryId: string,
  docType: PrintDocType,
  id: string,
  options?: PrintOptions
): Promise<void> {
  const url = `/${factoryId}/print/${docType}/${id}`;
  const config: BlobRequestConfig = {
    responseType: 'blob',
    params: options?.overrides,
    _keepResponse: true, // skip envelope unwrap, see api/request.ts:177
  };

  let response: AxiosResponse<Blob>;
  try {
    response = await request.get(url, config) as unknown as AxiosResponse<Blob>;
  } catch (err) {
    const axErr = err as AxiosError;
    const status = axErr?.response?.status;
    if (status === 403) {
      ElMessage.error('当前角色无该单据的打印权限');
    } else if (status === 502) {
      ElMessage.error('打印服务暂不可用 — 请稍后重试');
    } else {
      ElMessage.error(`PDF 下载失败 (${status ?? '网络异常'})`);
    }
    console.error('[printApi]', docType, id, err);
    throw err;
  }

  const blob = response.data instanceof Blob
    ? response.data
    : new Blob([response.data], { type: 'application/pdf' });
  if (blob.size === 0) {
    ElMessage.error('打印服务返回空 PDF');
    return;
  }

  const blobUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = `${options?.fileName ?? DEFAULT_FILENAMES[docType]}_${id}.pdf`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(blobUrl);
  ElMessage.success('PDF 已下载');
}

export const printApi = {
  printSalesOrder: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'sales-order', id, options),
  printPurchaseOrder: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'purchase-order', id, options),
  printQuotation: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'quotation', id, options),
  printProductionTask: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'production-task', id, options),
  printMaterialRequisition: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'material-requisition', id, options),
  printPurchaseReceipt: (factoryId: string, id: string, options?: PrintOptions) =>
    downloadPdf(factoryId, 'purchase-receipt', id, options),
};

/**
 * #726 SP12: 合并打印生产工单 (multi-plan batch)
 * Backend: GET /api/mobile/{factoryId}/print/production-work-order-multi?planIds=id1&planIds=id2
 * Max 20 planIds per request (validated server-side).
 */
export async function printWorkOrderMulti(
  factoryId: string | null | undefined,
  planIds: string[],
  fileName?: string
): Promise<void> {
  if (!factoryId) {
    ElMessage.warning('未识别工厂 — 请重新登录');
    return;
  }
  if (!planIds.length) {
    ElMessage.warning('请先勾选要打印的计划');
    return;
  }
  if (planIds.length > 20) {
    ElMessage.warning('单次最多合并打印 20 份，请缩减选择数量');
    return;
  }

  const url = `/${factoryId}/print/production-work-order-multi`;
  const config: BlobRequestConfig = {
    responseType: 'blob',
    params: { planIds },  // axios serializes array as planIds=x&planIds=y
    _keepResponse: true,
  };

  let response: AxiosResponse<Blob>;
  try {
    response = await request.get(url, config) as unknown as AxiosResponse<Blob>;
  } catch (err) {
    const axErr = err as AxiosError;
    const status = axErr?.response?.status;
    if (status === 403) {
      ElMessage.error('当前角色无批量打印权限');
    } else if (status === 502) {
      ElMessage.error('打印服务暂不可用 — 请稍后重试');
    } else {
      ElMessage.error(`批量 PDF 下载失败 (${status ?? '网络异常'})`);
    }
    console.error('[printApi] production-work-order-multi', planIds, err);
    return;
  }

  const blob = response.data instanceof Blob
    ? response.data
    : new Blob([response.data], { type: 'application/pdf' });
  if (blob.size === 0) {
    ElMessage.error('打印服务返回空 PDF');
    return;
  }

  const blobUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = `${fileName ?? `合并生产工单_${planIds.length}份`}.pdf`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(blobUrl);
  ElMessage.success(`已下载 ${planIds.length} 份合并工单 PDF`);
}

/**
 * Convenience wrapper for row-action handlers. Accepts a possibly-falsy factoryId
 * (the auth store may not have populated yet on first render) and surfaces a
 * gentle warning instead of crashing.
 */
export async function safePrint(
  docType: PrintDocType,
  factoryId: string | null | undefined,
  id: string | null | undefined,
  options?: PrintOptions
): Promise<void> {
  if (!factoryId) {
    ElMessage.warning('未识别工厂 — 请重新登录');
    return;
  }
  if (!id) return;
  try {
    await downloadPdf(factoryId, docType, id, options);
  } catch {
    // Already alerted in downloadPdf; swallow so caller doesn't need try/catch.
  }
}
