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

  /*
   * 2026-08-16 加固。上面守的是「那两个屏不调它」，而当时仍有**第二条**同样的死路：
   * `PurchaseOrderFinanceReviewScreen` 也在打 410 的 `/finance-approve`。它双重不可达
   * （喂它的列表查 PENDING_FINANCE_REVIEW = 全库 0 行；且没有任何地方 navigate 到那个列表屏），
   * 所以两个月没人撞到 —— 但只要有人加个入口或回填那个状态，它立刻变成活的死按钮。
   *
   * ⇒ 守的东西从「某两个屏不调它」抬到「**全仓不许再有采购侧的这三个死端点**」。
   * ⛔ 退货单与销售单的 finance-approve/reject 是另外两组**仍然活着**的端点，不在此列。
   */
  describe('全仓不许再出现采购侧的已停用(410)端点', () => {
    const DEAD_PURCHASE_SEGMENTS = ['finance-approve', 'finance-reject', 'submit-for-finance-review'];

    it('purchaseApiClient 不再构造这三个端点', () => {
      const code = readCode('services/api/purchaseApiClient.ts');
      expect(code.length).toBeGreaterThan(1000);              // 仪器自检
      expect(code).toContain('purchaseApiClient');            // 确实是这个文件
      for (const seg of DEAD_PURCHASE_SEGMENTS) {
        expect(code).not.toContain(seg);
      }
    });

    it('myTodoApiClient 不再构造 purchase 侧的财审端点, 但 sales/return 两组仍在', () => {
      const code = readCode('services/api/myTodoApiClient.ts');

      // 阴性: 采购那两条没了
      expect(code).not.toMatch(/purchase\/orders\/\$\{orderId\}\/finance-(approve|reject)/);
      expect(code).not.toContain('purchaseFinanceApprove');
      expect(code).not.toContain('purchaseFinanceReject');

      // 🔴 阳性对照: 扫描器确实看得见「这一类字符串」——
      //    否则上面那几条 not.toContain 无论如何都会通过(恒真式)。
      // ⚠️ 末尾的 ` 不能省: 不锚定收尾时 /…finance-approve/ 会把 `finance-approve-XX`
      //    也匹配上, 端点被改名它照样绿 —— 实测变异时就是这么发现这条对照原本是松的。
      expect(code).toMatch(/sales\/orders\/\$\{orderId\}\/finance-approve`/);
      expect(code).toMatch(/return-orders\/\$\{returnOrderId\}\/finance-approve`/);
    });

    it('那两块死屏已经不在仓里, 也不在导航与路由类型里', () => {
      for (const rel of [
        'screens/factory-admin/inventory/PurchaseOrderFinanceReviewScreen.tsx',
        'screens/factory-admin/inventory/PurchaseOrderFinanceReviewListScreen.tsx',
      ]) {
        expect(fs.existsSync(path.join(SRC, rel))).toBe(false);
      }
      // 阳性对照: 同目录别的屏还在, 证明这个路径拼得对(不是把不存在的目录测成了 false)
      expect(fs.existsSync(path.join(SRC, 'screens/factory-admin/inventory/PurchaseOrderCreateScreen.tsx'))).toBe(true);

      for (const rel of [
        'navigation/factory-admin/FAManagementStackNavigator.tsx',
        'types/navigation.ts',
      ]) {
        expect(readCode(rel)).not.toContain('PurchaseOrderFinanceReview');
      }
    });
  });
});
