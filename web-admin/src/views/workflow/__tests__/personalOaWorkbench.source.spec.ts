import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('personal OA workbench contract', () => {
  const router = source('src/router/index.ts');
  const pending = source('src/views/workflow/pending.vue');
  const acted = source('src/views/workflow/acted.vue');
  const copied = source('src/views/workflow/copied.vue');

  it('routes every authenticated role through dashboard access to four personal queues', () => {
    expect(router).toContain("title: '个人 OA'");
    expect(router).toContain("path: 'pending'");
    expect(router).toContain("path: 'my-created'");
    expect(router).toContain("path: 'acted'");
    expect(router).toContain("path: 'copied'");
    expect(router).toContain("title: '待我审批', module: 'dashboard'");
    expect(router).toContain("title: '我发起的', module: 'dashboard'");
    expect(router).toContain("title: '已处理', module: 'dashboard'");
    expect(router).toContain("title: '抄送我的', module: 'dashboard'");
  });

  it('uses truthful endpoints and only enables domains backed by the unified action adapter', () => {
    expect(acted).toContain('/workflow/instances/acted');
    expect(copied).toContain('/workflow/instances/copied');
    // 调拨与盘点陆续接入统一 OA, 本断言一直停在两项, 在 main 上就是红的
    expect(pending).toContain(
      "new Set(['PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT'])",
    );
    expect(pending).toContain('v-if="canAct(row)"');
    expect(pending).toContain('该业务域正在接入统一 OA，当前仅可查看审批进度');
  });

  it('keeps user-facing Chinese readable instead of accepting mojibake', () => {
    expect(acted).toContain('已处理');
    expect(copied).toContain('抄送我的');
    expect(`${acted}${copied}`).not.toMatch(/[锛閿鍔寮]/u);
  });

  it('renders pending business identity, approver roles and local time without internal-code leakage', () => {
    const enumDisplay = source('src/utils/enumDisplay.ts');
    expect(enumDisplay).toContain("SALES_ORDER: '销售订单'");
    expect(enumDisplay).toContain("PURCHASE_ORDER: '采购订单'");
    expect(enumDisplay).toContain("finance_manager: '财务主管'");
    expect(pending).toContain("import { formatDateTime } from '@/utils/dateFormat'");
    expect(pending).toContain('{{ formatDateTime(row.initiatedAt) }}');
    expect(pending).toContain('row.approverRoles?.map');
  });
});
