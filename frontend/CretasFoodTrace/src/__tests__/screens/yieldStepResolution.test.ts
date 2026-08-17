/**
 * 逐道报工定位判定。两条断言各自复现 2026-08-17 生产实测抓到的一个缺陷,
 * 夹具直接用当天 F006 批次 10759 的真实形状:
 *   ①1785 拆骨 COMPLETED / ②1786 卤制 PENDING / ③1787 拼装分装 PENDING,
 *   而 /yield 的 steps 是空的 (那批次一条报工记录都没有)。
 */
import fs from 'fs';
import path from 'path';
import {
  resolveInitialStepIndex,
  resolveInputUnit,
  resolveStepPhase,
  type StepPhase,
  type StepTaskLike,
} from '../../screens/processing/yieldStepResolution';

const TASKS: StepTaskLike[] = [
  { id: 1785, processOrder: 1, status: 'COMPLETED' },
  { id: 1786, processOrder: 2, status: 'PENDING' },
  { id: 1787, processOrder: 3, status: 'PENDING' },
];

/** /yield 没有任何 step 时的 phaseOf —— 当天生产上就是这个状态。 */
const phaseOfWithEmptyYield = (task: StepTaskLike): StepPhase =>
  resolveStepPhase(task, undefined);

describe('resolveStepPhase', () => {
  it('产出接口给了阶段就用它', () => {
    expect(resolveStepPhase({ status: 'PENDING' }, 'IN_PRODUCTION')).toBe('IN_PRODUCTION');
    expect(resolveStepPhase({ status: 'COMPLETED' }, 'AWAITING_INPUT')).toBe('AWAITING_INPUT');
  });

  it('产出接口没有这一道时, 用任务自己的状态, ⛔ 不把「不知道」说成「没开始」', () => {
    expect(resolveStepPhase({ status: 'COMPLETED' }, undefined)).toBe('COMPLETED');
    expect(resolveStepPhase({ status: 'SKIPPED' }, null)).toBe('COMPLETED');
    expect(resolveStepPhase({ status: 'PENDING' }, undefined)).toBe('AWAITING_INPUT');
  });

  it('无法识别的阶段值当作没有, 退回任务状态', () => {
    expect(resolveStepPhase({ status: 'COMPLETED' }, 'WHATEVER')).toBe('COMPLETED');
  });
});

describe('resolveInitialStepIndex', () => {
  it('🔴 用户点了第②道就打开第②道 —— 与 autoAssigned 无关', () => {
    // ⚠️ 这里三道都设成未完成是有意的: 当时生产上 /yield 为空, 回退定位落在【第 0 道】,
    //    而用户点的是第②道。若沿用 TASKS (①已 COMPLETED), 回退定位恰好也是 1,
    //    断言就会因为期望值巧合相同而永远不红 —— 变异对照当场抓到过这一点。
    const allPending: StepTaskLike[] = TASKS.map((t) => ({ ...t, status: 'PENDING' }));
    expect(
      resolveInitialStepIndex({ tasks: allPending, phaseOf: phaseOfWithEmptyYield }),
    ).toBe(0); // 先钉住「不指定时回退到第 0 道」, 否则下面那条不成其为对照
    expect(
      resolveInitialStepIndex({
        tasks: allPending,
        assignedWorkProcessTaskId: 1786,
        assignedProcessOrder: 2,
        phaseOf: phaseOfWithEmptyYield,
      }),
    ).toBe(1);
  });

  it('🔴 只传 processOrder 也认', () => {
    expect(
      resolveInitialStepIndex({
        tasks: TASKS,
        assignedProcessOrder: 3,
        phaseOf: phaseOfWithEmptyYield,
      }),
    ).toBe(2);
  });

  it('🔴 没有指定时, 已 COMPLETED 的第①道不再被当成待报 —— 停在第②道', () => {
    expect(
      resolveInitialStepIndex({ tasks: TASKS, phaseOf: phaseOfWithEmptyYield }),
    ).toBe(1);
  });

  it('点的那一道不在可见列表里, 退回默认定位而不是静默停在第 0 道', () => {
    expect(
      resolveInitialStepIndex({
        tasks: TASKS,
        assignedWorkProcessTaskId: 999999,
        phaseOf: phaseOfWithEmptyYield,
      }),
    ).toBe(1);
  });

  it('全部做完返回 -1', () => {
    const done: StepTaskLike[] = TASKS.map((t) => ({ ...t, status: 'COMPLETED' }));
    expect(resolveInitialStepIndex({ tasks: done, phaseOf: phaseOfWithEmptyYield })).toBe(-1);
  });

  it('空列表返回 -1', () => {
    expect(resolveInitialStepIndex({ tasks: [], phaseOf: phaseOfWithEmptyYield })).toBe(-1);
  });
});

describe('resolveInputUnit', () => {
  it('🔴 领上道半成品时用【那笔半成品的】单位, 不是本道的单位', () => {
    // 生产实测: 卤制产出 kg, 拼装分装本道单位是 盒。
    // 用 盒 提交, 后端 409「半成品单位与本道投入单位不一致」, 且填任何数字都过不去。
    expect(resolveInputUnit({ hasSourceWipInput: true, wipUnit: 'kg', processUnit: '盒' })).toBe('kg');
  });

  it('不领半成品时用本道单位', () => {
    expect(resolveInputUnit({ hasSourceWipInput: false, wipUnit: 'kg', processUnit: '盒' })).toBe('盒');
  });

  it('半成品单位缺失时退回本道单位, ⛔ 不返回空串', () => {
    expect(resolveInputUnit({ hasSourceWipInput: true, wipUnit: null, processUnit: '盒' })).toBe('盒');
    expect(resolveInputUnit({ hasSourceWipInput: true, wipUnit: '', processUnit: '盒' })).toBe('盒');
  });
});

describe('屏幕确实用上了它 (防「只改一半调用点」)', () => {
  // ⚠️ 当时的缺陷正是「两个 payload 构造处只改对了一个」。
  //    这条钉住: effectiveInputUnit 必须【同时】用在提交 payload 和数量框显示上。
  //    只改回其中任意一处, 计数就掉到 1, 这条红。
  const SCREEN = path.join(__dirname, '../../screens/processing/YieldStepReportScreen.tsx');

  it('effectiveInputUnit 至少被消费 2 次(提交 + 显示)', () => {
    const src = fs.readFileSync(SCREEN, 'utf8');
    const uses = src.split('effectiveInputUnit').length - 1;
    // 1 次定义 + 至少 2 次消费
    expect(uses).toBeGreaterThanOrEqual(3);
    expect(src).toContain('inputUnit: effectiveInputUnit');
    expect(src).toContain('unit={effectiveInputUnit}');
  });

  // 显式登记「故意不改」的那一处 (硬约束: 改共享结构要么改, 要么登记, 不许沉默):
  //   handleSentinelMaterialSubmit —— 哨兵领料是纯原料流程, 那一屏没有半成品选择器,
  //   payload 也不带 sourceWipNo, 所以它的 inputUnit: unit 是对的。
  //   ⇒ 闸只钉「会挂 sourceWipNo 的那个 payload」, ⛔ 不搞成全文件禁用 inputUnit: unit
  //     —— 那样会对一条正确的路径误报, 而误报的闸最终会被关掉。
  it('⛔ 带 sourceWipNo 的那个 payload 里不许写死 inputUnit: unit', () => {
    const src = fs.readFileSync(SCREEN, 'utf8');
    const anchor = src.indexOf('sourceWipNo: effectiveSourceWipNo');
    expect(anchor).toBeGreaterThan(-1); // 阳性对照: 锚点找不到就说明这条闸在扫空气
    const payload = src.slice(Math.max(0, anchor - 1200), anchor);
    expect(payload).toContain('inputUnit: effectiveInputUnit');
    expect(payload).not.toContain('inputUnit: unit,');
  });
});
