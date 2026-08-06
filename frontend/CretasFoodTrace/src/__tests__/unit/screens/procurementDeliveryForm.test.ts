import {
  filterSupplierOptions,
  getProcurementDeliverySubmitBlocker,
  resolveRequisitionMaterialName,
} from '../../../screens/restaurant/procurement/procurementDeliveryForm';

const supplier = {
  id: 'supplier-internal-id',
  factoryId: 'MOCK_REST',
  supplierCode: 'SUP-01',
  code: 'SUP-01',
  name: '春禾蔬菜',
  contactPerson: '陈师傅',
  phone: '13800000000',
  isActive: true,
  createdAt: '2026-08-02',
};

const material = {
  id: 'material-internal-id',
  factoryId: 'MOCK_REST',
  code: 'MAT-01',
  name: '小青菜',
  unit: 'kg',
  isActive: true,
  createdAt: '2026-08-02',
};

describe('procurement delivery form helpers', () => {
  it('searches suppliers by business-facing fields', () => {
    expect(filterSupplierOptions([supplier], '陈师傅')).toEqual([supplier]);
    expect(filterSupplierOptions([supplier], '')).toEqual([]);
  });

  it('resolves requisition material names without exposing the internal id', () => {
    expect(resolveRequisitionMaterialName(undefined, material.id, [material])).toBe('小青菜');
    expect(resolveRequisitionMaterialName(undefined, 'unknown-internal-id', [])).toBe('未命名食材');
  });

  it('blocks a typed but unselected material', () => {
    expect(getProcurementDeliverySubmitBlocker({
      supplierName: '春禾蔬菜',
      deliveryDate: '2026-08-02',
      lines: [{
        materialSearch: '小青菜',
        ingredientName: '小青菜',
        rawMaterialTypeId: '',
        quantity: '10',
        unit: 'kg',
        unitPrice: '',
      }],
      quoteUploading: false,
      voiceUploading: false,
    })).toBe('第 1 行：请从候选中选择食材。');
  });

  it('blocks unfinished uploads after the business fields are complete', () => {
    expect(getProcurementDeliverySubmitBlocker({
      supplierName: '春禾蔬菜',
      deliveryDate: '2026-08-02',
      lines: [{
        materialSearch: '小青菜',
        ingredientName: '小青菜',
        rawMaterialTypeId: material.id,
        quantity: '10',
        unit: 'kg',
        unitPrice: '',
      }],
      quoteUploading: true,
      voiceUploading: false,
    })).toBe('报价照片正在上传，请等待完成。');
  });

  it('allows submission when supplier, selected material and quantity are complete', () => {
    expect(getProcurementDeliverySubmitBlocker({
      supplierName: '春禾蔬菜',
      deliveryDate: '2026-08-02',
      lines: [{
        materialSearch: '小青菜',
        ingredientName: '小青菜',
        rawMaterialTypeId: material.id,
        quantity: '10',
        unit: 'kg',
        unitPrice: '',
      }],
      quoteUploading: false,
      voiceUploading: false,
    })).toBeNull();
  });

  it('rejects an invalid date and negative price before submission', () => {
    const completeLine = {
      materialSearch: '小青菜',
      ingredientName: '小青菜',
      rawMaterialTypeId: material.id,
      quantity: '10',
      unit: 'kg',
      unitPrice: '-1',
    };
    expect(getProcurementDeliverySubmitBlocker({
      supplierName: '春禾蔬菜',
      deliveryDate: '08/02/2026',
      lines: [completeLine],
      quoteUploading: false,
      voiceUploading: false,
    })).toContain('YYYY-MM-DD');
    expect(getProcurementDeliverySubmitBlocker({
      supplierName: '春禾蔬菜',
      deliveryDate: '2026-08-02',
      lines: [completeLine],
      quoteUploading: false,
      voiceUploading: false,
    })).toBe('第 1 行：单价不能为负数。');
  });
});
