import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');
const operationStart = source.indexOf('<el-table-column label="操作"');
const operationEnd = source.indexOf('</el-table>', operationStart);
const operations = source.slice(operationStart, operationEnd);

describe('production plan list information architecture', () => {
  it('keeps the three read-only groups and restores state-driven production operations', () => {
    expect(operationStart).toBeGreaterThan(0);
    expect(operations).toContain('>查看详情</el-button>');
    expect(operations).toContain('v-if="canShowProductionActions(row)"');
    expect(operations).toContain('生产操作<el-icon');
    expect(operations).toContain('command="complete"');
    expect(operations).toContain('>核对结单</el-dropdown-item>');
    expect(operations).toContain('command="interim-settle"');
    expect(operations).toContain('>生产小结</el-dropdown-item>');
    expect(operations).toContain('command="process-entry"');
    expect(operations).toContain('>逐道录入</el-dropdown-item>');
    expect(operations).toContain('command="stop-production"');
    expect(operations).toContain('command="cancel"');
    expect(operations).toContain('生产单据<el-icon');
    expect(operations).toContain('追溯与核算<el-icon');
    expect(operations).not.toContain('RowActionMenu');
    expect(operations).not.toContain('>确认入库</el-button>');
    expect(source).toContain("return canWrite.value && isUnfinishedStatus(String(row.status || ''))");
  });

  it('keeps summaries available and explains the finished-batch gate for cost accounting', () => {
    expect(operations).toContain('{{ yieldCostActionLabel(row) }}');
    expect(operations).toContain('生产计划汇总（随时查看）');
    expect(source).toContain('成品出厂核算（结单后）');
    expect(source).toContain('当前可先查看「生产计划汇总」');
    expect(operations).not.toContain(':disabled="String(row.status || \'\').toUpperCase() !== \'COMPLETED\'"');
  });

  it('consolidates import/export and selection-scoped batch actions', () => {
    expect(source).toContain('@command="handleDataCommand"');
    expect(source).toContain('导入/导出<el-icon');
    expect(source).toContain('v-if="selectedPlans.length > 0"');
    expect(source).toContain('批量操作 ({{ selectedPlans.length }})');
  });

  it('offers one selectable production document pack request without three print windows', () => {
    expect(source).toContain('下载单文件生产单据包');
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
});
