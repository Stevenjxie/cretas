import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 产品类型管理API客户端
 * 总计16个API - 路径：/api/mobile/{factoryId}/product-types/*
 *
 * Phase 5 更新: 新增 SKU 配置相关接口
 */

/**
 * 后端统一响应格式
 */
interface ApiResponse<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

// ==================== Phase 5: SKU Configuration Types ====================

/**
 * 加工步骤配置
 */
export interface ProcessingStep {
  /** 加工环节类型 (ProcessingStageType enum value) */
  stageType: string;
  /** 步骤顺序 (1-based) */
  orderIndex: number;
  /** 所需技能等级 (1-5) */
  requiredSkillLevel: number;
  /** 预估时间 (分钟) */
  estimatedMinutes: number;
  /** 备注 */
  notes?: string;
}

/**
 * 技能要求配置
 */
export interface SkillRequirement {
  /** 最低技能等级 (1-5) */
  minLevel: number;
  /** 建议技能等级 (1-5) */
  preferredLevel: number;
  /** 特殊技能标签 */
  specialSkills: string[];
}

/**
 * 加工环节类型选项 (用于下拉选择)
 */
export interface ProcessingStageOption {
  /** 枚举值 (如 'SLICING') */
  value: string;
  /** 显示名称 (如 '切片') */
  label: string;
  /** 描述 */
  description: string;
}

/**
 * 调度信息 (供调度系统使用)
 */
export interface ProductSchedulingInfo {
  productTypeId: string;
  productCode: string;
  productName: string;
  category?: string;
  workHours?: number;
  productionTimeMinutes?: number;
  complexityScore?: number;
  processingSteps?: ProcessingStep[];
  stepCount?: number;
  skillRequirements?: SkillRequirement;
  equipmentIds?: string[];
  qualityCheckIds?: string[];
}

// ==================== End Phase 5 Types ====================

// ==================== Custom Schema Configuration Types ====================

/**
 * 表单类型枚举
 */
export type FormSchemaType = 'MATERIAL_BATCH' | 'QUALITY_CHECK' | 'PROCESSING_BATCH';

/**
 * 自定义字段类型
 */
export type CustomFieldType = 'string' | 'number' | 'boolean' | 'date' | 'select' | 'multiSelect';

/**
 * 自定义字段配置
 */
export interface CustomFieldConfig {
  /** 字段类型 */
  type: CustomFieldType;
  /** 字段标题 */
  title: string;
  /** 字段描述 */
  description?: string;
  /** 是否必填 */
  required?: boolean;
  /** 默认值 */
  default?: string | number | boolean;
  /** 枚举选项 (用于 select/multiSelect 类型) */
  enum?: string[];
  /** 最小值 (用于 number 类型) */
  minimum?: number;
  /** 最大值 (用于 number 类型) */
  maximum?: number;
  /** 最小长度 (用于 string 类型) */
  minLength?: number;
  /** 最大长度 (用于 string 类型) */
  maxLength?: number;
}

/**
 * 单个表单类型的 Schema 配置
 */
export interface FormSchemaConfig {
  /** 自定义属性字段 */
  properties: Record<string, CustomFieldConfig>;
  /** 必填字段列表 */
  required?: string[];
}

/**
 * 产品类型的自定义 Schema 覆盖配置
 * Key 为 FormSchemaType, Value 为该表单类型的配置
 */
export type CustomSchemaOverrides = Partial<Record<FormSchemaType, FormSchemaConfig>>;

// ==================== End Custom Schema Configuration Types ====================

export interface ProductType {
  id: string;
  factoryId: string;
  productCode: string;
  name: string;
  category?: string;
  description?: string;
  unitPrice?: number;
  unit?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt?: string;

  // Phase 5: SKU Configuration Fields
  /** 标准工时 */
  workHours?: number;
  /** 加工步骤配置 */
  processingSteps?: ProcessingStep[];
  /** 技能要求配置 */
  skillRequirements?: SkillRequirement;
  /** 关联设备ID列表 */
  equipmentIds?: string[];
  /** 关联质检项ID列表 */
  qualityCheckIds?: string[];
  /** 复杂度评分 (1-5) */
  complexityScore?: number;
  /** 生产时间 (分钟) */
  productionTimeMinutes?: number;
  /** 保质期 (天) */
  shelfLifeDays?: number;
  /** 包装规格 */
  packageSpec?: string;
  level1Unit?: string;
  boxConversionCoefficient?: number;
  gramsPerUnit?: number;
  /** 自定义 Schema 覆盖配置 (JSON 格式，按 entityType 分组) */
  customSchemaOverrides?: Record<string, unknown>;
}

export interface ProductPackagingSpec {
  id: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor: number;
  defaultSpec: boolean;
  active: boolean;
  sortOrder: number;
  version?: number;
}

/**
 * 创建产品类型请求参数
 */
export interface CreateProductTypeRequest {
  name: string;
  productCode?: string;  // 可选，由后端自动生成或用户提供
  category?: string;
  description?: string;
  unitPrice?: number;
  unit?: string;
}

/**
 * 更新产品类型请求参数（所有字段可选）
 */
export interface UpdateProductTypeRequest extends Partial<CreateProductTypeRequest> {
  isActive?: boolean;
}

class ProductTypeApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/product-types`;
  }

  async getProductTypes(params?: { factoryId?: string; isActive?: boolean; limit?: number; page?: number }): Promise<{ data: ProductType[] }> {
    const { factoryId, ...query } = params || {};
    // apiClient拦截器已统一返回data
    const apiResponse = await apiClient.get<any>(this.getPath(factoryId), { params: query });

    // 处理分页响应：apiResponse.data.content
    if (apiResponse.data?.content) {
      return { data: apiResponse.data.content };
    }

    // 兼容直接返回数组的情况
    if (Array.isArray(apiResponse.data)) {
      return { data: apiResponse.data };
    }

    // 防御性编程：兼容旧格式
    if (Array.isArray(apiResponse)) {
      return { data: apiResponse };
    }

    console.warn('[ProductTypeAPI] 未预期的响应格式:', apiResponse);
    return { data: [] };
  }

  async createProductType(data: CreateProductTypeRequest, factoryId?: string): Promise<ProductType> {
    const response = await apiClient.post<ApiResponse<ProductType>>(this.getPath(factoryId), data);
    return response.data;
  }

  async getProductTypeById(id: string, factoryId?: string): Promise<ProductType> {
    const response = await apiClient.get<ApiResponse<ProductType>>(`${this.getPath(factoryId)}/${id}`);
    return response.data;
  }

  async updateProductType(id: string, data: UpdateProductTypeRequest, factoryId?: string): Promise<ProductType> {
    const response = await apiClient.put<ApiResponse<ProductType>>(`${this.getPath(factoryId)}/${id}`, data);
    return response.data;
  }

  async deleteProductType(id: string, factoryId?: string): Promise<void> {
    return await apiClient.delete(`${this.getPath(factoryId)}/${id}`);
  }

  async getActiveProductTypes(factoryId?: string): Promise<ProductType[]> {
    const response = await apiClient.get<ApiResponse<ProductType[]>>(`${this.getPath(factoryId)}/active`);
    return response.data || [];
  }

  async getPackagingSpecs(id: string, factoryId?: string): Promise<ProductPackagingSpec[]> {
    const response = await apiClient.get<ApiResponse<ProductPackagingSpec[]>>(
      `${this.getPath(factoryId)}/${id}/packaging-specs`
    );
    return response.data || [];
  }

  async getProductTypesByCategory(category: string, factoryId?: string): Promise<ProductType[]> {
    const response = await apiClient.get<ApiResponse<ProductType[]>>(`${this.getPath(factoryId)}/category/${category}`);
    return response.data || [];
  }

  async searchProductTypes(keyword: string, factoryId?: string): Promise<ProductType[]> {
    const response = await apiClient.get<ApiResponse<ProductType[]>>(`${this.getPath(factoryId)}/search`, { params: { keyword } });
    return response.data || [];
  }

  async checkProductCodeExists(productCode: string, factoryId?: string): Promise<{ exists: boolean }> {
    const response = await apiClient.get<ApiResponse<{ exists: boolean }>>(`${this.getPath(factoryId)}/check-code`, { params: { productCode } });
    return response.data;
  }

  async getCategories(factoryId?: string): Promise<string[]> {
    const response = await apiClient.get<ApiResponse<string[]>>(`${this.getPath(factoryId)}/categories`);
    return response.data || [];
  }

  async initDefaults(factoryId?: string): Promise<void> {
    return await apiClient.post(`${this.getPath(factoryId)}/init-defaults`);
  }

  async batchUpdateStatus(ids: string[], isActive: boolean, factoryId?: string): Promise<void> {
    return await apiClient.put(`${this.getPath(factoryId)}/batch/status`, { ids, isActive });
  }

  // ==================== Phase 5: SKU Configuration Methods ====================

  /**
   * 获取所有可用的加工环节类型
   * 用于前端下拉选择
   */
  async getProcessingStages(factoryId?: string): Promise<ProcessingStageOption[]> {
    const response = await apiClient.get<ApiResponse<ProcessingStageOption[]>>(`${this.getPath(factoryId)}/processing-stages`);
    return response.data || [];
  }

  /**
   * 更新产品类型的 SKU 配置
   * 仅更新调度相关字段
   */
  async updateProductTypeConfig(
    id: string,
    config: {
      workHours?: number;
      processingSteps?: ProcessingStep[];
      skillRequirements?: SkillRequirement;
      equipmentIds?: string[];
      qualityCheckIds?: string[];
      complexityScore?: number;
    },
    factoryId?: string
  ): Promise<ProductType> {
    const response = await apiClient.put<ApiResponse<ProductType>>(`${this.getPath(factoryId)}/${id}/config`, config);
    return response.data;
  }

  /**
   * 获取产品类型的调度信息
   * 返回调度系统所需的关键字段
   */
  async getSchedulingInfo(id: string, factoryId?: string): Promise<ProductSchedulingInfo> {
    const response = await apiClient.get<ApiResponse<ProductSchedulingInfo>>(`${this.getPath(factoryId)}/${id}/scheduling-info`);
    return response.data;
  }

  /**
   * 批量获取产品类型的调度信息
   */
  async getSchedulingInfoBatch(productTypeIds: string[], factoryId?: string): Promise<ProductSchedulingInfo[]> {
    const response = await apiClient.post<ApiResponse<ProductSchedulingInfo[]>>(`${this.getPath(factoryId)}/scheduling-info/batch`, productTypeIds);
    return response.data || [];
  }

  // ==================== Custom Schema Configuration Methods ====================

  /**
   * 获取产品类型的自定义表单配置
   * @param id 产品类型ID
   * @param factoryId 工厂ID (可选)
   * @returns 自定义表单配置，如果未配置则返回 null
   */
  async getCustomSchemaOverrides(id: string, factoryId?: string): Promise<CustomSchemaOverrides | null> {
    const productType = await this.getProductTypeById(id, factoryId);
    if (productType.customSchemaOverrides) {
      // 如果已经是对象则直接返回
      if (typeof productType.customSchemaOverrides === 'object') {
        return productType.customSchemaOverrides as CustomSchemaOverrides;
      }
      // 如果是字符串则尝试解析
      if (typeof productType.customSchemaOverrides === 'string') {
        try {
          return JSON.parse(productType.customSchemaOverrides);
        } catch {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * 更新产品类型的自定义表单配置
   * @param id 产品类型ID
   * @param schemaOverrides 自定义表单配置
   * @param factoryId 工厂ID (可选)
   */
  async updateCustomSchemaOverrides(
    id: string,
    schemaOverrides: CustomSchemaOverrides,
    factoryId?: string
  ): Promise<ProductType> {
    const response = await apiClient.put<ApiResponse<ProductType>>(`${this.getPath(factoryId)}/${id}`, {
      customSchemaOverrides: JSON.stringify(schemaOverrides),
    });
    return response.data;
  }

  /**
   * 清除产品类型的自定义表单配置
   * @param id 产品类型ID
   * @param factoryId 工厂ID (可选)
   */
  async clearCustomSchemaOverrides(id: string, factoryId?: string): Promise<ProductType> {
    const response = await apiClient.put<ApiResponse<ProductType>>(`${this.getPath(factoryId)}/${id}`, {
      customSchemaOverrides: null,
    });
    return response.data;
  }

  // ==================== End Custom Schema Configuration Methods ====================
}

export const productTypeApiClient = new ProductTypeApiClient();
