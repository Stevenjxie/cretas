import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import type {
  Recipe, RecipeCreateRequest,
  MaterialRequisition, RequisitionCreateRequest,
  StocktakingRecord,
  WastageRecord, WastageCreateRequest,
  SupplierDeliveryNote, CreateSupplierDeliveryRequest,
  SupplierDeliveryStatus, SupplierDeliveryLine,
  RejectSupplierDeliveryRequest, SupplierDeliveryOcrRequest,
} from '../../types/restaurant';

/**
 * Restaurant API Client
 * Covers: recipes, requisitions, stocktaking, wastage
 */

class RestaurantApiClient {
  // ==================== Helpers ====================

  private basePath(module: string, factoryId?: string) {
    const id = getCurrentFactoryId(factoryId);
    if (!id) throw new Error('factoryId is required');
    return `/api/mobile/${id}/restaurant/${module}`;
  }

  private async getList<T>(module: string, params?: Record<string, any>): Promise<{ data: T[]; totalElements: number }> {
    const { factoryId, ...query } = params || {};
    const res = await apiClient.get<any>(this.basePath(module, factoryId), { params: query });
    const payload = res?.data ?? res;
    const content = payload?.content ?? payload?.data?.content ?? [];
    const totalElements = payload?.totalElements ?? payload?.data?.totalElements ?? 0;
    return { data: Array.isArray(content) ? content : [], totalElements };
  }

  private async getOne<T>(module: string, id: string, factoryId?: string): Promise<T> {
    const res = await apiClient.get<any>(`${this.basePath(module, factoryId)}/${id}`);
    return res?.data ?? res;
  }

  // ==================== Recipes ====================

  async getRecipes(params?: { productTypeId?: string; isActive?: boolean; page?: number; size?: number; factoryId?: string }) {
    return this.getList<Recipe>('recipes', params);
  }

  async getRecipe(recipeId: string, factoryId?: string) {
    return this.getOne<Recipe>('recipes', recipeId, factoryId);
  }

  async getRecipesByDish(productTypeId: string, factoryId?: string): Promise<Recipe[]> {
    const res = await apiClient.get<any>(`${this.basePath('recipes', factoryId)}/by-dish/${productTypeId}`);
    const payload = res?.data ?? res;
    return Array.isArray(payload) ? payload : payload?.data ?? [];
  }

  async createRecipe(data: RecipeCreateRequest, factoryId?: string): Promise<Recipe> {
    const res = await apiClient.post<any>(this.basePath('recipes', factoryId), data);
    return res?.data ?? res;
  }

  async updateRecipe(recipeId: string, data: Partial<RecipeCreateRequest>, factoryId?: string): Promise<Recipe> {
    const res = await apiClient.put<any>(`${this.basePath('recipes', factoryId)}/${recipeId}`, data);
    return res?.data ?? res;
  }

  async deleteRecipe(recipeId: string, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.basePath('recipes', factoryId)}/${recipeId}`);
  }

  async getRecipeSummary(factoryId?: string): Promise<any> {
    const res = await apiClient.get<any>(`${this.basePath('recipes', factoryId)}/summary`);
    return res?.data ?? res;
  }

  // ==================== Requisitions ====================

  async getRequisitions(params?: { date?: string; status?: string; type?: string; page?: number; size?: number; factoryId?: string }) {
    return this.getList<MaterialRequisition>('requisitions', params);
  }

  async getRequisition(reqId: string, factoryId?: string) {
    return this.getOne<MaterialRequisition>('requisitions', reqId, factoryId);
  }

  async createRequisition(data: RequisitionCreateRequest, factoryId?: string): Promise<MaterialRequisition> {
    const res = await apiClient.post<any>(this.basePath('requisitions', factoryId), data);
    return res?.data ?? res;
  }

  async submitRequisition(reqId: string, factoryId?: string): Promise<MaterialRequisition> {
    const res = await apiClient.post<any>(`${this.basePath('requisitions', factoryId)}/${reqId}/submit`);
    return res?.data ?? res;
  }

  async approveRequisition(reqId: string, actualQuantity: number, factoryId?: string): Promise<MaterialRequisition> {
    const res = await apiClient.post<any>(`${this.basePath('requisitions', factoryId)}/${reqId}/approve`, { actualQuantity });
    return res?.data ?? res;
  }

  async rejectRequisition(reqId: string, reason: string, factoryId?: string): Promise<MaterialRequisition> {
    const res = await apiClient.post<any>(`${this.basePath('requisitions', factoryId)}/${reqId}/reject`, { reason });
    return res?.data ?? res;
  }

  async getRequisitionStats(factoryId?: string): Promise<{ totalRequisitions: number; pendingApproval: number; approved: number }> {
    const res = await apiClient.get<any>(`${this.basePath('requisitions', factoryId)}/statistics`);
    return res?.data ?? res;
  }

  async getDailySummary(date?: string, factoryId?: string): Promise<any> {
    const params = date ? { date } : {};
    const res = await apiClient.get<any>(`${this.basePath('requisitions', factoryId)}/daily-summary`, { params });
    return res?.data ?? res;
  }

  // ==================== Stocktaking ====================

  async getStocktakingRecords(params?: { status?: string; page?: number; size?: number; factoryId?: string }) {
    return this.getList<StocktakingRecord>('stocktaking', params);
  }

  async getStocktakingRecord(recordId: string, factoryId?: string) {
    return this.getOne<StocktakingRecord>('stocktaking', recordId, factoryId);
  }

  async createStocktaking(data: Partial<StocktakingRecord>, factoryId?: string): Promise<StocktakingRecord> {
    const res = await apiClient.post<any>(this.basePath('stocktaking', factoryId), data);
    return res?.data ?? res;
  }

  async completeStocktaking(recordId: string, data: { actualQuantity: number; adjustmentReason?: string }, factoryId?: string): Promise<StocktakingRecord> {
    const res = await apiClient.post<any>(`${this.basePath('stocktaking', factoryId)}/${recordId}/complete`, data);
    return res?.data ?? res;
  }

  async cancelStocktaking(recordId: string, factoryId?: string): Promise<StocktakingRecord> {
    const res = await apiClient.post<any>(`${this.basePath('stocktaking', factoryId)}/${recordId}/cancel`);
    return res?.data ?? res;
  }

  async getStocktakingSummary(factoryId?: string): Promise<any> {
    const res = await apiClient.get<any>(`${this.basePath('stocktaking', factoryId)}/latest-summary`);
    return res?.data ?? res;
  }

  // ==================== Wastage ====================

  async getWastageRecords(params?: { startDate?: string; endDate?: string; status?: string; type?: string; page?: number; size?: number; factoryId?: string }) {
    return this.getList<WastageRecord>('wastage', params);
  }

  async getWastageRecord(wastageId: string, factoryId?: string) {
    return this.getOne<WastageRecord>('wastage', wastageId, factoryId);
  }

  async createWastage(data: WastageCreateRequest, factoryId?: string): Promise<WastageRecord> {
    const res = await apiClient.post<any>(this.basePath('wastage', factoryId), data);
    return res?.data ?? res;
  }

  async submitWastage(wastageId: string, factoryId?: string): Promise<WastageRecord> {
    const res = await apiClient.post<any>(`${this.basePath('wastage', factoryId)}/${wastageId}/submit`);
    return res?.data ?? res;
  }

  async approveWastage(wastageId: string, factoryId?: string): Promise<WastageRecord> {
    const res = await apiClient.post<any>(`${this.basePath('wastage', factoryId)}/${wastageId}/approve`);
    return res?.data ?? res;
  }

  async rejectWastage(wastageId: string, reason: string, factoryId?: string): Promise<WastageRecord> {
    const res = await apiClient.post<any>(`${this.basePath('wastage', factoryId)}/${wastageId}/reject`, { reason });
    return res?.data ?? res;
  }

  async getWastageStats(params?: { startDate?: string; endDate?: string; factoryId?: string }): Promise<any> {
    const { factoryId, ...query } = params || {};
    const res = await apiClient.get<any>(`${this.basePath('wastage', factoryId)}/statistics`, { params: query });
    return res?.data ?? res;
  }

  // ==================== Supplier Delivery ====================

  async getSupplierDeliveryNotes(params?: { status?: SupplierDeliveryStatus; page?: number; size?: number; factoryId?: string }) {
    return this.getList<SupplierDeliveryNote>('supplier-delivery-notes', params);
  }

  async getSupplierDeliveryNote(noteId: string, factoryId?: string): Promise<SupplierDeliveryNote> {
    return this.getOne<SupplierDeliveryNote>('supplier-delivery-notes', noteId, factoryId);
  }

  async createSupplierDelivery(data: CreateSupplierDeliveryRequest, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(`${this.basePath('supplier-delivery-notes', factoryId)}/manual`, data);
    return res?.data ?? res;
  }

  async parseSupplierDeliveryOcr(request: SupplierDeliveryOcrRequest): Promise<SupplierDeliveryNote> {
    const mimeType = request.mimeType || 'image/jpeg';
    const fileName = request.fileName || `supplier_delivery_${Date.now()}.jpg`;
    const formData = new FormData();
    formData.append('photo', {
      uri: request.fileUri,
      name: fileName,
      type: mimeType,
    } as unknown as Blob);
    if (request.deliveryDate) {
      formData.append('deliveryDate', request.deliveryDate);
    }
    if (request.supplierId) {
      formData.append('supplierId', request.supplierId);
    }

    const res = await apiClient.post<{ success?: boolean; data?: SupplierDeliveryNote; message?: string }>(
      `${this.basePath('supplier-delivery-notes', request.factoryId)}/ocr-parse`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    if (res.success === false || !res.data) {
      throw new Error(res.message || '送货单 OCR 识别失败');
    }
    return res.data;
  }

  async confirmSupplierDelivery(noteId: string, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(`${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/confirm`);
    return res?.data ?? res;
  }

  async updateSupplierDeliveryLines(noteId: string, lines: SupplierDeliveryLine[], factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.put<{ data: SupplierDeliveryNote }>(
      `${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/lines`,
      lines
    );
    return res.data;
  }

  async rejectSupplierDelivery(noteId: string, data: RejectSupplierDeliveryRequest, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.put<{ data: SupplierDeliveryNote }>(
      `${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/reject`,
      data
    );
    return res.data;
  }

  async deleteSupplierDelivery(noteId: string, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}`);
  }

  async submitPriceAnomalyApproval(noteId: string, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(`${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/price-anomaly/submit`);
    return res?.data ?? res;
  }

  async approvePriceAnomaly(noteId: string, comment?: string, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(
      `${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/price-anomaly/approve`,
      { comment },
    );
    return res?.data ?? res;
  }

  async rejectPriceAnomaly(noteId: string, comment?: string, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(
      `${this.basePath('supplier-delivery-notes', factoryId)}/${noteId}/price-anomaly/reject`,
      { comment },
    );
    return res?.data ?? res;
  }

  async getPendingPriceAnomalyApprovals(params?: { page?: number; size?: number; factoryId?: string }) {
    const { factoryId, ...query } = params || {};
    const res = await apiClient.get<any>(
      `${this.basePath('supplier-delivery-notes', factoryId)}/price-anomaly/pending`,
      { params: query },
    );
    const payload = res?.data ?? res;
    const content = payload?.content ?? payload?.data?.content ?? [];
    const totalElements = payload?.totalElements ?? payload?.data?.totalElements ?? 0;
    return { data: Array.isArray(content) ? content : [], totalElements };
  }

  async createProcurementDelivery(data: import('../../types/restaurant').ProcurementConfirmRequest, factoryId?: string): Promise<SupplierDeliveryNote> {
    const res = await apiClient.post<any>(`${this.basePath('supplier-delivery-notes', factoryId)}/procurement-confirm`, data);
    return res?.data ?? res;
  }
}

export const restaurantApiClient = new RestaurantApiClient();
