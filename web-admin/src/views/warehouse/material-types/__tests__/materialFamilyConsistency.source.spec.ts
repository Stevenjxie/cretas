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

  it('requires a complete L1-L3 selection for every new material type', () => {
    expect(source).toContain("if (!editingId.value && (!segmentL1.value || !segmentL2.value || !segmentL3.value))");
    expect(source).toContain('16位编码级联（必填）');
    expect(source).not.toContain('16位编码级联（可选）');
  });

  it('filters material types by the selected L1, L2 or L3 code prefix', () => {
    expect(source).toContain("const filterSegmentL1 = ref('')");
    expect(source).toContain("const filterSegmentL2 = ref('')");
    expect(source).toContain("const filterSegmentL3 = ref('')");
    expect(source).toContain('const selectedSegmentPrefix = computed');
    expect(source).toContain('code.startsWith(prefix)');
    expect(source).toContain('v-model="filterSegmentL1"');
    expect(source).toContain('v-model="filterSegmentL2"');
    expect(source).toContain('v-model="filterSegmentL3"');
  });

  it('never fabricates a 16-digit preview when the preview API fails', () => {
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}${segmentL2.value}${segmentL3.value}...`');
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}-${segmentL2.value}-${segmentL3.value}`');
  });
});
