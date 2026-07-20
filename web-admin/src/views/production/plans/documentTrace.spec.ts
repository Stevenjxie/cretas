import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { documentTraceTarget, traceDocumentLabel } from './documentTrace';

const pageSource = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');
const apiSource = readFileSync(resolve(__dirname, '../../../api/productionPlan.ts'), 'utf8');

describe('production document trace navigation', () => {
  it('routes each supported document to its ordinary business module', () => {
    expect(documentTraceTarget({ documentType: 'SALES_ORDER', documentId: 'so-1' }))
      .toEqual({ path: '/sales/orders/so-1' });
    expect(documentTraceTarget({ documentType: 'PURCHASE_ORDER', documentId: 'po-1' }))
      .toEqual({ path: '/procurement/orders/po-1' });
    expect(documentTraceTarget({ documentType: 'MATERIAL_REQUISITION', documentId: 'mr-1' }))
      .toEqual({ path: '/production/material-requisitions', query: { documentId: 'mr-1' } });
    expect(documentTraceTarget({ documentType: 'PRODUCTION_BATCH', documentId: '101' }))
      .toEqual({ path: '/production/batches/101' });
  });

  it('uses clear business labels', () => {
    expect(traceDocumentLabel('PRODUCTION_SETTLEMENT')).toBe('核对结单');
    expect(traceDocumentLabel('FINISHED_GOODS_BATCH')).toBe('成品批次');
  });

  it('exposes the trace from each ordinary production-plan row', () => {
    expect(pageSource).toContain('<el-dropdown-item command="trace">单据追溯</el-dropdown-item>');
    expect(pageSource).toContain("if (command === 'trace')");
    expect(pageSource).toContain('void openDocumentTrace(row)');
    expect(pageSource).toContain('生产计划单据追踪');
    expect(pageSource).toContain('documentTrace.documents');
    expect(apiSource).toContain('/production-plans/${planId}/document-trace');
  });
});
