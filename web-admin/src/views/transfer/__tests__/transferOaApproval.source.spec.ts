import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('inventory transfer unified OA contract', () => {
  const list = source('src/views/transfer/list.vue');
  const detail = source('src/views/transfer/detail.vue');
  const pending = source('src/views/workflow/pending.vue');
  const myCreated = source('src/views/workflow/my-created.vue');

  it('exposes submit for draft transfers in list and detail', () => {
    expect(list).toContain("row.status === 'DRAFT'");
    expect(list).toContain('/transfers/${row.id}/request');
    expect(list).toContain('提交审批');
    expect(detail).toContain("transfer.status === 'DRAFT'");
    expect(detail).toContain('提交 OA 审批');
  });

  it('removes local approval actions from transfer business pages', () => {
    expect(detail).not.toContain('/transfers/${transferId.value}/approve');
    expect(detail).not.toContain('/transfers/${transferId.value}/reject');
    expect(detail).not.toContain('@click="handleAction(\'approve\')"');
    expect(detail).not.toContain('@click="handleAction(\'reject\')"');
    expect(detail).toContain("path: '/workflow/my-created'");
    expect(detail).toContain('该调拨单正在统一 OA 审批');
  });

  it('makes inventory transfer actionable and navigable in personal OA', () => {
    expect(pending).toContain("'INVENTORY_TRANSFER'");
    // 契约是「库存调拨可作为 OA 待办的业务类型筛选项」, 不是"必须写成一行硬编码 el-option"。
    // #1681 (2026-07-23) 原本硬编码了 label="库存调拨" value="INVENTORY_TRANSFER";
    // #1966 (2026-07-29) 把四个选项重构成 v-for MODULE_LABELS —— 功能没变, 但字面量没了,
    // 本断言自那天起一直红。断言随之改写而非删除: 只要 MODULE_LABELS 里有这条、
    // 且下拉是从 MODULE_LABELS 渲染的, 选项就真实存在。
    expect(pending).toMatch(/INVENTORY_TRANSFER:\s*'库存调拨'/);
    expect(pending).toContain('v-for="(label, code) in MODULE_LABELS"');
    expect(myCreated).toContain("row.moduleCode === 'INVENTORY_TRANSFER'");
    expect(myCreated).toContain('router.push(`/transfer/${row.businessEntityId}`)');
  });
});
