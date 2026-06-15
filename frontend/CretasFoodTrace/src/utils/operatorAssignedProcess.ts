import { BatchYieldDTO, WorkProcessTask, WorkProcessTaskStatus } from '../services/api/yieldReportApi';

// 两点报工哨兵工序 ID 常量 (与后端 WorkProcessTaskService.SENTINEL_* 保持一致)
export const SENTINEL_MATERIAL_INPUT = '__MATERIAL_INPUT__';
export const SENTINEL_FINAL_OUTPUT = '__FINAL_OUTPUT__';

/** 是否终态 (COMPLETED / SKIPPED / CANCELLED) */
export function isTerminalWorkProcessStatus(status: WorkProcessTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'SKIPPED' || status === 'CANCELLED';
}

/**
 * 判断一道工序任务是否"已完成报工", 适用于操作员任务列表过滤.
 *
 * 对普通工序: 只看 status (COMPLETED / SKIPPED / CANCELLED → 已完成).
 * 对哨兵工序: 额外检查 BatchYieldDTO 里对应 step 的 phase / 累计量 ——
 *   因为后端 status 在部分报工后可能还停在 IN_PROGRESS, 但 yield 已记录实际投入/产出.
 *   这样能准确过滤"已报过但 status 未立即翻转"的哨兵, 防操作员重复点击.
 */
export function isTaskReportComplete(task: WorkProcessTask, yieldData?: BatchYieldDTO | null): boolean {
  // 普通终态直接返回
  if (isTerminalWorkProcessStatus(task.status)) return true;

  // 非哨兵工序不额外检查 yield
  if (
    task.workProcessId !== SENTINEL_MATERIAL_INPUT &&
    task.workProcessId !== SENTINEL_FINAL_OUTPUT
  ) {
    return false;
  }

  // 没有 yield 数据时保守返回 false (不误隐藏)
  if (!yieldData?.steps) return false;

  const step = yieldData.steps.find(
    (s) => s.workProcessTaskId === task.id || s.processOrder === task.processOrder,
  );
  if (!step) return false;

  if (task.workProcessId === SENTINEL_MATERIAL_INPUT) {
    // 领料哨兵: 有实际投入量 或 phase 已进入生产/完成
    return (
      (step.totalInput ?? 0) > 0 ||
      step.phase === 'IN_PRODUCTION' ||
      step.phase === 'COMPLETED'
    );
  }

  // SENTINEL_FINAL_OUTPUT: 有实际产出量 或 phase 完成
  return (step.totalOutput ?? 0) > 0 || step.phase === 'COMPLETED';
}
