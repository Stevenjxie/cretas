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

  /**
   * 入口在 #1662 (2026-07-23 「simplify plan row actions」) 改过: 行内的
   * 「追溯与核算」下拉 → 「档案与核算」弹窗, 追溯挪进弹窗的「计划档案」页签。
   * 那个 PR 改了旁边的 productionPlanInformationArchitecture.spec.ts, 漏了这一份,
   * 于是这条用例从那天起一直红着 —— 而 vitest 当时不在任何 push 门禁里, 没人看见。
   *
   * 断言的是**入口可达**而不是某一段 markup 的字面写法: 行内有「档案与核算」入口,
   * 弹窗里有「查看单据追溯」按钮, 且它确实接到 openDocumentTrace 上。
   */
  it('exposes the trace from each ordinary production-plan row', () => {
    expect(pageSource).toContain('档案与核算');
    expect(pageSource).toContain('@click="openArchiveTrace"');
    expect(pageSource).toContain('查看单据追溯');
    expect(pageSource).toContain("if (command === 'trace')");
    expect(pageSource).toContain('void openDocumentTrace(row)');
    expect(pageSource).toContain('生产计划单据追踪');
    expect(pageSource).toContain('documentTrace.documents');
    expect(apiSource).toContain('/production-plans/${planId}/document-trace');
  });
});
