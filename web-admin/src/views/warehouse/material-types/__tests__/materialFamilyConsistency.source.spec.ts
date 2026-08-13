import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

describe('material type short-code and taxonomy source contract', () => {
  it('always offers basic material types independently from the optional taxonomy tree', () => {
    expect(source).toContain('const materialFamilyOptions = computed');
    expect(source).toContain('const values = [...MATERIAL_CATEGORY_ENUM_VALUES]');
    expect(source).toContain('for (const node of segmentL1Options.value)');
    expect(source).toContain('for (const c of seenCategories.value)');
    expect(source).not.toContain('system-config/enums/MATERIAL_CATEGORY');
    expect(source).not.toContain('if (!hasSegmentDictionary.value)');
  });

  it('suggests one short code from the basic type and lets the user change it', () => {
    expect(source).toContain('raw-material-types/preview-code');
    expect(source).toContain("classificationId: typeof segmentL3.value === 'number' ? segmentL3.value : undefined");
    expect(source).toContain('if (!codeManuallyEdited.value) form.value.code = res.data.code');
    expect(source).toContain('@input="handleCodeInput"');
    expect(source).toContain('系统建议后可修改；同一工厂内不可重复');
    expect(source).toContain('@click="refreshCodeSuggestion(true)"');
  });

  it('fails closed and disables the code field when a suggestion cannot be calculated', () => {
    expect(source).toContain('codeSuggestionError.value =');
    expect(source).toContain(':disabled="!form.category || codeSuggestionLoading || Boolean(codeSuggestionError)"');
    expect(source).toContain('系统暂时无法计算下一个可用料号，请稍后重试');
    expect(source).not.toContain('WL001`');
  });

  it('treats the three classification levels as optional all-or-none metadata', () => {
    // 2026-08-13: 这条原本钉着 `&& !editingId.value`(分类只在新建时可选)。
    // 那不是本用例守的意图 —— 它守的是「三级分类可选、要么全填要么全空」;
    // `!editingId` 只是当时的形状, 而它的后果是**存量物料的分类永远补不上**
    // (生产实测 479 个启用物料只有 10 个有分类, 包材 63 个里 0 个), 进而让画布上
    // 「替代包材」对所有人恒灰。编辑态现在也显示分类段选择器,
    // 由 packagingSubstituteScope.source.spec.ts 守住。
    expect(source).toContain('const showSegmentEditor = computed(() => hasSegmentDictionary.value)');
    expect(source).toContain('详细分类（选填）');
    expect(source).toContain('分类可全部留空；一旦选择则须完整选到三级');
    expect(source).toContain('const hasPartialClassification = Boolean(segmentL1.value || segmentL2.value || segmentL3.value)');
    expect(source).toContain('分类如需填写，必须完整选择一级、二级、三级；不需要分类时请全部留空');
    expect(source).toContain("classificationId: typeof segmentL3.value === 'number' ? segmentL3.value : undefined");
    expect(source).not.toContain('label="L1 大类" required');
    expect(source).not.toContain('label="L2 中类" required');
    expect(source).not.toContain('label="L3 小类" required');
  });

  it('exposes only the single short material code and removes all dual-code UI', () => {
    expect(source).toContain('return String(row.code ||');
    expect(source).toContain('<el-table-column label="料号"');
    expect(source).toContain('placeholder="搜索原料名称 / 料号"');
    expect(source).not.toContain('businessCode');
    expect(source).not.toContain('历史兼容编码');
    expect(source).not.toContain('16位');
    expect(source).not.toContain('16 位');
  });

  it('submits the short code for every create regardless of dictionary availability', () => {
    expect(source).toContain('if (editingId.value) {');
    expect(source).toContain('delete materialPayload.code;');
    expect(source).not.toContain('editingId.value || hasSegmentDictionary.value');
    expect(source).toContain('...materialPayload,');
  });

  it('filters material types by independent classification node IDs', () => {
    expect(source).toContain('const filterSegmentL1 = ref<number | null>(null)');
    expect(source).toContain('const filterSegmentL2 = ref<number | null>(null)');
    expect(source).toContain('const filterSegmentL3 = ref<number | null>(null)');
    expect(source).toContain('classificationId: selectedClassificationId.value || undefined');
    expect(source).toContain('keyword: searchKeyword.value.trim() || undefined');
    expect(source).not.toContain('FETCH_ALL_SIZE = 2000');
  });

  it('creates shared classifications with generated IDs and no code field', () => {
    expect(source).toContain('parentId: segmentL2.value');
    expect(source).toContain('segmentL3.value = response.data.id');
    expect(source).toContain('label="＋ 新建共享三级分类"');
    expect(source).toContain('v-if="canManageClassification"');
    expect(source).not.toContain('material-segments/next-code');
    expect(source).not.toContain('系统编码');
    expect(source).not.toContain('segmentCode,');
  });

  it('keeps packaging conversion optional for variable-weight materials', () => {
    expect(source).toContain('packagingRules.value = [];');
    expect(source).toContain('packagingRules.value = legacyRules;');
    expect(source).toContain('未配置包装换算：收货按');
    expect(source).not.toContain('legacyRules.length ? legacyRules : [blankPackagingRule()]');
  });

  it('does not double-toast request errors', () => {
    expect(source).toContain("handleCatchError(e, '原料类型保存失败，请稍后重试')");
    expect(source).not.toContain('if (e instanceof Error) ElMessage.error(e.message)');
  });
});
