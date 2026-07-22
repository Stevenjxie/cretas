import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('sales order OA web contract', () => {
  const detail = source('src/views/sales/orders/detail.vue');
  const list = source('src/views/sales/orders/list.vue');
  const api = source('src/api/salesFinanceReview.ts');

  it('does not predict approval from a frontend amount threshold', () => {
    expect(`${detail}${list}`).not.toContain('> 5000');
    expect(`${detail}${list}`).not.toContain('超过默认阈值');
    expect(`${detail}${list}`).toContain('当前已发布的销售订单 OA 规则');
  });

  it('reads the persisted OA instance after submission', () => {
    expect(api).toContain('/sales/orders/${orderId}/approval-progress');
    expect(api).toContain('hasInstance: boolean');
    expect(detail).toContain('getSalesOrderApprovalProgress');
    expect(detail).toContain('approvalProgress.currentNodeNames');
    expect(detail).toContain('approvalProgress.approverRoles');
    expect(detail).toContain('前往个人 OA 查看');
    expect(detail).toContain('await Promise.all([loadOrder(), loadApprovalProgress()])');
  });

  it('keeps approval actions inside the unified OA workbench', () => {
    expect(detail).toContain('前往 OA 审批中心');
    expect(detail).not.toContain('/finance-approve');
    expect(detail).not.toContain('/finance-reject');
    expect(detail).not.toContain('openFinanceReview');
    expect(detail).not.toContain('submitFinanceReview');
    expect(detail).not.toContain('提交财务审核');
    expect(list).not.toContain('提交财务审核');
    expect(list).not.toContain('免审通过');
    expect(list).toContain('按 OA 规则自动通过');
  });

  it('does not query or render linked purchase orders without procurement read access', () => {
    expect(detail).toContain("const canViewLinkedPurchases = computed(() => permissionStore.canAccess('procurement'))");
    expect(detail).toContain('if (!canViewLinkedPurchases.value || !factoryId.value || !orderId.value) return;');
    expect(detail).toContain('<el-tab-pane v-if="canViewLinkedPurchases" name="purchase">');
  });

  it('keeps batch submission truthful instead of aggregating guessed outcomes', () => {
    expect(list).not.toContain('pendingReviewCount');
    expect(list).not.toContain('exemptCount');
    expect(list).toContain('系统将逐单按当前已发布的销售订单 OA 规则自动路由');
  });
});
