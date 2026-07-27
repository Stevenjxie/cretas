import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8');

const bomSource = readSource('src/views/production/bom/index.vue');
const materialTypeSource = readSource('src/views/warehouse/material-types/list.vue');
const unifiedSource = readSource('src/views/production/bom-unified/index.vue');
const workflowEditorSource = readSource('src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue');
const routerSource = readSource('src/router/index.ts');
const bomCostDtoSource = readSource('../backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomCostSummaryDTO.java');

describe('seasoning BOM integration source contract', () => {
  it('keeps only the materials and conversion top-level tabs', () => {
    expect(unifiedSource).toContain('label="原辅料配方"');
    expect(unifiedSource).toContain('label="转换率"');
    expect(unifiedSource).not.toContain('label="调料配方"');
    expect(unifiedSource).not.toContain('RecipeContent');
  });

  it('passes the Workflow target SKU into the embedded BOM while keeping the conversion tab', () => {
    expect(workflowEditorSource).toContain(':initial-product-type-id="bomDrawerProductTypeId"');
    expect(workflowEditorSource).toContain('openBomDrawer(bomMissingProducts[0]?.id)');
    expect(workflowEditorSource).toContain('targetIds.includes(productTypeId.value)');
    expect(unifiedSource).toContain(':initial-product-type-id="props.initialProductTypeId"');
    expect(bomSource).toContain('props.initialProductTypeId');
    expect(unifiedSource).toContain('name="conversion"');
  });

  it('removes the legacy conversion guidance banner without removing conversion management', () => {
    expect(bomSource).not.toContain('ConceptDisambiguationAlert');
    expect(bomSource).not.toContain('other-path="/production/conversions"');
    expect(unifiedSource).toContain("const ConversionContent");
  });

  it('keeps the canonical BOM auxiliary entry without restoring the removed product recipe route', () => {
    expect(unifiedSource).toContain("tab: 'materials'");
    expect(unifiedSource).toContain("category: 'AUXILIARY'");
    expect(unifiedSource).toContain("auxView: 'process'");
    expect(routerSource).toContain("path: 'bom'");
    expect(routerSource).toContain("name: 'BomManagement'");
    expect(routerSource).not.toContain("path: 'product-recipes'");
    expect(routerSource).not.toContain("name: 'ProductRecipes'");
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
    expect(bomSource).toContain(':placeholder="`请选择${bomItemCategoryLabel}`"');
    expect(bomSource).toContain('所选物料为必填');
    expect(bomSource).toContain('参考单价从物料档案带入');
    expect(bomSource).toContain('bomForm.value.standardQuantity = null');
    expect(bomSource).not.toContain('bomForm.value.standardQuantity = skuGramsPerUnit.value');
    expect(materialTypeSource).toContain('<el-form-item label="计税方式" required>');
    expect(materialTypeSource).toContain("v-if=\"form.taxTreatment === 'TAXABLE'\" label=\"采购税率\" required");
    expect(materialTypeSource).toContain("form.taxTreatment === 'EXEMPT' ? `免税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）` : `含税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`");
    expect(materialTypeSource).toContain("ElMessage.warning('采购参考价如填写，必须大于 0；未知价格请留空')");
  });

  it('supports single-active version lifecycle with direct historical activation and a ten-version UX limit', () => {
    expect(bomSource).toContain("ARCHIVED: '历史 / 未生效'");
    expect(bomSource).toContain("row.status === 'ARCHIVED'");
    expect(bomSource).toContain("row.status !== 'ACTIVE'");
    expect(bomSource).toContain('MAX_RECIPE_VERSIONS = 10');
    expect(bomSource).toContain('已有生产计划快照和已激活 Workflow 不受影响');
    expect(bomSource).toContain('prop="notes" label="备注"');
    expect(bomSource).toContain('draftEntryLabel');
    expect(bomSource).toContain('selectedRecipeEditable');
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
    expect(bomSource).toContain('return formatPriceUnit(skuOutputUnit.value);');
    expect(bomSource).toContain('{{ costDisplayUnit }}');
    expect(bomSource).toContain('元/kg');
  });

  it('keeps RAW Excel import relationship-only while requiring quantities for non-RAW rows', () => {
    expect(bomSource).toContain('成品含量（RAW可空）');
    expect(bomSource).toContain("materialCategory === 'RAW'");
    expect(bomSource).toContain("? null\n            : Number(quantityCell ?? 0)");
    expect(bomSource).toContain('辅料/包材成品含量必须大于 0');
  });
});
