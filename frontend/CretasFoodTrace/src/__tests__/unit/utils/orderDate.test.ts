import { readFileSync } from 'fs';
import { resolve } from 'path';
import { todayIso } from '../../../utils/orderDate';

/**
 * 下单日期契约 —— 2026-08-04 客户反馈「不能新建采购订单，提示 request failed code 400」。
 *
 * 根因: 后端 CreatePurchaseOrderRequest 上 `@NotNull(message = "下单日期不能为空")`,
 * 而采购建单整条链上都没有这个字段 —— 界面不收集、RN 类型没声明、payload 不送。
 * prod 实测: 按旧形状发 → 400「下单日期不能为空」(hintTarget: orderDate);
 * 只补这一个字段 → 请求穿过校验进入业务层。销售建单一直在送, 所以只有采购坏。
 */
describe('todayIso — 下单日期取本地日历日', () => {
  it('产出 YYYY-MM-DD', () => {
    expect(todayIso()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('🔴 按本地时区取, 不用 toISOString —— 东八区凌晨不能记成前一天', () => {
    // 2026-08-05 00:30 (+08:00) 的绝对时刻: UTC 是 2026-08-04T16:30Z。
    // toISOString().slice(0,10) 会得到 "2026-08-04"(前一天), 本函数必须给 "2026-08-05"。
    const localMidnightish = new Date(2026, 7, 5, 0, 30, 0);
    expect(todayIso(localMidnightish)).toBe('2026-08-05');
  });

  it('月/日补零', () => {
    expect(todayIso(new Date(2026, 0, 9, 12, 0, 0))).toBe('2026-01-09');
  });
});

describe('采购建单必须携带 orderDate', () => {
  const screen = readFileSync(
    resolve(__dirname, '../../../screens/factory-admin/inventory/PurchaseOrderCreateScreen.tsx'),
    'utf8',
  );
  const client = readFileSync(
    resolve(__dirname, '../../../services/api/purchaseApiClient.ts'),
    'utf8',
  );

  it('payload 里有 orderDate', () => {
    expect(screen).toContain('orderDate: todayIso()');
  });

  it('类型声明成必填 —— 让 tsc 在每个调用点守这条契约', () => {
    const iface = client.match(/export interface CreatePurchaseOrderRequest \{[\s\S]*?\n\}/)?.[0] ?? '';
    // 没切到接口则断言无效
    expect(iface).not.toBe('');
    expect(iface).toContain('orderDate: string;');
    // 声明成可选就等于没守
    expect(iface).not.toContain('orderDate?:');
  });

  it('两个建单屏共用同一个 todayIso, 不留私有副本', () => {
    const sales = readFileSync(
      resolve(__dirname, '../../../screens/factory-admin/inventory/SalesOrderCreateScreen.tsx'),
      'utf8',
    );
    expect(sales).toContain("from '../../../utils/orderDate'");
    // 私有副本会和共用实现漂移(那份还用 toISOString 按 UTC 截断)
    expect(sales).not.toContain('const todayIso = () =>');
  });
});
