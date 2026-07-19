import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 员工AI分析API客户端
 *
 * 对应后端 AIController 的员工分析相关端点
 * 基础路径: /api/mobile/{factoryId}/ai/analysis/employee
 *
 * @version 1.0.0
 * @since 2025-12-27
 */

// ============ 类型定义 ============

/**
 * 员工AI分析请求
 */
export interface EmployeeAnalysisRequest {
  /** 分析天数（默认30天） */
  days?: number;
  /** 自定义问题（用于追问） */
  question?: string;
  /** 会话ID（用于追问） */
  sessionId?: string;
  /** 是否启用思考模式 */
  enableThinking?: boolean;
  /** 思考预算（10-100） */
  thinkingBudget?: number;
}

/**
 * 追问请求
 */
export interface EmployeeFollowupRequest {
  /** 会话ID（必需） */
  sessionId: string;
  /** 追问问题（必需） */
  question: string;
}

/**
 * 考勤分析
 */
export interface AttendanceAnalysis {
  /** 评分(0-100) */
  score: number | null;
  /** 出勤率(%) */
  attendanceRate: number | null;
  /** 考勤记录数 */
  recordCount: number;
  /** 非ABSENT状态考勤记录数 */
  attendanceDays: number;
  /** 持久化LATE状态记录数 */
  lateCount: number;
  /** 持久化EARLY_LEAVE状态记录数 */
  earlyLeaveCount: number;
  /** 持久化ABSENT状态记录数 */
  absentDays: number;
  /** 打卡记录工作分钟数合计 */
  clockedWorkMinutes: number;
  /** 部门平均出勤率(%) */
  departmentAvgRate: number | null;
  /** AI洞察 */
  insight: string | null;
  /** 洞察类型 */
  insightType: 'positive' | 'warning' | 'neutral' | null;
}

/**
 * 工时效率分析
 */
export interface WorkHoursAnalysis {
  /** 评分(0-100) */
  score: number | null;
  /** 工作会话实际分钟数合计 */
  totalMinutes: number;
  /** 工作会话数 */
  sessionCount: number;
  /** 日均工时(小时) */
  avgDailyHours: number | null;
  /** 本月加班时长(小时) */
  overtimeHours: number | null;
  /** 工时效率(%) */
  efficiency: number | null;
  /** 参与工作类型数 */
  workTypeCount: number | null;
  /** 部门平均日工时 */
  departmentAvgHours: number | null;
  /** AI洞察 */
  insight: string | null;
  /** 洞察类型 */
  insightType: 'positive' | 'warning' | 'neutral' | null;
}

/**
 * 生产贡献分析
 */
export interface ProductionAnalysis {
  /** 评分(0-100) */
  score: number | null;
  /** 参与批次数 */
  batchCount: number;
  /** 完成批次数 */
  completedBatches: number;
  /** 批次工作分钟数合计 */
  batchWorkMinutes: number;
  /** 质检记录数 */
  totalInspections: number;
  /** 质检通过记录数 */
  passedInspections: number;
  /** 产量贡献(kg) */
  outputQuantity: number | null;
  /** 质检通过率(%，无质检记录时不可计算) */
  qualityRate: number | null;
  /** 人均产能(kg/h) */
  productivityRate: number | null;
  /** 部门平均产能 */
  departmentAvgProductivity: number | null;
  /** 擅长产品线 */
  topProductLine: string | null;
  /** AI洞察 */
  insight: string | null;
  /** 洞察类型 */
  insightType: 'positive' | 'warning' | 'neutral' | null;
}

/**
 * 技能分布
 */
export interface SkillDistribution {
  /** 技能/工序名称 */
  skillName: string;
  /** 参与占比(%) */
  percentage: number;
  /** 熟练程度 */
  proficiency: '精通' | '熟练' | '学习中' | '新手';
  /** 工时(小时) */
  hours: number;
}

/**
 * 员工建议
 */
export interface EmployeeSuggestion {
  /** 建议类型 */
  type: '优势' | '建议' | '关注';
  /** 标题 */
  title: string;
  /** 详细描述 */
  description: string;
  /** 优先级 */
  priority: 'high' | 'medium' | 'low';
}

/**
 * 绩效趋势
 */
export interface PerformanceTrend {
  /** 月份(yyyy-MM) */
  month: string;
  /** 评分 */
  score: number;
  /** 等级 */
  grade: string;
}

/**
 * 员工AI综合分析响应
 */
export interface EmployeeAnalysisResponse {
  /** 员工ID */
  employeeId: number;
  /** 员工姓名 */
  employeeName: string;
  /** 部门 */
  department: string | null;
  /** 职位 */
  position: string | null;
  /** 入职时长(月) */
  tenureMonths: number | null;
  /** 分析周期开始 */
  periodStart: string;
  /** 分析周期结束 */
  periodEnd: string;
  /** 数据点数量 */
  dataPoints: number;
  /** 综合评分(0-100) */
  overallScore: number | null;
  /** 综合等级 */
  overallGrade: 'A' | 'B' | 'C' | 'D' | null;
  /** 环比变化百分比 */
  scoreChange: number | null;
  /** 部门排名百分比(Top N%) */
  departmentRankPercent: number | null;
  /** 考勤表现分析 */
  attendance: AttendanceAnalysis;
  /** 工时效率分析 */
  workHours: WorkHoursAnalysis;
  /** 生产贡献分析 */
  production: ProductionAnalysis;
  /** 技能分布 */
  skills: SkillDistribution[];
  /** AI综合建议 */
  suggestions: EmployeeSuggestion[];
  /** 绩效趋势(近6个月) */
  trends: PerformanceTrend[];
  /** AI深度洞察 */
  aiInsight: string;
  /** 会话ID(用于追问) */
  sessionId: string | null;
  /** 分析时间 */
  analyzedAt: string;
  /** 消耗Token数 */
  tokensUsed: number | null;
  /** 因缺少可信数据或配置而不可计算的字段路径 */
  notComputableMetrics: string[];
}

/**
 * 通用API响应包装格式
 */
interface ApiResponseWrapper<T> {
  code: number;
  message: string;
  data: T;
  timestamp?: string;
}

// ============ API客户端类 ============

/**
 * 员工AI分析API客户端
 */
class EmployeeAIApiClient {
  /**
   * 获取基础路径
   */
  private getBasePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/ai`;
  }

  /**
   * 员工AI综合分析
   *
   * 对指定员工进行多维度AI分析，包括：
   * - 考勤表现分析
   * - 工时效率分析
   * - 生产贡献分析
   * - 技能分布
   * - AI综合建议
   *
   * @param employeeId 员工ID
   * @param request 分析请求参数
   * @param factoryId 工厂ID（可选）
   */
  async analyzeEmployee(
    employeeId: number,
    request?: EmployeeAnalysisRequest,
    factoryId?: string
  ): Promise<EmployeeAnalysisResponse> {
    const response = await apiClient.post<ApiResponseWrapper<EmployeeAnalysisResponse>>(
      `${this.getBasePath(factoryId)}/analysis/employee/${employeeId}`,
      request ?? {}
    );
    return response.data;
  }

  /**
   * 员工AI分析追问
   *
   * 基于之前的分析结果继续追问
   *
   * @param employeeId 员工ID
   * @param request 追问请求
   * @param factoryId 工厂ID（可选）
   */
  async followupAnalysis(
    employeeId: number,
    request: EmployeeFollowupRequest,
    factoryId?: string
  ): Promise<EmployeeAnalysisResponse> {
    const response = await apiClient.post<ApiResponseWrapper<EmployeeAnalysisResponse>>(
      `${this.getBasePath(factoryId)}/analysis/employee/${employeeId}/followup`,
      request
    );
    return response.data;
  }

  /**
   * 批量员工分析概览
   *
   * 获取多个员工的基础分析数据（用于列表展示）
   *
   * @param employeeIds 员工ID列表
   * @param days 分析天数（默认30）
   * @param factoryId 工厂ID（可选）
   */
  async batchAnalyzeEmployees(
    employeeIds: number[],
    days: number = 30,
    factoryId?: string
  ): Promise<Array<{
    employeeId: number;
    employeeName: string;
    overallScore: number | null;
    overallGrade: string | null;
    scoreChange: number | null;
  }>> {
    // 并行请求每个员工的简要分析
    const promises = employeeIds.map(async (id) => {
      try {
        const result = await this.analyzeEmployee(id, { days }, factoryId);
        return {
          employeeId: result.employeeId,
          employeeName: result.employeeName,
          overallScore: result.overallScore,
          overallGrade: result.overallGrade,
          scoreChange: result.scoreChange,
        };
      } catch (error) {
        console.error(`分析员工 ${id} 失败:`, error);
        return null;
      }
    });

    const results = await Promise.all(promises);
    return results.filter((r): r is NonNullable<typeof r> => r !== null);
  }
}

// ============ 导出 ============

/**
 * 员工AI分析API客户端单例
 */
export const employeeAIApiClient = new EmployeeAIApiClient();

/**
 * 默认导出
 */
export default employeeAIApiClient;
