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
    expect(source).toContain('物料分类与业务编码（必填）');
    expect(source).not.toContain('>16位编码级联（必填）<');
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

  it('previews and saves through the same material code contract', () => {
    expect(source).toContain('raw-material-types/preview-code');
    expect(source).toContain('segmentCode: segmentL3.value');
    expect(source).toContain('businessCodePreview.value = res.data.businessCode');
    expect(source).toContain('历史兼容编码（16位）');
    expect(source).toContain('if (!editingId.value && !(await generateSP8Code(true)))');
    expect(source).toContain('不会按分类名称猜测或覆盖历史前缀');
  });

  it('uses the short business code as the primary user-visible material identity', () => {
    expect(source).toContain('function materialDisplayCode(row: TableRow)');
    expect(source).toContain('row.displayCode || row.businessCode || row.code');
    expect(source).toContain('<el-table-column label="业务编码"');
    expect(source).toContain('{{ materialDisplayCode(row) }}');
    expect(source).toContain('v-if="!materialHasBusinessCode(row)"');
    expect(source).toContain('历史编码</el-tag>');
    expect(source).toContain('历史兼容编码：${row.code}');
    expect(source).not.toContain('<el-table-column prop="code" label="原料编码"');
    expect(source).toContain('placeholder="搜索原料名称 / 业务编码 / 历史编码"');
  });

  it('presents classification names before their internal numeric category codes', () => {
    expect(source).toContain('function formatSegmentOptionLabel');
    expect(source).toContain('（分类码 ${option.segmentCode}）');
    expect(source).toContain(':label="formatSegmentOptionLabel(opt)"');
    expect(source).not.toContain(':label="`${opt.segmentCode} — ${opt.segmentLabel}`"');
  });

  it('shows the business code in edit mode and keeps a clear historical fallback', () => {
    expect(source).toContain('editingDisplayCode.value = materialDisplayCode(row)');
    expect(source).toContain('<el-form-item v-if="editingId" label="业务编码">');
    expect(source).toContain(':model-value="editingDisplayCode"');
    expect(source).toContain('该历史记录尚未分配业务编码，当前回退显示原16位编码');
    expect(source).not.toContain('<el-input v-model="form.code" disabled');
  });

  it('does not add a second error toast after the request interceptor handled the failure', () => {
    expect(source).toContain("handleCatchError(e, '原料类型保存失败，请稍后重试')");
    expect(source).not.toContain('if (e instanceof Error) ElMessage.error(e.message)');
  });

  it('uses the requested material-family form defaults and visibility contracts', () => {
    expect(source).toContain("taxRate: 'TAX_13'");
    expect(source).toContain('label="入库计量单位"');
    expect(source).toContain('新建默认 kg（公斤）');
    expect(source).toContain('v-if="!isPackagingMaterial" label="储存类型"');
    expect(source).toContain('<template v-if="canViewPrice">');
    expect(source).toContain('<template v-if="isPackagingMaterial">');
    expect(source).toContain('包材专属字段（选填）');
    expect(source).toContain('<el-form-item v-if="form.unit" label="包装换算" required>');
    expect(source).not.toContain('采购与库存单位换算（可选）');
    expect(source).toContain('v-for="(rule, index) in packagingRules"');
    expect(source).toContain('packagingSpecs: submittedPackagingRules');
    expect(source).not.toContain('包装层级（包材专属，可选）');
  });

  it('matches historical L3 under the selected L1/L2 and reuses the real dictionary create endpoint', () => {
    expect(source).toContain('params: { page: 1, size: 20, codePrefix: l2, keyword: normalizedName }');
    expect(source).toContain('label="＋ 新建共享 L3 分类"');
    expect(source).toContain('v-if="canManageClassification"');
    expect(source).toContain('系统不会复制当前原料名称');
    expect(source).toContain('`/${factoryId.value}/material-segments`');
    expect(source).toContain('level: 3');
    expect(source).toContain('parentCode: segmentL2.value');
    expect(source).toContain('创建并选中');
    expect(source).toContain('const nextL3Code = computed');
    expect(source).toContain(':model-value="nextL3Code"');
    expect(source).toContain('已直接选中');
    expect(source).not.toContain('createL3Form.suffix');
    expect(source).not.toContain('L3 四位编码');
  });
});
