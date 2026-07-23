// @ts-nocheck
import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
const BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/production-plans`;

let mock: MockAdapter;

beforeEach(() => {
  mock = createApiMock();
});

afterEach(() => {
  resetApiMock(mock);
});

describe('productionPlanApiClient', () => {
  // ---- List & Query ----

  describe('getProductionPlans', () => {
    it('should GET base path without params', async () => {
      mock.onGet(BASE).reply(200, {
        success: true,
        data: { content: [], totalElements: 0, totalPages: 0 },
        message: 'ok',
      });

      const result = await productionPlanApiClient.getProductionPlans();
      expect(result.success).toBe(true);
      expect(result.data.content).toHaveLength(0);
    });

    it('should GET base path with pagination params', async () => {
      mock.onGet(BASE).reply(200, {
        success: true,
        data: {
          content: [{ id: 'pp1', productName: 'Tofu', status: 'PLANNED' }],
          totalElements: 1,
          totalPages: 1,
        },
        message: 'ok',
      });

      const result = await productionPlanApiClient.getProductionPlans({ page: 0, size: 20 });
      expect(result.success).toBe(true);
      expect(result.data.content).toHaveLength(1);
    });
  });

  describe('getProductionPlanById', () => {
    it('should GET /{planId}', async () => {
      mock.onGet(`${BASE}/pp1`).reply(200, {
        success: true,
        data: { id: 'pp1', productName: 'Tofu', plannedQuantity: 500, status: 'PLANNED' },
        message: 'ok',
      });

      const result = await productionPlanApiClient.getProductionPlanById('pp1');
      expect(result.success).toBe(true);
      expect(result.data.id).toBe('pp1');
    });
  });

  describe('getTodayPlans', () => {
    it('should GET /today', async () => {
      mock.onGet(`${BASE}/today`).reply(200, {
        success: true,
        data: [{ id: 'pp2', productName: 'Noodles', status: 'IN_PROGRESS' }],
        message: 'ok',
      });

      const result = await productionPlanApiClient.getTodayPlans();
      expect(result.success).toBe(true);
      expect(result.data).toHaveLength(1);
    });
  });

  describe('getPendingExecutionPlans', () => {
    it('should GET /pending-execution', async () => {
      mock.onGet(`${BASE}/pending-execution`).reply(200, {
        success: true,
        data: [{ id: 'pp3', productName: 'Rice Ball', status: 'PLANNED' }],
        message: 'ok',
      });

      const result = await productionPlanApiClient.getPendingExecutionPlans();
      expect(result.success).toBe(true);
      expect(result.data[0].status).toBe('PLANNED');
    });
  });

  // ---- Create & Update ----

  describe('createProductionPlan', () => {
    it('should POST to base path', async () => {
      const data = { productName: 'Dumpling', plannedQuantity: 1000, plannedDate: '2026-03-20' };
      mock.onPost(BASE).reply(200, {
        success: true,
        data: { id: 'pp4', ...data, status: 'PLANNED' },
        message: 'Created',
      });

      const result = await productionPlanApiClient.createProductionPlan(data);
      expect(result.success).toBe(true);
      expect(result.data.id).toBe('pp4');
      const request = mock.history.post[0];
      const body = JSON.parse(request.data);
      expect(body.clientRequestId).toMatch(/^rn-plan-/);
      expect(request.headers?.['Idempotency-Key']).toBe(body.clientRequestId);
    });

    it('reuses a caller-provided idempotency key for retries', async () => {
      const data = {
        productTypeId: 'pt1',
        plannedQuantity: 10,
        clientRequestId: 'rn-plan-stable-retry',
      };
      mock.onPost(BASE).reply(200, {
        success: true,
        data: { id: 'pp5', status: 'PLANNED' },
        message: 'Created',
      });

      await productionPlanApiClient.createProductionPlan(data);
      await productionPlanApiClient.createProductionPlan(data);

      const first = mock.history.post[0];
      const second = mock.history.post[1];
      expect(first.headers?.['Idempotency-Key']).toBe('rn-plan-stable-retry');
      expect(second.headers?.['Idempotency-Key']).toBe('rn-plan-stable-retry');
      expect(JSON.parse(first.data).clientRequestId).toBe('rn-plan-stable-retry');
      expect(JSON.parse(second.data).clientRequestId).toBe('rn-plan-stable-retry');
    });
  });

  describe('updateProductionPlan', () => {
    it('should PUT /{planId}', async () => {
      const data = { plannedQuantity: 2000 };
      mock.onPut(`${BASE}/pp1`).reply(200, {
        success: true,
        data: { id: 'pp1', plannedQuantity: 2000 },
        message: 'Updated',
      });

      const result = await productionPlanApiClient.updateProductionPlan('pp1', data);
      expect(result.success).toBe(true);
      expect(result.data.plannedQuantity).toBe(2000);
    });
  });

  describe('deleteProductionPlan', () => {
    it('should DELETE /{planId}', async () => {
      mock.onDelete(`${BASE}/pp1`).reply(200, {
        success: true,
        data: null,
        message: 'Deleted',
      });

      const result = await productionPlanApiClient.deleteProductionPlan('pp1');
      expect(result.success).toBe(true);
    });
  });

  // ---- Lifecycle operations ----

  describe('startProduction', () => {
    it('should POST /{planId}/start', async () => {
      mock.onPost(`${BASE}/pp1/start`).reply(200, {
        success: true,
        data: { id: 'pp1', status: 'IN_PROGRESS', startedAt: '2026-03-20T08:00:00Z' },
        message: 'Production started',
      });

      const result = await productionPlanApiClient.startProduction('pp1');
      expect(result.success).toBe(true);
      expect(result.data.status).toBe('IN_PROGRESS');
    });
  });

  describe('completeProduction', () => {
    it('should POST /{planId}/complete with actual quantity', async () => {
      mock.onPost(`${BASE}/pp1/complete`).reply(200, {
        success: true,
        data: { id: 'pp1', status: 'COMPLETED', actualQuantity: 480 },
        message: 'Production completed',
      });

      const result = await productionPlanApiClient.completeProduction('pp1', 480);
      expect(result.success).toBe(true);
      expect(result.data.status).toBe('COMPLETED');
      expect(result.data.actualQuantity).toBe(480);
    });
  });

  describe('cancelProductionPlan', () => {
    it('should POST /{planId}/cancel with reason', async () => {
      mock.onPost(`${BASE}/pp1/cancel`).reply(200, {
        success: true,
        data: { id: 'pp1', status: 'CANCELLED' },
        message: 'Cancelled',
      });

      const result = await productionPlanApiClient.cancelProductionPlan('pp1', 'Material shortage');
      expect(result.success).toBe(true);
      expect(result.data.status).toBe('CANCELLED');
    });

    it('should POST /{planId}/cancel without reason', async () => {
      mock.onPost(`${BASE}/pp2/cancel`).reply(200, {
        success: true,
        data: { id: 'pp2', status: 'CANCELLED' },
        message: 'Cancelled',
      });

      const result = await productionPlanApiClient.cancelProductionPlan('pp2');
      expect(result.success).toBe(true);
    });
  });

  // ---- Material consumption ----

  describe('recordMaterialConsumption', () => {
    it('should POST /{planId}/consumption', async () => {
      const consumption = { materialId: 'm1', quantity: 50, unit: 'kg' };
      mock.onPost(`${BASE}/pp1/consumption`).reply(200, {
        success: true,
        data: { id: 'mc1', planId: 'pp1', materialId: 'm1', quantity: 50 },
        message: 'Recorded',
      });

      const result = await productionPlanApiClient.recordMaterialConsumption('pp1', consumption);
      expect(result.success).toBe(true);
      expect(result.data.quantity).toBe(50);
    });
  });

  // ---- Error handling ----

  describe('error handling', () => {
    it('should propagate network errors', async () => {
      mock.onGet(BASE).networkError();
      await expect(productionPlanApiClient.getProductionPlans()).rejects.toThrow();
    });

    it('should propagate 404 for non-existent plan', async () => {
      mock.onGet(`${BASE}/nonexistent`).reply(404, {
        success: false,
        data: null,
        message: 'Plan not found',
      });

      await expect(productionPlanApiClient.getProductionPlanById('nonexistent')).rejects.toThrow();
    });

    it('should propagate 500 errors', async () => {
      mock.onPost(`${BASE}/pp1/start`).reply(500, {
        success: false,
        data: null,
        message: 'Internal Server Error',
      });

      await expect(productionPlanApiClient.startProduction('pp1')).rejects.toThrow();
    });
  });
});
