/**
 * 逐道报工屏的两个判定:「该打开哪一道」与「这一道算不算做完了」。
 *
 * 抽成独立模块是因为两条在 2026-08-17 的生产实测里各栽了一次
 * (F006 批次 10759, 三道: ①拆骨 COMPLETED / ②卤制 PENDING / ③拼装分装 PENDING):
 *
 *  F1 工人在列表点「卤制」(第②道), 打开的却是「拆骨」(第①道)。
 *     列表页传了 assignedWorkProcessTaskId, 接收侧只在 autoAssigned 为真时才理会它 ——
 *     字段发出去了, 被静默丢掉。后果是工人以为在报第②道, 报工落到了第①道。
 *
 *  F2 「拆骨」在 work_process_tasks 里是 COMPLETED, 而 /yield 的 steps 里没有它
 *     (那道完工时没留报工记录), 于是被当成「未开始」又列出来让人再报一次。
 *     同一个 App 里, 列表页读 task.status (对) 而报工页读报工推导的 phase (错),
 *     两个真相源对同一个问题给出两个答案。
 */

export type StepPhase = 'AWAITING_INPUT' | 'IN_PRODUCTION' | 'COMPLETED';

const STEP_PHASES: readonly string[] = ['AWAITING_INPUT', 'IN_PRODUCTION', 'COMPLETED'];

/** 判定所需的最小任务形状 —— 只依赖这三个字段, 便于测试与复用。 */
export interface StepTaskLike {
  id: number;
  processOrder: number;
  status: string;
}

/**
 * 一道工序处于哪个阶段。
 *
 * ⛔ 产出接口没有这一道的数据时, 不许翻译成「这道没开始」——
 *    那是把「我不知道」说成一个具体读数。任务自己的 status 才是权威。
 */
export function resolveStepPhase(
  task: Pick<StepTaskLike, 'status'>,
  yieldPhase: string | null | undefined,
): StepPhase {
  if (yieldPhase != null && STEP_PHASES.includes(yieldPhase)) {
    return yieldPhase as StepPhase;
  }
  return task.status === 'COMPLETED' || task.status === 'SKIPPED'
    ? 'COMPLETED'
    : 'AWAITING_INPUT';
}

/**
 * 首屏该停在哪一道; 全部做完返回 -1。
 *
 * 用户明确点了某一道就打开那一道 —— autoAssigned 决定的是「能不能切到别的道」和页头文案,
 * ⛔ 不决定「要不要理会用户点的是哪一道」。没有指定时才退回「第一道还没做完的」。
 */
export function resolveInitialStepIndex(params: {
  tasks: readonly StepTaskLike[];
  assignedWorkProcessTaskId?: number | null;
  assignedProcessOrder?: number | null;
  phaseOf: (task: StepTaskLike) => StepPhase;
}): number {
  const { tasks, assignedWorkProcessTaskId, assignedProcessOrder, phaseOf } = params;
  if (tasks.length === 0) return -1;

  if (assignedWorkProcessTaskId != null || assignedProcessOrder != null) {
    const requested = tasks.findIndex((task) => {
      if (assignedWorkProcessTaskId != null && task.id === assignedWorkProcessTaskId) return true;
      return assignedProcessOrder != null && task.processOrder === assignedProcessOrder;
    });
    if (requested !== -1) return requested;
    // 点的那一道不在可见列表里 (被指派过滤掉 / 已删) —— 退回下面的默认定位,
    // ⛔ 不要静默停在第 0 道假装那就是用户要的。
  }

  return tasks.findIndex((task) => phaseOf(task) !== 'COMPLETED');
}

/**
 * 本道【投入】用哪个单位。
 *
 * 领上道半成品时是那笔半成品的单位；否则才是本道自己的 unit。
 *
 * ⚠️ 本道的 unit 描述的是它的**产出**（卤制出 kg、拼装分装出 盒）。拿它标投入会同时造成两件事，
 * 2026-08-17 生产实测两件都发生了：
 *  - 界面写出「上道产出 2.5 盒 / 半成品领用量 2.5 盒」，而那 2.5 是 **kg** —— 对工人是假话；
 *  - 提交被后端以「半成品单位与本道投入单位不一致」**409** 拒收，
 *    而单位是结构性不兼容的 ⇒ **工人填任何数字都过不去**，界面成了死胡同。
 *
 * ⛔ 显示与提交必须共用这一处，不许各写各的 —— 当时正是「两个 payload 构造处只改对了一个」。
 */
export function resolveInputUnit(params: {
  hasSourceWipInput: boolean;
  wipUnit: string | null | undefined;
  processUnit: string;
}): string {
  const { hasSourceWipInput, wipUnit, processUnit } = params;
  return hasSourceWipInput && wipUnit ? wipUnit : processUnit;
}
