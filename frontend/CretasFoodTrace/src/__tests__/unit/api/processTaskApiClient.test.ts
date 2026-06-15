/**
 * processTaskApiClient 单元测试
 * 测试工序任务API客户端：查询任务、审批报工、补报、错误处理
 */

import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

// Helper type for raw API responses in mock tests
// axios-mock-adapter returns the full envelope, so result has success/data/message
interface MockApiResponse<T = any> {
  success: boolean;
  code?: number;
  message?: string;
  data: T;
}

describe('processTaskApiClient', () => {
  let mock: MockAdapter;
  const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
  const BASE = `/api/mobile/${DEFAULT_FACTORY_ID}`;

  beforeEach(() => {
    mock = createApiMock();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  // ========== RN-API-01: getActiveTasks ==========
  describe('RN-API-01: getActiveTasks', () => {
    it('should GET /process-tasks/active and return task list', async () => {
      const mockTasks = [
        { id: 'task-1', status: 'IN_PROGRESS', processName: '炸制', plannedQuantity: 100, completedQuantity: 50 },
        { id: 'task-2', status: 'PENDING', processName: '冷却', plannedQuantity: 200, completedQuantity: 0 },
      ];

      mock.onGet(`${BASE}/process-tasks/active`).reply(200, {
        success: true,
        code: 200,
        message: '成功',
        data: mockTasks,
      });

      const result = await processTaskApiClient.getActiveTasks('CRETAS_2024_001') as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data).toHaveLength(2);
      expect(result.data[0].id).toBe('task-1');
      expect(result.data[0].status).toBe('IN_PROGRESS');
      expect(result.data[1].processName).toBe('冷却');
    });

    it('should use default factoryId when not provided', async () => {
      mock.onGet(/\/process-tasks\/active/).reply((config) => {
        expect(config.url).toContain(DEFAULT_FACTORY_ID);
        return [200, { success: true, data: [] }];
      });

      const result = await processTaskApiClient.getActiveTasks() as MockApiResponse;
      expect(result.success).toBe(true);
    });
  });

  // ========== RN-API-02: getTasks with query params ==========
  describe('RN-API-02: getTasks', () => {
    it('should GET /process-tasks with status, productTypeId, and page params', async () => {
      const mockResponse = {
        success: true,
        code: 200,
        message: '成功',
        data: {
          content: [
            { id: 'task-1', status: 'COMPLETED', processName: '炸制', productTypeId: 'PT-001' },
          ],
          totalElements: 1,
          totalPages: 1,
        },
      };

      mock.onGet(`${BASE}/process-tasks`).reply((config) => {
        expect(config.params).toHaveProperty('status', 'COMPLETED');
        expect(config.params).toHaveProperty('productTypeId', 'PT-001');
        expect(config.params).toHaveProperty('page', 1);
        return [200, mockResponse];
      });

      const result = await processTaskApiClient.getTasks({
        status: 'COMPLETED',
        productTypeId: 'PT-001',
        page: 1,
        size: 20,
      }) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.content).toHaveLength(1);
      expect(result.data.totalElements).toBe(1);
    });

    it('should handle empty result', async () => {
      mock.onGet(`${BASE}/process-tasks`).reply(200, {
        success: true,
        code: 200,
        message: '成功',
        data: { content: [], totalElements: 0, totalPages: 0 },
      });

      const result = await processTaskApiClient.getTasks({ page: 1, size: 10 }) as MockApiResponse;
      expect(result.data.content).toHaveLength(0);
    });
  });

  // ========== RN-API-03: getTaskSummary ==========
  describe('RN-API-03: getTaskSummary', () => {
    it('should GET /process-tasks/{id}/summary and return summary data', async () => {
      const taskId = 'task-abc-123';
      const mockSummary = {
        success: true,
        code: 200,
        message: '成功',
        data: {
          task: { id: taskId, processName: '炸制', plannedQuantity: 100, completedQuantity: 60 },
          totalReported: 70,
          approvedTotal: 60,
          pendingTotal: 10,
          rejectedTotal: 0,
          workerCount: 5,
        },
      };

      mock.onGet(`${BASE}/process-tasks/${taskId}/summary`).reply(200, mockSummary);

      const result = await processTaskApiClient.getTaskSummary(taskId) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.task.id).toBe(taskId);
      expect(result.data.approvedTotal).toBe(60);
      expect(result.data.workerCount).toBe(5);
    });
  });

  // ========== RN-API-04: getRunOverview ==========
  describe('RN-API-04: getRunOverview', () => {
    it('should GET /process-tasks/run/{runId} and return run overview', async () => {
      const runId = 'run-2026-001';
      const mockOverview = {
        success: true,
        code: 200,
        message: '成功',
        data: {
          productionRunId: runId,
          tasks: [
            { id: 'task-1', processName: '炸制', status: 'COMPLETED' },
            { id: 'task-2', processName: '冷却', status: 'IN_PROGRESS' },
          ],
          overallProgress: 65,
          completedTasks: 1,
          totalTasks: 2,
        },
      };

      mock.onGet(`${BASE}/process-tasks/run/${runId}`).reply(200, mockOverview);

      const result = await processTaskApiClient.getRunOverview(runId) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.productionRunId).toBe(runId);
      expect(result.data.tasks).toHaveLength(2);
      expect(result.data.overallProgress).toBe(65);
      expect(result.data.completedTasks).toBe(1);
    });
  });

  // ========== RN-API-05: approveReport ==========
  describe('RN-API-05: approveReport', () => {
    it('should PUT /process-work-reporting/{id}/approve', async () => {
      const reportId = 42;
      mock.onPut(`${BASE}/process-work-reporting/${reportId}/approve`).reply(200, {
        success: true,
        code: 200,
        message: '审批通过',
        data: { id: reportId, approvalStatus: 'APPROVED' },
      });

      const result = await processTaskApiClient.approveReport(reportId) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.approvalStatus).toBe('APPROVED');
    });
  });

  // ========== RN-API-06: rejectReport ==========
  describe('RN-API-06: rejectReport', () => {
    it('should PUT /process-work-reporting/{id}/reject with reason body', async () => {
      const reportId = 42;
      const reason = '数量与实际不符';

      mock.onPut(`${BASE}/process-work-reporting/${reportId}/reject`).reply((config) => {
        const body = JSON.parse(config.data);
        expect(body.reason).toBe(reason);
        return [200, {
          success: true,
          code: 200,
          message: '已驳回',
          data: { id: reportId, approvalStatus: 'REJECTED', rejectedReason: reason },
        }];
      });

      const result = await processTaskApiClient.rejectReport(reportId, reason) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.approvalStatus).toBe('REJECTED');
      expect(result.data.rejectedReason).toBe(reason);
    });
  });

  // ========== RN-API-07: submitSupplement ==========
  describe('RN-API-07: submitSupplement', () => {
    it('should POST /process-work-reporting/supplement with task data', async () => {
      const supplementData = {
        processTaskId: 'task-abc-123',
        outputQuantity: 25,
        reportDate: '2026-03-12',
        notes: '补报漏记数量',
      };

      mock.onPost(`${BASE}/process-work-reporting/supplement`).reply((config) => {
        const body = JSON.parse(config.data);
        expect(body.processTaskId).toBe('task-abc-123');
        expect(body.outputQuantity).toBe(25);
        expect(body.reportDate).toBe('2026-03-12');
        expect(body.notes).toBe('补报漏记数量');
        return [200, {
          success: true,
          code: 200,
          message: '补报提交成功',
          data: { id: 101, processTaskId: 'task-abc-123', isSupplemental: true, approvalStatus: 'PENDING' },
        }];
      });

      const result = await processTaskApiClient.submitSupplement(supplementData) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data.isSupplemental).toBe(true);
      expect(result.data.approvalStatus).toBe('PENDING');
    });
  });

  // ========== RN-API-08: Error handling ==========
  describe('RN-API-08: Error handling', () => {
    it('404 Not Found should throw', async () => {
      mock.onGet(/\/process-tasks\/nonexistent/).reply(404, {
        success: false,
        message: '任务不存在',
        code: 'NOT_FOUND',
      });

      await expect(
        processTaskApiClient.getTaskById('nonexistent')
      ).rejects.toThrow();
    });

    it('409 Conflict should throw', async () => {
      mock.onPut(/\/process-work-reporting\/\d+\/approve/).reply(409, {
        success: false,
        message: '报工已审批，不可重复操作',
        code: 'CONFLICT',
      });

      await expect(
        processTaskApiClient.approveReport(99)
      ).rejects.toThrow();
    });

    it('500 Internal Server Error should throw', async () => {
      mock.onPost(/\/process-work-reporting\/supplement/).reply(500, {
        success: false,
        message: '服务器内部错误',
        code: 'INTERNAL_ERROR',
      });

      await expect(
        processTaskApiClient.submitSupplement({
          processTaskId: 'task-1',
          outputQuantity: 10,
        })
      ).rejects.toThrow();
    });

    it('network error should throw', async () => {
      mock.onGet(`${BASE}/process-tasks/active`).networkError();

      await expect(
        processTaskApiClient.getActiveTasks()
      ).rejects.toThrow();
    });

    it('timeout should throw', async () => {
      mock.onGet(`${BASE}/process-tasks/active`).timeout();

      await expect(
        processTaskApiClient.getActiveTasks()
      ).rejects.toThrow();
    });
  });

  // ========== Additional coverage: batchApprove, getReportsByTask, getWorkersByTask ==========
  describe('batchApprove', () => {
    it('should PUT /process-work-reporting/batch-approve with reportIds', async () => {
      const reportIds = [10, 11, 12];

      mock.onPut(`${BASE}/process-work-reporting/batch-approve`).reply((config) => {
        const body = JSON.parse(config.data);
        // batchApprove sends the array directly as body, not wrapped in { reportIds: ... }
        expect(body).toEqual(reportIds);
        return [200, {
          success: true,
          code: 200,
          message: '批量审批成功',
          data: { approvedCount: 3 },
        }];
      });

      const result = await processTaskApiClient.batchApprove(reportIds) as MockApiResponse;
      expect(result.success).toBe(true);
      expect(result.data.approvedCount).toBe(3);
    });
  });

  describe('getReportsByTask', () => {
    it('should GET /process-work-reporting/by-task/{taskId}', async () => {
      const taskId = 'task-abc-123';
      mock.onGet(`${BASE}/process-work-reporting/by-task/${taskId}`).reply(200, {
        success: true,
        code: 200,
        message: '成功',
        data: [
          { id: 1, processTaskId: taskId, outputQuantity: 30, approvalStatus: 'APPROVED' },
          { id: 2, processTaskId: taskId, outputQuantity: 20, approvalStatus: 'PENDING' },
        ],
      });

      const result = await processTaskApiClient.getReportsByTask(taskId) as MockApiResponse;
      expect(result.success).toBe(true);
      expect(result.data).toHaveLength(2);
    });
  });

  describe('getWorkersByTask', () => {
    it('should GET /process-work-reporting/by-task/{taskId}/workers', async () => {
      const taskId = 'task-abc-123';
      mock.onGet(`${BASE}/process-work-reporting/by-task/${taskId}/workers`).reply(200, {
        success: true,
        code: 200,
        message: '成功',
        data: [
          { workerId: 33, workerName: '张三', totalQuantity: 50, approvedQuantity: 40, reportCount: 3 },
        ],
      });

      const result = await processTaskApiClient.getWorkersByTask(taskId) as MockApiResponse;
      expect(result.success).toBe(true);
      expect(result.data).toHaveLength(1);
      expect(result.data[0].workerName).toBe('张三');
    });
  });

  describe('generateTasksFromProduct', () => {
    it('POSTs plannedQuantities so generated tasks use the plan quantity', async () => {
      mock.onPost(`${BASE}/process-tasks/generate-from-product`).reply((config) => {
        const body = JSON.parse(config.data);
        expect(body.productTypeId).toBe('PT-1');
        expect(body.sourceCustomerName).toBe('Customer A');
        expect(body.plannedQuantities).toEqual({
          'WP-CUT': 380,
          'WP-PACK': 380,
        });
        return [200, {
          success: true,
          data: [{ id: 'TASK-1', productionRunId: 'RUN-1' }],
        }];
      });

      const result = await processTaskApiClient.generateTasksFromProduct({
        productTypeId: 'PT-1',
        sourceCustomerName: 'Customer A',
        plannedQuantities: {
          'WP-CUT': 380,
          'WP-PACK': 380,
        },
      }) as MockApiResponse;

      expect(result.success).toBe(true);
      expect(result.data[0].productionRunId).toBe('RUN-1');
    });
  });
});
