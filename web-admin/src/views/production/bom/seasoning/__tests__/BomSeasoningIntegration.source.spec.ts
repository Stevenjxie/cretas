import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');

const bomSource = readSource('src/views/production/bom/index.vue');
const materialTypeSource = readSource('src/views/warehouse/material-types/list.vue');
const unifiedSource = readSource('src/views/production/bom-unified/index.vue');
const routerSource = readSource('src/router/index.ts');

describe('seasoning BOM integration source contract', () => {
  it('keeps only the materials and conversion top-level tabs', () => {
    expect(unifiedSource).toContain('label="原辅料配方"');
    expect(unifiedSource).toContain('label="转换率"');
    expect(unifiedSource).not.toContain('label="调料配方"');
    expect(unifiedSource).not.toContain('RecipeContent');
  });

  it('normalizes both legacy seasoning entries to the process-first auxiliary view', () => {
    for (const source of [unifiedSource, routerSource]) {
      expect(source).toContain("tab: 'materials'");
      expect(source).toContain("category: 'AUXILIARY'");
      expect(source).toContain("auxView: 'process'");
    }
    expect(routerSource).toContain("path: 'product-recipes'");
    expect(routerSource).not.toContain("import('@/views/production/ProductRecipeView.vue')");
  });

  it('renders the process workspace only for auxiliary materials with an explicit recipe', () => {
    expect(bomSource).toContain("activeCategoryTab === 'AUXILIARY'");
    expect(bomSource).toContain('<BomAuxiliaryWorkspace');
    expect(bomSource).toContain(':recipe-id="selectedRecipe.id"');
    expect(bomSource).toContain(':recipe-status="selectedRecipe.status"');
    expect(bomSource).toContain("recipe.status === 'DRAFT'");
    expect(bomSource).toContain("recipe.status === 'ACTIVE'");
  });

  it('does not offer auxiliary material through the generic create flow', () => {
    expect(bomSource).not.toContain('<el-option label="辅料" value="AUXILIARY" />');
    expect(bomSource).toContain('待绑定工序 / 可能重复计成本');
    expect(bomSource).toContain('批量绑定工序（暂不可用）');
  });

  it('keeps yield and per-serving cost system-owned instead of manually editable', () => {
    expect(bomSource).toContain('系统历史出成率');
    expect(bomSource).toContain('历史出成率由正式报工批次自动统计');
    expect(bomSource).not.toContain('v-model="recipeForm.overallYieldRate"');
    expect(bomSource).not.toContain('handleOpenRecalcPreview');
    expect(bomSource).not.toContain('standardServingWeight');
    expect(bomSource).not.toContain('kg/份');
    expect(bomSource).not.toContain('@click="handleOpenAdjustDialog"');
    expect(bomSource).not.toContain('@click="handleImportClick"');
  });

  it('uses category-specific material pickers and material-master pricing', () => {
    expect(bomSource).toContain("? '选择包材' : '选择原料'");
    expect(bomSource).toContain('所选物料为必填');
    expect(bomSource).toContain('单价与税率从物料档案自动带入');
    expect(bomSource).toContain('bomForm.value.standardQuantity = null');
    expect(bomSource).not.toContain('bomForm.value.standardQuantity = skuGramsPerUnit.value');
    expect(materialTypeSource).toContain('<el-form-item label="税率" required>');
    expect(materialTypeSource).toContain('<el-form-item label="含税单价 (元)" required>');
    expect(materialTypeSource).toContain("ElMessage.warning('请填写大于 0 的含税单价')");
  });
});
