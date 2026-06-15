import type { ApiResponse } from '@/types/api';

export interface PurchaseOrderPdfOptions {
  factoryId: string;
  orderId: string;
  external: boolean;
}

function apiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || '/api/mobile').replace(/\/$/, '');
}

async function extractBackendMessage(response: Response): Promise<string> {
  const fallback = `PDF 下载失败 (${response.status})`;
  const contentType = response.headers.get('content-type') || '';

  try {
    if (contentType.includes('application/json')) {
      const body = await response.json() as Partial<ApiResponse<unknown>> & { error?: string };
      return body.message || body.error || fallback;
    }

    const text = await response.text();
    if (!text) return fallback;

    try {
      const body = JSON.parse(text) as Partial<ApiResponse<unknown>> & { error?: string };
      return body.message || body.error || text;
    } catch {
      return text;
    }
  } catch {
    return fallback;
  }
}

export async function downloadPurchaseOrderPdf(options: PurchaseOrderPdfOptions): Promise<Blob> {
  const token = localStorage.getItem('cretas_access_token');
  const headers: HeadersInit = {
    'X-Client-Type': 'web',
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const url = `${apiBaseUrl()}/${encodeURIComponent(options.factoryId)}`
    + `/purchase/orders/${encodeURIComponent(options.orderId)}/pdf`
    + `?external=${options.external ? 'true' : 'false'}`;

  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers,
  });

  if (!response.ok) {
    throw new Error(await extractBackendMessage(response));
  }

  return response.blob();
}
