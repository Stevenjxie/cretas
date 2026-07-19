// @ts-nocheck
/**
 * hrApiClient 单元测试
 * 测试HR管理API客户端：入职查询、Dashboard聚合、依赖服务委托
 */

import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

jest.mock('../../../services/api/timeStatsApiClient', () => ({
  timeStatsApiClient: {
    getDailyStats: jest.fn(),
  },
}));
jest.mock('../../../services/api/whitelistApiClient', () => ({
  whitelistApiClient: {
    getWhitelistStats: jest.fn(),
    getWhitelist: jest.fn(),
  },
}));
jest.mock('../../../services/api/userApiClient', () => ({
  userApiClient: {
    getUsers: jest.fn(),
  },
  UserDTO: {},
  PageResponse: {},
}));
jest.mock('../../../services/api/employeeAIApiClient', () => ({
  employeeAIApiClient: {
    analyzeEmployee: jest.fn(),
  },
}));

import { hrApiClient } from '../../../services/api/hrApiClient';
import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';
import { whitelistApiClient } from '../../../services/api/whitelistApiClient';
import { userApiClient } from '../../../services/api/userApiClient';
import { employeeAIApiClient } from '../../../services/api/employeeAIApiClient';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
const BASE = `/api/mobile/${DEFAULT_FACTORY_ID}`;

describe('hrApiClient', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = createApiMock();
    jest.clearAllMocks();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  // ========== getUsersByJoinDateRange ==========
  describe('getUsersByJoinDateRange', () => {
    it('should GET /users/join-date-range with date params and return page response', async () => {
      const pageResponse = {
        content: [{ id: 1, username: 'new_hire1' }],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      };

      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        code: 200,
        data: pageResponse,
        message: 'ok',
      });

      const result = await hrApiClient.getUsersByJoinDateRange({
        startDate: '2026-03-01',
        endDate: '2026-03-20',
      });

      expect(result).toEqual(pageResponse);
      expect(result.totalElements).toBe(1);
    });

    it('should pass query params excluding factoryId', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 },
        message: 'ok',
      });

      await hrApiClient.getUsersByJoinDateRange({
        startDate: '2026-01-01',
        endDate: '2026-01-31',
        page: 1,
        size: 10,
        factoryId: DEFAULT_FACTORY_ID,
      });

      const params = mock.history.get[0].params;
      expect(params.startDate).toBe('2026-01-01');
      expect(params.endDate).toBe('2026-01-31');
      expect(params.page).toBe(1);
      expect(params.size).toBe(10);
      expect(params.factoryId).toBeUndefined();
    });

    it('should return default empty page when data is null', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: null,
        message: 'ok',
      });

      const result = await hrApiClient.getUsersByJoinDateRange({
        startDate: '2026-03-01',
        endDate: '2026-03-20',
      });

      expect(result).toEqual({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 });
    });
  });

  // ========== getThisMonthNewHires ==========
  describe('getThisMonthNewHires', () => {
    it('should return totalElements from join-date-range query', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 5, totalPages: 1, size: 1, number: 0 },
        message: 'ok',
      });

      const count = await hrApiClient.getThisMonthNewHires();
      expect(count).toBe(5);
    });

    it('should return 0 on error', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).networkError();

      const count = await hrApiClient.getThisMonthNewHires();
      expect(count).toBe(0);
    });
  });

  // ========== getLastMonthNewHires ==========
  describe('getLastMonthNewHires', () => {
    it('should return totalElements for last month range', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 3, totalPages: 1, size: 1, number: 0 },
        message: 'ok',
      });

      const count = await hrApiClient.getLastMonthNewHires();
      expect(count).toBe(3);
    });

    it('should return 0 on error', async () => {
      mock.onGet(`${BASE}/users/join-date-range`).reply(500, { success: false, message: '错误' });

      const count = await hrApiClient.getLastMonthNewHires();
      expect(count).toBe(0);
    });
  });

  // ========== getDashboardData ==========
  describe('getDashboardData', () => {
    it('should aggregate data from all services when all succeed', async () => {
      (timeStatsApiClient.getDailyStats as jest.Mock).mockResolvedValue({
        data: { employeeCount: 25, records: [{ isLate: true }, { isLate: false }, { lateMinutes: 15 }] },
      });
      (whitelistApiClient.getWhitelistStats as jest.Mock).mockResolvedValue({
        pending: 3,
        disabled: 1,
      });
      (userApiClient.getUsers as jest.Mock).mockResolvedValue({
        totalElements: 50,
        content: [],
      });
      // Mock join-date-range for thisMonth and lastMonth
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 4, totalPages: 1, size: 1, number: 0 },
        message: 'ok',
      });

      const data = await hrApiClient.getDashboardData();

      expect(data.todayOnSite).toBe(25);
      expect(data.lateCount).toBe(2);
      expect(data.whitelistPending).toBe(4);
      expect(data.totalStaff).toBe(50);
      expect(data.thisMonthNewHires).toBe(4);
      expect(typeof data.attendanceRate).toBe('number');
    });

    it('should return defaults when all services fail', async () => {
      (timeStatsApiClient.getDailyStats as jest.Mock).mockRejectedValue(new Error('fail'));
      (whitelistApiClient.getWhitelistStats as jest.Mock).mockRejectedValue(new Error('fail'));
      (userApiClient.getUsers as jest.Mock).mockRejectedValue(new Error('fail'));
      mock.onGet(`${BASE}/users/join-date-range`).networkError();

      const data = await hrApiClient.getDashboardData();

      expect(data.todayOnSite).toBe(0);
      expect(data.totalStaff).toBe(0);
      expect(data.attendanceRate).toBe(0);
      expect(data.lateCount).toBe(0);
      expect(data.whitelistPending).toBe(0);
      expect(data.thisMonthNewHires).toBe(0);
    });

    it('should handle partial service failures gracefully', async () => {
      (timeStatsApiClient.getDailyStats as jest.Mock).mockResolvedValue({
        data: { employeeCount: 10, records: [] },
      });
      (whitelistApiClient.getWhitelistStats as jest.Mock).mockRejectedValue(new Error('fail'));
      (userApiClient.getUsers as jest.Mock).mockResolvedValue({
        totalElements: 30,
        content: [],
      });
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 2, totalPages: 1, size: 1, number: 0 },
        message: 'ok',
      });

      const data = await hrApiClient.getDashboardData();

      expect(data.todayOnSite).toBe(10);
      expect(data.whitelistPending).toBe(0);
      expect(data.totalStaff).toBe(30);
    });

    it('should compute attendanceRate correctly', async () => {
      (timeStatsApiClient.getDailyStats as jest.Mock).mockResolvedValue({
        data: { employeeCount: 15, records: [] },
      });
      (whitelistApiClient.getWhitelistStats as jest.Mock).mockResolvedValue({ pending: 0, disabled: 0 });
      (userApiClient.getUsers as jest.Mock).mockResolvedValue({
        totalElements: 20,
        content: [],
      });
      mock.onGet(`${BASE}/users/join-date-range`).reply(200, {
        success: true,
        data: { content: [], totalElements: 0, totalPages: 0, size: 1, number: 0 },
        message: 'ok',
      });

      const data = await hrApiClient.getDashboardData();

      expect(data.attendanceRate).toBe(75); // 15/20 * 100
    });

    it('should compute newHiresChange as this minus last', async () => {
      (timeStatsApiClient.getDailyStats as jest.Mock).mockResolvedValue({ data: { employeeCount: 0, records: [] } });
      (whitelistApiClient.getWhitelistStats as jest.Mock).mockResolvedValue({ pending: 0, disabled: 0 });
      (userApiClient.getUsers as jest.Mock).mockResolvedValue({ totalElements: 0, content: [] });

      // First call = thisMonth (5), second = lastMonth (3)
      let callCount = 0;
      mock.onGet(`${BASE}/users/join-date-range`).reply(() => {
        callCount++;
        const total = callCount === 1 ? 5 : 3;
        return [200, {
          success: true,
          data: { content: [], totalElements: total, totalPages: 1, size: 1, number: 0 },
          message: 'ok',
        }];
      });

      const data = await hrApiClient.getDashboardData();

      expect(data.thisMonthNewHires).toBe(5);
      expect(data.newHiresChange).toBe(2); // 5 - 3
    });
  });

  describe('employee AI compatibility methods', () => {
    it('preserves null insight and score instead of fabricating zero-score content', async () => {
      (employeeAIApiClient.analyzeEmployee as jest.Mock).mockResolvedValue({
        employeeId: 17,
        analyzedAt: '2026-07-19T10:00:00',
        aiInsight: null,
        overallScore: null,
        suggestions: [],
      });

      const result = await hrApiClient.getEmployeeAIAnalysis(17, DEFAULT_FACTORY_ID);

      expect(employeeAIApiClient.analyzeEmployee).toHaveBeenCalledTimes(1);
      expect(employeeAIApiClient.analyzeEmployee).toHaveBeenCalledWith(
        17,
        { days: 90 },
        DEFAULT_FACTORY_ID
      );
      expect(result?.summary).toBeNull();
      expect(result?.performanceScore).toBeNull();
      expect(result?.strengths).toEqual([]);
      expect(result?.improvements).toEqual([]);
    });

    it('requests one analysis and returns that exact result without a local analysis id', async () => {
      const analysis = {
        employeeId: 17,
        analyzedAt: '2026-07-19T10:00:00',
        aiInsight: '仅基于事实的洞察',
        overallScore: null,
        suggestions: [],
      };
      (employeeAIApiClient.analyzeEmployee as jest.Mock).mockResolvedValue(analysis);

      const result = await hrApiClient.requestEmployeeAIAnalysis(17, DEFAULT_FACTORY_ID);

      expect(employeeAIApiClient.analyzeEmployee).toHaveBeenCalledTimes(1);
      expect(result).toEqual({
        success: true,
        message: '分析请求已完成',
        analysis,
      });
      expect(result).not.toHaveProperty('analysisId');
    });
  });
});
