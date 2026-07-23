import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('BOM packaging usage UX', () => {
  it('uses SKU packaging layers as the primary business expression', () => {
    expect(source).toContain('function matchPackagingLayerForMaterial()');
    expect(source).toContain('canonicalUnitCode(layer.packageUnit) === materialUnit');
    expect(source).toContain('function onPackagingRoleChange(role: string)');
    expect(source).toContain('每1${displayUnit(denominator)}成品使用');
    expect(source).toContain('label="业务用量"');
    expect(source).toContain('label="基础单位折算（成本）"');
  });
});
