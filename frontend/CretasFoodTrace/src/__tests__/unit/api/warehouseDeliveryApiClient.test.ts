// @ts-nocheck
import { warehouseDeliveryApiClient } from '../../../services/api/warehouseDeliveryApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
const WH_BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/warehouse/deliveries`;
const SALES_BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/sales/deliveries`;

let mock: MockAdapter;

beforeEach(() => {
  mock = createApiMock();
});

afterEach(() => {
  resetApiMock(mock);
});

describe('warehouseDeliveryApiClient', () => {
  describe('getPendingDeliveries', () => {
    it('lists real DLV-* pending deliveries from /warehouse/deliveries/pending', async () => {
      const page = {
        content: [
          { id: 'd1', deliveryNumber: 'DLV-20260705-0001', customerName: '叮咚', status: 'PENDING_WAREHOUSE_CONFIRM' },
        ],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      };
      mock.onGet(`${WH_BASE}/pending`).reply(200, { success: true, data: page });

      const res = await warehouseDeliveryApiClient.getPendingDeliveries();
      expect(res.success).toBe(true);
      expect(res.data.content[0].deliveryNumber).toBe('DLV-20260705-0001');
      expect(res.data.totalElements).toBe(1);
    });

    it('defaults to 1-based pagination (page=1)', async () => {
      mock.onGet(`${WH_BASE}/pending`).reply(200, { success: true, data: { content: [] } });
      await warehouseDeliveryApiClient.getPendingDeliveries();
      expect(mock.history.get[0].params).toEqual({ page: 1, size: 20 });
    });

    it('forwards explicit page/size params', async () => {
      mock.onGet(`${WH_BASE}/pending`).reply(200, { success: true, data: { content: [] } });
      await warehouseDeliveryApiClient.getPendingDeliveries({ page: 2, size: 50 });
      expect(mock.history.get[0].params).toEqual({ page: 2, size: 50 });
    });
  });

  describe('getDeliveryDetail', () => {
    it('fetches delivery detail (with items) from /sales/deliveries/{id}', async () => {
      const record = {
        id: 'd1',
        deliveryNumber: 'DLV-1',
        items: [{ id: 51, productName: '卤猪蹄', deliveredQuantity: 100, unit: 'kg' }],
      };
      mock.onGet(`${SALES_BASE}/d1`).reply(200, { success: true, data: record });

      const res = await warehouseDeliveryApiClient.getDeliveryDetail('d1');
      expect(res.data.items[0].id).toBe(51);
      expect(res.data.items[0].deliveredQuantity).toBe(100);
    });
  });

  describe('confirmDelivery', () => {
    it('POSTs actualQuantities to /warehouse/deliveries/{id}/confirm (real FG deduction)', async () => {
      mock.onPost(`${WH_BASE}/d1/confirm`).reply(200, { success: true, data: { id: 'd1', status: 'SHIPPED' } });

      const res = await warehouseDeliveryApiClient.confirmDelivery('d1', { '51': 80 });
      expect(res.success).toBe(true);
      expect(res.data.status).toBe('SHIPPED');
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ actualQuantities: { '51': 80 } });
    });

    it('surfaces backend 409 (batch not allocated) as a rejected axios error carrying the message', async () => {
      mock.onPost(`${WH_BASE}/d1/confirm`).reply(409, {
        success: false,
        message: '发货行 51 未完成批次分配，无法确认发货',
        actionHint: '请到"发货记录"点"分配批次"',
      });

      await expect(warehouseDeliveryApiClient.confirmDelivery('d1', { '51': 100 })).rejects.toMatchObject({
        response: { status: 409, data: { message: '发货行 51 未完成批次分配，无法确认发货' } },
      });
    });
  });
});
