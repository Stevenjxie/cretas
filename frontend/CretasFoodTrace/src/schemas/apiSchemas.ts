/**
 * API响应 Zod Schema 定义
 * 用于运行时验证API响应数据的正确性
 */

import { z } from 'zod';

// ========== 基础Schema ==========

/**
 * 标准API响应包装
 */
export const ApiResponseSchema = <T extends z.ZodTypeAny>(dataSchema: T) =>
  z.object({
    success: z.boolean(),
    code: z.number().optional(),
    message: z.string().optional(),
    data: dataSchema.optional(),
    timestamp: z.string().optional(),
  });

/**
 * 分页响应Schema
 */
export const PageResponseSchema = <T extends z.ZodTypeAny>(contentSchema: T) =>
  z.object({
    content: z.array(contentSchema),
    totalElements: z.number(),
    totalPages: z.number(),
    size: z.number(),
    number: z.number(),
  });

// ========== 用户相关Schema ==========

/**
 * 用户DTO Schema
 */
export const UserDTOSchema = z.object({
  id: z.number(),
  username: z.string(),
  email: z.string().optional(),
  realName: z.string(),
  phone: z.string().optional(),
  role: z.string(),
  department: z.string().optional(),
  position: z.string().optional(),
  isActive: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string().optional(),
});

/**
 * 员工DTO Schema
 */
export const EmployeeDTOSchema = z.object({
  id: z.number(),
  username: z.string(),
  fullName: z.string(),
  department: z.string().optional(),
  roleCode: z.string().optional(),
});

// ========== 生产相关Schema ==========

/**
 * 产品类型Schema
 */
export const ProductTypeSchema = z.object({
  id: z.string(),
  name: z.string(),
  baseProductName: z.string().optional(), // T123: 产品基础名 (名称分离), RN 优先显示, 无则 fallback 到 name
  category: z.string().optional(),
  unit: z.string(),
  description: z.string().optional(),
  specifications: z.string().optional(),
  isActive: z.boolean().optional(),
});

/**
 * 原材料类型Schema
 */
export const MaterialTypeSchema = z.object({
  id: z.string(),
  name: z.string(),
  category: z.string().optional(),
  unit: z.string(),
  description: z.string().optional(),
});

/**
 * 工种Schema
 */
export const WorkTypeSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().optional(),
  hourlyRate: z.number().optional(),
  isActive: z.boolean().optional(),
});

/**
 * 转化率Schema
 */
export const ConversionRateSchema = z.object({
  id: z.string(),
  productTypeId: z.string(),
  productTypeName: z.string().optional(),
  materialTypeId: z.string(),
  materialTypeName: z.string().optional(),
  conversionRate: z.number(),
  unit: z.string().optional(),
  isActive: z.boolean().optional(),
});

// ========== 客户供应商相关Schema ==========

/**
 * 供应商Schema
 */
export const SupplierSchema = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  contactPerson: z.string().optional(),
  phone: z.string().optional(),
  email: z.string().optional(),
  address: z.string().optional(),
  category: z.string().optional(),
  isActive: z.boolean().optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
});

/**
 * 客户Schema
 */
export const CustomerSchema = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  contactPerson: z.string().optional(),
  phone: z.string().optional(),
  email: z.string().optional(),
  address: z.string().optional(),
  category: z.string().optional(),
  isActive: z.boolean().optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
});

// ========== 白名单Schema ==========

/**
 * 白名单DTO Schema
 */
export const WhitelistDTOSchema = z.object({
  id: z.number(),
  phoneNumber: z.string(),
  realName: z.string().optional(),
  department: z.string().optional(),
  position: z.string().optional(),
  factoryId: z.string().optional(),
  status: z.enum(['active', 'used', 'expired']),
  expiresAt: z.string().optional(),
  createdAt: z.string(),
  createdBy: z.string().optional(),
  usedAt: z.string().optional(),
  notes: z.string().optional(),
});

// ========== 考勤相关Schema ==========

/**
 * 考勤记录Schema
 */
export const AttendanceRecordSchema = z.object({
  id: z.string(),
  userId: z.number(),
  userName: z.string().optional(),
  date: z.string(),
  checkInTime: z.string().optional(),
  checkOutTime: z.string().optional(),
  workHours: z.number().optional(),
  status: z.string().optional(),
  location: z.string().optional(),
  notes: z.string().optional(),
});

/**
 * 工时统计Schema
 */
export const TimeStatisticsSchema = z.object({
  userId: z.number().optional(),
  userName: z.string().optional(),
  totalHours: z.number(),
  workDays: z.number(),
  averageHours: z.number(),
  period: z.string().optional(),
});

// ========== 生产计划Schema ==========

/**
 * 生产计划Schema
 */
export const ProductionPlanSchema = z.object({
  id: z.string(),
  planNumber: z.string(),
  productTypeId: z.string(),
  productTypeName: z.string().optional(),
  targetQuantity: z.number(),
  startDate: z.string(),
  endDate: z.string(),
  status: z.enum(['pending', 'in_progress', 'completed', 'cancelled']),
  priority: z.enum(['low', 'medium', 'high']).optional(),
  notes: z.string().optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
});

// ========== 仪表板统计Schema ==========

/**
 * 仪表板统计Schema
 */
export const DashboardStatsSchema = z.object({
  production: z.object({
    total: z.number(),
    todayCount: z.number(),
    weekCount: z.number(),
    monthCount: z.number(),
    trend: z.string().optional(),
  }).optional(),
  quality: z.object({
    passRate: z.number(),
    inspectionCount: z.number(),
    failedCount: z.number(),
    trend: z.string().optional(),
  }).optional(),
  attendance: z.object({
    presentCount: z.number(),
    totalEmployees: z.number(),
    attendanceRate: z.number(),
    avgWorkHours: z.number(),
  }).optional(),
  inventory: z.object({
    totalValue: z.number(),
    lowStockCount: z.number(),
    expiringCount: z.number(),
  }).optional(),
});

// ========== 工厂设置Schema ==========

/**
 * 工厂设置Schema
 */
export const FactorySettingsSchema = z.object({
  factoryId: z.string(),
  factoryName: z.string().optional(),
  settings: z.object({
    workHours: z.object({
      start: z.string(),
      end: z.string(),
    }).optional(),
    timezone: z.string().optional(),
    currency: z.string().optional(),
    language: z.string().optional(),
  }).optional(),
  updatedAt: z.string().optional(),
});

// ========== AI分析相关Schema ==========

/**
 * AI分析响应Schema
 */
export const AIAnalysisResponseSchema = z.object({
  analysis: z.string(),
  confidence: z.number().optional(),
  suggestions: z.array(z.string()).optional(),
  estimatedCost: z.number().optional(),
  timestamp: z.string().optional(),
});

// ========== 导出类型 ==========

export type UserDTO = z.infer<typeof UserDTOSchema>;
export type EmployeeDTO = z.infer<typeof EmployeeDTOSchema>;
export type ProductType = z.infer<typeof ProductTypeSchema>;
export type MaterialType = z.infer<typeof MaterialTypeSchema>;
export type WorkType = z.infer<typeof WorkTypeSchema>;
export type ConversionRate = z.infer<typeof ConversionRateSchema>;
export type Supplier = z.infer<typeof SupplierSchema>;
export type Customer = z.infer<typeof CustomerSchema>;
export type WhitelistDTO = z.infer<typeof WhitelistDTOSchema>;
export type AttendanceRecord = z.infer<typeof AttendanceRecordSchema>;
export type TimeStatistics = z.infer<typeof TimeStatisticsSchema>;
export type ProductionPlan = z.infer<typeof ProductionPlanSchema>;
export type DashboardStats = z.infer<typeof DashboardStatsSchema>;
export type FactorySettings = z.infer<typeof FactorySettingsSchema>;
export type AIAnalysisResponse = z.infer<typeof AIAnalysisResponseSchema>;
