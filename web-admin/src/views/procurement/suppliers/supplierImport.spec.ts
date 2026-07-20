import { describe, expect, it } from 'vitest';
import {
  mappedSupplierRows,
  missingRequiredMappings,
  suggestSupplierMappings,
  type SupplierColumnMapping,
} from './supplierImport';

describe('supplier Excel field mapping', () => {
  it('maps the strict standard template without depending on column order', () => {
    expect(suggestSupplierMappings(
      ['地址', '供应商名称', '联系电话', '联系人', '备注'],
      'STANDARD',
    )).toEqual({
      地址: 'address',
      供应商名称: 'name',
      联系电话: 'phone',
      联系人: 'contactPerson',
      备注: 'notes',
    });
  });

  it('suggests common aliases in smart mode and leaves unknown columns unmapped', () => {
    expect(suggestSupplierMappings(
      ['供货商', '手机', '负责人', '经营地址', '纳税人识别号', '内部颜色'],
      'SMART',
    )).toEqual({
      供货商: 'name',
      手机: 'phone',
      负责人: 'contactPerson',
      经营地址: 'address',
      纳税人识别号: 'taxNumber',
      内部颜色: '',
    });
  });

  it('reports every missing mandatory target field before preview', () => {
    expect(missingRequiredMappings({ 供应商: 'name' })).toEqual(['联系人', '联系电话', '地址']);
  });
});

describe('supplier import local preview validation', () => {
  const mapping: SupplierColumnMapping = {
    供货商: 'name',
    负责人: 'contactPerson',
    电话: 'phone',
    公司地址: 'address',
  };

  it('keeps Excel row numbers and validates the shared four required fields', () => {
    const rows = mappedSupplierRows({
      headers: Object.keys(mapping),
      rows: [
        { 供货商: '华东食品', 负责人: '李经理', 电话: '021-12345678', 公司地址: '上海市浦东新区 88 号' },
        { 供货商: '缺电话供应商', 负责人: '王经理', 电话: '', 公司地址: '杭州市滨江区 1 号' },
        { 供货商: '占位地址供应商', 负责人: '赵经理', 电话: '13800138000', 公司地址: '-' },
      ],
    }, mapping);

    expect(rows[0]).toMatchObject({ rowNumber: 2, errors: [], ignored: false });
    expect(rows[1].errors).toContain('联系电话不能为空');
    expect(rows[2].errors).toContain('地址必须包含可识别文字或数字');
  });

  it('classifies a physically blank row as ignored rather than creating a supplier', () => {
    const [row] = mappedSupplierRows({
      headers: Object.keys(mapping),
      rows: [{ 供货商: ' ', 负责人: '', 电话: '', 公司地址: '' }],
    }, mapping);
    expect(row.ignored).toBe(true);
    expect(row.errors).toEqual([]);
  });
});
