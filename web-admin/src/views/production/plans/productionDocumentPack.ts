import type { AxiosError, AxiosRequestConfig, AxiosResponse } from 'axios';
import { ElMessage } from 'element-plus';
import request from '@/api/request';

export const PRODUCTION_DOCUMENT_CHAPTERS = [
  { value: 'work-order', label: '生产工单' },
  { value: 'material-requisition', label: '领料单' },
  { value: 'batching-sheet', label: '配料单' },
] as const;

export type ProductionDocumentChapter = typeof PRODUCTION_DOCUMENT_CHAPTERS[number]['value'];

export const DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS: ProductionDocumentChapter[] =
  PRODUCTION_DOCUMENT_CHAPTERS.map(({ value }) => value);

type BlobRequestConfig = AxiosRequestConfig & { _keepResponse?: boolean };

export function normalizeProductionDocumentChapters(
  chapters: readonly string[],
): ProductionDocumentChapter[] {
  const requested = new Set(chapters);
  return DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS.filter((chapter) => requested.has(chapter));
}

export function productionDocumentPackRequest(
  factoryId: string,
  planId: string,
  chapters: readonly string[],
) {
  const selected = normalizeProductionDocumentChapters(chapters);
  if (!factoryId.trim()) throw new Error('未识别工厂');
  if (!planId.trim()) throw new Error('未识别生产计划');
  if (selected.length === 0) throw new Error('请至少选择一章生产单据');
  return {
    url: `/${factoryId}/print/production-document-pack/${planId}`,
    params: { chapters: selected.join(',') },
  };
}

export async function downloadProductionDocumentPack(
  factoryId: string,
  planId: string,
  planNumber: string,
  chapters: readonly string[],
): Promise<void> {
  let contract: ReturnType<typeof productionDocumentPackRequest>;
  try {
    contract = productionDocumentPackRequest(factoryId, planId, chapters);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '生产单据包参数不完整');
    return;
  }

  const config: BlobRequestConfig = {
    responseType: 'blob',
    params: contract.params,
    _keepResponse: true,
  };

  let response: AxiosResponse<Blob>;
  try {
    response = await request.get(contract.url, config) as unknown as AxiosResponse<Blob>;
  } catch (error) {
    const status = (error as AxiosError)?.response?.status;
    if (status === 403) {
      ElMessage.error('当前角色无生产单据包权限');
    } else if (status === 404) {
      ElMessage.error('打印服务尚未提供单文件生产单据包，请联系管理员完成打印服务升级');
    } else if (status === 409 || status === 422) {
      ElMessage.error('生产单据快照不完整，无法生成单据包；请先补齐缺失单据');
    } else if (status === 502) {
      ElMessage.error('打印服务暂不可用，请稍后重试');
    } else {
      ElMessage.error(`生产单据包下载失败 (${status ?? '网络异常'})`);
    }
    throw error;
  }

  const contentType = String(response.headers?.['content-type'] ?? '').toLowerCase();
  const blob = response.data instanceof Blob
    ? response.data
    : new Blob([response.data], { type: contentType || 'application/pdf' });
  const effectiveContentType = contentType || String(blob.type || '').toLowerCase();
  if (blob.size === 0 || !effectiveContentType.includes('application/pdf')) {
    ElMessage.error('打印服务未返回有效 PDF，已停止下载');
    return;
  }

  const blobUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = `生产单据包_${planNumber || planId}.pdf`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(blobUrl);
  ElMessage.success('单文件生产单据包已下载，可直接打印');
}
