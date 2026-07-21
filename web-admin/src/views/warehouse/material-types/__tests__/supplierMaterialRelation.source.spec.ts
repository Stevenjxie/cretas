import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const materialSource = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);
const supplierSource = readFileSync(
  resolve(process.cwd(), 'src/views/procurement/suppliers/SupplierDetailDrawer.vue'),
  'utf8',
);
const apiSource = readFileSync(
  resolve(process.cwd(), 'src/api/supplierManagement.ts'),
  'utf8',
);

describe('supplier material bidirectional relation contract', () => {
  it('uses the canonical material-to-suppliers relationship endpoint', () => {
    expect(apiSource).toContain('`/${factoryId}/materials/${materialTypeId}/suppliers`');
    expect(materialSource).toContain('listMaterialSuppliers(factoryId.value, material.id)');
    expect(materialSource).not.toContain('/suppliers/by-material');
    expect(materialSource).toContain('关联供应商');
    expect(materialSource).toContain('createSupplierMaterial(factoryId.value, relationForm.supplierId, payload)');
    expect(materialSource).toContain('updateSupplierMaterial(factoryId.value, relationForm.supplierId, relationEditing.value.id, payload)');
    expect(materialSource).toContain('deleteSupplierMaterial(factoryId.value, supplierId, row.id, row.version)');
  });

  it('prevents duplicate supplier choices and keeps preferred/active on the relation', () => {
    expect(materialSource).toContain('const linked = new Set(suppliersForMaterial.value.map');
    expect(materialSource).toContain('!linked.has(String(supplier.id))');
    expect(materialSource).toContain('preferred: relationForm.preferred');
    expect(materialSource).toContain('active: relationForm.active');
    expect(materialSource).toContain('已关联的供应商不会再次出现');
  });

  it('uses the shared purchase quantity unit catalog and makes price units explicit', () => {
    for (const source of [materialSource, supplierSource]) {
      expect(source).toContain('usage-scope="PURCHASE_QUANTITY"');
      expect(source).toContain('displayUnit(relationForm.purchaseUnit)');
      expect(source).toContain('未配置时采购单');
    }
    expect(supplierSource).not.toContain('usage-scope="PURCHASE"');
    expect(supplierSource).not.toContain('<el-input v-model="relationForm.purchaseUnit"');
  });

  it('keeps material reference prices optional and bound to the inventory base unit', () => {
    expect(materialSource).toContain('选填；未知价格请留空');
    expect(materialSource).toContain('displayUnit(form.unit)');
    expect(materialSource).toContain('采购参考价如填写，必须大于 0；未知价格请留空');
    expect(materialSource).not.toContain('delete materialPayload.taxIncludedUnitPrice;\n      delete materialPayload.associatedCustomerId');
  });
});
