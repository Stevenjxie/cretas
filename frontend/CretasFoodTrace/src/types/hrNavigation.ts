/**
 * HR 模块导航类型定义
 *
 * 与后端 UserController、TimeClockController、DepartmentController 对应
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import type { NavigatorScreenParams } from '@react-navigation/native';

// ==================== HR 导航参数类型 ====================

/** HR 主 Stack 参数 */
export type HRStackParamList = {
  HRTabs: undefined;
  // Staff 详情页 (在 Tab 外显示)
  StaffDetail: { staffId: number };
  StaffAIAnalysis: { staffId: number };
  StaffAdd: undefined;
  // Department 详情页
  DepartmentDetail: { departmentId: string };
  DepartmentAdd: undefined;
  // Whitelist
  WhitelistAdd: undefined;
  // Production 详情页
  BatchWorkers: { batchId: string; batchName: string };
  BatchAssignment: undefined;
  // 考勤详情
  AttendanceStats: undefined;
  AttendanceAnomaly: undefined;
  MyAttendance: undefined;
  MyInfo: undefined;
  // 排班和分析
  WorkSchedule: undefined;
  LaborCost: undefined;
  Performance: undefined;
  NewHires: undefined;
};

/** HR Tab 参数 */
export type HRTabParamList = {
  HomeTab: NavigatorScreenParams<HRHomeStackParamList>;
  StaffTab: NavigatorScreenParams<HRStaffStackParamList>;
  AttendanceTab: NavigatorScreenParams<HRAttendanceStackParamList>;
  WhitelistTab: NavigatorScreenParams<HRWhitelistStackParamList>;
  ProfileTab: NavigatorScreenParams<HRProfileStackParamList>;
};

/** 首页 Stack 参数 */
export type HRHomeStackParamList = {
  HRHome: undefined;
  NewHires: undefined;
  LaborCost: undefined;
  Performance: undefined;
  WorkSchedule: undefined;
  BatchAssignment: undefined;
  BatchWorkers: { batchId: string; batchName: string };
  StaffDetail: { staffId: number };
  StaffAIAnalysis: { staffId: number };
  AttendanceAnomaly: undefined;
};

/** 人员 Stack 参数 */
export type HRStaffStackParamList = {
  StaffList: undefined;
  StaffAdd: undefined;
  StaffDetail: { staffId: number };
  StaffAIAnalysis: { staffId: number };
  BatchWorkers: { batchId: string; batchName: string };
};

/** 考勤 Stack 参数 */
export type HRAttendanceStackParamList = {
  AttendanceManage: undefined;
  AttendanceStats: undefined;
  AttendanceAnomaly: undefined;
  MyAttendance: undefined;
  StaffDetail: { staffId: number };
};

/** 白名单 Stack 参数 */
export type HRWhitelistStackParamList = {
  WhitelistList: undefined;
  WhitelistAdd: undefined;
  DepartmentList: undefined;
  DepartmentAdd: undefined;
  DepartmentDetail: { departmentId: string };
  StaffDetail: { staffId: number };
};

/** 个人中心 Stack 参数 */
export type HRProfileStackParamList = {
  HRProfile: undefined;
  MyInfo: undefined;
  MyAttendance: undefined;
  AttendanceStats: undefined;
  AttendanceAnomaly: undefined;
  AttendanceManage: undefined;
  DepartmentList: undefined;
  DepartmentAdd: undefined;
  DepartmentDetail: { departmentId: string };
  WhitelistList: undefined;
  WhitelistAdd: undefined;
  WorkSchedule: undefined;
  StaffDetail: { staffId: number };
  Feedback: undefined;
  Membership: undefined;
};

// ==================== HR Dashboard 类型 ====================

/** HR Dashboard 统计数据 */
export interface HRDashboardStats {
  /** 在岗人数 */
  todayOnSite: number;
  /** 迟到人数 */
  lateCount: number;
  /** 待激活白名单数 */
  whitelistPending: number;
  /** 本月入职人数 */
  thisMonthNewHires: number;
  /** 总员工数 */
  totalStaff: number;
  /** 出勤率 (百分比) */
  attendanceRate: number;
  /** 上月入职人数 (用于环比) */
  lastMonthNewHires?: number;
}

/** 统计卡片类型 */
export type HRStatCardType = 'onSite' | 'late' | 'whitelist' | 'newHires';

/** 统计卡片配置 */
export interface HRStatCardConfig {
  type: HRStatCardType;
  title: string;
  icon: string;
  color: string;
  getValue: (stats: HRDashboardStats) => number | string;
  getSubtext?: (stats: HRDashboardStats) => string;
  onPress?: () => void;
}

// ==================== 考勤相关类型 ====================

/** 考勤异常类型 */
export type AttendanceAnomalyType =
  | 'LATE'
  | 'ABSENT'
  | 'EARLY_LEAVE'
  | 'NO_CLOCK_IN'
  | 'NO_CLOCK_OUT';

/** 考勤异常记录 */
export interface AttendanceAnomaly {
  id: string;
  userId: number;
  userName: string;
  userAvatar?: string;
  department?: string;
  anomalyType: AttendanceAnomalyType;
  anomalyTime: string;
  expectedTime?: string;
  actualTime?: string;
  duration?: number; // 迟到/早退分钟数
  notes?: string;
  isResolved: boolean;
  resolvedBy?: number;
  resolvedAt?: string;
}

/** 考勤异常类型显示配置 */
export const ANOMALY_TYPE_CONFIG: Record<AttendanceAnomalyType, {
  label: string;
  color: string;
  icon: string;
}> = {
  LATE: { label: '迟到', color: '#fa8c16', icon: 'clock-alert' },
  ABSENT: { label: '缺勤', color: '#ff4d4f', icon: 'account-remove' },
  EARLY_LEAVE: { label: '早退', color: '#faad14', icon: 'exit-run' },
  NO_CLOCK_IN: { label: '未打上班卡', color: '#ff7a45', icon: 'card-off' },
  NO_CLOCK_OUT: { label: '未打下班卡', color: '#ff7a45', icon: 'card-off' },
};

// ==================== 员工相关类型 ====================

/** 员工状态 */
export type StaffStatus = 'active' | 'on_leave' | 'resigned' | 'suspended';

/** 员工列表项 */
export interface StaffListItem {
  id: number;
  username: string;
  fullName: string;
  employeeCode?: string;
  phone?: string;
  email?: string;
  department?: string;
  departmentId?: string;
  position?: string;
  avatarUrl?: string;
  status: StaffStatus;
  hireDate?: string;
  roleCode?: string;
  roleName?: string;
}

/** 员工详情 */
export interface StaffDetail extends StaffListItem {
  // 考勤统计
  attendanceStats?: {
    workDays: number;
    lateDays: number;
    earlyLeaveDays: number;
    absentDays: number;
  };
  // 批次参与
  recentBatches?: {
    batchNumber: string;
    productName: string;
    processType: string;
    workHours: number;
    workDate: string;
    laborCost: number;
  }[];
  // 工时汇总
  workTimeSummary?: {
    totalBatches: number;
    totalHours: number;
    totalEarnings: number;
    avgHourlyRate: number;
  };
  // 技能信息
  skillLevels?: Record<string, number>;
  // 合同信息
  contractEndDate?: string;
  hireType?: 'full_time' | 'part_time' | 'temporary' | 'intern';
}

/** 员工状态显示配置 */
export const STAFF_STATUS_CONFIG: Record<StaffStatus, {
  label: string;
  color: string;
  bgColor: string;
}> = {
  active: { label: '在岗', color: '#52c41a', bgColor: '#f6ffed' },
  on_leave: { label: '休假', color: '#1890ff', bgColor: '#e6f7ff' },
  resigned: { label: '离职', color: '#8c8c8c', bgColor: '#fafafa' },
  suspended: { label: '停职', color: '#ff4d4f', bgColor: '#fff2f0' },
};

// ==================== 部门相关类型 ====================

/** 部门列表项 */
export interface DepartmentListItem {
  id: string;
  name: string;
  code?: string;
  managerId?: number;
  managerName?: string;
  parentId?: string;
  parentName?: string;
  employeeCount: number;
  activeCount: number;
  monthlyBatches?: number;
  monthlyOutput?: number;
  status: 'active' | 'inactive';
  departmentType: 'production' | 'support';
}

/** 部门详情 */
export interface DepartmentDetail extends DepartmentListItem {
  description?: string;
  employees?: StaffListItem[];
  performanceStats?: {
    avgEfficiency: number;
    avgQuality: number;
    totalOutput: number;
    laborCost: number;
  };
}

// ==================== 排班相关类型 ====================

/** 班次类型 */
export type ShiftType = 'morning' | 'afternoon' | 'evening' | 'night';

/** 排班记录 */
export interface WorkScheduleItem {
  id: string;
  date: string;
  shiftType: ShiftType;
  startTime: string;
  endTime: string;
  assignedCount: number;
  confirmedCount: number;
  employees: {
    id: number;
    name: string;
    avatar?: string;
    isConfirmed: boolean;
  }[];
}

/** 班次配置 */
export const SHIFT_CONFIG: Record<ShiftType, {
  label: string;
  timeRange: string;
  color: string;
}> = {
  morning: { label: '早班', timeRange: '08:00-12:00', color: '#fa8c16' },
  afternoon: { label: '午班', timeRange: '12:00-18:00', color: '#1890ff' },
  evening: { label: '晚班', timeRange: '18:00-22:00', color: '#722ed1' },
  night: { label: '夜班', timeRange: '22:00-06:00', color: '#13c2c2' },
};

// ==================== 批次分配相关类型 ====================

/** 批次分配项 */
export interface BatchAssignmentItem {
  batchId: string;
  batchNumber: string;
  productName: string;
  productSpec?: string;
  status: 'in_progress' | 'pending' | 'completed';
  assignedCount: number;
  requiredCount: number;
  totalWorkHours: number;
  laborCost: number;
  assignedEmployees: {
    id: number;
    name: string;
    avatar?: string;
  }[];
}

// ==================== 成本分析相关类型 ====================

/** 劳动成本汇总 */
export interface LaborCostSummary {
  period: string;
  totalCost: number;
  totalWorkMinutes: number;
  avgHourlyRate: number;
  participatingBatches: number;
  participatingEmployees: number;
}

/** 部门成本分布 */
export interface DepartmentCostDistribution {
  departmentId: string;
  departmentName: string;
  cost: number;
  percentage: number;
  color: string;
}

/** 员工工时排行 */
export interface WorkerHoursRank {
  rank: number;
  userId: number;
  userName: string;
  userAvatar?: string;
  department?: string;
  totalMinutes: number;
  totalHours: number;
}

// ==================== 绩效分析相关类型 ====================

/** 绩效等级 */
export type PerformanceGrade = 'A' | 'B' | 'C' | 'D' | 'E';

/** 绩效统计 */
export interface PerformanceStats {
  avgScore: number;
  excellentCount: number;
  needAttentionCount: number;
  needImprovementCount: number;
  gradeDistribution: {
    grade: PerformanceGrade;
    count: number;
    percentage: number;
  }[];
}

/** 员工绩效 */
export interface EmployeePerformance {
  userId: number;
  userName: string;
  userAvatar?: string;
  department?: string;
  position?: string;
  score: number;
  grade: PerformanceGrade;
  efficiency?: number;
  quality?: number;
  attendance?: number;
}

/** 绩效等级配置 */
export const PERFORMANCE_GRADE_CONFIG: Record<PerformanceGrade, {
  label: string;
  color: string;
  bgColor: string;
  minScore: number;
}> = {
  A: { label: '优秀', color: '#52c41a', bgColor: '#f6ffed', minScore: 90 },
  B: { label: '良好', color: '#1890ff', bgColor: '#e6f7ff', minScore: 80 },
  C: { label: '合格', color: '#faad14', bgColor: '#fffbe6', minScore: 70 },
  D: { label: '需关注', color: '#fa8c16', bgColor: '#fff7e6', minScore: 60 },
  E: { label: '待改进', color: '#ff4d4f', bgColor: '#fff2f0', minScore: 0 },
};

// ==================== 白名单相关类型 ====================

/** 白名单状态 */
export type WhitelistStatus = 'pending' | 'active' | 'disabled' | 'expired';

/** 白名单条目 */
export interface WhitelistEntry {
  id: string;
  phoneNumber: string;
  name?: string;
  accountCreated?: boolean;
  accountActive?: boolean;
  maskedPhone?: string;
  presetRole?: string;
  presetRoleName?: string;
  status: WhitelistStatus;
  addedBy?: number;
  addedByName?: string;
  addedAt: string;
  activatedAt?: string;
  activatedUserId?: number;
  activatedUserName?: string;
  disabledAt?: string;
  disabledReason?: string;
  expiresAt?: string;
}

/** 白名单状态配置 */
export const WHITELIST_STATUS_CONFIG: Record<WhitelistStatus, {
  label: string;
  color: string;
  bgColor: string;
}> = {
  pending: { label: '待激活', color: '#fa8c16', bgColor: '#fff7e6' },
  active: { label: '已激活', color: '#52c41a', bgColor: '#f6ffed' },
  disabled: { label: '已禁用', color: '#8c8c8c', bgColor: '#fafafa' },
  expired: { label: '已过期', color: '#ff4d4f', bgColor: '#fff2f0' },
};

// ==================== 快捷操作类型 ====================

/** 快捷操作项 */
export interface HRQuickAction {
  id: string;
  title: string;
  icon: string;
  color: string;
  route: keyof HRHomeStackParamList | keyof HRStaffStackParamList;
  badge?: number;
}

/** HR 快捷操作列表 */
export const HR_QUICK_ACTIONS: HRQuickAction[] = [
  { id: 'add-staff', title: '添加员工', icon: 'account-plus', color: '#1890ff', route: 'StaffAdd' },
  { id: 'batch-assign', title: '批次分配', icon: 'clipboard-account', color: '#722ed1', route: 'BatchAssignment' },
  { id: 'labor-cost', title: '工时成本', icon: 'currency-cny', color: '#52c41a', route: 'LaborCost' },
  { id: 'performance', title: '绩效分析', icon: 'chart-line', color: '#fa8c16', route: 'Performance' },
  { id: 'schedule', title: '排班管理', icon: 'calendar-clock', color: '#13c2c2', route: 'WorkSchedule' },
  { id: 'new-hires', title: '本月入职', icon: 'account-star', color: '#eb2f96', route: 'NewHires' },
];

// ==================== 主题颜色 ====================

/** HR 主题颜色 */
export const HR_THEME = {
  primary: '#667eea',        // HR 紫色
  secondary: '#764ba2',      // 深紫色
  accent: '#f5576c',         // 粉红色
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
  gradientStart: '#667eea',
  gradientEnd: '#764ba2',
} as const;

// ==================== 角色选项 ====================

/** 角色选项 - 用于白名单添加时选择预设角色 */
export const ROLE_OPTIONS = [
  { label: '操作员', value: 'operator' },
  { label: '报工员', value: 'yield_operator' },
  { label: '质检员', value: 'quality_inspector' },
  { label: '车间主任', value: 'workshop_supervisor' },
  { label: '仓储主管', value: 'warehouse_manager' },
  { label: '仓库员', value: 'warehouse_worker' },
  { label: '调度员', value: 'dispatcher' },
  { label: '质量经理', value: 'quality_manager' },
  { label: 'HR管理员', value: 'hr_admin' },
  { label: '工厂总监', value: 'factory_super_admin' },
] as const;

export type RoleOptionValue = typeof ROLE_OPTIONS[number]['value'];

// ==================== 部门类型 (简化版) ====================

/** 部门简化类型 - 用于选择器和列表 */
export interface Department {
  id: string | number;
  name: string;
  code?: string;
  employeeCount?: number;
  memberCount?: number;
  managerName?: string;
  managerId?: number;
  description?: string;
  isActive?: boolean;
  status?: 'active' | 'inactive';
}
