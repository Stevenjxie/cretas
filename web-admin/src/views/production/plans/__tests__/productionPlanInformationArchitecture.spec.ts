import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');
const mainTableStart = source.indexOf('class="wide-table business-list-table"');
const operationLabel = source.indexOf('label="操作"', mainTableStart);
const operationStart = source.lastIndexOf('<el-table-column', operationLabel);
const operationEnd = source.indexOf('</el-table>', operationStart);
const operations = source.slice(operationStart, operationEnd);

describe('production plan list information architecture', () => {
  it('keeps high-frequency execution actions visible and moves low-frequency actions into more', () => {
    expect(operationStart).toBeGreaterThan(0);
    expect(source).toContain('class="plan-number-link"');
    expect(source).toContain('@click="handleViewPlan(row)"');
    expect(operations).toContain('v-if="canShowProductionActions(row)"');
    expect(operations).toContain('{{ processEntryActionLabel(row) }}');
    expect(operations).toContain('@click="openProcessEntry(row)"');
    expect(operations).toContain('{{ settlementActionLabel(row) }}');
    expect(operations).toContain('@click="handlePrimarySettlementAction(row)"');
    expect(operations).toContain('>档案与核算</el-button>');
    expect(operations).toContain('更多<el-icon');
    expect(operations).toContain('command="stop-production"');
    expect(operations).toContain('command="cancel"');
    expect(operations).not.toContain('生产操作<el-icon');
    expect(operations).not.toContain('生产单据<el-icon');
    expect(operations).not.toContain('追溯与核算<el-icon');
    expect(operations).not.toContain('>查看详情</el-button>');
    expect(operations).not.toContain('RowActionMenu');
    expect(operations).not.toContain('>确认入库</el-button>');
    expect(source).toContain("return canWrite.value && isUnfinishedStatus(String(row.status || ''))");
  });

  it('consolidates documents, trace and accounting into one archive center', () => {
    expect(source).toContain('v-model="archiveCenterVisible"');
    expect(source).toContain('档案与核算 —');
    expect(source).toContain('<el-tab-pane label="生产单据" name="documents">');
    expect(source).toContain('<el-tab-pane label="计划档案" name="trace">');
    expect(source).toContain('<el-tab-pane label="汇总与核算" name="accounting">');
    expect(source).toContain("openArchiveDocument('work-order')");
    expect(source).toContain("openArchiveDocument('material-requisition')");
    expect(source).toContain("openArchiveDocument('batching-sheet')");
    expect(source).toContain("openArchiveDocument('document-pack')");
    expect(source).toContain('@click="openArchiveTrace"');
    expect(source).toContain('@click="openArchiveSummary"');
    expect(source).toContain('@click="openArchiveYieldCost"');
  });

  it('keeps summaries available and explains the finished-batch gate for cost accounting', () => {
    expect(source).toContain('{{ yieldCostActionLabel(archiveCenterPlan) }}');
    expect(source).toContain('生产计划汇总');
    expect(source).toContain('成品出厂核算（结单后）');
    expect(source).toContain('当前可先查看「生产计划汇总」');
    expect(source).toContain('实际耗用、出成率、成本、质检和证据属于完工批次事实');
  });

  it('consolidates import/export and selection-scoped batch actions', () => {
    expect(source).toContain('@command="handleDataCommand"');
    expect(source).toContain('导入/导出<el-icon');
    expect(source).toContain('v-if="productionBatchMode && selectedPlans.length > 0"');
    expect(source).toContain('{{ productionBatchMode ? \'退出批量打印\' : \'批量打印\' }}');
    expect(source).toContain('批量操作 ({{ selectedPlans.length }})');
  });

  it('offers one selectable production document pack request without three print windows', () => {
    expect(source).toContain('生产单据包 PDF');
    expect(source).toContain('一次下载工单、领料单和配料单');
    expect(source).toContain('v-for="chapter in PRODUCTION_DOCUMENT_CHAPTERS"');
    expect(source).toContain('不会打开三个打印窗口');
    expect(source).not.toContain('window.open(');
  });

  it('uses localized actual quantities and business sales-order navigation', () => {
    expect(source).toContain('{{ formatPlanActualQuantity(row) }}');
    expect(source).toContain('{{ sourceOrderDisplay(row) }}');
    expect(source).toContain('@click="openSourceOrder(row)"');
    expect(source).not.toContain('{{ viewPlan.actualQuantity || \'-\' }}');
  });

  it('keeps the complete footer summary on the same keyword and status filters as the list', () => {
    expect(source).toContain("...(searchForm.value.status ? { status: searchForm.value.status } : {})");
    expect(source).toContain("...(searchForm.value.keyword.trim() ? { keyword: searchForm.value.keyword.trim() } : {})");
    expect(source).toContain("useListSummary('productionPlan', summaryRequest)");
    expect(source).toContain(':stats="footerSummary?.stats ?? []"');
  });

  it('shows finished products as the primary identity and keeps the workflow route behind a compact preview', () => {
    expect(source).toContain('label="生产成品"');
    expect(source).toContain('{{ planFinishedProductNames(row).join(\'、\') }}');
    expect(source).toContain('aria-label="预览工序路线"');
    expect(source).toContain('>工序图</el-button>');
    expect(source).toContain('<WorkflowRoutePreview');
    expect(source).toContain('item.workflowId === selectedWorkflowId');
    expect(source).toContain('item.definitionVersion === selectedWorkflowVersion');
    expect(source).toContain('未读取到计划固定的工序路线');
  });

  it('separates inventory-production summaries from sales-order final settlement', () => {
    expect(source).toContain("return row.sourceType === 'SAFETY_STOCK' ? '生产小结' : '核对结单'");
    expect(source).toContain('command="stop-production"');
    expect(source).toContain('v-if="row.sourceType === \'SAFETY_STOCK\'"');
    expect(source).toContain('label="计划成品数量"');
    expect(source).toContain('数量来自销售订单产品行，创建生产计划时不可修改');
  });

  it('offers only inventory production and sales-order production for new plans', () => {
    const sourceTypeStart = source.indexOf('<el-radio-group v-model="planForm.sourceType"');
    const sourceTypeEnd = source.indexOf('</el-radio-group>', sourceTypeStart);
    const sourceTypeControls = source.slice(sourceTypeStart, sourceTypeEnd);
    expect(sourceTypeControls).toContain('label="SAFETY_STOCK"');
    expect(sourceTypeControls).toContain('label="CUSTOMER_ORDER"');
    expect(sourceTypeControls).not.toContain('label="MANUAL"');
    expect(sourceTypeControls).not.toContain('label="AI_FORECAST"');
  });
});
