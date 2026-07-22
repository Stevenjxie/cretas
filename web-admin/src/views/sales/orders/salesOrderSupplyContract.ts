export type SalesProcessingMode = 'STANDARD_SALE' | 'TOLL_PROCESSING';
export type MaterialSupplyMode = 'CUSTOMER_SUPPLIED' | 'FACTORY_SUPPLIED';

export interface SalesOrderSupplyContract {
  processingMode: SalesProcessingMode | '';
  materialSupplyMode: MaterialSupplyMode | '';
}

export interface CustomerSuppliedMaterialRequirement {
  id?: string;
  salesOrderItemId?: number;
  materialTypeId: string;
  materialName: string;
  expectedQuantity: number;
  unit: string;
  expectedArrivalAt: string;
  targetWarehouseId: string;
  targetWarehouseName?: string;
  receivedQuantity?: number;
  remainingQuantity?: number;
  status?: string;
}

export const SALES_PROCESSING_MODE_OPTIONS: ReadonlyArray<{
  value: SalesProcessingMode;
  label: string;
}> = [
  { value: 'STANDARD_SALE', label: '普通销售' },
  { value: 'TOLL_PROCESSING', label: '代加工' },
];

export const MATERIAL_SUPPLY_MODE_OPTIONS: ReadonlyArray<{
  value: MaterialSupplyMode;
  label: string;
}> = [
  { value: 'FACTORY_SUPPLIED', label: '工厂备料' },
  { value: 'CUSTOMER_SUPPLIED', label: '客户自带原料' },
];

const processingModeLabels: Record<SalesProcessingMode, string> = {
  STANDARD_SALE: '普通销售',
  TOLL_PROCESSING: '代加工',
};

const materialSupplyModeLabels: Record<MaterialSupplyMode, string> = {
  FACTORY_SUPPLIED: '工厂备料',
  CUSTOMER_SUPPLIED: '客户自带原料',
};

export function newSalesOrderSupplyContract(): SalesOrderSupplyContract {
  return {
    processingMode: 'STANDARD_SALE',
    materialSupplyMode: 'FACTORY_SUPPLIED',
  };
}

export function processingModeLabel(value: unknown): string {
  return processingModeLabels[value as SalesProcessingMode] || '未设置（历史数据）';
}

export function materialSupplyModeLabel(value: unknown): string {
  return materialSupplyModeLabels[value as MaterialSupplyMode] || '未设置（历史数据）';
}

export function customerMaterialReceivingStatusLabel(value: unknown): string {
  const labels: Record<string, string> = {
    PENDING: '待收货',
    PENDING_RECEIPT: '待收货',
    RECEIVING: '收货中',
    PARTIALLY_RECEIVED: '部分收货',
    RECEIVED: '已收货',
    CONFIRMED: '已确认入库',
    POSTED: '已入库',
  };
  return labels[String(value || '')] || '请前往仓储查看';
}

export function supplyContractValidationError(contract: SalesOrderSupplyContract): string | null {
  if (!contract.processingMode) return '请选择加工方式';
  if (!contract.materialSupplyMode) return '请选择物料供应方式';
  if (contract.processingMode === 'STANDARD_SALE'
    && contract.materialSupplyMode === 'CUSTOMER_SUPPLIED') {
    return '普通销售不能选择客户自带原料；请改为代加工，或选择工厂备料';
  }
  return null;
}

export function suppliedMaterialsValidationError(
  contract: SalesOrderSupplyContract,
  requirements: CustomerSuppliedMaterialRequirement[],
): string | null {
  if (contract.processingMode !== 'TOLL_PROCESSING'
    || contract.materialSupplyMode !== 'CUSTOMER_SUPPLIED') {
    return null;
  }
  if (requirements.length === 0) return '请至少添加一项客户自带原料需求';
  const seen = new Set<string>();
  for (const row of requirements) {
    if (!row.materialTypeId) return '请选择全部客户自带原料';
    if (seen.has(row.materialTypeId)) return `客户自带原料“${row.materialName || row.materialTypeId}”不能重复添加`;
    seen.add(row.materialTypeId);
    if (!(Number(row.expectedQuantity) > 0)) return `“${row.materialName || '客户自带原料'}”预计数量必须大于 0`;
    if (!row.unit) return `“${row.materialName || '客户自带原料'}”缺少库存计量单位`;
    if (!row.expectedArrivalAt) return `请填写“${row.materialName || '客户自带原料'}”预计到货时间`;
    if (!row.targetWarehouseId) return `请选择“${row.materialName || '客户自带原料'}”目标仓库`;
  }
  return null;
}

export function warehouseReceivingRoute(order: {
  id?: unknown;
  orderNumber?: unknown;
}): { path: string; query: Record<string, string> } {
  return {
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED',
      salesOrderId: String(order.id || ''),
      salesOrderNo: String(order.orderNumber || ''),
    },
  };
}
