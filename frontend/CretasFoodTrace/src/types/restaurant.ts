/**
 * Restaurant module types
 * Types for recipe, requisition, stocktaking, and wastage management
 */

// ==================== Recipe (配方) ====================

export interface Recipe {
  id: string;
  factoryId: string;
  productTypeId: string;
  productTypeName?: string;
  rawMaterialTypeId: string;
  rawMaterialTypeName?: string;
  standardQuantity: number;
  actualQuantity?: number;
  unit: string;
  netYieldRate?: number;
  isMainIngredient: boolean;
  isActive: boolean;
  createdBy?: number;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RecipeCreateRequest {
  productTypeId: string;
  rawMaterialTypeId: string;
  standardQuantity: number;
  unit: string;
  netYieldRate?: number;
  isMainIngredient?: boolean;
  notes?: string;
}

// ==================== Material Requisition (领料) ====================

export type RequisitionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
export type RequisitionType = 'PRODUCTION' | 'MANUAL';

export interface MaterialRequisition {
  id: string;
  factoryId: string;
  requisitionNumber: string;
  requisitionDate: string;
  type: RequisitionType;
  status: RequisitionStatus;
  productTypeId?: string;
  productTypeName?: string;
  rawMaterialTypeId: string;
  rawMaterialTypeName?: string;
  requestedQuantity: number;
  actualQuantity?: number;
  unit: string;
  requestedBy?: number;
  requestedByName?: string;
  approvedBy?: number;
  approvedByName?: string;
  approvedAt?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RequisitionCreateRequest {
  type: RequisitionType;
  productTypeId?: string;
  rawMaterialTypeId: string;
  requestedQuantity: number;
  unit: string;
  notes?: string;
}

// ==================== Stocktaking (盘点) ====================

export type StocktakingStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type DifferenceType = 'SURPLUS' | 'SHORTAGE' | 'MATCH';

export interface StocktakingRecord {
  id: string;
  factoryId: string;
  stocktakingNumber: string;
  stocktakingDate: string;
  status: StocktakingStatus;
  rawMaterialTypeId: string;
  rawMaterialTypeName?: string;
  systemQuantity: number;
  actualQuantity?: number;
  unit: string;
  differenceQuantity?: number;
  differenceType?: DifferenceType;
  countedBy?: number;
  countedByName?: string;
  verifiedBy?: number;
  completedAt?: string;
  adjustmentReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ==================== Wastage (损耗) ====================

export type WastageType = 'EXPIRED' | 'DAMAGED' | 'SPOILED' | 'PROCESSING_LOSS' | 'OTHER';
export type WastageStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

export interface WastageRecord {
  id: string;
  factoryId: string;
  wastageNumber: string;
  wastageDate: string;
  type: WastageType;
  status: WastageStatus;
  rawMaterialTypeId: string;
  rawMaterialTypeName?: string;
  quantity: number;
  unit: string;
  estimatedCost?: number;
  reportedBy?: number;
  reportedByName?: string;
  approvedBy?: number;
  approvedAt?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WastageCreateRequest {
  type: WastageType;
  rawMaterialTypeId: string;
  quantity: number;
  unit: string;
  estimatedCost?: number;
  notes?: string;
}

// ==================== Supplier Delivery (供应商送货验收入库) ====================

export type SupplierDeliveryStatus = 'DRAFT' | 'CONFIRMED' | 'REJECTED';
export type DeliveryPostingStatus = 'UNPOSTED' | 'POSTING' | 'POSTED' | 'FAILED';

export interface SupplierDeliveryLine {
  id?: number;
  ingredientName: string;
  rawMaterialTypeId?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number | null;
  baselineUnitPrice?: number | null;
  priceVarianceRate?: number | null;
  priceAnomalyFlag?: boolean;
  priceAnomalyReasonCode?: string | null;
  priceAnomalyExplanation?: string | null;
  lineAmount?: number | null;
  qcResult?: string;
  materialBatchId?: string;
  remark?: string;
  ocrConfidence?: number;
}

export type PriceAnomalyApprovalStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface SupplierDeliveryNote {
  id: string;
  factoryId: string;
  sourceType?: 'OCR' | 'MANUAL';
  photoOssUrl?: string;
  supplierId?: string;
  supplierName?: string;
  deliveryDate: string;
  warehouseId?: string;
  noteNumber?: string;
  totalAmount?: number | null;
  ocrConfidence?: number;
  ocrErrorMessage?: string;
  status: SupplierDeliveryStatus;
  postingStatus?: DeliveryPostingStatus;
  receiveRecordId?: string;
  postedAt?: string;
  postedBy?: number | string;
  postingError?: string;
  rejectReasonCode?: string;
  rejectReasonNote?: string;
  priceAnomalyApprovalStatus?: PriceAnomalyApprovalStatus;
  priceAnomalySubmittedBy?: number;
  priceAnomalySubmittedAt?: string;
  priceAnomalyApprovedBy?: number;
  priceAnomalyApprovedAt?: string;
  priceAnomalyRejectedBy?: number;
  priceAnomalyRejectedAt?: string;
  priceAnomalyApprovalComment?: string;
  sourceRequisitionId?: string;
  procurementConfirmedBy?: number;
  procurementConfirmedAt?: string;
  supplierContactNote?: string;
  voiceAudioUrl?: string;
  voiceTranscriptText?: string;
  supplierQuotePhotoUrls?: string[];
  expectedDeliveryDate?: string;
  lowConfidenceWarning?: boolean;
  lines?: SupplierDeliveryLine[];
}

export interface CreateSupplierDeliveryRequest {
  supplierId: string;
  supplierName?: string;
  deliveryDate: string;
  warehouseId?: string;
  noteNumber?: string;
  lines: SupplierDeliveryLine[];
}

export interface RejectSupplierDeliveryRequest {
  rejectReasonCode: 'IMAGE_BLUR' | 'LOW_LIGHT' | 'WRONG_DOCUMENT' | 'SUPPLIER_NOT_FOUND' | 'OTHER';
  rejectReasonNote?: string;
}

export interface SupplierDeliveryOcrRequest {
  fileUri: string;
  fileName?: string;
  mimeType?: string;
  deliveryDate?: string;
  supplierId?: string;
  factoryId?: string;
}

export interface ProcurementConfirmRequest {
  sourceRequisitionId?: string;
  supplierId?: string;
  supplierName?: string;
  deliveryDate?: string;
  expectedDeliveryDate?: string;
  supplierContactNote?: string;
  voiceAudioUrl?: string;
  voiceTranscriptText?: string;
  supplierQuotePhotoUrls?: string[];
  lines: SupplierDeliveryLine[];
}
