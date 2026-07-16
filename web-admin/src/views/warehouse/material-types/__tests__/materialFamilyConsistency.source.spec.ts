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

  it('requires a complete L1-L3 selection for new and legacy-code material types', () => {
    expect(source).toContain('const showSegmentEditor = computed(() => !editingId.value || editingNeedsSegmentRepair.value)');
    expect(source).toContain("if (showSegmentEditor.value && (!segmentL1.value || !segmentL2.value || !segmentL3.value))");
    expect(source).toContain('16位编码级联（必填）');
    expect(source).not.toContain('16位编码级联（可选）');
  });

  it('filters material types by the selected L1, L2 or L3 code prefix', () => {
    expect(source).toContain("const filterSegmentL1 = ref('')");
    expect(source).toContain("const filterSegmentL2 = ref('')");
    expect(source).toContain("const filterSegmentL3 = ref('')");
    expect(source).toContain('const selectedSegmentPrefix = computed');
    expect(source).toContain('codePrefix: selectedSegmentPrefix.value || undefined');
    expect(source).toContain('keyword: searchKeyword.value.trim() || undefined');
    expect(source).not.toContain('FETCH_ALL_SIZE = 2000');
    expect(source).toContain('v-model="filterSegmentL1"');
    expect(source).toContain('v-model="filterSegmentL2"');
    expect(source).toContain('v-model="filterSegmentL3"');
  });

  it('never fabricates a 16-digit preview when the preview API fails', () => {
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}${segmentL2.value}${segmentL3.value}...`');
    expect(source).not.toContain('segmentCodePreview.value = `${segmentL1.value}-${segmentL2.value}-${segmentL3.value}`');
  });

  it('uses the requested material-family form defaults and visibility contracts', () => {
    expect(source).toContain("taxRate: 'TAX_13'");
    expect(source).toContain('label="入库计量单位"');
    expect(source).toContain('新建默认 kg（公斤）');
    expect(source).toContain('v-if="!isPackagingMaterial" label="储存类型"');
    expect(source).toContain('v-if="isPackagingMaterial" :label="form.taxTreatment');
    expect(source).toContain('<template v-if="isPackagingMaterial">');
    expect(source).toContain('包装层级（包材专属，可选）');
    expect(source).toContain('原料/辅料完全不发送 hierarchy');
  });

  it('matches historical L3 under the selected L1/L2 and reuses the real dictionary create endpoint', () => {
    expect(source).toContain('params: { page: 1, size: 20, codePrefix: l2, keyword: normalizedName }');
    expect(source).toContain('label="＋ 快捷创建新品类"');
    expect(source).toContain('`/${factoryId.value}/material-segments`');
    expect(source).toContain('level: 3');
    expect(source).toContain('parentCode: segmentL2.value');
    expect(source).toContain('创建并选中');
  });
});
