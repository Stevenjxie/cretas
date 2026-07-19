// @ts-nocheck
import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { aiApiClient } from '../../../services/api/aiApiClient';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
const AI_BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/ai`;
const CONV_BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/conversation`;
const INTENT_BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/ai-intents`;

let mock: MockAdapter;

beforeEach(() => {
  mock = createApiMock();
});

afterEach(() => {
  resetApiMock(mock);
});

describe('aiApiClient', () => {
  // ---- Cost Analysis ----

  describe('analyzeBatchCost', () => {
    it('should POST to /ai/analysis/cost/batch and return transformed response', async () => {
      const request = { batchId: 1, includeDetails: true };
      mock.onPost(`${AI_BASE}/analysis/cost/batch`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Cost breakdown text', session_id: 's1' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.analyzeBatchCost(request);
      expect(result).toBeDefined();
      // transformAnalysisResponse maps session_id through as-is
      expect(result.session_id).toBe('s1');
      expect(result.analysis).toBe('Cost breakdown text');
    });

    it('should handle missing optional fields gracefully', async () => {
      const request = { batchId: 2 };
      mock.onPost(`${AI_BASE}/analysis/cost/batch`).reply(200, {
        code: 200,
        data: { success: false, analysis: null, session_id: null },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.analyzeBatchCost(request);
      expect(result).toBeDefined();
      // transformAnalysisResponse coalesces null analysis to ''
      expect(result.analysis).toBe('');
    });
  });

  describe('analyzeTimeRangeCost', () => {
    it('should POST to /ai/analysis/cost/time-range', async () => {
      const request = { startDate: '2026-01-01', endDate: '2026-01-31' };
      mock.onPost(`${AI_BASE}/analysis/cost/time-range`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Monthly cost trends', session_id: 's2' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.analyzeTimeRangeCost(request);
      expect(result).toBeDefined();
      expect(result.session_id).toBe('s2');
    });
  });

  describe('compareBatchCosts', () => {
    it('should POST to /ai/analysis/cost/compare', async () => {
      const request = { batchIds: [1, 2] };
      mock.onPost(`${AI_BASE}/analysis/cost/compare`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Comparison result', session_id: 's3' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.compareBatchCosts(request);
      expect(result).toBeDefined();
      expect(result.analysis).toBe('Comparison result');
    });
  });

  // ---- Quota ----

  describe('getQuotaInfo', () => {
    it('should GET /ai/quota and transform backend format to frontend format', async () => {
      // Backend returns AIQuotaInfoBackend: { total, used, remaining, usageRate, exceeded, resetDate }
      mock.onGet(`${AI_BASE}/quota`).reply(200, {
        code: 200,
        data: { total: 100, used: 25, remaining: 75, usageRate: 25, exceeded: false, resetDate: '2026-03-27' },
        success: true,
        message: 'ok',
      });

      // transformQuotaResponse maps to AIQuotaInfo
      const result = await aiApiClient.getQuotaInfo();
      expect(result).toBeDefined();
      expect(result.weeklyQuota).toBe(100);
      expect(result.usedQuota).toBe(25);
      expect(result.remainingQuota).toBe(75);
      expect(result.resetDate).toBe('2026-03-27');
      expect(result.usagePercentage).toBe(25);
      expect(result.status).toBe('active');
    });

    it('should handle zero quota without division error', async () => {
      mock.onGet(`${AI_BASE}/quota`).reply(200, {
        code: 200,
        data: { total: 0, used: 0, remaining: 0, usageRate: 0, exceeded: false, resetDate: '' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.getQuotaInfo();
      expect(result).toBeDefined();
      expect(result.weeklyQuota).toBe(0);
    });
  });

  describe('updateQuota', () => {
    it('should PUT /ai/quota with new quota value', async () => {
      // updateQuota returns void; the PUT sends params: { newQuotaLimit }
      mock.onPut(`${AI_BASE}/quota`).reply(200, {
        code: 200,
        data: null,
        success: true,
        message: 'ok',
      });

      await expect(aiApiClient.updateQuota(200)).resolves.toBeUndefined();
    });
  });

  // ---- Conversation ----

  describe('startConversation', () => {
    it('should POST to /conversation/start', async () => {
      mock.onPost(`${CONV_BASE}/start`, { userInput: 'Hello' }).reply(200, {
        success: true,
        data: { status: 'ACTIVE', message: 'Hi there', sessionId: 'conv-001' },
      });

      const result = await aiApiClient.startConversation('Hello');
      expect(result).toBeDefined();
      expect(result.sessionId).toBe('conv-001');
    });
  });

  describe('replyConversation', () => {
    it('should POST to /conversation/{sessionId}/reply', async () => {
      // replyConversation(sessionId, userReply) POSTs to /{sessionId}/reply
      mock.onPost(`${CONV_BASE}/conv-001/reply`).reply(200, {
        success: true,
        data: { status: 'ACTIVE', message: 'Reply text', sessionId: 'conv-001' },
      });

      const result = await aiApiClient.replyConversation('conv-001', 'Next question');
      expect(result).toBeDefined();
    });
  });

  describe('confirmConversationIntent', () => {
    it('should POST to /conversation/{sessionId}/confirm', async () => {
      // confirmConversationIntent(sessionId, intentCode) POSTs to /{sessionId}/confirm
      mock.onPost(`${CONV_BASE}/conv-001/confirm`).reply(200, {
        success: true,
        message: 'Confirmed',
      });

      const result = await aiApiClient.confirmConversationIntent('conv-001', 'MATERIAL_QUERY');
      expect(result).toBeDefined();
    });
  });

  describe('getConversation', () => {
    it('should GET /conversation/{sessionId}', async () => {
      mock.onGet(`${CONV_BASE}/conv-001`).reply(200, {
        code: 200,
        data: { sessionId: 'conv-001', messages: [], status: 'active' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.getConversation('conv-001');
      expect(result).toBeDefined();
      expect(result.sessionId).toBe('conv-001');
    });
  });

  describe('continueConversation', () => {
    it('should POST to /ai/analysis/cost/batch with session context', async () => {
      // continueConversation reuses the batch cost endpoint with question + sessionId
      const data = { sessionId: 'conv-001', message: 'Follow up' };
      mock.onPost(`${AI_BASE}/analysis/cost/batch`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Continued analysis' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.continueConversation(data);
      expect(result).toBeDefined();
      expect(result.analysis).toBe('Continued analysis');
    });
  });

  describe('closeConversation', () => {
    it('should POST to /conversation/{sessionId}/cancel', async () => {
      // closeConversation POSTs to /{sessionId}/cancel, returns void
      mock.onPost(`${CONV_BASE}/conv-001/cancel`).reply(200, {
        success: true,
        message: 'ok',
      });

      await expect(aiApiClient.closeConversation('conv-001')).resolves.toBeUndefined();
    });
  });

  // ---- Reports ----

  describe('getReports', () => {
    it('should GET /ai/reports without params', async () => {
      mock.onGet(`${AI_BASE}/reports`).reply(200, {
        code: 200,
        data: { reports: [], total: 0 },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.getReports();
      expect(result).toBeDefined();
      expect(result.reports).toHaveLength(0);
    });

    it('should GET /ai/reports with params', async () => {
      mock.onGet(`${AI_BASE}/reports`).reply(200, {
        code: 200,
        data: { reports: [{ id: 1, reportType: 'batch', createdAt: '2026-03-20' }], total: 1 },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.getReports({ reportType: 'batch' });
      expect(result).toBeDefined();
      expect(result.total).toBe(1);
    });
  });

  describe('getReportDetail', () => {
    it('should GET /ai/reports/{reportId}', async () => {
      mock.onGet(`${AI_BASE}/reports/1`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Report details' },
        success: true,
        message: 'ok',
      });

      // getReportDetail takes a number
      const result = await aiApiClient.getReportDetail(1);
      expect(result).toBeDefined();
      expect(result.analysis).toBe('Report details');
    });
  });

  describe('generateReport', () => {
    it('should POST to /ai/reports/generate', async () => {
      const request = { reportType: 'batch' as const, batchId: 1 };
      mock.onPost(`${AI_BASE}/reports/generate`).reply(200, {
        code: 200,
        data: { success: true, analysis: 'Generated report' },
        success: true,
        message: 'ok',
      });

      const result = await aiApiClient.generateReport(request);
      expect(result).toBeDefined();
      expect(result.analysis).toBe('Generated report');
    });
  });

  // ---- Intent ----

  describe('recognizeIntent', () => {
    it('should POST to /ai-intents/recognize', async () => {
      mock.onPost(`${INTENT_BASE}/recognize`).reply(200, {
        code: 200,
        data: { intentCode: 'INVENTORY_QUERY', confidence: 0.95 },
        success: true,
        message: 'ok',
      });

      // recognizeIntent(userInput, context?)
      const result = await aiApiClient.recognizeIntent('查询库存');
      expect(result).toBeDefined();
      expect(result.intentCode).toBe('INVENTORY_QUERY');
    });
  });

  describe('executeIntent', () => {
    it('should POST to /ai-intents/execute', async () => {
      mock.onPost(`${INTENT_BASE}/execute`).reply(200, {
        code: 200,
        data: { success: true, resultType: 'data', resultData: 'Inventory data' },
        success: true,
        message: 'ok',
      });

      // executeIntent(intentCode, parameters?)
      const result = await aiApiClient.executeIntent('INVENTORY_QUERY', { userInput: '查库存' });
      expect(result).toBeDefined();
      expect(result.success).toBe(true);
    });
  });

  // ---- Error handling ----

  describe('error handling', () => {
    it('should propagate network errors', async () => {
      mock.onPost(`${AI_BASE}/analysis/cost/batch`).networkError();
      await expect(aiApiClient.analyzeBatchCost({ batchId: 1 })).rejects.toThrow();
    });

    it('should propagate 500 errors', async () => {
      mock.onGet(`${AI_BASE}/quota`).reply(500, {
        code: 500,
        data: null,
        success: false,
        message: 'Internal Server Error',
      });

      await expect(aiApiClient.getQuotaInfo()).rejects.toThrow();
    });

    it('should propagate 404 errors for conversation', async () => {
      mock.onGet(`${CONV_BASE}/nonexistent`).reply(404, {
        code: 404,
        data: null,
        success: false,
        message: 'Not found',
      });

      await expect(aiApiClient.getConversation('nonexistent')).rejects.toThrow();
    });
  });
});
