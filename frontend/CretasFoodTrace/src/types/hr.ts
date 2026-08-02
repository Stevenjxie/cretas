/**
 * HR管理模块类型定义
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-27
 */

// ========== Dashboard 相关类型 ==========

/**
 * HR Dashboard 统计卡片数据
 */
export interface HRDashboardStats {
  /** 今日在岗人数 */
  todayOnSite: number;
  /** 总员工数 */
  totalStaff: number;
  /** 出勤率 (百分比) */
  attendanceRate: number;
  /** 迟到人数 */
  lateCount: number;
  /** 白名单待激活数量; null = 统计接口失败或无权限, 展示层必须显示不可用而不是 0 (APP-RBAC-003) */
  whitelistPending: number | null;
  /** 本月入职人数 */
  thisMonthNewHires: number;
  /** 与上月入职人数对比 (+/- 数字) */
  newHiresChange?: number;
}

/**
 * 统计卡片类型
 */
export type StatCardType = 'attendance' | 'late' | 'whitelist' | 'newHires';

/**
 * 统计卡片配置
 */
export interface StatCardConfig {
  type: StatCardType;
  title: string;
  icon: string;
  iconColor: string;
  backgroundColor: string;
  getValue: (stats: HRDashboardStats) => string;
  getSubValue?: (stats: HRDashboardStats) => string;
  getTrend?: (stats: HRDashboardStats) => {
    value: string;
    type: 'up' | 'down' | 'normal' | 'warning';
  };
}

// ========== 考勤相关类型 ==========

/**
 * 考勤异常类型
 */
export type AttendanceAnomalyType =
  | 'LATE'           // 迟到
  | 'ABSENT'         // 缺勤
  | 'EARLY_LEAVE'    // 早退
  | 'NO_CLOCK_IN'    // 未打上班卡
  | 'NO_CLOCK_OUT';  // 未打下班卡

/**
 * 考勤异常类型显示映射
 */
export const ANOMALY_TYPE_DISPLAY: Record<AttendanceAnomalyType, string> = {
  LATE: '迟到',
  ABSENT: '缺勤',
  EARLY_LEAVE: '早退',
  NO_CLOCK_IN: '未打卡',
  NO_CLOCK_OUT: '未打卡',
};

/**
 * 考勤异常记录
 */
export interface AttendanceAnomaly {
  id: number;
  userId: number;
  userName: string;
  department?: string;
  position?: string;
  anomalyType: AttendanceAnomalyType;
  anomalyTypeDisplay: string;
  details?: string;
  date: string;
  expectedTime?: string;
  actualTime?: string;
}

// ========== 待处理事项类型 ==========

/**
 * 待处理事项类型
 */
export type TodoItemType =
  | 'WHITELIST_PENDING'   // 白名单待激活
  | 'ATTENDANCE_ANOMALY'; // 考勤异常

/**
 * 待处理事项
 */
export interface HRTodoItem {
  id: string;
  type: TodoItemType;
  title: string;
  description: string;
  count?: number;
  createdAt?: string;
  priority: 'high' | 'medium' | 'low';
}

// ========== 用户/员工相关类型 ==========

/**
 * 员工基础信息 (列表项)
 */
export interface EmployeeListItem {
  id: number;
  username: string;
  realName: string;
  phone?: string;
  department?: string;
  position?: string;
  role: string;
  isActive: boolean;
  createdAt: string;
}

/**
 * 员工角色
 */
export type EmployeeRole =
  | 'factory_super_admin'
  | 'factory_admin'
  | 'department_admin'
  | 'production_supervisor'
  | 'quality_inspector'
  | 'factory_operator'
  | 'permission_admin';  // HR角色

/**
 * 员工角色显示名称
 */
export const ROLE_DISPLAY_NAME: Record<EmployeeRole, string> = {
  factory_super_admin: '工厂超级管理员',
  factory_admin: '工厂管理员',
  department_admin: '部门管理员',
  production_supervisor: '生产主管',
  quality_inspector: '质检员',
  factory_operator: '操作员',
  permission_admin: 'HR管理员',
};

// ========== 白名单相关类型 ==========

/**
 * 白名单状态
 */
export type WhitelistStatus =
  | 'PENDING'      // 待激活
  | 'ACTIVE'       // 已激活
  | 'DISABLED'     // 已禁用
  | 'EXPIRED'      // 已过期
  | 'LIMIT_REACHED'// 达到使用上限
  | 'DELETED';     // 已删除

/**
 * 白名单状态显示配置
 */
export const WHITELIST_STATUS_CONFIG: Record<WhitelistStatus, {
  label: string;
  color: string;
  backgroundColor: string;
}> = {
  PENDING: { label: '待激活', color: '#fa8c16', backgroundColor: '#fff7e6' },
  ACTIVE: { label: '已激活', color: '#52c41a', backgroundColor: '#f6ffed' },
  DISABLED: { label: '已禁用', color: '#ff4d4f', backgroundColor: '#fff1f0' },
  EXPIRED: { label: '已过期', color: '#8c8c8c', backgroundColor: '#fafafa' },
  LIMIT_REACHED: { label: '达到上限', color: '#722ed1', backgroundColor: '#f9f0ff' },
  DELETED: { label: '已删除', color: '#bfbfbf', backgroundColor: '#f5f5f5' },
};

/**
 * 白名单记录
 */
export interface WhitelistEntry {
  id: number;
  phoneNumber: string;
  realName: string;
  role: string;
  department?: string;
  status: WhitelistStatus;
  maxUsageCount?: number;
  usedCount: number;
  expiresAt?: string;
  createdBy: string;
  createdAt: string;
  updatedAt?: string;
}

// ========== 快捷操作类型 ==========

/**
 * 快捷操作项
 */
export interface QuickActionItem {
  id: string;
  label: string;
  icon: string;
  iconColor: string;
  backgroundColor: string;
  route: string;
  params?: Record<string, unknown>;
}

/**
 * HR Dashboard 快捷操作配置
 */
export const HR_QUICK_ACTIONS: QuickActionItem[] = [
  {
    id: 'employee-ai',
    label: '员工分析',
    icon: 'robot',
    iconColor: '#667eea',
    backgroundColor: '#f0f2ff',
    route: 'HREmployeeAI',
  },
  {
    id: 'add-employee',
    label: '添加员工',
    icon: 'account-plus',
    iconColor: '#1890ff',
    backgroundColor: '#e6f7ff',
    route: 'UserCreate',
  },
  {
    id: 'whitelist',
    label: '白名单',
    icon: 'shield-check',
    iconColor: '#52c41a',
    backgroundColor: '#f6ffed',
    route: 'WhitelistManagement',
  },
  {
    id: 'department',
    label: '部门管理',
    icon: 'office-building',
    iconColor: '#fa8c16',
    backgroundColor: '#fff7e6',
    route: 'DepartmentManagement',
  },
];
