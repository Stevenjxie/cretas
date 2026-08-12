import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「创建发货单」的计划数量必须能填小数。
 *
 * <h2>🔴 2026-08-13 真机 E2E 抓到的死结 (LIUSHANMEN 生产)</h2>
 * 订单行 0.5kg / 0.2kg, 点「创建发货单」, 两行都显示 1。原因是
 * {@code <el-input-number :min="1">} —— Element Plus 会把 <b>模型值向上钳到 min</b>,
 * 预填的 0.5 一打开就被改写成 1, 手动再填 0.5 也会被弹回 1。
 *
 * <p>而后端 {@code SalesServiceImpl} 只接受 ≤「下单量 − 已安排」:
 * <pre>发货数量超过订单行剩余可安排数量（剩余 0.5000kg）</pre>
 * 两边合起来 = <b>0.5 的订单行永远发不出货</b> —— 界面把唯一合法的值变成了填不进去的值。
 *
 * <p>这是防呆规则里最坏的一种: <b>闸是对的, 但没给出遵守它的办法</b>。
 * 与「提示把人指向错误方向」(见 materialLineAllocation.source.spec.ts) 同源。
 *
 * <h2>为什么必须锁住 min</h2>
 * 按 kg 计价的小数下单量是这门生意的常态, 成品行同样中招; 物料开卖之后
 * (原料/辅料/包材几乎全是 kg) 触发频率更高。谁把 min 改回 1, 这条死结立刻复活,
 * 而现场表现只是「数量怎么自己变成 1 了」, 没人会怀疑到一个输入框属性上。
 */
const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/**
 * 「计划发货数量」表单项到下一个 form-item 之间的片段, <b>已剥掉 HTML 注释</b>。
 *
 * ⚠️ 剥注释这一步不能省: 那段代码上方的注释里就写着 `:min="1"`(在讲缺陷本身),
 * 不剥的话这条闸会命中自己的注释, 修好了照样红 —— 「锚在注释上的假红」。
 */
function planQuantityBlock(): string {
  const start = source.indexOf('label="计划发货数量"');
  expect(start, '找不到「计划发货数量」表单项 —— 结构变了, 这条闸需要重写').toBeGreaterThan(-1);
  const end = source.indexOf('</el-form-item>', start);
  expect(end).toBeGreaterThan(start);
  return source.slice(start, end).replace(/<!--[\s\S]*?-->/g, '');
}

describe('创建发货单 · 计划发货数量', () => {
  it('① 不再把下限钉在 1 (钉住 1 就会把 0.5 的行改写成 1, 且改不回来)', () => {
    const block = planQuantityBlock();
    expect(block, ':min="1" 会把模型值向上钳到 1 —— 小数订单行就此发不出货')
      .not.toMatch(/:min="1"/);
    expect(block, '下限应当是 0 (>0 由后端 DELIVERY_QUANTITY_INVALID 兜)')
      .toMatch(/:min="0"/);
  });

  it('② 允许小数 —— precision 必须显式给, 且不能是 0', () => {
    const block = planQuantityBlock();
    const precision = block.match(/:precision="(\d+)"/);
    expect(precision, '没有 :precision 时, 步进/失焦的取整行为随版本变, 必须显式声明').not.toBeNull();
    expect(Number(precision![1]), 'precision=0 等于禁止小数, 与缺陷同效').toBeGreaterThan(0);
  });

  it('③ 有上限, 且上限来自订单行下单量 (否则能随手填出必然 409 的数)', () => {
    expect(planQuantityBlock()).toMatch(/:max="item\.maxQuantity"/);
    expect(source, 'maxQuantity 必须在构造 items 时按下单量填好')
      .toContain('maxQuantity: Number(item.quantity) > 0 ? Number(item.quantity) : undefined');
  });

  /**
   * ⚠️ 预填值本身一直是对的 (`item.quantity`) —— 缺陷全部来自输入框把它改写掉。
   * 断言它没被改成「取整」之类的「顺手修复」。
   */
  it('④ 预填仍然是订单行原值, 不做任何取整', () => {
    expect(source).toContain('deliveredQuantity: item.quantity || 0,');
  });
});

/**
 * 仓库端「确认发货」的实际数量输入是这次修复的对照样板 —— 它一直是对的
 * (min 0 / max 计划量 / 2 位小数)。两端口径必须一致, 否则销售能计划的数量
 * 仓库填不进去, 或者反过来。
 */
describe('对照: 仓库确认发货的实际数量输入', () => {
  const warehouseSource = readFileSync(
    resolve(__dirname, '..', '..', '..', 'warehouse', 'shipments', 'list.vue'),
    'utf8',
  );

  it('实际数量允许小数且有上限 (销售端向它看齐)', () => {
    expect(warehouseSource).toMatch(/:min="0"/);
    expect(warehouseSource).toMatch(/:max="row\.plannedQty"/);
    expect(warehouseSource).toMatch(/:precision="2"/);
  });
});
