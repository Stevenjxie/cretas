import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadPurchaseOrderPdf } from '../purchaseOrderPdf';

describe('purchase order PDF API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('downloads the external supplier PDF with auth and cookies', async () => {
    localStorage.setItem('cretas_access_token', 'token-123');
    const fetchMock = vi.fn().mockResolvedValue(new Response('%PDF', {
      status: 200,
      headers: { 'content-type': 'application/pdf' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    const blob = await downloadPurchaseOrderPdf({
      factoryId: 'F006',
      orderId: 'PO-1',
      external: true,
    });

    expect(await blob.text()).toBe('%PDF');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/F006/purchase/orders/PO-1/pdf?external=true',
      expect.objectContaining({
        method: 'GET',
        credentials: 'include',
        headers: expect.objectContaining({
          Authorization: 'Bearer token-123',
          'X-Client-Type': 'web',
        }),
      })
    );
  });

  it('throws the backend message from a failed blob download', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ success: false, message: '无权下载内部采购 PDF' }),
      {
        status: 403,
        headers: { 'content-type': 'application/json' },
      }
    ));
    vi.stubGlobal('fetch', fetchMock);

    await expect(downloadPurchaseOrderPdf({
      factoryId: 'F006',
      orderId: 'PO-1',
      external: false,
    })).rejects.toThrow('无权下载内部采购 PDF');
  });
});
