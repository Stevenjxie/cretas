import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('procurement unified OA contract', () => {
  const detail = source('src/views/procurement/orders/detail.vue');
  const pending = source('src/views/workflow/pending.vue');
  const router = source('src/router/index.ts');

  it('keeps purchase details read-only after submission and links to truthful progress', () => {
    expect(detail).toContain('/approval-progress');
    expect(detail).toContain("path: '/workflow/my-created'");
    expect(detail).not.toContain("path: '/workflow/my-participated'");
    expect(detail).not.toContain("finance-approve");
    expect(detail).not.toContain("finance-reject");
    expect(detail).not.toContain("submit-for-finance-review");
  });

  it('provides one pending-work entry and guards each action with the visible node', () => {
    expect(router).toContain("path: 'pending'");
    expect(router).toContain("name: 'WorkflowPending'");
    expect(pending).toContain('/workflow/instances/pending');
    expect(pending).toContain('/workflow/instances/${row.instanceId}/actions');
    expect(pending).toContain('expectedNodeId: row.currentNodeId');
    expect(pending).toContain('idempotencyKey: `oa-${action.toLowerCase()}-${row.instanceId}-${row.currentNodeId}`');
    expect(pending).toContain("operatingId.value = row.instanceId");
    // 可操作域 = 后端 executeDomainAction 里有分支的域。
    // ⚠️ 这里刻意不断言整个 new Set([...]) 字面量: 那种写法在加盘点那次就漏同步红过一次
    //   (见本行原注释), 加 BUDGET 时又红了一次 —— 它锁的是源码格式而不是意图。
    //   改为逐个断言成员, 新增域只加一行, 且不会被换行/顺序变化假红。
    const actionable = pending.match(
      /ACTIONABLE_MODULE_CODES\s*=\s*new Set\(\[([\s\S]*?)\]\)/,
    )?.[1] ?? '';
    for (const code of [
      'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT',
      'BUDGET',
    ]) {
      expect(actionable, `${code} 应在可操作白名单里 (后端已有对应 adapter 分支)`)
        .toContain(code);
    }
    // 筛选下拉改为 v-for MODULE_LABELS: 下拉与表格列共用同一份中文名
    expect(pending).toContain("SALES_ORDER: '销售订单'");
    expect(pending).toContain('v-for="(label, code) in MODULE_LABELS"');
  });
});
