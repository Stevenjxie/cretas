import { bigCategoryOf } from '@/utils/materialCategory';

export interface SeasoningMaterialOption {
  id: string;
  name: string;
  materialCategory?: string | null;
  category?: string | null;
  unit?: string | null;
  movingAvgPrice?: number | null;
}

export interface SeasoningRowValidationInput {
  name: string;
  materialTypeId?: string | null;
  dosagePerKgG?: number | null;
  priceSource1?: number | null;
}

export interface AppliedSeasoningMaterial {
  materialTypeId: string;
  name: string;
  unit: string;
  priceSource1: number | null;
  priceSource2: number | null;
}

export function filterSeasoningMaterials(
  materials: SeasoningMaterialOption[],
): SeasoningMaterialOption[] {
  return materials.filter((material) => {
    if (material.materialCategory === 'AUXILIARY') return true;
    const category = bigCategoryOf(material.category);
    return category === '辅料' || category === '调料';
  });
}

export function applySeasoningMaterial(material: SeasoningMaterialOption): AppliedSeasoningMaterial {
  return {
    materialTypeId: material.id,
    name: material.name,
    unit: material.unit || 'g',
    priceSource1: material.movingAvgPrice ?? null,
    priceSource2: null,
  };
}

export function isPotSequencingEnabled(ratio: number | null | undefined): boolean {
  return ratio !== null && ratio !== undefined;
}

export function percentToRatio(percent: number | null | undefined): number | null {
  return percent === null || percent === undefined ? null : percent / 100;
}

export function ratioToPercent(ratio: number | null | undefined): number | null {
  return ratio === null || ratio === undefined ? null : ratio * 100;
}

export function validateSeasoningRows(rows: SeasoningRowValidationInput[]): string[] {
  const errors: string[] = [];
  for (const row of rows) {
    const label = row.name?.trim() || '未命名调料';
    if (!row.materialTypeId) {
      errors.push(`「${label}」是历史调料，请重新选择物料`);
    } else if (row.dosagePerKgG === null || row.dosagePerKgG === undefined) {
      errors.push(`「${label}」未填写每 1 kg 本工序投入用量`);
    }
  }
  return errors;
}

export function validatePotRatio(enabled: boolean, percent: number | null | undefined): string | null {
  if (!enabled) return null;
  if (percent === null || percent === undefined) return '请填写后续锅占第一锅的百分比';
  if (percent < 0 || percent > 100) return '后续锅比例必须在 0% 到 100% 之间';
  return null;
}
