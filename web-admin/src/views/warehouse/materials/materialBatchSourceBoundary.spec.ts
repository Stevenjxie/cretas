import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/materials/list.vue'),
  'utf8',
);

describe('material batch source-only boundary', () => {
  it('does not expose ordinary no-source inbound creation', () => {
    expect(source).not.toContain('>入库登记</el-button>');
    expect(source).not.toContain('@click="handleCreate"');
    expect(source).not.toContain('批次数量仅由仓储待收货、退货、调拨、盘点或受控调整任务写入');
  });

  it('does not expose direct batch replenish controls or mutation calls', () => {
    expect(source).not.toContain('>续入</el-button>');
    expect(source).not.toContain('/replenish`');
    expect(source).not.toContain('handleReplenishSubmit');
  });

  it('uses the unified inbound-task and material-batch workspace', () => {
    expect(source).toContain('<span class="page-title">入库任务与批次</span>');
    expect(source).toContain('`待收货 ${receivingCounts.WAITING_RECEIVE}`');
    expect(source).toContain('`已入库批次 ${pagination.total}`');
    expect(source).toContain('v-if="summaryTruncated" class="summary-warning"');
    expect(source).not.toContain('原料 / 物料批次');
    expect(source).not.toContain('原料 / 物料管理 (采购入库)');
  });
});
