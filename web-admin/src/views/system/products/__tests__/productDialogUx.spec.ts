import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('SKU editor dialog UX', () => {
  it('keeps conversion labels readable and primary actions visible', () => {
    expect(source).toContain('class="product-edit-dialog"');
    expect(source).toContain('label-width="120px"');
    expect(source).toContain(':modal-class="\'product-edit-modal\'"');
    expect(source).toContain('.product-edit-modal .el-dialog__footer');
    expect(source).toContain('position: sticky');
    expect(source).toContain('aria-label="上移工序"');
    expect(source).toContain('aria-label="下移工序"');
    expect(source).toContain('aria-label="移除工序"');
    expect(source).toContain('label="销售单位 / 净含量"');
    expect(source).toContain('class="sku-measure-row"');
    expect(source).toContain('v-model="netContentUnit"');
    expect(source).toContain('g/kg/ml/L');
    expect(source).not.toContain('标准单位换算（1${formData.unit');
  });

  it('keeps semi-finished base units editable and exposes a real preview-first import flow', () => {
    expect(source).toContain('if (!formData.unit) formData.unit = \'kg\'');
    expect(source).not.toContain(':disabled="isSemiFinishedSku"');
    expect(source).toContain('v-model="importDialogVisible"');
    expect(source).toContain('/product-types/import/template');
    expect(source).toContain('/product-types/import/preview');
    expect(source).toContain('/product-types/import/confirm');
    expect(source).toContain('/upload/product-image');
    expect(source).toContain("formData.append('imageMappings', JSON.stringify(allMappings))");
    expect(source).toContain('accept=".xlsx"');
    expect(source).not.toContain('accept=".xlsx,.xls"');
    expect(source).toContain('prop="specification" label="生成规格"');
    expect(source).toContain("{{ row.specification || '—' }}");
  });

  it('keeps category authoritative, scopes suggestions, and explains multi-box rules', () => {
    const dialogSource = source.slice(source.indexOf('class="product-edit-dialog"'));
    expect(dialogSource.indexOf('label="产品大类"')).toBeLessThan(dialogSource.indexOf('label="产品名称"'));
    expect(source).toContain('params: { name, productCategory: formData.productCategory || undefined }');
    expect(source).toContain('名称建议只在当前大类内匹配，绝不反写或清空大类');
    expect(source).not.toContain('formData.productCategory = newCat');
    expect(source).toContain('const exactNameDuplicate = computed');
    expect(source).toContain('同厂已存在同名 SKU');
    expect(source).toContain('class="packaging-rule-note"');
    expect(source).toContain('一个 SKU 只有一个基本单位和一份标准克重');
    expect(source).toContain('添加多装箱包装规则');
    expect(source).toContain('reconcilePackagingSpecs(formData.productCategory');
    expect(source).toContain('isCurrentCategorySuggestion(');
    expect(source).toContain("recommendation?.source === 'PUBLISHED_WORKFLOW'");
    expect(source).toContain("recommendation?.reasonCode === 'COMPLETE_PUBLISHED_WORKFLOW'");
    expect(source).not.toContain("ElMessage.warning(res.message || res.data?.message || '暂未生成推荐工序");
  });

  it('keeps the quick process drawer with searchable, filterable and paginated catalog UX', () => {
    expect(source).toContain('@click="handleConfigProcesses(row)"');
    expect(source).toContain('placeholder="搜索工序名称 / 编码 / 类别标签"');
    expect(source).toContain('v-model="processCategoryFilter"');
    // 工序主数据不再承载计量单位；快捷关联只按类别和关联状态筛选。
    expect(source).not.toContain('v-model="processOutputUnitFilter"');
    expect(source).toContain('v-model="processRelationFilter"');
    expect(source).toContain('结果 {{ filteredProcessCatalog.length }} / 总计 {{ availableProcesses.length }}');
    expect(source).toContain('v-model:current-page="processCatalogPage"');
    expect(source).toContain('已关联 · 第 {{ linkedProcessById.get(proc.id)?.processOrder }} 道');
  });
});
