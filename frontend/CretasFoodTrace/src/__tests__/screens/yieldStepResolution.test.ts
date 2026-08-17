/**
 * 逐道报工定位判定。两条断言各自复现 2026-08-17 生产实测抓到的一个缺陷,
 * 夹具直接用当天 F006 批次 10759 的真实形状:
 *   ①1785 拆骨 COMPLETED / ②1786 卤制 PENDING / ③1787 拼装分装 PENDING,
 *   而 /yield 的 steps 是空的 (那批次一条报工记录都没有)。
 */
import {
  resolveInitialStepIndex,
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
