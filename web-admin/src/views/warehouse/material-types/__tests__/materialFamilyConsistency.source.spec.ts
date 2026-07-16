import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('material type family source contract', () => {
  it('uses material code L1 families as the only category option source', () => {
    expect(source).toContain('const materialFamilyOptions = computed');
    expect(source).toContain('segmentL1Options.value.map');
    expect(source).not.toContain('system-config/enums/MATERIAL_CATEGORY');

    const sharedOptionLoops = source.match(/v-for="opt in materialFamilyOptions"/g) ?? [];
    expect(sharedOptionLoops).toHaveLength(2);
    expect(source).not.toContain('<el-option label="原料" value="原料" />');
  });

  it('loads L1 families with the page and keeps category and L1 synchronized', () => {
    expect(source).toContain('await Promise.all([loadDictionaries(), loadSegmentTree()])');
    expect(source).toContain('syncMaterialFamilyFromCategory');
    expect(source).toContain('syncMaterialFamilyFromSegment');
  });

  it('canonicalizes legacy smart suggestions before writing a new category', () => {
    expect(source).toContain('resolveMaterialFamily(d.category)');
    expect(source).toContain("bucket === '调料' ? '辅料' : bucket");
  });
});
