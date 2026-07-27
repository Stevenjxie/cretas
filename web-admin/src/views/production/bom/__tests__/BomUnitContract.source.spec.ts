import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('BOM item unit contract wiring', () => {
  it('displays localized labels while submitting the canonical material unit', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/production/bom/index.vue'), 'utf8');

    expect(source).toContain('const quantityUnit = canonicalUnitCode(bomForm.value.quantityUnit || bomForm.value.unit)');
    expect(source).toContain('unit: quantityUnit');
    expect(source).toContain('<el-input :model-value="bomFormUnitLabel" disabled />');
    expect(source).toContain('单位从物料档案自动继承且只读');
    expect(source).toContain('function packagingUsagePerOutput(row: BomItemRow)');
    expect(source).toContain('<template #default="{ row }">{{ displayUnit(row.unit) }}</template>');
    expect(source).toContain('{{ formatFriendlyNumber(row.unitPrice, 4) }} {{ formatPriceUnit(row.priceUnit) }}');
    expect(source).toContain('return formatPriceUnit(skuOutputUnit.value);');
    expect(source).toContain('{{ formatFriendlyNumber(row.totalCost, 2) }} {{ formatPriceUnit(row.outputUnit || skuOutputUnit) }}');
  });

  it('fails closed before ensure-draft and preserves substitute conversion semantics', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/production/bom/index.vue'), 'utf8');
    const readinessGuard = source.indexOf('if (!(await ensureBomConfigurable())) return null;');
    const ensureRequest = source.indexOf('return await ensureEditableDraftRequest(');

    expect(readinessGuard).toBeGreaterThan(0);
    expect(ensureRequest).toBeGreaterThan(readinessGuard);
    expect(source).toContain('data-testid="workflow-first-bom-gate"');
    expect(source).toContain(':disabled="!bomConfigurationAllowed || configurationReadinessLoading"');
    expect(source).toContain('? bomForm.value.substituteFactors[materialTypeId] ?? null');
    expect(source).toContain(': null,');
    expect(source).toContain('不同单位的替代物料必须填写大于0的明确等价换算系数');
    expect(source).toContain('包材替代必须与主包材属于同一分类/包装作用域');
  });
});
