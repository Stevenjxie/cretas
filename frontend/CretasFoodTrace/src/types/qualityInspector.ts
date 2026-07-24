/**
 * 质检模块类型定义
 * Quality Inspector Module Types
 */

// ============================================
// 评分项类型
// ============================================

export interface ScoreItem {
  score: number;          // 0-20分
  notes: string[];        // 备注
}

// ============================================
// 质检表单数据
// ============================================

export interface QualityInspectionForm {
  batchId: string;
  sampleSize: number;           // 抽样数量

  // 评分项 (每项0-20分)
  appearance: ScoreItem;        // 外观
  smell: ScoreItem;             // 气味
  specification: ScoreItem;     // 规格
  weight: ScoreItem;            // 重量
  packaging: ScoreItem;         // 包装

  // 汇总
  totalScore: number;           // 0-100
  grade: QualityGrade;
  passed: boolean;

  // 照片证据
  photos: string[];             // Base64 或 URI

  // 元信息
  inspectorId: number;
  inspectedAt: string;
  remarks?: string;
}

// ============================================
// 质检等级
// ============================================

export type QualityGrade = 'A' | 'B' | 'C' | 'D';

export const GRADE_THRESHOLDS = {
  A: 95,   // ≥95分
  B: 85,   // ≥85分
  C: 70,   // ≥70分
  D: 0,    // <70分
} as const;

export const GRADE_COLORS = {
  A: '#00C853', // 优秀 - 绿
  B: '#2196F3', // 良好 - 蓝
  C: '#FFC107', // 合格 - 黄
  D: '#F44336', // 不合格 - 红
} as const;

export const GRADE_LABELS = {
  A: '优秀',
  B: '良好',
  C: '合格',
  D: '不合格',
} as const;

// ============================================
// 批次信息
// ============================================

export interface QIBatch {
  id: string;
  batchNumber: string;
  productName: string;
  productTypeName?: string;
  quantity: number;
  unit: string;
  status: BatchStatus;
  sourceProcess?: string;         // 来源工序
  createdAt: string;
  factoryId: string;
}

export type BatchStatus =
  | 'pending'      // 待检
  | 'inspecting'   // 检验中
  | 'passed'       // 已通过
  | 'failed'       // 未通过
  | 'rejected';    // 已驳回

export const BATCH_STATUS_LABELS: Record<BatchStatus, string> = {
  pending: '待检验',
  inspecting: '检验中',
  passed: '已通过',
  failed: '未通过',
  rejected: '已驳回',
};

export const BATCH_STATUS_COLORS: Record<BatchStatus, string> = {
  pending: '#FFA500',
  inspecting: '#1976D2',
  passed: '#00C853',
  failed: '#F44336',
  rejected: '#9E9E9E',
};

// ============================================
// 质检记录
// ============================================

export interface QualityRecord {
  id: string;
  batchId: string;
  batchNumber: string;
  productName: string;

  // 评分数据
  sampleSize: number;
  appearance: ScoreItem;
  smell: ScoreItem;
  specification: ScoreItem;
  weight: ScoreItem;
  packaging: ScoreItem;
  totalScore: number;
  grade: QualityGrade;
  passed: boolean;

  // 照片
  photos: string[];

  // 元信息
  inspector: {
    id: number;
    name: string;
  };
  inspectedAt: string;
  remarks?: string;

  // 审核信息
  reviewedBy?: {
    id: number;
    name: string;
  };
  reviewedAt?: string;
  reviewStatus?: 'pending' | 'approved' | 'rejected';
  reviewNotes?: string;
}

// ============================================
// 质检统计
// ============================================

export interface QualityStatistics {
  today: {
    total: number;
    passed: number;
    failed: number;
    pending: number;
    passRate: number;
    inspecting?: number;
    avgInspectionMinutes?: number;
  };
  week: {
    total: number;
    passed: number;
    failed: number;
    passRate: number;
  };
  month: {
    total: number;
    passed: number;
    failed: number;
    passRate: number;
  };
  gradeDistribution: {
    A: number;
    B: number;
    C: number;
    D: number;
  };
}

// ============================================
// 质检趋势
// ============================================

export interface QualityTrendItem {
  date: string;
  total: number;
  passed: number;
  failed: number;
  passRate: number;
  avgScore: number;
}

export interface QualityTrendData {
  period: 'week' | 'month' | 'quarter';
  items: QualityTrendItem[];
}

// ============================================
// 考勤相关
// ============================================

export interface ClockRecord {
  id: string;
  userId: number;
  date: string;
  clockInTime?: string;
  clockOutTime?: string;
  status: ClockStatus;
  location?: {
    latitude: number;
    longitude: number;
    address?: string;
  };
  notes?: string;
}

export type ClockStatus =
  | 'not_clocked'    // 未打卡
  | 'clocked_in'     // 已上班
  | 'clocked_out'    // 已下班
  | 'late'           // 迟到
  | 'early_leave'    // 早退
  | 'absent';        // 缺勤

export const CLOCK_STATUS_LABELS: Record<ClockStatus, string> = {
  not_clocked: '未打卡',
  clocked_in: '已上班',
  clocked_out: '已下班',
  late: '迟到',
  early_leave: '早退',
  absent: '缺勤',
};

// ============================================
// 通知相关
// ============================================

export interface QINotification {
  id: string;
  type: NotificationType;
  title: string;
  content: string;
  read: boolean;
  createdAt: string;
  data?: {
    batchId?: string;
    recordId?: string;
  };
}

export type NotificationType =
  | 'new_batch'        // 新批次待检
  | 'review_result'    // 审核结果
  | 'urgent'           // 紧急通知
  | 'system';          // 系统消息

// ============================================
// API 响应类型
// ============================================

export interface QIApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

export interface QIPagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ============================================
// 导航参数类型
// ============================================

export type QualityInspectorStackParamList = {
  // Tab 根页面 (support nested navigation via { screen, params })
  QIHomeTab: { screen?: string; params?: Record<string, unknown> } | undefined;
  QIInspectTab: { screen?: string; params?: Record<string, unknown> } | undefined;
  QIRecordsTab: { screen?: string; params?: Record<string, unknown> } | undefined;
  QIAnalysisTab: { screen?: string; params?: Record<string, unknown> } | undefined;
  QIProfileTab: { screen?: string; params?: Record<string, unknown> } | undefined;

  // Stack 页面
  QIHome: undefined;
  QINotifications: undefined;
  QIInspectList: undefined;
  QIBatchSelect: { type?: string };
  QIScan: undefined;
  QIForm: { batchId: string; batchNumber: string };
  QIVoice: { batchId: string; batchNumber: string };
  QIVoiceResult: {
    batchId: string;
    batchNumber: string;
    recordId?: string;      // 已有记录ID（查看/编辑模式）
    audioUri?: string;      // 新录音文件URI
  };
  QICamera: { batchId: string; batchNumber: string };
  QILabelQcCreate: undefined;
  QILabelQcSubmitted: {
    taskId: string;
    skuCode: string;
    skuName: string;
    batchNumber: string;
    productionDate: string;
  };
  QIResult: { recordId: string; passed: boolean };
  QIRecords: undefined;
  QIRecordDetail: { recordId: string };
  QIAnalysis: undefined;
  QITrend: undefined;
  QIReport: undefined;
  QIProfile: undefined;
  QISettings: undefined;
  QIClockIn: undefined;
};

// ============================================
// 样式常量
// ============================================

export const QI_COLORS = {
  primary: '#00A86B',      // 质检绿
  secondary: '#1976D2',    // 辅助蓝
  warning: '#FFA500',      // 警告橙
  danger: '#DC3545',       // 危险红
  success: '#00C853',      // 成功绿
  background: '#F5F7FA',   // 背景灰
  card: '#FFFFFF',         // 卡片白
  text: '#333333',         // 主文字
  textSecondary: '#666666',// 次要文字
  border: '#E0E0E0',       // 边框
  disabled: '#9E9E9E',     // 禁用
} as const;

// ============================================
// 工具函数
// ============================================

/**
 * 根据总分计算等级
 */
export function calculateGrade(totalScore: number): QualityGrade {
  if (totalScore >= GRADE_THRESHOLDS.A) return 'A';
  if (totalScore >= GRADE_THRESHOLDS.B) return 'B';
  if (totalScore >= GRADE_THRESHOLDS.C) return 'C';
  return 'D';
}

/**
 * 判断是否通过
 */
export function isPassed(grade: QualityGrade): boolean {
  return grade !== 'D';
}

/**
 * 计算总分
 */
export function calculateTotalScore(form: Partial<QualityInspectionForm>): number {
  const scores = [
    form.appearance?.score ?? 0,
    form.smell?.score ?? 0,
    form.specification?.score ?? 0,
    form.weight?.score ?? 0,
    form.packaging?.score ?? 0,
  ];
  return scores.reduce((sum, score) => sum + score, 0);
}

/**
 * 格式化日期时间
 */
export function formatDateTime(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 格式化日期
 */
export function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
}
