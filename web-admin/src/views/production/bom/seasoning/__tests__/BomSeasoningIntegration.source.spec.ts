import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');

const bomSource = readSource('src/views/production/bom/index.vue');
const materialTypeSource = readSource('src/views/warehouse/material-types/list.vue');
const unifiedSource = readSource('src/views/production/bom-unified/index.vue');
const routerSource = readSource('src/router/index.ts');
const bomCostDtoSource = readSource('../backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomCostSummaryDTO.java');

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
    expect(bomSource).toContain('历史出成率由正式报工自动统计');
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

  it('supports single-active version lifecycle with direct historical activation and a ten-version UX limit', () => {
    expect(bomSource).toContain("ARCHIVED: '历史 / 未生效'");
    expect(bomSource).toContain('!row.isCurrent');
    expect(bomSource).toContain("row.status !== 'ACTIVE'");
    expect(bomSource).toContain('MAX_RECIPE_VERSIONS = 10');
    expect(bomSource).toContain('已有生产计划快照和已激活 Workflow 不受影响');
    expect(bomSource).toContain('prop="notes" label="备注"');
  });

  it('labels cost bases explicitly and formats numbers without meaningless trailing zeros', () => {
    expect(bomCostDtoSource).toContain('private BigDecimal materialCostTotal;');
    expect(bomCostDtoSource).toContain('private BigDecimal laborCostTotal;');
    expect(bomCostDtoSource).toContain('private BigDecimal overheadCostTotal;');
    expect(bomCostDtoSource).toContain('private String costUnit;');
    expect(bomSource).toContain("replace(/\\.?0+$/, '')");
    expect(bomSource).toContain('costSummary.value?.materialCostTotal');
    expect(bomSource).toContain('costSummary.value?.laborCostTotal');
    expect(bomSource).toContain('costSummary.value?.overheadCostTotal');
    expect(bomSource).not.toContain("summaryNumber('totalMaterialCost'");
    expect(bomSource).not.toContain("summaryNumber('totalLaborCost'");
    expect(bomSource).not.toContain("summaryNumber('totalOverheadCost'");
    expect(bomSource).toContain('costSummary.value?.costUnit?.trim()');
    expect(bomSource).toContain('{{ costDisplayUnit }}');
    expect(bomSource).toContain('元/kg');
  });
});
