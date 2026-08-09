import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const panel = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/materials/PendingPurchaseReceivingPanel.vue'),
  'utf8',
);
const orderDetail = readFileSync(
  resolve(process.cwd(), 'src/views/sales/orders/detail.vue'),
  'utf8',
);

/**
 * 🔴 2026-08-07 真机走客供料全流程时撞到的两个阻断。两条都不是「某处写错」，
 * 是「前端自己造的值 / 前端漏发的字段」撞上后端的约束，而且**必然**撞上。
 */
describe('客供料收货 —— 幂等键长度', () => {
  /**
   * 后端 CustomerSuppliedMaterialReceiptRequest#idempotencyKey 是 @Size(max = 64)。
   * 原来前端拼的是
   *   `warehouse-customer-receipt-`(28) + taskId(UUID 36) + `-`(1) + Date.now()(13) = 78
   * taskId 恒为 UUID，所以**每一次**客户来料收货都必然 400「幂等键不能超过64个字符」——
   * 这条路上没有任何人收得了货（prod 实测: 全库 CUSTOMER_SUPPLIED 订单 0 张、客供料需求 0 行）。
   */
  it('生成的幂等键必须稳在 64 字符内', () => {
    const m = panel.match(/customerIdempotencyKey\.value\s*=\s*\r?\n?\s*(`[^`]+`)/);
    expect(m, '找不到幂等键的生成表达式').not.toBeNull();
    const tpl = m![1];
    // 用真实形状的 UUID taskId 求值，避免只断言写法
    const taskId = '5d76e7e5-5271-4f41-96e1-88999ecf122b';
    // eslint-disable-next-line no-new-func
    const key = new Function('task', `return ${tpl};`)({ taskId });
    expect(key.length).toBeLessThanOrEqual(64);
    // 仍要能区分不同任务
    // eslint-disable-next-line no-new-func
    const other = new Function('task', `return ${tpl};`)({ taskId: '11111111-2222-3333-4444-555555555555' });
    expect(key).not.toEqual(other);
  });

  /**
   * 无订单入库申请是第二条客供入库路径。生产真机复现过旧写法
   * `warehouse-arrival-${taskId}-${Date.now()}` 长 68，首笔收货稳定被后端 400 拒绝。
   * 两条路径必须各自锁住，不能只修销售订单客供料那一条。
   */
  it('无订单入库申请的幂等键也必须稳在 64 字符内且区分每次收货', () => {
    const block = panel.slice(
      panel.indexOf('async function openArrivalReceive'),
      panel.indexOf('function onArrivalMaterialChange'),
    );
    const m = block.match(/idempotencyKey:\s*(`[^`]+`)/);
    expect(m, '找不到无订单入库申请幂等键的生成表达式').not.toBeNull();
    const tpl = m![1];
    const task = { taskId: '5d76e7e5-5271-4f41-96e1-88999ecf122b' };
    // eslint-disable-next-line no-new-func
    const build = new Function('task', 'Date', `return ${tpl};`);
    const first = build(task, { now: () => 1786197116288 });
    const second = build(task, { now: () => 1786197116289 });
    expect(first.length).toBeLessThanOrEqual(64);
    expect(second.length).toBeLessThanOrEqual(64);
    expect(first.startsWith(`arrival-${task.taskId}-`)).toBe(true);
    expect(first).not.toEqual(second);
  });
});

describe('销售订单编辑产品行 —— 必填字段回传', () => {
  /**
   * UpdateSalesOrderRequest 把 processingMode / materialSupplyMode 标了 @NotNull，
   * 而 saveEditItems 原本只发 { items, version } → 400「加工方式不能为空, 物料供应方式不能为空」。
   * 配上「单价为空不许提审」那道闸，用户被彻底卡死：
   *   建单单价留空 → 提审拦「请先补全单价」→ 编辑产品行 → 保存拦「加工方式不能为空」→ 出不去。
   * 不限客供料，任何草稿订单都撞得上。
   */
  it('saveEditItems 必须把订单已有的加工方式/物料供应方式原样回传', () => {
    const block = orderDetail.slice(
      orderDetail.indexOf('async function saveEditItems'),
      orderDetail.indexOf('onMounted(async'),
    );
    expect(block).toContain('processingMode: current?.processingMode');
    expect(block).toContain('materialSupplyMode: current?.materialSupplyMode');
    // 乐观锁的 version 不能在这次改动里丢掉
    expect(block).toContain('version: current?.version');
  });
});
