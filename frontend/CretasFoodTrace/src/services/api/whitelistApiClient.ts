import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 白名单管理API客户端 - MVP精简版
 * MVP保留：5个核心API
 * 已移除：15个高级功能API（过期管理、使用统计、导入导出等）
 * 路径：/api/mobile/{factoryId}/whitelist/*
 *
 * 业务场景：管理员批量添加允许注册的手机号，员工注册时自动验证
 */

// ========== 类型定义 ==========

export interface WhitelistDTO {
  id: number;
  phoneNumber: string;
  name?: string;
  realName?: string;
  role: string;
  department?: string;
  status: 'PENDING' | 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'LIMIT_REACHED' | 'DELETED';
  maxUsageCount?: number;
  usedCount?: number;
  accountCreated?: boolean;
  accountActive?: boolean;
  registeredUserId?: number;
  registeredUsername?: string;
  expiresAt?: string;
  createdBy: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateWhitelistRequest {
  phoneNumber: string;
  realName: string;
  role: string;
  department?: string;
  maxUsageCount?: number;
  expiresAt?: string;
}

export interface BatchAddRequest {
  entries: Array<{
    phoneNumber: string;
    name?: string;
    position?: string;
  }>;
  department?: string;
  role?: string;
  notes?: string;
  expiresAt?: string;
}

export interface BatchResult {
  successCount: number;
  failedCount: number;
  successPhones: string[];
  failedEntries: Array<{ phoneNumber: string; reason: string }>;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

class WhitelistApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/whitelist`;
  }

  /**
   * 1. 添加单个白名单
   * POST /api/{factoryId}/whitelist
   */
  async addWhitelist(params: {
    phoneNumber: string;
    presetRole: string;
    presetRoleName?: string;
    realName?: string;
    department?: string;
    notes?: string;
    status?: string;
    factoryId?: string;
  }): Promise<{ success: boolean; message?: string; data?: WhitelistDTO }> {
    const { factoryId, phoneNumber, presetRole, realName, department, notes } = params;
    try {
      const response = await apiClient.post<{
        code: number;
        data: BatchResult;
        message: string;
        success: boolean;
      }>(this.getPath(factoryId), {
          phoneNumber,
          name: realName,
          role: presetRole,
          departmentId: department,
          notes,
        });
      const result = response.data;
      const firstFailure = result.failedEntries?.[0]?.reason;
      return {
        success: result.successCount > 0,
        message: result.successCount > 0 ? '邀请已发送' : (firstFailure || '邀请失败'),
      };
    } catch (error) {
      console.error('添加白名单失败:', error);
      throw error;
    }
  }

  /**
   * 兼容批量邀请：后端要求 entries + 公共 role/department 字段。
   */
  createBatchRequest(
    whitelists: CreateWhitelistRequest[],
    role: string,
    department?: string
  ): BatchAddRequest {
    return {
      entries: whitelists.map(item => {
        const name = item.realName.trim();
        return {
          phoneNumber: item.phoneNumber,
          ...(name ? { name } : {}),
        };
      }),
      role,
      department,
    };
  }

  /**
   * 2. 获取白名单列表（分页）
   * GET /api/{factoryId}/whitelist
   */
  async getWhitelist(params?: {
    factoryId?: string;
    status?: string;
    department?: string;
    role?: string;
    keyword?: string;
    page?: number;
    size?: number;
    sortBy?: string;
    sortDirection?: 'ASC' | 'DESC';
  }): Promise<PageResponse<WhitelistDTO>> {
    const { factoryId, ...queryParams } = params || {};
    const response = await apiClient.get<{ code: number; data: PageResponse<WhitelistDTO>; message: string; success: boolean; }>(
      this.getPath(factoryId),
      { params: queryParams }
    );
    return response.data || { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };
  }

  /**
   * 2. 删除白名单
   * DELETE /api/{factoryId}/whitelist/{id}
   */
  async deleteWhitelist(id: number, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.getPath(factoryId)}/${id}`);
  }

  /**
   * 3. 批量添加白名单
   * POST /api/{factoryId}/whitelist/batch
   */
  async batchAddWhitelist(
    request: BatchAddRequest,
    factoryId?: string
  ): Promise<BatchResult> {
    const response = await apiClient.post<{ code: number; data: BatchResult; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/batch`,
      request
    );
    return response.data;
  }

  /**
   * 4. 批量删除白名单
   * DELETE /api/{factoryId}/whitelist/batch
   */
  async batchDeleteWhitelist(
    ids: number[],
    factoryId?: string
  ): Promise<BatchResult> {
    const response = await apiClient.delete<{ code: number; data: BatchResult; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/batch`,
      { data: { ids } }
    );
    return response.data;
  }

  /**
   * 5. 验证手机号是否在白名单中（注册时检查）
   * GET /api/{factoryId}/whitelist/validate/{phoneNumber}
   */
  async validatePhoneNumber(
    phoneNumber: string,
    factoryId?: string
  ): Promise<{
    isValid: boolean;
    whitelist?: WhitelistDTO;
    message?: string;
  }> {
    const response = await apiClient.get<{
      code: number;
      data: { isValid: boolean; whitelist?: WhitelistDTO; message?: string };
      message: string;
      success: boolean;
    }>(
      `${this.getPath(factoryId)}/validate/${encodeURIComponent(phoneNumber)}`
    );
    return response.data;
  }

  // ===== 新增功能 (Phase 3) =====

  /**
   * 6. 获取白名单详情
   * GET /api/{factoryId}/whitelist/{id}
   */
  async getWhitelistById(
    id: number,
    factoryId?: string
  ): Promise<WhitelistDTO> {
    const response = await apiClient.get<{ code: number; data: WhitelistDTO; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/${id}`
    );
    return response.data;
  }

  /**
   * 7. 更新白名单
   * PUT /api/{factoryId}/whitelist/{id}
   */
  async updateWhitelist(
    id: number,
    request: Partial<CreateWhitelistRequest>,
    factoryId?: string
  ): Promise<WhitelistDTO> {
    const response = await apiClient.put<{ code: number; data: WhitelistDTO; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/${id}`,
      request
    );
    return response.data;
  }

  /**
   * 8. 获取白名单统计信息
   * GET /api/{factoryId}/whitelist/stats
   */
  async getWhitelistStats(factoryId?: string): Promise<{
    total: number;
    pending: number;
    active: number;
    disabled: number;
    expired: number;
    limitReached: number;
  }> {
    const response = await apiClient.get<{
      code: number;
      data: {
        totalCount?: number;
        activeCount?: number;
        activeUsersCount?: number;
        disabledCount?: number;
        expiredCount?: number;
        limitReachedCount?: number;
      };
      message: string;
      success: boolean;
    }>(
      `${this.getPath(factoryId)}/stats`
    );
    const data = response.data || {};
    const activeInvitations = data.activeCount || 0;
    const openedAccounts = data.activeUsersCount || 0;
    return {
      total: data.totalCount || 0,
      pending: Math.max(0, activeInvitations - openedAccounts),
      active: openedAccounts,
      disabled: data.disabledCount || 0,
      expired: data.expiredCount || 0,
      limitReached: data.limitReachedCount || 0,
    };
  }

  /**
   * 9. 更新过期状态
   * PUT /api/{factoryId}/whitelist/expired
   */
  async updateExpiredStatus(factoryId?: string): Promise<{ updated: number }> {
    const response = await apiClient.put<{ code: number; data: { updated: number }; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/expired`
    );
    return response.data;
  }

  /**
   * 10. 搜索白名单
   * GET /api/{factoryId}/whitelist/search
   */
  async searchWhitelist(params: {
    keyword: string;
    page?: number;
    size?: number;
    factoryId?: string;
  }): Promise<PageResponse<WhitelistDTO>> {
    const { factoryId, ...queryParams } = params;
    const response = await apiClient.get<{ code: number; data: PageResponse<WhitelistDTO>; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/search`,
      { params: queryParams }
    );
    return response.data || { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };
  }

  /**
   * 11. 获取即将过期的白名单
   * GET /api/{factoryId}/whitelist/expiring
   */
  async getExpiringSoon(params?: {
    days?: number;
    factoryId?: string;
  }): Promise<WhitelistDTO[]> {
    const { factoryId, ...queryParams } = params || {};
    const response = await apiClient.get<{ code: number; data: WhitelistDTO[]; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/expiring`,
      { params: queryParams }
    );
    return response.data || [];
  }

  /**
   * 12. 获取最活跃用户
   * GET /api/{factoryId}/whitelist/most-active
   */
  async getMostActiveUsers(params?: {
    limit?: number;
    factoryId?: string;
  }): Promise<WhitelistDTO[]> {
    const { factoryId, ...queryParams } = params || {};
    const response = await apiClient.get<{ code: number; data: WhitelistDTO[]; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/most-active`,
      { params: queryParams }
    );
    return response.data || [];
  }

  /**
   * 13. 获取最近使用
   * GET /api/{factoryId}/whitelist/recently-used
   */
  async getRecentlyUsed(params?: {
    limit?: number;
    factoryId?: string;
  }): Promise<WhitelistDTO[]> {
    const { factoryId, ...queryParams } = params || {};
    const response = await apiClient.get<{ code: number; data: WhitelistDTO[]; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/recently-used`,
      { params: queryParams }
    );
    return response.data || [];
  }

  /**
   * 14. 导出白名单
   * GET /api/{factoryId}/whitelist/export
   */
  async exportWhitelist(params?: {
    status?: string;
    factoryId?: string;
  }): Promise<Blob> {
    const { factoryId, ...queryParams } = params || {};
    return await apiClient.get<Blob>(
      `${this.getPath(factoryId)}/export`,
      {
        params: queryParams,
        responseType: 'blob'
      }
    );
  }

  /**
   * 15. 导入白名单
   * POST /api/{factoryId}/whitelist/import
   */
  async importWhitelist(
    csvData: string,
    factoryId?: string
  ): Promise<BatchResult> {
    const response = await apiClient.post<{ code: number; data: BatchResult; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/import`,
      { csvData }
    );
    return response.data;
  }

  /**
   * 16. 清理已删除的记录
   * DELETE /api/{factoryId}/whitelist/cleanup
   */
  async cleanupDeleted(params?: {
    daysOld?: number;
    factoryId?: string;
  }): Promise<{ deleted: number }> {
    const { factoryId, ...queryParams } = params || {};
    const response = await apiClient.delete<{ code: number; data: { deleted: number }; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/cleanup`,
      { params: queryParams }
    );
    return response.data;
  }

  /**
   * 17. 延长有效期
   * PUT /api/{factoryId}/whitelist/{id}/extend
   */
  async extendExpiration(params: {
    id: number;
    days: number;
    factoryId?: string;
  }): Promise<WhitelistDTO> {
    const { factoryId, id, days } = params;
    const response = await apiClient.put<{ code: number; data: WhitelistDTO; message: string; success: boolean; }>(
      `${this.getPath(factoryId)}/${id}/extend`,
      { days }
    );
    return response.data;
  }

  // ===== 不实现的功能 (详见 .claude/rules/unused-api-endpoints.md) =====
  /*
   * 以下功能由后端自动处理，前端不需要调用：
   * - resetUsageCount - 重置使用次数 (PUT /{id}/reset-usage)
   * - incrementUsage - 增加使用次数 (PUT /usage/{phoneNumber})
   * - updateLimitReached - 更新达限状态 (PUT /limit-reached)
   */
}

export const whitelistApiClient = new WhitelistApiClient();
export default whitelistApiClient;
