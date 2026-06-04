/**
 * Wave2 月结看板纯函数 helpers — 抽出来供 vitest 单测 (Vue SFC `<script setup>` 不易直接测).
 */

export interface AdjustWindowInfo {
  /** 状态: 未结账 / 调整窗口内 / 已锁定. */
  state: 'NOT_CLOSED' | 'OPEN_WINDOW' | 'LOCKED';
  /** 剩余天数 (OPEN_WINDOW 时 >= 0). */
  remainingDays: number;
  /** 给 UI 的展示文本. */
  label: string;
}

/**
 * 计算调整窗口状态 + 剩余天数. 兑现邓总 "20天调整窗口" 的看板倒计时.
 *
 * @param status   期间状态
 * @param adjustDeadline ISO 时间字符串 (closed_at + 20天), CLOSED 才有
 * @param now      当前时间 (默认 new Date(), 测试可注入)
 */
export function computeAdjustWindow(
  status: 'OPEN' | 'PENDING_CLOSE' | 'CLOSED',
  adjustDeadline: string | undefined | null,
  now: Date = new Date()
): AdjustWindowInfo {
  if (status !== 'CLOSED') {
    return { state: 'NOT_CLOSED', remainingDays: 0, label: '—' };
  }
  if (!adjustDeadline) {
    // 旧 CLOSED 行无 deadline → 立即锁定 (backwards compat)
    return { state: 'LOCKED', remainingDays: 0, label: '已锁定' };
  }
  const deadlineMs = new Date(adjustDeadline).getTime();
  const nowMs = now.getTime();
  if (Number.isNaN(deadlineMs)) {
    return { state: 'LOCKED', remainingDays: 0, label: '已锁定' };
  }
  if (nowMs >= deadlineMs) {
    return { state: 'LOCKED', remainingDays: 0, label: '已锁定 (调整窗口已过)' };
  }
  // ceil: 还剩不足 1 天也显示"剩 1 天"
  const remainingDays = Math.ceil((deadlineMs - nowMs) / (1000 * 60 * 60 * 24));
  return {
    state: 'OPEN_WINDOW',
    remainingDays,
    label: `调整窗口剩 ${remainingDays} 天`,
  };
}

/**
 * 月结对账预览能否结账 — 任一 BLOCKING check 失败则不可结账.
 * (前端镜像后端 canClose 逻辑, 用于 button disable 防御; 以后端 canClose 为准.)
 */
export function canCloseFromChecks(
  checks: Array<{ passed: boolean; severity: string }>
): boolean {
  return checks.every((c) => c.severity !== 'BLOCKING' || c.passed);
}

/**
 * 对账状态 tag 类型 (Element Plus).
 */
export function reconciliationTagType(
  status: string | undefined
): 'success' | 'warning' | 'info' {
  if (status === 'PASS') return 'success';
  if (status === 'WARNING') return 'warning';
  return 'info';
}
