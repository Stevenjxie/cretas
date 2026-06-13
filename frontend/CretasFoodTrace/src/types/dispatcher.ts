/**
 * 调度员模块类型定义
 *
 * 与后端 SchedulingController 和 LinUCBController 对应
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

// ==================== 枚举类型 ====================

/** 排程状态 */
export type ScheduleStatus = 'draft' | 'confirmed' | 'in_progress' | 'completed' | 'cancelled';

/** 工人分配状态 */
export type AssignmentStatus = 'assigned' | 'checked_in' | 'working' | 'checked_out' | 'absent';

/** 告警级别 */
export type AlertSeverity = 'low' | 'medium' | 'high' | 'critical';

/** 告警类型 */
export type SchedulingAlertType =
  | 'worker_shortage'
  | 'equipment_unavailable'
  | 'material_shortage'
  | 'deadline_risk'
  | 'efficiency_low'
  | 'capacity_overload';

/** 计划来源类型 */
export type PlanSourceType =
  | 'customer_order'
  | 'ai_forecast'
  | 'safety_stock'
  | 'manual'
  | 'urgent_insert';

/** 混批类型 */
export type MixedBatchType = 'same_material' | 'same_process';

/** 雇用类型 */
export type HireType = 'full_time' | 'part_time' | 'dispatch' | 'intern' | 'temporary';

// ==================== 生产计划 ====================

/** 生产计划 */
export interface ProductionPlan {
  id: string;
  factoryId: string;
  planNumber: string;
  productTypeId: string;
  productTypeName?: string;
  plannedQuantity: number;
  actualQuantity?: number;
  unit: string;
  plannedStartTime: string;
  plannedEndTime: string;
  actualStartTime?: string;
  actualEndTime?: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
  priority: number;
  workshopId?: string;
  workshopName?: string;
  assignedLineId?: string;
  notes?: string;

  // 计划来源信息
  sourceType?: PlanSourceType;
  sourceOrderId?: string;
  sourceCustomerName?: string;
  aiConfidence?: number;
  forecastReason?: string;
  crValue?: number;

  // 混批信息
  isMixedBatch?: boolean;
  relatedOrders?: string[];

  createdAt: string;
  updatedAt: string;
}

/** 生产计划来源信息 */
export interface PlanSource {
  type: PlanSourceType;
  priority: number;
  confidence?: number;
  orderId?: string;
  customerName?: string;
  forecastReason?: string;
}

/**
 * 生产计划 DTO（用于调度模块紧急状态监控）
 * 与后端 ProductionPlanDTO 对应
 */
export interface ProductionPlanDTO {
  // 基础信息
  id: string;
  planNumber: string;
  productTypeId: string;
  productTypeName?: string;
  plannedQuantity: number;
  expectedCompletionDate: string; // ISO date (YYYY-MM-DD)
  status: string;
  priority?: number;

  // 来源信息
  sourceType: string;
  customerOrderNumber?: string;
  sourceCustomerName?: string;

  // 调度字段
  crValue?: number;
  aiConfidence?: number;
  forecastReason?: string;
  isMixedBatch?: boolean;
  allocatedQuantity?: number;
  isFullyMatched?: boolean;
  matchingProgress?: number;

  // 紧急监控字段
  currentProbability?: number; // 0-1
  probabilityUpdatedAt?: string; // ISO datetime
  isUrgent?: boolean;

  // 强制插单审批字段
  isForceInserted?: boolean;
  requiresApproval?: boolean;
  approvalStatus?: 'PENDING' | 'APPROVED' | 'REJECTED';
  forceInsertReason?: string;
  forceInsertBy?: number;
  forceInsertedAt?: string;
  approverId?: number;
  approverName?: string;
  approvedAt?: string;
  approvalComment?: string;

  // 时间戳
  createdAt: string;
  updatedAt: string;
}

// ==================== 调度排程 ====================

/** 调度计划 */
export interface SchedulingPlan {
  id: string;
  factoryId: string;
  planDate: string;
  shiftType: 'day' | 'night' | 'full_day';
  totalWorkers: number;
  totalEquipment: number;
  estimatedOutput: number;
  actualOutput?: number;
  status: ScheduleStatus;
  createdBy: number;
  confirmedBy?: number;
  confirmedAt?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;

  // 关联数据
  lineSchedules?: LineSchedule[];

  // AI 调度返回的扩展字段 (可选)
  batchNumber?: string;
  productTypeName?: string;
  plannedStartTime?: string;
  plannedEndTime?: string;
}

/** 产线排程 */
export interface LineSchedule {
  id: string;
  schedulingPlanId: string;
  productionLineId: string;
  productionLineName?: string;
  productionPlanId?: string;
  productionPlanNumber?: string;
  productTypeId: string;
  productTypeName?: string;
  plannedQuantity: number;
  actualQuantity?: number;
  plannedStartTime: string;
  plannedEndTime: string;
  actualStartTime?: string;
  actualEndTime?: string;
  status: ScheduleStatus;
  workerCount: number;
  efficiency?: number;
  notes?: string;

  // AI预测
  aiPredictedEfficiency?: number;
  aiConfidenceLevel?: number;
  completionProbability?: number;
  confidenceInterval?: {
    lower: number;
    upper: number;
  };
  llmAnalysis?: string;

  // 工人分配
  workerAssignments?: WorkerAssignment[];

  createdAt: string;
  updatedAt: string;
}

/** 工人分配 */
export interface WorkerAssignment {
  id: string;
  scheduleId: string;
  userId: number;
  workerName?: string;
  workerCode?: string;
  assignedAt: string;
  actualStartTime?: string;
  actualEndTime?: string;
  isTemporary: boolean;
  laborCost?: number;
  performanceScore?: number;
  status: AssignmentStatus;
  notes?: string;

  // LinUCB 相关字段
  linucbFeedbackId?: string;
  linucbScore?: number;
}

// ==================== LinUCB 人员推荐 ====================

/** 工人推荐结果 */
export interface WorkerRecommendation {
  workerId: number;
  workerCode?: string;
  workerName?: string;
  ucbScore: number;
  expectedEfficiency?: number;
  confidenceWidth?: number;
  recommendation?: string;
}

/** 工人绩效排名 */
export interface WorkerPerformanceRank {
  rank: number;
  workerId: number;
  workerCode?: string;
  workerName?: string;
  avgReward: number;
  avgEfficiency?: number;
  avgQuality?: number;
  taskCount: number;
}

/** LinUCB 模型信息 */
export interface LinUCBModel {
  id: string;
  workerId: number;
  featureDim: number;
  updateCount: number;
  lastReward?: number;
  avgReward?: number;
  lastUpdatedAt?: string;
}

/** 模型训练统计 */
export interface ModelTrainingStats {
  totalModels: number;
  activeModels: number;
  totalUpdates: number;
  unprocessedFeedbacks: number;
  avgReward?: number;
  lastTrainingTime?: string;
}

// ==================== 调度告警 ====================

/** 调度告警 */
export interface SchedulingAlert {
  id: string;
  factoryId: string;
  scheduleId?: string;
  alertType: SchedulingAlertType;
  severity: AlertSeverity;
  title: string;
  message: string;
  suggestion?: string;
  isResolved: boolean;
  resolvedBy?: number;
  resolvedAt?: string;
  resolution?: string;
  createdAt: string;
}

// ==================== 员工信息 ====================

/** 调度员工信息 */
export interface DispatcherEmployee {
  id: number;
  username: string;
  fullName: string;
  employeeCode: string;
  phone?: string;
  email?: string;
  hireType: HireType;
  contractEndDate?: string;
  hireDate?: string;
  departmentId?: string;
  departmentName?: string;
  workshopId?: string;
  workshopName?: string;
  position?: string;
  skillLevels?: Record<string, number>;
  hourlyRate?: number;
  avatarUrl?: string;
  isActive: boolean;

  // 绩效信息
  efficiency?: number;
  qualityScore?: number;
  attendanceRate?: number;

  // 当日工作状态
  todayWorkHours?: number;
  currentTaskId?: string;
  currentTaskName?: string;
  status: 'idle' | 'working' | 'break' | 'off_duty';
}

/** 员工技能信息 */
export interface EmployeeSkill {
  skillName: string;
  level: number;
  certifiedAt?: string;
  expiresAt?: string;
}

// ==================== 车间状态 ====================

/** 车间状态 */
export interface WorkshopStatus {
  workshopId: string;
  workshopName: string;
  totalWorkers: number;
  activeWorkers: number;
  idleWorkers: number;
  temporaryWorkers: number;
  utilization: number;
  currentOutput: number;
  targetOutput: number;
  efficiency: number;
  activeTaskGroups: TaskGroup[];
  alerts: SchedulingAlert[];
}

/** 任务组 */
export interface TaskGroup {
  id: string;
  name: string;
  batchNumber?: string;
  workerCount: number;
  progress: number;
  status: 'running' | 'paused' | 'completed';
  workers: {
    id: number;
    name: string;
    code: string;
    isTemporary: boolean;
  }[];
}

// ==================== Dashboard 数据 ====================

/** 调度 Dashboard */
export interface SchedulingDashboard {
  overview: {
    totalPlans: number;
    activePlans: number;
    completedPlans: number;
    delayedPlans: number;
    onTimeRate: number;
  };
  workers: {
    total: number;
    active: number;
    idle: number;
    absent: number;
    temporary: number;
    utilization: number;
  };
  production: {
    todayPlanned: number;
    todayActual: number;
    efficiency: number;
    trend: 'up' | 'down' | 'stable';
  };
  alerts: {
    critical: number;
    high: number;
    medium: number;
    low: number;
    unresolved: number;
  };
  aiInsights: {
    completionProbability: number;
    riskLevel: 'low' | 'medium' | 'high';
    suggestions: string[];
    optimizationPotential?: number;
  };
}

/** 生产趋势数据 */
export interface ProductionTrend {
  date: string;
  planned: number;
  actual: number;
  efficiency: number;
}

// ==================== 紧急插单 ====================

/** 推荐评分分解 (多维度评分) */
export interface ScoreBreakdown {
  capacityFactor: number;    // 产能利用率 (0-1)
  workerFactor: number;      // 工人可用性 (0-1)
  deadlineFactor: number;    // 交期紧迫度 (0-1)
  impactFactor: number;      // 影响程度 (0-1, 越高越好)
  switchCostFactor: number;  // 换线成本 (0-1, 越高越好)
}

/** 链式影响详情 */
export interface ChainImpactDetails {
  directConflicts: number;   // 直接冲突计划数
  cascadeDelays: number;     // 级联延误计划数
  maxDelayMinutes: number;   // 最大延误时间(分钟)
  affectsVipCustomer?: boolean; // 是否影响VIP客户
  criticalCrPlans?: number;  // CR<0.5的关键计划数
}

/** 资源状态 */
export interface ResourceStatus {
  availableWorkers: number;
  requiredWorkers: number;
  workerNames?: string[];
  availableEquipment?: string[];
  materialStatus?: 'sufficient' | 'partial' | 'insufficient';
}

/** 紧急插单时段 (增强版) */
export interface InsertSlot {
  id: string;
  productionLineId?: string;
  productionLineName?: string;
  startTime: string;
  endTime: string;
  availableCapacity: number;

  // 推荐评分 (0-100)
  recommendScore: number;
  scoreBreakdown?: ScoreBreakdown;

  // 影响分析
  impactLevel: 'none' | 'low' | 'medium' | 'high' | 'critical';
  impactDetails?: ChainImpactDetails;
  impactedPlans?: ImpactedPlan[];

  // 资源状态
  requiredWorkers: number;
  availableWorkers: number;
  resourceStatus?: ResourceStatus;

  // 换线成本
  switchCostMinutes: number;

  // 时段状态
  status?: 'available' | 'locked' | 'unavailable';
  isFeasible?: boolean;
  infeasibleReason?: string;

  // 锁定信息
  isLocked?: boolean;
  lockedBy?: string;
  lockExpireAt?: string;

  // 推荐理由
  recommendation?: string;
}

/** 受影响的计划 */
export interface ImpactedPlan {
  planId: string;
  planNumber: string;
  productName?: string;
  originalEndTime?: string;
  newEndTime?: string;
  delayMinutes: number;
  customerName?: string;
  isVip?: boolean;
  crValue?: number;
}

/** 确认紧急插单请求 */
export interface ConfirmUrgentInsertRequest {
  slotId: string;
  productTypeId: string;
  plannedQuantity: number;
  priority?: number;  // 1-10, default 9
  urgentReason: string;
  requestedDeadline?: string;
  customerName?: string;
  customerOrderNumber?: string;
  assignedWorkerIds?: number[];
  assignedEquipmentIds?: string[];
  materialBatchIds?: string[];
  notes?: string;
  forceInsert?: boolean;
}

/** 强制插单请求 */
export interface ForceInsertRequest extends ConfirmUrgentInsertRequest {
  forceInsert: true;
  overrideReason: string;
}

/** 紧急插单影响分析结果 */
export interface UrgentInsertImpactAnalysis {
  slotId: string;
  impactLevel: 'none' | 'low' | 'medium' | 'high' | 'critical';
  impactScore: number;
  chainImpact: ChainImpactDetails;
  affectedPlans: ImpactedPlan[];
  resourceImpact: {
    workerOvertime?: number;
    equipmentConflicts?: string[];
    materialShortage?: boolean;
  };
  recommendation: string;
  requiresApproval: boolean;
  approverRole?: string;
}

// ==================== 混批排产 ====================

/** 混批分组 */
export interface MixedBatchGroup {
  id: string;
  groupType: MixedBatchType;
  materialBatchId?: string;
  materialBatchNumber?: string;
  processType?: string;
  orders: MixedBatchOrder[];
  totalQuantity: number;
  estimatedSwitchSaving: number;
  status: 'pending' | 'confirmed' | 'rejected';
  confirmedBy?: number;
  confirmedAt?: string;
  productionPlanId?: string;
}

/** 混批订单 */
export interface MixedBatchOrder {
  orderId: string;
  customerName: string;
  productName: string;
  quantity: number;
  unit: string;
  deadline: string;
}

/** 混批规则 */
export interface MixedBatchRule {
  id: string;
  ruleType: MixedBatchType;
  isEnabled: boolean;
  maxDeadlineGapHours: number;
  minSwitchSavingMinutes: number;
  processSimilarityThreshold: number;
}

// ==================== API 请求/响应 ====================

/** 创建排程请求 */
export interface CreateScheduleRequest {
  planDate: string;
  shiftType: 'day' | 'night' | 'full_day';
  lineSchedules: {
    productionLineId: string;
    productionPlanId?: string;
    productTypeId: string;
    plannedQuantity: number;
    plannedStartTime: string;
    plannedEndTime: string;
    workerIds: number[];
  }[];
  notes?: string;
}

/** 工人分配请求 */
export interface AssignWorkersRequest {
  scheduleId: string;
  workerIds: number[];
  isTemporary?: boolean;
  notes?: string;
}

/** LinUCB 推荐请求 */
export interface RecommendWorkersRequest {
  taskFeatures: {
    quantity: number;
    deadlineHours: number;
    productType: string;
    priority: number;
    complexity: number;
    workshopId: string;
  };
  candidateWorkerIds: number[];
}

/** LinUCB 分配记录请求 */
export interface RecordAllocationRequest {
  taskId: string;
  taskType: string;
  workerId: number;
  workerCode?: string;
  taskFeatures?: Record<string, unknown>;
  workerFeatures?: Record<string, unknown>;
  predictedScore?: number;
  plannedQuantity?: number;
  plannedHours?: number;
}

/** LinUCB 反馈完成请求 */
export interface CompleteFeedbackRequest {
  feedbackId: string;
  actualQuantity: number;
  actualHours: number;
  qualityScore?: number;
}

/** 获取插单时段请求 */
export interface GetInsertSlotsRequest {
  productTypeId: string;
  requiredQuantity: number;
  deadline: string;
  urgencyLevel?: 'normal' | 'urgent' | 'critical';
}

/** 确认插单请求 (已废弃，使用 ConfirmUrgentInsertRequest) */
export interface ConfirmInsertRequest {
  slotId: string;
  productTypeId: string;
  quantity: number;
  deadline: string;
  workerIds?: number[];
  notes?: string;
  /** @deprecated 使用 ConfirmUrgentInsertRequest.urgentReason */
  reason?: string;
  /** @deprecated 使用 ConfirmUrgentInsertRequest.priority */
  priority?: number;
}

/** 检测混批请求 */
export interface DetectMixedBatchRequest {
  orderIds?: string[];
  startDate?: string;
  endDate?: string;
  minSavingMinutes?: number;
}

// ==================== 分页响应 ====================

/** 分页数据 */
export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

// ==================== 调度统计 ====================

/** 紧急插单统计数据 */
export interface UrgentInsertStatistics {
  // 总体统计
  totalInserts: number;
  successfulInserts: number;
  failedInserts: number;
  successRate: number;
  averageImpactScore: number;
  forcedInsertCount: number;
  forcedInsertRate: number;

  // 按时段分布
  byPeriod?: {
    dayShift: number;
    nightShift: number;
  };

  // 按产品类型分布
  byProductType?: {
    productTypeName: string;
    count: number;
  }[];

  // 趋势数据（最近7天）
  trend?: {
    date: string;
    count: number;
    successCount: number;
  }[];

  // 最近插单记录
  recentInserts?: {
    id: string;
    productName: string;
    quantity: number;
    status: 'success' | 'failed' | 'pending';
    impactScore: number;
    isForced: boolean;
    createdAt: string;
  }[];
}

/** 调度统计数据 */
export interface SchedulingStatistics {
  period: string;
  totalPlans: number;
  completedPlans: number;
  onTimeDeliveryRate: number;
  avgEfficiency: number;
  totalWorkerHours: number;
  laborCost: number;
  outputPerWorker: number;
  aiRecommendationAccuracy?: number;
  emergencyResponseTime?: number;
}

/** 人员利用率统计 */
export interface WorkerUtilizationStats {
  workshopId: string;
  workshopName: string;
  totalWorkers: number;
  activeWorkers: number;
  utilization: number;
  avgEfficiency: number;
  totalOutput: number;
}

// ==================== 导航参数类型 ====================

/** 调度员导航参数 */
export type DispatcherStackParamList = {
  DispatcherMain: undefined;
  WorkshopStatus: { workshopId: string };
  ApprovalList: undefined;
  ApprovalDetail: { approvalId: string };
  NotificationList: undefined;
  PlanList: undefined;
  PlanDetail: { planId: string };
  PlanCreate: undefined;
  PlanEdit: { planId: string };
  PlanCancel: { planId: string };
  ProcessRunOverview: { productionRunId: string };
  TaskAssignment: { scheduleId: string };
  TaskEdit: { taskId: string };
  BatchWorkers: { scheduleId: string };
  AISchedule: undefined;
  AICompletionProb: { scheduleId: string };
  AIWorkerOptimize: { scheduleId?: string };
  AIScheduleGenerate: undefined;
  LinUCBDebug: undefined;
  PersonnelList: undefined;
  PersonnelDetail: { personnelId: number };
  PersonnelSchedule: undefined;
  PersonnelTransfer: { employeeId?: number };
  PersonnelAttendance: { employeeId?: number };
  DSProfile: undefined;
  DSSettings: undefined;
  DSStatistics: undefined;
  DSAbout: undefined;
  UrgentInsertSlots: { orderId?: string };
  UrgentInsertConfirm: { slotId: string };
  MixedBatch: undefined;
  MixedBatchDetail: { groupId: string };
};

/** 调度员 Tab 参数 */
export type DispatcherTabParamList = {
  HomeTab: undefined;
  PlanTab: undefined;
  AITab: undefined;
  SmartBITab: undefined;
  PersonnelTab: undefined;
  ProfileTab: undefined;
};

// ==================== 主题颜色 ====================

/** 调度员主题颜色 */
export const DISPATCHER_THEME = {
  primary: '#722ed1',        // 紫色
  secondary: '#a18cd1',      // 淡紫色
  accent: '#fbc2eb',         // 粉色
  success: '#52c41a',
  warning: '#fa8c16',
  danger: '#ff4d4f',
  info: '#1890ff',
  background: '#f5f5f5',
  cardBackground: '#ffffff',
  textPrimary: '#333333',
  textSecondary: '#666666',
  textMuted: '#999999',
  border: '#e8e8e8',

  // 渐变色
  gradientStart: '#722ed1',
  gradientEnd: '#fbc2eb',
} as const;
