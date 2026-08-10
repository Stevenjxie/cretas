import { describe, expect, it } from 'vitest';
import { resolveRawMaterialByExactName, type RawMaterialPickerOption } from '../rawMaterialCatalog';

const options: RawMaterialPickerOption[] = [
  { id: 'RM-PIG', name: '恒尔带筋猪蹄', code: 'YL001', unit: 'kg', category: '原料' },
  { id: 'RM-PIG-OTHER', name: '恒尔带筋猪蹄', code: 'YL999', unit: 'kg', category: '原料' },
  { id: 'RM-LIVER', name: '一双汇冻猪肝', code: 'YL063', unit: 'kg', category: '原料' },
];

describe('resolveRawMaterialByExactName', () => {
  it('resolves a unique exact material name without asking the Cell user twice', () => {
    expect(resolveRawMaterialByExactName('  一双汇冻猪肝 ', options)).toMatchObject({ id: 'RM-LIVER' });
  });

  it('uses the current product BOM identity to disambiguate same-named catalog rows', () => {
    expect(resolveRawMaterialByExactName('恒尔带筋猪蹄', options, ['RM-PIG']))
      .toMatchObject({ id: 'RM-PIG' });
  });

  it('fails closed when the exact name is ambiguous or missing', () => {
    expect(resolveRawMaterialByExactName('恒尔带筋猪蹄', options)).toBeNull();
    expect(resolveRawMaterialByExactName('不存在的原料', options)).toBeNull();
  });
});
