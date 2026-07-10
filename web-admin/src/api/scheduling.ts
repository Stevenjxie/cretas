/**
 * 调度模块 API
 * 提供调度计划、排程、工人分配、告警等功能的API接口
 */
import { get, post, put, del } from './request';
import type { ApiResponse } from '@/types/api';

// ==================== 类型定义 ====================

export interface SchedulingPlan {
  id: string;
  factoryId: string;
  planDate: string;
  status: 'draft' | 'confirmed' | 'in_progress' | 'completed' | 'cancelled';
  totalBatches: number;
  totalWorkers: number;
  averageCompletionProbability: number;
  lineSchedules: LineSchedule[];
  createdBy: number;
  createdAt: string;
  confirmedBy?: number;
  confirmedAt?: string;
}

export interface LineSchedule {
  id: string;
  planId: string;
  productionLineId: string;
  productionLineName: string;
  batchId: number;
  batchNumber: string;
  sequenceOrder: number;
  plannedStartTime: string;
  plannedEndTime: string;
  actualStartTime?: string;
  actualEndTime?: string;
  assignedWorkerCount: number;
  completedQuantity: number;
  targetQuantity: number;
  predictedEfficiency: number;
  predictedCompletionProb: number;
  status: 'pending' | 'in_progress' | 'completed' | 'delayed';
  workerAssignments: WorkerAssignment[];
  completionRate: number;
  isDelayed: boolean;
}

export interface WorkerAssignment {
  id: string;
  scheduleId: string;
  userId: number;
  userName: string;
  role: string;
  skillLevel: number;
  isTemporary: boolean;
  assignedAt: string;
  checkedInAt?: string;
  checkedOutAt?: string;
  workedHours?: number;
  performanceScore?: number;
  laborCost?: number;
  status: 'assigned' | 'checked_in' | 'checked_out';
}

export interface ProductionLine {
  id: string;
  factoryId: string;
  departmentId?: number;
  name: string;
  lineType: string;
  minWorkers: number;
  maxWorkers: number;
  requiredSkillLevel: number;
  hourlyCapacity: number;
  status: 'active' | 'maintenance' | 'inactive';
  createdAt: string;
}

export interface SchedulingAlert {
  id: string;
  factoryId: string;
  scheduleId?: string;
  planId?: string;
  alertType: 'low_probability' | 'resource_conflict' | 'deadline_risk' | 'efficiency_drop';
  severity: 'info' | 'warning' | 'critical';
  message: string;
  suggestedAction?: string;
  isResolved: boolean;
  acknowledgedBy?: number;
  acknowledgedAt?: string;
  resolvedBy?: number;
  resolvedAt?: string;
  resolutionNotes?: string;
  createdAt: string;
}

export interface CompletionProbability {
  scheduleId: string;
  probability: number;
  meanHours: number;
  stdHours: number;
  percentile90: number;
  confidenceLower: number;
  confidenceUpper: number;
  riskLevel: 'low' | 'medium' | 'high' | 'critical';
  suggestedActions: string[];
}

export interface SchedulingDashboard {
  planStats: {
    totalPlans: number;
    draftPlans: number;
    confirmedPlans: number;
    inProgressPlans: number;
    completedPlans: number;
  };
  workerStats: {
    totalAssigned: number;
    checkedIn: number;
    temporaryWorkers: number;
    averagePerformance: number;
  };
  lineStats: {
    activeLines: number;
    utilizationRate: number;
    averageEfficiency: number;
  };
  alerts: SchedulingAlert[];
  todaySchedules: LineSchedule[];
}

function ok<T>(data: T, message = 'OK'): Promise<ApiResponse<T>> {
  return Promise.resolve({ success: true, data, message });
}

export function isDemoSchedulingFactory(factoryId?: string): boolean {
  return /^DEMO_/.test(factoryId || '') || factoryId === 'F001';
}

function todayDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function atTime(hour: number, minute = 0): string {
  const date = new Date();
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
}

function demoAssignments(scheduleId: string): WorkerAssignment[] {
  return [
    {
      id: `${scheduleId}-w1`,
      scheduleId,
      userId: 3101,
      userName: '赵明',
      role: '卤制主操',
      skillLevel: 5,
      isTemporary: false,
      assignedAt: atTime(8, 10),
      checkedInAt: atTime(8, 20),
      workedHours: 3.5,
      performanceScore: 92,
      laborCost: 210,
      status: 'checked_in',
    },
    {
      id: `${scheduleId}-w2`,
      scheduleId,
      userId: 3102,
      userName: '李蓉',
      role: '包装线长',
      skillLevel: 4,
      isTemporary: false,
      assignedAt: atTime(8, 12),
      checkedInAt: atTime(8, 25),
      workedHours: 3.2,
      performanceScore: 88,
      laborCost: 180,
      status: 'checked_in',
    },
  ];
}

function demoSchedules(planId: string): LineSchedule[] {
  const scheduleA = `${planId}-s1`;
  const scheduleB = `${planId}-s2`;
  return [
    {
      id: scheduleA,
      planId,
      productionLineId: 'line-luzhi-01',
      productionLineName: '一号卤制线',
      batchId: 240701,
      batchNumber: 'B-2407-卤味礼盒',
      sequenceOrder: 1,
      plannedStartTime: atTime(9, 0),
      plannedEndTime: atTime(13, 30),
      actualStartTime: atTime(9, 8),
      assignedWorkerCount: 8,
      completedQuantity: 3200,
      targetQuantity: 5200,
      predictedEfficiency: 0.94,
      predictedCompletionProb: 0.91,
      status: 'in_progress',
      workerAssignments: demoAssignments(scheduleA),
      completionRate: 62,
      isDelayed: false,
    },
    {
      id: scheduleB,
      planId,
      productionLineId: 'line-pack-02',
      productionLineName: '包装线 B',
      batchId: 240702,
      batchNumber: 'B-2407-休闲小包',
      sequenceOrder: 2,
      plannedStartTime: atTime(13, 40),
      plannedEndTime: atTime(17, 30),
      assignedWorkerCount: 6,
      completedQuantity: 0,
      targetQuantity: 4600,
      predictedEfficiency: 0.87,
      predictedCompletionProb: 0.78,
      status: 'pending',
      workerAssignments: [
        {
          id: `${scheduleB}-w1`,
          scheduleId: scheduleB,
          userId: 3103,
          userName: '王磊',
          role: '包装操作',
          skillLevel: 4,
          isTemporary: false,
          assignedAt: atTime(8, 18),
          status: 'assigned',
        },
        {
          id: `${scheduleB}-w2`,
          scheduleId: scheduleB,
          userId: 4101,
          userName: '顺达人力 / 陈师傅',
          role: '临时包装',
          skillLevel: 3,
          isTemporary: true,
          assignedAt: atTime(8, 24),
          status: 'assigned',
        },
      ],
      completionRate: 0,
      isDelayed: false,
    },
  ];
}

function demoPlans(factoryId: string): SchedulingPlan[] {
  const planDate = todayDate();
  const plans: SchedulingPlan[] = [
    {
      id: 'demo-plan-main',
      factoryId,
      planDate,
      status: 'confirmed',
      totalBatches: 2,
      totalWorkers: 14,
      averageCompletionProbability: 0.85,
      lineSchedules: demoSchedules('demo-plan-main'),
      createdBy: 1,
      createdAt: atTime(8, 0),
      confirmedBy: 1,
      confirmedAt: atTime(8, 20),
    },
    {
      id: 'demo-plan-risk',
      factoryId,
      planDate,
      status: 'draft',
      totalBatches: 1,
      totalWorkers: 5,
      averageCompletionProbability: 0.68,
      lineSchedules: demoSchedules('demo-plan-risk').slice(0, 1),
      createdBy: 1,
      createdAt: atTime(9, 15),
    },
  ];
  return plans;
}

function demoAlerts(factoryId: string): SchedulingAlert[] {
  return [
    {
      id: 'demo-alert-worker-gap',
      factoryId,
      planId: 'demo-plan-main',
      scheduleId: 'demo-plan-main-s2',
      alertType: 'resource_conflict',
      severity: 'warning',
      message: '包装线 B 15:30 后存在 2 人缺口',
      suggestedAction: '建议调入临时包装工，或将休闲小包拆成两段排程',
      isResolved: false,
      createdAt: atTime(9, 42),
    },
    {
      id: 'demo-alert-deadline',
      factoryId,
      planId: 'demo-plan-risk',
      scheduleId: 'demo-plan-risk-s1',
      alertType: 'deadline_risk',
      severity: 'critical',
      message: '临时加单与客户交付时间窗冲突',
      suggestedAction: '建议优先锁定包装线并减少换线次数',
      isResolved: false,
      createdAt: atTime(9, 55),
    },
  ];
}

function demoDashboard(factoryId: string): SchedulingDashboard {
  const plans = demoPlans(factoryId);
  const schedules = plans.flatMap(plan => plan.lineSchedules);
  const alerts = demoAlerts(factoryId);
  return {
    planStats: {
      totalPlans: plans.length,
      draftPlans: plans.filter(plan => plan.status === 'draft').length,
      confirmedPlans: plans.filter(plan => plan.status === 'confirmed').length,
      inProgressPlans: 1,
      completedPlans: 0,
    },
    workerStats: {
      totalAssigned: 19,
      checkedIn: 14,
      temporaryWorkers: 4,
      averagePerformance: 89.2,
    },
    lineStats: {
      activeLines: 3,
      utilizationRate: 0.82,
      averageEfficiency: 0.91,
    },
    alerts,
    todaySchedules: schedules,
  };
}

// ==================== API 函数 ====================

/**
 * 获取调度计划列表
 */
export function getSchedulingPlans(
  factoryId: string,
  params?: {
    startDate?: string;
    endDate?: string;
    status?: string;
    page?: number;
    size?: number;
  }
) {
  if (isDemoSchedulingFactory(factoryId)) {
    const plans = demoPlans(factoryId).filter(plan => !params?.status || plan.status === params.status);
    return ok({
      content: plans,
      totalElements: plans.length,
      totalPages: 1,
    });
  }
  return get<{
    content: SchedulingPlan[];
    totalElements: number;
    totalPages: number;
  }>(`/${factoryId}/scheduling/plans`, { params });
}

/**
 * 获取调度计划详情
 */
export function getSchedulingPlan(factoryId: string, planId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const plan = demoPlans(factoryId).find(item => item.id === planId) || demoPlans(factoryId)[0];
    return ok(plan);
  }
  return get<SchedulingPlan>(`/${factoryId}/scheduling/plans/${planId}`);
}

/**
 * 创建调度计划
 */
export function createSchedulingPlan(
  factoryId: string,
  data: {
    planDate: string;
    batchIds: number[];
    autoAssignWorkers?: boolean;
    notes?: string;
  }
) {
  return post<SchedulingPlan>(`/${factoryId}/scheduling/plans`, data);
}

/**
 * 更新调度计划
 */
export function updateSchedulingPlan(
  factoryId: string,
  planId: string,
  data: {
    planDate?: string;
    batchIds?: number[];
    notes?: string;
  }
) {
  return put<SchedulingPlan>(`/${factoryId}/scheduling/plans/${planId}`, data);
}

/**
 * 确认调度计划
 */
export function confirmSchedulingPlan(factoryId: string, planId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const plan = demoPlans(factoryId).find(item => item.id === planId) || demoPlans(factoryId)[0];
    return ok({ ...plan, status: 'confirmed' as const, confirmedAt: new Date().toISOString() });
  }
  return post<SchedulingPlan>(`/${factoryId}/scheduling/plans/${planId}/confirm`);
}

/**
 * 取消调度计划
 */
export function cancelSchedulingPlan(factoryId: string, planId: string, reason: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    void reason;
    const plan = demoPlans(factoryId).find(item => item.id === planId) || demoPlans(factoryId)[0];
    return ok({ ...plan, status: 'cancelled' as const });
  }
  return post<void>(`/${factoryId}/scheduling/plans/${planId}/cancel`, null, {
    params: { reason }
  });
}

/**
 * AI生成调度建议
 */
export function generateSchedule(
  factoryId: string,
  data: {
    planDate: string;
    batchIds: number[];
    optimizationGoal?: 'minimize_cost' | 'maximize_efficiency' | 'balanced';
    considerTemporaryWorkers?: boolean;
    minCompletionProbability?: number;
  }
) {
  return post<SchedulingPlan>(`/${factoryId}/scheduling/generate`, data);
}

/**
 * 获取排程详情
 */
export function getSchedule(factoryId: string, scheduleId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const dashboard = demoDashboard(factoryId);
    const schedule = dashboard.todaySchedules.find(item => item.id === scheduleId) || dashboard.todaySchedules[0];
    return ok(schedule);
  }
  return get<LineSchedule>(`/${factoryId}/scheduling/schedules/${scheduleId}`);
}

/**
 * 更新排程
 */
export function updateSchedule(
  factoryId: string,
  scheduleId: string,
  data: {
    plannedStartTime?: string;
    plannedEndTime?: string;
    sequenceOrder?: number;
  }
) {
  return put<LineSchedule>(`/${factoryId}/scheduling/schedules/${scheduleId}`, data);
}

/**
 * 开始排程生产
 */
export function startSchedule(factoryId: string, scheduleId: string) {
  return post<LineSchedule>(`/${factoryId}/scheduling/schedules/${scheduleId}/start`);
}

/**
 * 完成排程
 */
export function completeSchedule(factoryId: string, scheduleId: string, completedQuantity: number) {
  return post<LineSchedule>(`/${factoryId}/scheduling/schedules/${scheduleId}/complete`, null, {
    params: { completedQuantity }
  });
}

/**
 * 更新排程进度
 */
export function updateScheduleProgress(factoryId: string, scheduleId: string, completedQuantity: number) {
  return post<LineSchedule>(`/${factoryId}/scheduling/schedules/${scheduleId}/progress`, null, {
    params: { completedQuantity }
  });
}

/**
 * 计算完成概率
 */
export function calculateProbability(factoryId: string, scheduleId: string) {
  return get<CompletionProbability>(`/${factoryId}/scheduling/schedules/${scheduleId}/probability`);
}

/**
 * 批量计算完成概率
 */
export function calculateBatchProbabilities(factoryId: string, planId: string) {
  return get<CompletionProbability[]>(`/${factoryId}/scheduling/plans/${planId}/probabilities`);
}

/**
 * 分配工人
 */
export function assignWorkers(
  factoryId: string,
  data: {
    scheduleId: string;
    userIds: number[];
  }
) {
  if (isDemoSchedulingFactory(factoryId)) {
    const assignments = data.userIds.map((userId, index) => ({
      id: `${data.scheduleId}-assigned-${userId}`,
      scheduleId: data.scheduleId,
      userId,
      userName: `演示工人 ${index + 1}`,
      role: '操作员',
      skillLevel: 4,
      isTemporary: false,
      assignedAt: new Date().toISOString(),
      status: 'assigned' as const,
    }));
    return ok(assignments);
  }
  return post<WorkerAssignment[]>(`/${factoryId}/scheduling/workers/assign`, data);
}

/**
 * 移除工人分配
 */
export function removeWorkerAssignment(factoryId: string, assignmentId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    void assignmentId;
    return ok(undefined);
  }
  return del<void>(`/${factoryId}/scheduling/workers/assignments/${assignmentId}`);
}

/**
 * 工人签到
 */
export function workerCheckIn(factoryId: string, assignmentId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    return ok({
      id: assignmentId,
      scheduleId: 'demo-plan-main-s2',
      userId: 3103,
      userName: '王磊',
      role: '包装操作',
      skillLevel: 4,
      isTemporary: false,
      assignedAt: atTime(8, 18),
      checkedInAt: new Date().toISOString(),
      status: 'checked_in' as const,
    });
  }
  return post<WorkerAssignment>(`/${factoryId}/scheduling/workers/assignments/${assignmentId}/check-in`);
}

/**
 * 工人签退
 */
export function workerCheckOut(factoryId: string, assignmentId: string, performanceScore?: number) {
  if (isDemoSchedulingFactory(factoryId)) {
    return ok({
      id: assignmentId,
      scheduleId: 'demo-plan-main-s2',
      userId: 3103,
      userName: '王磊',
      role: '包装操作',
      skillLevel: 4,
      isTemporary: false,
      assignedAt: atTime(8, 18),
      checkedInAt: atTime(9, 0),
      checkedOutAt: new Date().toISOString(),
      workedHours: 4,
      performanceScore: performanceScore || 88,
      status: 'checked_out' as const,
    });
  }
  return post<WorkerAssignment>(`/${factoryId}/scheduling/workers/assignments/${assignmentId}/check-out`, null, {
    params: { performanceScore }
  });
}

/**
 * 获取工人分配
 */
export function getWorkerAssignments(factoryId: string, userId: number, date?: string) {
  return get<WorkerAssignment[]>(`/${factoryId}/scheduling/workers/${userId}/assignments`, {
    params: { date }
  });
}

/**
 * 优化工人分配 (OR-Tools)
 */
export function optimizeWorkers(
  factoryId: string,
  data: {
    scheduleIds: string[];
    objective?: 'minimize_cost' | 'maximize_efficiency' | 'balanced';
    maxTemporaryRatio?: number;
    minSkillMatch?: number;
  }
) {
  if (isDemoSchedulingFactory(factoryId)) {
    const assignments = data.scheduleIds.flatMap(scheduleId => demoAssignments(scheduleId));
    return ok(assignments);
  }
  return post<WorkerAssignment[]>(`/${factoryId}/scheduling/optimize-workers`, data);
}

/**
 * 重新调度
 */
export function reschedule(
  factoryId: string,
  data: {
    planId: string;
    reason: string;
    adjustments?: Array<{
      scheduleId: string;
      newStartTime?: string;
      newEndTime?: string;
      additionalWorkers?: number;
    }>;
  }
) {
  return post<SchedulingPlan>(`/${factoryId}/scheduling/reschedule`, data);
}

/**
 * 获取告警列表
 */
export function getAlerts(
  factoryId: string,
  params?: {
    severity?: string;
    alertType?: string;
    page?: number;
    size?: number;
  }
) {
  if (isDemoSchedulingFactory(factoryId)) {
    const alerts = demoAlerts(factoryId)
      .filter(alert => !params?.severity || alert.severity === params.severity)
      .filter(alert => !params?.alertType || alert.alertType === params.alertType);
    return ok({
      content: alerts,
      totalElements: alerts.length,
      totalPages: 1,
    });
  }
  return get<{
    content: SchedulingAlert[];
    totalElements: number;
    totalPages: number;
  }>(`/${factoryId}/scheduling/alerts`, { params });
}

/**
 * 获取未解决告警
 */
export function getUnresolvedAlerts(factoryId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    return ok(demoAlerts(factoryId));
  }
  return get<SchedulingAlert[]>(`/${factoryId}/scheduling/alerts/unresolved`);
}

/**
 * 确认告警
 */
export function acknowledgeAlert(factoryId: string, alertId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const alert = demoAlerts(factoryId).find(item => item.id === alertId) || demoAlerts(factoryId)[0];
    return ok({ ...alert, acknowledgedAt: new Date().toISOString(), acknowledgedBy: 1 });
  }
  return post<SchedulingAlert>(`/${factoryId}/scheduling/alerts/${alertId}/acknowledge`);
}

/**
 * 解决告警
 */
export function resolveAlert(factoryId: string, alertId: string, resolutionNotes: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const alert = demoAlerts(factoryId).find(item => item.id === alertId) || demoAlerts(factoryId)[0];
    return ok({
      ...alert,
      isResolved: true,
      resolvedAt: new Date().toISOString(),
      resolvedBy: 1,
      resolutionNotes,
    });
  }
  return post<SchedulingAlert>(`/${factoryId}/scheduling/alerts/${alertId}/resolve`, null, {
    params: { resolutionNotes }
  });
}

/**
 * 获取产线列表
 */
export function getProductionLines(factoryId: string, status?: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    const allLines: ProductionLine[] = [
      {
        id: 'line-luzhi-01',
        factoryId,
        name: '一号卤制线',
        lineType: '卤制',
        minWorkers: 6,
        maxWorkers: 10,
        requiredSkillLevel: 4,
        hourlyCapacity: 1200,
        status: 'active',
        createdAt: atTime(8, 0),
      },
      {
        id: 'line-pack-02',
        factoryId,
        name: '包装线 B',
        lineType: '包装',
        minWorkers: 5,
        maxWorkers: 9,
        requiredSkillLevel: 3,
        hourlyCapacity: 1500,
        status: 'active',
        createdAt: atTime(8, 0),
      },
    ];
    const lines = allLines.filter(line => !status || line.status === status);
    return ok(lines);
  }
  return get<ProductionLine[]>(`/${factoryId}/scheduling/production-lines`, {
    params: { status }
  });
}

/**
 * 创建产线
 */
export function createProductionLine(factoryId: string, data: Partial<ProductionLine>) {
  return post<ProductionLine>(`/${factoryId}/scheduling/production-lines`, data);
}

/**
 * 更新产线
 */
export function updateProductionLine(factoryId: string, lineId: string, data: Partial<ProductionLine>) {
  return put<ProductionLine>(`/${factoryId}/scheduling/production-lines/${lineId}`, data);
}

/**
 * 更新产线状态
 */
export function updateProductionLineStatus(factoryId: string, lineId: string, status: string) {
  return put<ProductionLine>(`/${factoryId}/scheduling/production-lines/${lineId}/status`, null, {
    params: { status }
  });
}

/**
 * 获取调度 Dashboard
 */
export function getSchedulingDashboard(factoryId: string, date?: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    void date;
    return ok(demoDashboard(factoryId));
  }
  return get<SchedulingDashboard>(`/${factoryId}/scheduling/dashboard`, {
    params: { date }
  });
}

/**
 * 获取实时监控数据
 */
export function getRealtimeMonitor(factoryId: string, planId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    void planId;
    return ok(demoDashboard(factoryId));
  }
  return get<SchedulingDashboard>(`/${factoryId}/scheduling/realtime/${planId}`);
}

// ==================== 排产自动化设置 (R31) ====================

export interface SchedulingSettings {
  autoSchedulingMode: 'DISABLED' | 'MANUAL_CONFIRM' | 'FULLY_AUTO';
  lowRiskThreshold: number;
  mediumRiskThreshold: number;
  enableNotifications: boolean;
  autoTriggerEnabled: boolean;
}

export function getSchedulingSettings(factoryId: string) {
  if (isDemoSchedulingFactory(factoryId)) {
    return ok({
      autoSchedulingMode: 'MANUAL_CONFIRM' as const,
      lowRiskThreshold: 0.85,
      mediumRiskThreshold: 0.7,
      enableNotifications: true,
      autoTriggerEnabled: false,
    });
  }
  return get<SchedulingSettings>(`/${factoryId}/scheduling/settings`);
}

export function updateSchedulingSettings(factoryId: string, settings: Partial<SchedulingSettings>) {
  if (isDemoSchedulingFactory(factoryId)) {
    return ok({
      autoSchedulingMode: settings.autoSchedulingMode || 'MANUAL_CONFIRM',
      lowRiskThreshold: settings.lowRiskThreshold ?? 0.85,
      mediumRiskThreshold: settings.mediumRiskThreshold ?? 0.7,
      enableNotifications: settings.enableNotifications ?? true,
      autoTriggerEnabled: settings.autoTriggerEnabled ?? false,
    });
  }
  return put<SchedulingSettings>(`/${factoryId}/scheduling/settings`, settings);
}
