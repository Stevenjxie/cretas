import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const stocktakeSource = readFileSync(resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'), 'utf8');
const approvalSource = readFileSync(resolve(process.cwd(), 'src/views/workflow/pending.vue'), 'utf8');

describe('stocktake OA-only approval contract', () => {
  it('submits into OA and removes direct approval actions from the stocktake row', () => {
    expect(stocktakeSource).toContain('workflowInstanceId');
    expect(stocktakeSource).toContain("path: '/workflow/pending'");
    expect(stocktakeSource).toContain('前往 OA 审批中心');
    expect(stocktakeSource).not.toContain('@click="openApproveDialog(row)"');
    expect(stocktakeSource).not.toContain('@click="openRejectDialog(row)"');
  });

  it('keeps the OA center actionable for inventory-adjustment tasks', () => {
    expect(approvalSource).toContain("'INVENTORY_ADJUSTMENT'");
    expect(approvalSource).toContain('库存盘点');
  });

  it('labels an empty approval preview as no inventory impact', () => {
    expect(stocktakeSource).toContain('无实际差异，无库存影响');
  });
});
