import { BatchYieldDTO, WorkProcessTask, WorkProcessTaskStatus } from '../services/api/yieldReportApi';

// 两点报工哨兵工序 ID 常量 (与后端 WorkProcessTaskService.SENTINEL_* 保持一致)
export const SENTINEL_MATERIAL_INPUT = '__MATERIAL_INPUT__';
export const SENTINEL_FINAL_OUTPUT = '__FINAL_OUTPUT__';

/** 是否终态 (COMPLETED / SKIPPED / CANCELLED) */
export function isTerminalWorkProcessStatus(status: WorkProcessTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'SKIPPED' || status === 'CANCELLED';
}

/**
 * 一道工序任务当前操作员能否认领/报工.
 *
 * <p>⛔ 未指派 (assignedTo == null) → <b>任何操作员都可以</b>, 这不是放松而是与后端既有设计对齐:
 * - `ReportAuthGuard#assertCanReport`: 允许集合为空时直接放行, 注释原文「未指派, 任何操作员均可报工」
 * - `WorkProcessTaskServiceImpl#start`: assignedTo 为 null 时自动把任务认给当前操作员
 * - `WorkProcessTaskServiceImpl#listByBatch`: M1 兜底「防止未配默认责任人的老批次把任何人锁死」
 *
 * <p>背景 (2026-08-04): prod 的指派配置从未被填过 (18 条任务 assigned_to 全 null / assignee 关联表 0 行 /
 * 10 条计划无一填 supervisor)。严格相等过滤会让操作员永远看到空列表, 手机端报工无从开始。
 *
 * <p>⛔ <b>指派给他人的仍然不可认领</b> —— 放开那条是越权, 不在本兜底范围内.
 */
export function isTaskClaimableBy(
  task: WorkProcessTask,
  workerId: number | null | undefined,
): boolean {
  // 未指派 → 谁都能捡 (含当前用户身份未知的情况)
  if (task.assignedTo == null) return true;
  return workerId != null && task.assignedTo === workerId;
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

  const step = yieldData?.steps?.find(
    (s) => s.workProcessTaskId === task.id || s.processOrder === task.processOrder,
  );
  if (step?.phase === 'COMPLETED') return true;

  // 非哨兵工序不额外检查 yield
  if (
    task.workProcessId !== SENTINEL_MATERIAL_INPUT &&
    task.workProcessId !== SENTINEL_FINAL_OUTPUT
  ) {
    return false;
  }

  if (!step) return false;

  if (task.workProcessId === SENTINEL_MATERIAL_INPUT) {
    // 领料哨兵: 有实际投入量 或 phase 已进入生产
    return (
      (step.totalInput ?? 0) > 0 ||
      step.phase === 'IN_PRODUCTION'
    );
  }

  // SENTINEL_FINAL_OUTPUT: 有实际产出量
  return (step.totalOutput ?? 0) > 0;
}
