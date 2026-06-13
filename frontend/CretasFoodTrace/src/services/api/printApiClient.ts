/**
 * RN client for the 5 PDF print endpoints shipped by Sprint 1 PR #659 +
 * tightened by Sprint1-Fix-K2 (PR #671 — RBAC + price masking).
 *
 * Backend contract (PrintController):
 *   GET /api/mobile/{factoryId}/print/sales-order/{id}        @RequirePermission(sales:read | sales:read_write)
 *   GET /api/mobile/{factoryId}/print/purchase-order/{id}     @RequirePermission(procurement:read | procurement:read_write)
 *   GET /api/mobile/{factoryId}/print/quotation/{id}          @RequirePermission(sales:read | sales:read_write)
 *   GET /api/mobile/{factoryId}/print/production-work-order/{id}
 *                                                             @RequirePermission(production:read | production:read_write)
 *   GET /api/mobile/{factoryId}/print/material-requisition/{id}
 *                                                             @RequirePermission(procurement:read | procurement:read_write |
 *                                                                                warehouse:read | warehouse:read_write)
 *   Response: application/pdf bytes, Content-Disposition: attachment; filename="…pdf"
 *   Errors:   403 if role missing module:read; 502 if upstream Python printer down.
 *
 * Implementation note: we deliberately bypass apiClient's axios interceptor
 * (which unwraps response.data and would mangle the binary stream) and use
 * expo-file-system's downloadAsync to stream PDF bytes straight to the
 * documentDirectory. The auth token is pulled from SecureStore and forwarded
 * as a Bearer header — same source apiClient uses, so 401 refresh remains
 * triggered the next time apiClient.ts runs.
 */

import { Alert } from 'react-native';
import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { StorageService } from '../storage/storageService';
import { API_BASE_URL } from '../../constants/config';
import { useAuthStore } from '../../store/authStore';
import { logger } from '../../utils/logger';

const printLogger = logger.createContextLogger('PrintApi');

export type PrintDocType =
  | 'sales-order'
  | 'purchase-order'
  | 'quotation'
  | 'production-work-order'
  | 'material-requisition';

/** Backend Day-6 MVP accepts query overrides as placeholder data. */
export interface PrintOptions {
  /** Optional query overrides forwarded as ?key=value to backend. */
  overrides?: Record<string, string>;
  /** Auto-open the system share sheet after download. Defaults to true. */
  share?: boolean;
}

export interface PrintResult {
  /** Local file URI of the saved PDF (file:// scheme). */
  uri: string;
  fileName: string;
  fileSize: number;
}

/** Thrown when the backend returns 403 — caller surfaces as "无权限". */
export class PrintForbiddenError extends Error {
  readonly status = 403;
  constructor(message = '当前角色无该单据的打印权限') {
    super(message);
    this.name = 'PrintForbiddenError';
  }
}

/** Thrown for any non-2xx that isn't 403. Caller surfaces a generic alert. */
export class PrintFailedError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'PrintFailedError';
  }
}

class PrintApiClient {
  private async download(
    factoryId: string,
    docType: PrintDocType,
    id: string,
    options?: PrintOptions
  ): Promise<PrintResult> {
    const accessToken = await StorageService.getSecureItem('secure_access_token');
    if (!accessToken) {
      throw new PrintFailedError(401, '未登录或登录已过期');
    }

    let url = `${API_BASE_URL}/api/mobile/${factoryId}/print/${docType}/${id}`;
    if (options?.overrides) {
      const qs = new URLSearchParams(options.overrides).toString();
      if (qs) url += `?${qs}`;
    }

    const fileName = `${docType}-${id}-${Date.now()}.pdf`;
    const fileUri = `${FileSystem.documentDirectory}${fileName}`;

    printLogger.info('PDF 下载开始', { docType, id, factoryId });

    let result: FileSystem.FileSystemDownloadResult;
    try {
      result = await FileSystem.downloadAsync(url, fileUri, {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
    } catch (err) {
      printLogger.error('PDF 下载网络异常', err);
      throw new PrintFailedError(0, '网络异常 — 请检查网络后重试');
    }

    if (result.status === 403) {
      printLogger.warn('PDF 下载 403 — RBAC 拒绝', { docType, id });
      // Best-effort cleanup of partial file (downloadAsync still writes status-page bytes on 403).
      void FileSystem.deleteAsync(result.uri, { idempotent: true });
      throw new PrintForbiddenError();
    }
    if (result.status !== 200) {
      printLogger.warn('PDF 下载失败', { docType, id, status: result.status });
      void FileSystem.deleteAsync(result.uri, { idempotent: true });
      throw new PrintFailedError(result.status, `打印失败 (HTTP ${result.status})`);
    }

    const info = await FileSystem.getInfoAsync(result.uri);
    const fileSize = info.exists && 'size' in info ? info.size : 0;
    if (fileSize === 0) {
      throw new PrintFailedError(502, '打印服务返回空 PDF');
    }

    printLogger.info('PDF 下载完成', { docType, id, fileSize, uri: result.uri });

    if (options?.share !== false) {
      const sharingAvailable = await Sharing.isAvailableAsync();
      if (sharingAvailable) {
        await Sharing.shareAsync(result.uri, {
          mimeType: 'application/pdf',
          dialogTitle: '打印 / 分享 PDF',
          UTI: 'com.adobe.pdf',
        });
      }
    }

    return { uri: result.uri, fileName, fileSize };
  }

  printSalesOrder(factoryId: string, id: string, options?: PrintOptions): Promise<PrintResult> {
    return this.download(factoryId, 'sales-order', id, options);
  }

  printPurchaseOrder(factoryId: string, id: string, options?: PrintOptions): Promise<PrintResult> {
    return this.download(factoryId, 'purchase-order', id, options);
  }

  printQuotation(factoryId: string, id: string, options?: PrintOptions): Promise<PrintResult> {
    return this.download(factoryId, 'quotation', id, options);
  }

  printProductionWorkOrder(factoryId: string, id: string, options?: PrintOptions): Promise<PrintResult> {
    return this.download(factoryId, 'production-work-order', id, options);
  }

  printMaterialRequisition(factoryId: string, id: string, options?: PrintOptions): Promise<PrintResult> {
    return this.download(factoryId, 'material-requisition', id, options);
  }
}

export const printApiClient = new PrintApiClient();

const PRINT_DISPATCH: Record<PrintDocType, (factoryId: string, id: string, options?: PrintOptions) => Promise<PrintResult>> = {
  'sales-order': (f, i, o) => printApiClient.printSalesOrder(f, i, o),
  'purchase-order': (f, i, o) => printApiClient.printPurchaseOrder(f, i, o),
  quotation: (f, i, o) => printApiClient.printQuotation(f, i, o),
  'production-work-order': (f, i, o) => printApiClient.printProductionWorkOrder(f, i, o),
  'material-requisition': (f, i, o) => printApiClient.printMaterialRequisition(f, i, o),
};

/**
 * Convenience wrapper for row-action handlers: resolves factoryId from
 * authStore, dispatches to the right printer, surfaces errors via Alert.
 *
 * Returns null on any failure (already alerted) so callers can fire-and-forget:
 *   `'print-pdf': (e) => { void safePrint('sales-order', e.id); }`
 */
export async function safePrint(
  docType: PrintDocType,
  id: string,
  options?: PrintOptions
): Promise<PrintResult | null> {
  const factoryId = useAuthStore.getState().getFactoryId();
  if (!factoryId) {
    Alert.alert('打印失败', '未识别工厂 — 请重新登录');
    return null;
  }
  try {
    return await PRINT_DISPATCH[docType](factoryId, id, options);
  } catch (err) {
    if (err instanceof PrintForbiddenError) {
      Alert.alert('无权限', err.message);
    } else if (err instanceof PrintFailedError) {
      Alert.alert('打印失败', err.message);
    } else {
      Alert.alert('打印失败', err instanceof Error ? err.message : String(err));
    }
    return null;
  }
}
