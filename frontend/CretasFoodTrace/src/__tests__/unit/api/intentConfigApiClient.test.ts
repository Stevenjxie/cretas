// @ts-nocheck
import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { intentConfigApiClient } from '../../../services/api/intentConfigApiClient';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
// Actual base path: /api/mobile/{factoryId}/ai-intents
const BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/ai-intents`;

let mock: MockAdapter;

beforeEach(() => {
  mock = createApiMock();
});

afterEach(() => {
  resetApiMock(mock);
});

describe('intentConfigApiClient', () => {
  // ---- Query intents ----

  describe('getAllIntents', () => {
    it('should GET base path and return array directly', async () => {
      // getAllIntents GETs the base path, returns AIIntentConfig[] (unwrapped)
      mock.onGet(BASE).reply(200, {
        success: true,
        data: [{ intentCode: 'MATERIAL_QUERY', intentName: 'Query materials' }],
        message: 'ok',
      });

      const result = await intentConfigApiClient.getAllIntents();
      // Returns AIIntentConfig[] directly (not { success, data })
      expect(result).toHaveLength(1);
      expect(result[0].intentCode).toBe('MATERIAL_QUERY');
    });

    it('should return empty array when no intents exist', async () => {
      mock.onGet(BASE).reply(200, {
        success: true,
        data: [],
        message: 'ok',
      });

      const result = await intentConfigApiClient.getAllIntents();
      expect(result).toHaveLength(0);
    });
  });

  describe('getIntentsByCategory', () => {
    it('should GET /category/{category}', async () => {
      mock.onGet(`${BASE}/category/DATA_QUERY`).reply(200, {
        success: true,
        data: [{ intentCode: 'INVENTORY_QUERY', intentCategory: 'DATA_QUERY' }],
        message: 'ok',
      });

      const result = await intentConfigApiClient.getIntentsByCategory('DATA_QUERY');
      // Returns AIIntentConfig[] directly
      expect(result).toHaveLength(1);
      expect(result[0].intentCategory).toBe('DATA_QUERY');
    });
  });

  describe('getAllCategories', () => {
    it('should GET /categories', async () => {
      mock.onGet(`${BASE}/categories`).reply(200, {
        success: true,
        data: ['DATA_QUERY', 'DATA_OPERATION', 'REPORT'],
        message: 'ok',
      });

      const result = await intentConfigApiClient.getAllCategories();
      // Returns string[] directly
      expect(result).toContain('DATA_QUERY');
    });
  });

  describe('getIntent', () => {
    it('should GET /{intentCode}', async () => {
      mock.onGet(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', intentName: 'Query materials', enabled: true },
        message: 'ok',
      });

      const result = await intentConfigApiClient.getIntent('MATERIAL_QUERY');
      // Returns AIIntentConfig | null directly
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('MATERIAL_QUERY');
    });
  });

  // ---- Intent recognition & execution ----

  describe('recognizeIntent', () => {
    it('should POST /recognize with user input', async () => {
      mock.onPost(`${BASE}/recognize`).reply(200, {
        success: true,
        data: { matched: true, intentCode: 'INVENTORY_QUERY' },
        message: 'ok',
      });

      // recognizeIntent(userInput: string)
      const result = await intentConfigApiClient.recognizeIntent('查库存');
      // Returns IntentRecognitionResult directly
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('INVENTORY_QUERY');
    });
  });

  describe('executeIntent', () => {
    it('should POST /execute', async () => {
      const request = { userInput: '查面粉库存' };
      mock.onPost(`${BASE}/execute`).reply(200, {
        success: true,
        data: { success: true, resultType: 'data', message: 'Inventory list' },
        message: 'ok',
      });

      // executeIntent(request: IntentExecuteRequest)
      const result = await intentConfigApiClient.executeIntent(request);
      // Returns IntentExecuteResponse directly
      expect(result).toBeDefined();
      expect(result.resultType).toBe('data');
    });
  });

  describe('previewIntent', () => {
    it('should POST /preview', async () => {
      const request = { userInput: '更新原料数量为100' };
      const confirmableAction = {
        confirmToken: 'opaque-preview-token',
        commandDigest: 'a'.repeat(64),
        expiresAt: '2026-07-18T10:05:00Z',
        requestId: 'request-12345678',
        idempotencyKey: 'idem-12345678',
      };
      mock.onPost(`${BASE}/preview`).reply(200, {
        success: true,
        data: {
          success: true,
          resultType: 'preview',
          previewData: { currentValue: 50, newValue: 100 },
          confirmableAction,
        },
        message: 'ok',
      });

      // previewIntent(request: IntentExecuteRequest)
      const result = await intentConfigApiClient.previewIntent(request);
      // Returns IntentExecuteResponse directly
      expect(result).toBeDefined();
      expect(result.resultType).toBe('preview');
      expect(result.confirmableAction).toEqual(confirmableAction);
      expect(result).not.toHaveProperty('confirmToken');
    });
  });

  describe('confirmIntent', () => {
    it('uses the fixed path and keeps the opaque token out of URL and JSON body', async () => {
      const proof = {
        confirmToken: 'opaque-sensitive-confirmation-token',
        commandDigest: 'b'.repeat(64),
        expiresAt: '2026-07-18T10:05:00Z',
        requestId: 'request-12345678',
        idempotencyKey: 'idem-12345678',
      };

      mock.onPost(`${BASE}/confirm`).reply(config => {
        const body = JSON.parse(config.data);
        expect(config.url).toBe(`${BASE}/confirm`);
        expect(config.url).not.toContain(proof.confirmToken);
        expect(config.headers?.['X-Cretas-Confirmation-Token']).toBe(proof.confirmToken);
        expect(body).toEqual({
          commandDigest: proof.commandDigest,
          expiresAt: proof.expiresAt,
          requestId: proof.requestId,
          idempotencyKey: proof.idempotencyKey,
        });
        expect(body).not.toHaveProperty('confirmToken');
        expect(JSON.stringify(body)).not.toContain(proof.confirmToken);
        return [200, {
          success: true,
          data: { success: true, resultType: 'action' },
          message: 'ok',
        }];
      });

      const result = await intentConfigApiClient.confirmIntent(proof);

      expect(result.resultType).toBe('action');
      expect(mock.history.post).toHaveLength(1);
    });
  });

  // ---- CRUD ----

  describe('createIntent', () => {
    it('should POST to base path', async () => {
      const data = { intentCode: 'NEW_INTENT', intentName: 'New feature', intentCategory: 'ANALYSIS', keywords: [], regexPatterns: [], priority: 50, sensitivityLevel: 'LOW', enabled: true };
      // createIntent POSTs to base path (not /intents)
      mock.onPost(BASE).reply(200, {
        success: true,
        data: { intentCode: 'NEW_INTENT', intentName: 'New feature' },
        message: 'ok',
      });

      const result = await intentConfigApiClient.createIntent(data);
      // Returns AIIntentConfig directly
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('NEW_INTENT');
    });
  });

  describe('updateIntent', () => {
    it('should PUT /{intentCode}', async () => {
      const data = { intentName: 'Updated name' };
      // updateIntent PUTs to /{intentCode}
      mock.onPut(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', intentName: 'Updated name' },
        message: 'ok',
      });

      const result = await intentConfigApiClient.updateIntent('MATERIAL_QUERY', data);
      // Returns AIIntentConfig directly
      expect(result).toBeDefined();
      expect(result.intentName).toBe('Updated name');
    });
  });

  describe('setIntentActive', () => {
    it('should PATCH /{intentCode}/active to activate', async () => {
      // setIntentActive uses PATCH (not PUT), returns void
      mock.onPatch(`${BASE}/MATERIAL_QUERY/active`).reply(200, {
        success: true,
        data: null,
        message: 'ok',
      });

      await expect(intentConfigApiClient.setIntentActive('MATERIAL_QUERY', true)).resolves.toBeUndefined();
    });

    it('should PATCH /{intentCode}/active to deactivate', async () => {
      mock.onPatch(`${BASE}/TEST_INTENT/active`).reply(200, {
        success: true,
        data: null,
        message: 'ok',
      });

      await expect(intentConfigApiClient.setIntentActive('TEST_INTENT', false)).resolves.toBeUndefined();
    });
  });

  describe('deleteIntent', () => {
    it('should DELETE /{intentCode}', async () => {
      // deleteIntent DELETEs /{intentCode}, returns void
      mock.onDelete(`${BASE}/OLD_INTENT`).reply(200, {
        success: true,
        data: null,
        message: 'Deleted',
      });

      await expect(intentConfigApiClient.deleteIntent('OLD_INTENT')).resolves.toBeUndefined();
    });
  });

  // ---- Keywords ----

  describe('addKeywords', () => {
    it('should add keywords via GET + updateIntent', async () => {
      // addKeywords does: getIntent (GET) → merge keywords → updateIntent (PUT)
      mock.onGet(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', keywords: ['material', 'query'] },
        message: 'ok',
      });
      mock.onPut(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', keywords: ['material', 'query', 'new-kw'] },
        message: 'ok',
      });

      const result = await intentConfigApiClient.addKeywords('MATERIAL_QUERY', ['new-kw']);
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('MATERIAL_QUERY');
    });
  });

  describe('removeKeywords', () => {
    it('should remove keywords via GET + updateIntent', async () => {
      // removeKeywords does: getIntent (GET) → filter keywords → updateIntent (PUT)
      mock.onGet(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', keywords: ['material', 'query'] },
        message: 'ok',
      });
      mock.onPut(`${BASE}/MATERIAL_QUERY`).reply(200, {
        success: true,
        data: { intentCode: 'MATERIAL_QUERY', keywords: ['material'] },
        message: 'ok',
      });

      const result = await intentConfigApiClient.removeKeywords('MATERIAL_QUERY', ['query']);
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('MATERIAL_QUERY');
    });
  });

  // ---- Cache ----

  describe('refreshCache', () => {
    it('should POST /cache/refresh', async () => {
      // refreshCache returns void
      mock.onPost(`${BASE}/cache/refresh`).reply(200, {
        success: true,
        data: null,
        message: 'Cache refreshed',
      });

      await expect(intentConfigApiClient.refreshCache()).resolves.toBeUndefined();
    });
  });

  // ---- Error handling ----

  describe('error handling', () => {
    it('should propagate network errors', async () => {
      mock.onGet(BASE).networkError();
      await expect(intentConfigApiClient.getAllIntents()).rejects.toThrow();
    });

    it('should propagate 500 errors', async () => {
      mock.onPost(`${BASE}/execute`).reply(500, {
        success: false,
        data: null,
        message: 'Internal Server Error',
      });

      await expect(
        intentConfigApiClient.executeIntent({ userInput: 'bad input' })
      ).rejects.toThrow();
    });
  });
});
