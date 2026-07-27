import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('BOM packaging usage UX', () => {
  it('only asks for per-output usage and inherits the packaging material unit', () => {
    expect(source).toContain('label="每 1 份成品使用量"');
    expect(source).toContain('单位固定来自包材档案，不在 BOM 中修改');
    expect(source).toContain("packagingSpecId: null");
    expect(source).toContain("packagingRole: isPackaging ? 'PRIMARY_CONTAINER' : null");
    expect(source).toContain('bomForm.value.standardQuantity = naturalQuantity');
    expect(source).toContain('label="每 1 份成品用量"');
    expect(source).not.toContain('label="包装规格"');
    expect(source).not.toContain('label="包材角色"');
    expect(source).not.toContain('function matchPackagingLayerForMaterial()');
    expect(source).not.toContain('function onPackagingRoleChange(role: string)');
  });
});
