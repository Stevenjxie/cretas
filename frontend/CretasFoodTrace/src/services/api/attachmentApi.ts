/**
 * 附件 API 客户端 — C-ATT-1.
 *
 * 对应后端 AttachmentController. 8 endpoint 完整封装.
 * Base path: /api/mobile/{factoryId}/attachments
 */

import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/** 关联实体类型 — 后端 Attachment.EntityType 枚举完整复刻 (18 值). */
export type AttachmentEntityType =
  | 'CUSTOMER'
  | 'CUSTOMER_TRACKING'
  | 'PURCHASE_ORDER'
  | 'PURCHASE_RECEIPT'
  | 'QUALITY_CHECK'
  | 'PRODUCTION_BATCH'
  | 'PAYMENT_VOUCHER'
  | 'INVOICE'
  | 'RD_SAMPLE'
  | 'RECEIPT'
  | 'RETURN_ORDER'
  | 'SHIPMENT'
  | 'WASTAGE_RECORD'
  | 'GROUP_LEADER_REPORT'
  | 'EXPENSE_REPORT'
  | 'LEAVE_REQUEST'
  | 'TIMECLOCK_PHOTO'
  | 'SALES_ORDER'
  | 'INVENTORY'
  | 'ECN'
  | 'CALL_RECORD'
  | 'PRODUCTION_REPORT'
  | 'GENERIC';

export type AttachmentFileCategory =
  | 'PHOTO'
  | 'VIDEO'
  | 'DOCUMENT'
  | 'VOUCHER'
  | 'SIGNATURE'
  | 'CONTRACT'
  | 'OTHER';

export interface Attachment {
  id: string;
  factoryId: string;
  entityType: AttachmentEntityType;
  entityId: string;
  fileName: string;
  fileUrl: string;
  thumbnailUrl?: string;
  fileSize: number;
  fileType: string;
  fileCategory: AttachmentFileCategory;
  fileStorage: string;
  fileHash?: string;
  businessTag?: string;
  description?: string;
  uploadedBy: number;
  uploadedAt: string;
  uploadSource: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RegisterAttachmentRequest {
  entityType: AttachmentEntityType;
  entityId: string;
  fileName: string;
  fileUrl: string;
  thumbnailUrl?: string;
  fileSize: number;
  fileType: string;
  fileCategory?: AttachmentFileCategory;
  fileStorage?: string;
  fileHash?: string;
  businessTag?: string;
  description?: string;
  uploadSource?: string;
}

export interface UpdateAttachmentRequest {
  description?: string;
  businessTag?: string;
  fileCategory?: AttachmentFileCategory;
}

export interface UploadUrlResponse {
  uploadUrl: string;
  fileUrl: string;
}

class AttachmentApi {
  private base(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/attachments`;
  }

  /** 1. 列表 — 某实体的所有附件. */
  async list(entityType: AttachmentEntityType, entityId: string, factoryId?: string): Promise<Attachment[]> {
    // apiClient 拦截器已 unwrap response.data — 返 ApiResponse envelope { success, data, ... }
    const r = await apiClient.get<{ data: Attachment[] }>(
      `${this.base(factoryId)}?entityType=${entityType}&entityId=${encodeURIComponent(entityId)}`
    );
    return r.data ?? [];
  }

  /** 2. 详情. */
  async getById(id: string, factoryId?: string): Promise<Attachment> {
    const r = await apiClient.get<{ data: Attachment }>(`${this.base(factoryId)}/${id}`);
    return r.data;
  }

  /** 3. 下载链接 — 后端 302 重定向到签名 URL. 这里直接返 endpoint, 调用方 fetch 自动跟随. */
  downloadUrl(id: string, factoryId?: string): string {
    return `${this.base(factoryId)}/${id}/download`;
  }

  /** 4. 取 OSS pre-signed PUT URL — 5min. 返 { uploadUrl, fileUrl }. */
  async getUploadUrl(fileName: string, fileType: string, factoryId?: string): Promise<UploadUrlResponse> {
    const r = await apiClient.post<{ data: UploadUrlResponse }>(
      `${this.base(factoryId)}/upload-url`,
      { fileName, fileType }
    );
    return r.data;
  }

  /** 4b. 直传 OSS 并返回 fileUrl（不注册附件元数据）. */
  async uploadToOss(
    file: { uri: string; name: string; type: string },
    factoryId?: string,
  ): Promise<string> {
    const { uploadUrl, fileUrl } = await this.getUploadUrl(file.name, file.type, factoryId);
    const blob = await (await fetch(file.uri)).blob();
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: blob,
    });
    if (!putRes.ok) throw new Error(`OSS 上传失败 HTTP ${putRes.status}`);
    return fileUrl;
  }

  /** 5. 注册元数据 (前端 OSS 直传完后调). */
  async register(req: RegisterAttachmentRequest, factoryId?: string): Promise<Attachment> {
    const r = await apiClient.post<{ data: Attachment }>(this.base(factoryId), req);
    return r.data;
  }

  /** 6. 更新 description/businessTag/fileCategory. */
  async update(id: string, req: UpdateAttachmentRequest, factoryId?: string): Promise<Attachment> {
    const r = await apiClient.put<{ data: Attachment }>(`${this.base(factoryId)}/${id}`, req);
    return r.data;
  }

  /** 7. 软删除. */
  async delete(id: string, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.base(factoryId)}/${id}`);
  }

  /** 8. 批量计数 — 列表页徽章. */
  async batchCount(
    entityType: AttachmentEntityType,
    entityIds: string[],
    factoryId?: string
  ): Promise<Record<string, number>> {
    const r = await apiClient.post<{ data: Record<string, number> }>(
      `${this.base(factoryId)}/batch-by-entity`,
      { entityType, entityIds }
    );
    return r.data ?? {};
  }

  /**
   * 端到端上传辅助 — pickFile → getUploadUrl → PUT to OSS → register.
   * 调用方应先用 expo-image-picker 拿到 file uri + name + type, 然后调本方法.
   */
  async uploadAndRegister(
    file: { uri: string; name: string; type: string; size: number },
    entityType: AttachmentEntityType,
    entityId: string,
    extras?: { businessTag?: string; description?: string; fileCategory?: AttachmentFileCategory },
    factoryId?: string
  ): Promise<Attachment> {
    const { uploadUrl, fileUrl } = await this.getUploadUrl(file.name, file.type, factoryId);

    const blob = await (await fetch(file.uri)).blob();
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: blob,
    });
    if (!putRes.ok) throw new Error(`OSS 上传失败 HTTP ${putRes.status}`);

    return this.register(
      {
        entityType,
        entityId,
        fileName: file.name,
        fileUrl,
        fileSize: file.size,
        fileType: file.type,
        fileCategory: extras?.fileCategory,
        businessTag: extras?.businessTag,
        description: extras?.description,
        uploadSource: 'MOBILE',
      },
      factoryId
    );
  }
}

export const attachmentApi = new AttachmentApi();
