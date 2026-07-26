import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import { timeStatsApiClient } from './timeStatsApiClient';
import { whitelistApiClient } from './whitelistApiClient';
import { userApiClient, UserDTO, PageResponse } from './userApiClient';
import {
  employeeAIApiClient,
  type EmployeeAnalysisResponse,
} from './employeeAIApiClient';

/**
 * HR管理API客户端
 * 聚合多个API为HR Dashboard提供数据
 *
 * 核心功能：
 * 1. 获取今日考勤统计 (复用 timeStatsApiClient)
 * 2. 获取白名单待激活数量 (复用 whitelistApiClient)
 * 3. 获取本月入职人数 (新API: /join-date-range)
 * 4. 获取考勤异常列表 (复用 timeclockApiClient)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-27
 */

// ========== 类型定义 ==========

/**
 * HR Dashboard 统计数据
 */
export interface HRDashboardData {
  /** 今日在岗人数 */
  todayOnSite: number;
  /** 总员工数 */
  totalStaff: number;
  /** 出勤率 (百分比) */
  attendanceRate: number;
  /** 迟到人数 */
  lateCount: number;
  /** 白名单待激活数量 */
  whitelistPending: number;
  /** 本月入职人数 */
  thisMonthNewHires: number;
  /** 与上月入职人数对比 (+/- 数字) */
  newHiresChange?: number;
}

/**
 * 考勤异常记录
 */
export interface AttendanceAnomaly {
  id: number;
  userId: number;
  userName: string;
  department?: string;
  position?: string;
  anomalyType: 'LATE' | 'ABSENT' | 'EARLY_LEAVE' | 'NO_CLOCK_IN' | 'NO_CLOCK_OUT';
  anomalyTypeDisplay: string;
  details?: string;
  date: string;
  expectedTime?: string;
  actualTime?: string;
  /** 异常发生时间 */
  anomalyTime: string;
  /** 是否已处理 */
  isResolved: boolean;
  /** 处理时间 */
  resolvedAt?: string;
  /** 处理人 */
  resolvedBy?: string;
  /** 处理备注 */
  resolutionNotes?: string;
}

/**
 * 今日考勤统计
 */
export interface TodayAttendanceStats {
  /** 总人数 */
  totalEmployees: number;
  /** 已打卡人数 */
  clockedIn: number;
  /** 迟到人数 */
  lateCount: number;
  /** 未打卡人数 */
  notClockedIn: number;
  /** 出勤率 */
  attendanceRate: number;
}

/**
 * 入职日期范围查询参数
 */
export interface JoinDateRangeParams {
  startDate: string; // yyyy-MM-dd
  endDate: string;   // yyyy-MM-dd
  page?: number;
  size?: number;
  factoryId?: string;
}

// ========== API客户端类 ==========

class HRApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}`;
  }

  /**
   * 格式化日期为 yyyy-MM-dd 字符串
   */
  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  /**
   * 获取本月第一天的日期字符串
   */
  private getFirstDayOfMonth(): string {
    const now = new Date();
    const firstDay = new Date(now.getFullYear(), now.getMonth(), 1);
    return this.formatDate(firstDay);
  }

  /**
   * 获取今天的日期字符串
   */
  private getToday(): string {
    return this.formatDate(new Date());
  }

  /**
   * 获取上月第一天和最后一天
   */
  private getLastMonthRange(): { startDate: string; endDate: string } {
    const now = new Date();
    const firstDayLastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const lastDayLastMonth = new Date(now.getFullYear(), now.getMonth(), 0);
    return {
      startDate: this.formatDate(firstDayLastMonth),
      endDate: this.formatDate(lastDayLastMonth),
    };
  }

  /**
   * 1. 获取指定日期范围内入职的用户列表
   * GET /api/{factoryId}/users/join-date-range
   *
   * @param params 查询参数
   */
  async getUsersByJoinDateRange(params: JoinDateRangeParams): Promise<PageResponse<UserDTO>> {
    const { factoryId, ...queryParams } = params;
    const response = await apiClient.get<{
      code: number;
      data: PageResponse<UserDTO>;
      message: string;
      success: boolean;
    }>(
      `${this.getPath(factoryId)}/users/join-date-range`,
      { params: queryParams }
    );
    return response.data || { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };
  }

  /**
   * 2. 获取本月入职人数
   */
  async getThisMonthNewHires(factoryId?: string): Promise<number> {
    try {
      const response = await this.getUsersByJoinDateRange({
        startDate: this.getFirstDayOfMonth(),
        endDate: this.getToday(),
        page: 1,
        size: 1, // 只需要 totalElements
        factoryId,
      });
      return response.totalElements;
    } catch (error) {
      console.error('[hrApiClient] 获取本月入职人数失败:', error);
      return 0;
    }
  }

  /**
   * 3. 获取上月入职人数 (用于计算环比)
   */
  async getLastMonthNewHires(factoryId?: string): Promise<number> {
    try {
      const { startDate, endDate } = this.getLastMonthRange();
      const response = await this.getUsersByJoinDateRange({
        startDate,
        endDate,
        page: 1,
        size: 1,
        factoryId,
      });
      return response.totalElements;
    } catch (error) {
      console.error('[hrApiClient] 获取上月入职人数失败:', error);
      return 0;
    }
  }

  /**
   * 4. 获取HR Dashboard完整数据 (并行查询)
   *
   * 聚合以下数据:
   * - 今日考勤统计 (timeStatsApiClient)
   * - 白名单待激活数 (whitelistApiClient)
   * - 本月入职人数 (新API)
   * - 总员工数 (userApiClient)
   */
  async getDashboardData(factoryId?: string): Promise<HRDashboardData> {
    try {
      // 并行查询所有数据
      const [
        dailyStatsResult,
        whitelistStatsResult,
        thisMonthHiresResult,
        lastMonthHiresResult,
        usersResult,
      ] = await Promise.allSettled([
        timeStatsApiClient.getDailyStats(this.getToday(), factoryId),
        whitelistApiClient.getWhitelistStats(factoryId),
        this.getThisMonthNewHires(factoryId),
        this.getLastMonthNewHires(factoryId),
        userApiClient.getUsers({ factoryId, page: 1, size: 1 }), // 只需要 totalElements
      ]);

      // 解析今日考勤统计
      let todayOnSite = 0;
      let lateCount = 0;
      if (dailyStatsResult.status === 'fulfilled' && dailyStatsResult.value?.data) {
        const dailyData = dailyStatsResult.value.data;
        todayOnSite = dailyData.employeeCount || 0;
        // 从records中筛选迟到记录 (如果有isLate或lateMinutes字段)
        if (dailyData.records && Array.isArray(dailyData.records)) {
          lateCount = dailyData.records.filter((r) => {
            const record = r as unknown as Record<string, unknown>;
            return record.isLate === true || (typeof record.lateMinutes === 'number' && record.lateMinutes > 0);
          }).length;
        }
      }

      // 解析白名单统计
      let whitelistPending = 0;
      if (whitelistStatsResult.status === 'fulfilled') {
        const stats = whitelistStatsResult.value;
        // 待激活 = pending + disabled 状态
        whitelistPending = (stats.pending || 0) + (stats.disabled || 0);
      }

      // 解析入职人数
      const thisMonthNewHires = thisMonthHiresResult.status === 'fulfilled'
        ? thisMonthHiresResult.value : 0;
      const lastMonthNewHires = lastMonthHiresResult.status === 'fulfilled'
        ? lastMonthHiresResult.value : 0;
      const newHiresChange = thisMonthNewHires - lastMonthNewHires;

      // 解析总员工数
      let totalStaff = 0;
      if (usersResult.status === 'fulfilled') {
        totalStaff = usersResult.value.totalElements || 0;
      }

      // 计算出勤率
      const attendanceRate = totalStaff > 0
        ? Math.round((todayOnSite / totalStaff) * 100)
        : 0;

      return {
        todayOnSite,
        totalStaff,
        attendanceRate,
        lateCount,
        whitelistPending,
        thisMonthNewHires,
        newHiresChange,
      };
    } catch (error) {
      console.error('[hrApiClient] 获取Dashboard数据失败:', error);
      // 返回默认值，不阻塞页面渲染
      return {
        todayOnSite: 0,
        totalStaff: 0,
        attendanceRate: 0,
        lateCount: 0,
        whitelistPending: 0,
        thisMonthNewHires: 0,
        newHiresChange: 0,
      };
    }
  }

  /**
   * 5. 获取今日考勤异常列表
   *
   * 从打卡记录中筛选出异常情况
   */
  async getTodayAnomalies(factoryId?: string): Promise<AttendanceAnomaly[]> {
    try {
      const today = this.getToday();
      const response = await timeStatsApiClient.getDailyStats(today, factoryId);

      if (!response?.data?.records) {
        return [];
      }

      // 将迟到记录转换为异常格式
      const anomalies: AttendanceAnomaly[] = [];

      for (const record of response.data.records) {
        // 检查是否迟到 (假设有 isLate 或 lateMinutes 字段)
        if ((record as { isLate?: boolean }).isLate || (record as { lateMinutes?: number }).lateMinutes) {
          const lateMinutes = (record as { lateMinutes?: number }).lateMinutes || 0;
          anomalies.push({
            id: record.id,
            userId: record.employeeId,
            userName: record.employeeName || '未知',
            department: record.department,
            position: undefined,
            anomalyType: 'LATE',
            anomalyTypeDisplay: `迟到 ${lateMinutes}分钟`,
            date: today,
            expectedTime: (record as { expectedStartTime?: string }).expectedStartTime,
            actualTime: record.startTime,
            anomalyTime: record.startTime || today,
            isResolved: false,
          });
        }
      }

      return anomalies;
    } catch (error) {
      console.error('[hrApiClient] 获取今日考勤异常失败:', error);
      return [];
    }
  }

  /**
   * 6. 获取待激活白名单列表 (用于待处理事项)
   */
  async getPendingWhitelist(params?: {
    page?: number;
    size?: number;
    factoryId?: string;
  }): Promise<PageResponse<{
    id: number;
    phoneNumber: string;
    realName: string;
    role: string;
    department?: string;
    createdAt: string;
  }>> {
    const { factoryId, ...queryParams } = params || {};
    const response = await whitelistApiClient.getWhitelist({
      factoryId,
      status: 'PENDING',
      ...queryParams,
    });
    return {
      ...response,
      content: response.content.map(item => ({
        id: item.id,
        phoneNumber: item.phoneNumber,
        realName: item.name || item.realName || '',
        role: item.role,
        department: item.department,
        createdAt: item.createdAt,
      })),
    };
  }

  /**
   * 7. 获取考勤异常列表 (按日期范围)
   * GET /api/mobile/{factoryId}/time-stats/anomaly
   * @param params 查询参数
   *
   * 注意: 当前后端 /time-stats/anomaly API 仅返回统计数据 (lateCount, earlyLeaveCount 等)，
   * 不返回具体的异常记录详情。因此直接使用 getTodayAnomalies 从打卡记录中提取异常。
   * 未来后端提供详细异常记录 API 后可以直接返回。
   */
  async getAttendanceAnomalies(params?: {
    startDate?: string;
    endDate?: string;
    status?: string;
    page?: number;
    size?: number;
    factoryId?: string;
  }): Promise<AttendanceAnomaly[]> {
    try {
      // 当前后端仅返回异常统计，不返回具体记录
      // 直接从打卡记录中提取异常详情
      return await this.getTodayAnomalies(params?.factoryId);
    } catch (error) {
      console.error('[hrApiClient] 获取考勤异常列表失败:', error);
      return [];
    }
  }

  /**
   * 8. 处理考勤异常
   * PUT /api/mobile/{factoryId}/timeclock/records/{recordId}
   * @param anomalyId 异常记录ID (对应打卡记录ID)
   * @param resolution 处理结果
   */
  async resolveAnomaly(
    anomalyId: number | string,
    resolution: { action: string; notes?: string },
    factoryId?: string
  ): Promise<{ success: boolean }> {
    try {
      const response = await apiClient.put<{
        code: number;
        data: unknown;
        message: string;
        success: boolean;
      }>(`${this.getPath(factoryId)}/timeclock/records/${anomalyId}`, {
        status: resolution.action === 'approve' ? 'RESOLVED' : 'REJECTED',
        notes: resolution.notes || '',
        resolvedAt: new Date().toISOString(),
      });

      return { success: response.success };
    } catch (error) {
      console.error('[hrApiClient] 处理考勤异常失败:', error);
      return { success: false };
    }
  }

  /**
   * 9. 获取绩效统计
   * GET /api/mobile/{factoryId}/time-stats/productivity
   *
   * 从后端获取生产力指数，并从员工绩效列表计算等级分布
   */
  async getPerformanceStats(params?: {
    period?: string;
    departmentId?: string;
    factoryId?: string;
  }): Promise<{
    avgScore: number;
    excellentCount: number;
    needAttentionCount: number;
    needImprovementCount: number;
    gradeDistribution: { grade: string; count: number; percentage: number }[];
  }> {
    try {
      // 计算日期范围
      const today = new Date();
      let startDate: string;
      let endDate: string = this.formatDate(today);

      switch (params?.period) {
        case 'week':
          const weekStart = new Date(today);
          weekStart.setDate(today.getDate() - 7);
          startDate = this.formatDate(weekStart);
          break;
        case 'year':
          startDate = `${today.getFullYear()}-01-01`;
          break;
        case 'month':
        default:
          startDate = this.getFirstDayOfMonth();
          break;
      }

      // 并行获取生产力数据和员工列表
      const [productivityResponse, employeeListResult] = await Promise.allSettled([
        apiClient.get<{
          code: number;
          data: {
            efficiencyIndex?: number;
            totalOutput?: number;
            totalInputHours?: number;
            outputPerWorker?: number;
            outputPerHour?: number;
            growthRate?: number;
            improvements?: string[];
          };
          message: string;
          success: boolean;
        }>(`${this.getPath(params?.factoryId)}/time-stats/productivity`, {
          params: { startDate, endDate },
        }),
        this.getEmployeePerformanceList({ factoryId: params?.factoryId, size: 100 }),
      ]);

      // 解析生产力数据
      let avgScore = 0;
      if (productivityResponse.status === 'fulfilled' &&
          productivityResponse.value?.success &&
          productivityResponse.value?.data) {
        const data = productivityResponse.value.data;
        // 将效率指数转换为分数 (0-100)
        avgScore = Math.round((data.efficiencyIndex || 0) * 100);
      }

      // 从员工绩效列表计算等级分布
      let excellentCount = 0;
      let goodCount = 0;
      let needAttentionCount = 0;
      let needImprovementCount = 0;

      if (employeeListResult.status === 'fulfilled' && employeeListResult.value?.content) {
        const employees = employeeListResult.value.content;
        for (const emp of employees) {
          switch (emp.grade) {
            case 'A':
              excellentCount++;
              break;
            case 'B':
              goodCount++;
              break;
            case 'C':
              needAttentionCount++;
              break;
            case 'D':
              needImprovementCount++;
              break;
          }
        }
      }

      const total = excellentCount + goodCount + needAttentionCount + needImprovementCount;

      return {
        avgScore,
        excellentCount,
        needAttentionCount,
        needImprovementCount,
        gradeDistribution: total > 0 ? [
          { grade: 'A', count: excellentCount, percentage: Math.round((excellentCount / total) * 100) },
          { grade: 'B', count: goodCount, percentage: Math.round((goodCount / total) * 100) },
          { grade: 'C', count: needAttentionCount, percentage: Math.round((needAttentionCount / total) * 100) },
          { grade: 'D', count: needImprovementCount, percentage: Math.round((needImprovementCount / total) * 100) },
        ] : [],
      };
    } catch (error) {
      console.error('[hrApiClient] 获取绩效统计失败:', error);
      // 返回默认值
      return {
        avgScore: 0,
        excellentCount: 0,
        needAttentionCount: 0,
        needImprovementCount: 0,
        gradeDistribution: [],
      };
    }
  }

  /**
   * 10. 获取员工绩效列表
   * GET /api/mobile/{factoryId}/time-stats/workers
   */
  async getEmployeePerformanceList(params?: {
    page?: number;
    size?: number;
    grade?: string;
    factoryId?: string;
  }): Promise<{
    content: {
      userId: number;
      userName: string;
      department?: string;
      position?: string;
      score: number;
      grade: string;
    }[];
    totalElements: number;
    totalPages: number;
  }> {
    try {
      // 获取本月数据
      const startDate = this.getFirstDayOfMonth();
      const endDate = this.getToday();
      const topN = params?.size || 50;

      const response = await apiClient.get<{
        code: number;
        data: Array<{
          workerId?: number;
          employeeId?: number;
          workerName?: string;
          employeeName?: string;
          department?: string;
          efficiency?: number;
          attendanceRate?: number;
          totalHours?: number;
          ranking?: number;
        }>;
        message: string;
        success: boolean;
      }>(`${this.getPath(params?.factoryId)}/time-stats/workers`, {
        params: { startDate, endDate, topN },
      });

      if (!response.success || !response.data) {
        throw new Error(response.message || '获取员工绩效列表失败');
      }

      // 将后端数据转换为前端格式
      const content = response.data.map((worker) => {
        // 计算综合评分 (效率 * 0.6 + 出勤率 * 0.4)
        const efficiency = worker.efficiency || 0;
        const attendanceRate = worker.attendanceRate || 0;
        const score = Math.round(efficiency * 0.6 + attendanceRate * 0.4);

        // 根据分数确定等级
        let grade: string;
        if (score >= 90) grade = 'A';
        else if (score >= 75) grade = 'B';
        else if (score >= 60) grade = 'C';
        else grade = 'D';

        return {
          userId: worker.workerId || worker.employeeId || 0,
          userName: worker.workerName || worker.employeeName || '未知',
          department: worker.department,
          position: undefined,
          score,
          grade,
        };
      });

      // 如果指定了等级筛选
      const filteredContent = params?.grade
        ? content.filter((item) => item.grade === params.grade)
        : content;

      // 分页处理
      const page = params?.page || 1;
      const size = params?.size || 20;
      const startIndex = (page - 1) * size;
      const pagedContent = filteredContent.slice(startIndex, startIndex + size);

      return {
        content: pagedContent,
        totalElements: filteredContent.length,
        totalPages: Math.ceil(filteredContent.length / size),
      };
    } catch (error) {
      console.error('[hrApiClient] 获取员工绩效列表失败:', error);
      return {
        content: [],
        totalElements: 0,
        totalPages: 0,
      };
    }
  }

  /**
   * 11. 获取员工AI分析结果
   * POST /api/mobile/{factoryId}/ai/analysis/employee/{employeeId}
   */
  async getEmployeeAIAnalysis(
    userId: number,
    factoryId?: string
  ): Promise<{
    userId: number;
    analysisType: string;
    lastUpdated: string;
    summary: string | null;
    strengths: string[];
    improvements: string[];
    recommendations: string[];
    performanceScore: number | null;
  } | null> {
    try {
      const data = await employeeAIApiClient.analyzeEmployee(userId, { days: 90 }, factoryId);
      const strengths: string[] = [];
      const improvements: string[] = [];
      const recommendations: string[] = [];

      for (const suggestion of data.suggestions) {
          if (suggestion.type === '优势') {
            strengths.push(suggestion.description);
          } else if (suggestion.type === '关注') {
            improvements.push(suggestion.description);
          } else {
            recommendations.push(suggestion.description);
          }
      }

      return {
        userId: data.employeeId,
        analysisType: 'COMPREHENSIVE',
        lastUpdated: data.analyzedAt,
        summary: data.aiInsight,
        strengths,
        improvements,
        recommendations,
        performanceScore: data.overallScore,
      };
    } catch (error) {
      console.error('[hrApiClient] 获取员工AI分析失败:', error);
      return null;
    }
  }

  /**
   * 12. 请求生成员工AI分析
   * POST /api/mobile/{factoryId}/ai/analysis/employee/{employeeId}
   */
  async requestEmployeeAIAnalysis(
    userId: number,
    factoryId?: string
  ): Promise<{
    success: boolean;
    message: string;
    analysis?: EmployeeAnalysisResponse;
  }> {
    try {
      const analysis = await employeeAIApiClient.analyzeEmployee(userId, { days: 90 }, factoryId);

      return {
        success: true,
        message: '分析请求已完成',
        analysis,
      };
    } catch (error) {
      console.error('[hrApiClient] 请求员工AI分析失败:', error);
      return {
        success: false,
        message: error instanceof Error ? error.message : 'AI分析请求失败',
      };
    }
  }
}

export const hrApiClient = new HRApiClient();
export default hrApiClient;
