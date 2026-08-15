import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸：OA 待办的通过/驳回**不许再打已停用的采购端点**。
 *
 * 2026-07-21 (#1557) 后端把 `/purchase/orders/{id}/finance-approve` `/finance-reject`
 * 停用了，直接抛 **410**「采购审批只能在 OA 审批中心处理」。而 RN 这两个屏
 * （2026-06-11 #782 建的）此前一直调它们 —— 也就是说卡片上的按钮**点了必失败**。
 *
 * ⚠️ 之所以一直没人发现：那种卡片**根本不出现**。待办查的是
 * `PurchaseOrderStatus.PENDING_FINANCE_REVIEW`，而 OA 投影从不经过这个状态，
 * prod 实测该状态采购单数恒为 0。死按钮藏在一个永远空的列表后面。
 * 2026-08-15 把取数改成按 OA 实例的当前节点取之后，卡片会真的出现 ——
 * 那一刻这个死按钮就变成用户点得到的了，所以必须同时换成 OA 动作端点。
 *
 * 守两件事：
 *   ① 两个屏都不许再出现 `purchaseFinanceApprove` / `purchaseFinanceReject`
 *   ② 走的是 `oaAction`（`POST /workflow/instances/{id}/actions`）
 *
 * ⚠️ 剥注释后再断言 —— 上面这段说明里就写着被禁的方法名，不剥会被自己的文档喂成假绿。
 */
describe('闸: OA 待办审批走活着的端点', () => {
  const SRC = path.join(__dirname, '..', '..', '..');

  const readCode = (rel: string): string => fs
    .readFileSync(path.join(SRC, rel), 'utf-8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/.*/g, ' ');

  const SCREENS = [
    'screens/oa/MyTodoListScreen.tsx',
    'screens/oa/TodoDetailScreen.tsx',
  ];

  it('两个 OA 屏都不再调用已停用(410)的采购财审端点', () => {
    for (const rel of SCREENS) {
      const code = readCode(rel);

      // 仪器自检: 确实读到了这一屏的审批代码
      expect(code.length).toBeGreaterThan(1000);
      expect(code).toContain('PURCHASE_FINANCE_REVIEW');

      expect(code).not.toContain('purchaseFinanceApprove');
      expect(code).not.toContain('purchaseFinanceReject');
      expect(code).toContain('oaAction');
    }
  });

  it('API 客户端的 oaAction 打的是 OA 统一动作入口, 且带乐观锁与幂等键', () => {
    const code = readCode('services/api/myTodoApiClient.ts');
    expect(code).toContain('oaAction');
    expect(code).toMatch(/workflow\/instances\/\$\{instanceId\}\/actions/);
    // expectedNodeId = 乐观锁; idempotencyKey = 后端强制要求, 缺了直接 400
    expect(code).toContain('expectedNodeId');
    expect(code).toContain('idempotencyKey');
  });
});
