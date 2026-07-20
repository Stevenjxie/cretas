import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');
const operationStart = source.indexOf('<el-table-column label="操作"');
const operationEnd = source.indexOf('</el-table>', operationStart);
const operations = source.slice(operationStart, operationEnd);

describe('production plan list information architecture', () => {
  it('keeps exactly the three read-only row-action groups', () => {
    expect(operationStart).toBeGreaterThan(0);
    expect(operations).toContain('>查看详情</el-button>');
    expect(operations).toContain('生产单据<el-icon');
    expect(operations).toContain('追溯与核算<el-icon');
    expect(operations).not.toContain('RowActionMenu');
    expect(operations).not.toContain('>核对结单</el-button>');
    expect(operations).not.toContain('>小结</el-button>');
    expect(operations).not.toContain('>确认入库</el-button>');
    expect(operations).not.toContain('>取消</el-button>');
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
