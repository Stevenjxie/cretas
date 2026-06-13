export type PlanStatusTone = 'pending' | 'in-progress' | 'completed' | 'cancelled' | 'exception' | 'default';

export function normalizePlanStatus(status: string | null | undefined): string {
  return String(status || '').toUpperCase();
}

export function planStatusTone(status: string | null | undefined): PlanStatusTone {
  const normalized = normalizePlanStatus(status);
  if (normalized === 'PLANNED' || normalized === 'PENDING') return 'pending';
  if (normalized === 'IN_PROGRESS') return 'in-progress';
  if (normalized === 'COMPLETED') return 'completed';
  if (normalized === 'CANCELLED') return 'cancelled';
  if (normalized === 'EXCEPTION' || normalized === 'ABNORMAL' || normalized === 'FAILED') return 'exception';
  return 'default';
}

export function planRowClassNameByStatus(status: string | null | undefined): string {
  const tone = planStatusTone(status);
  return tone === 'default' ? '' : `plan-row-${tone}`;
}

export function planStatusClass(status: string | null | undefined): string {
  return `plan-status-${planStatusTone(status)}`;
}

export function getPlanStatusType(status: string | null | undefined): string {
  const tone = planStatusTone(status);
  const map: Record<PlanStatusTone, string> = {
    pending: 'warning',
    'in-progress': 'warning',
    completed: 'success',
    cancelled: 'danger',
    exception: 'danger',
    default: 'info',
  };
  return map[tone];
}

export function getPlanStatusText(status: string | null | undefined): string {
  const normalized = normalizePlanStatus(status);
  const map: Record<string, string> = {
    PLANNED: '待执行',
    PENDING: '未完成',
    PREPARED: '草稿',
    IN_PROGRESS: '进行中',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    EXCEPTION: '异常',
    ABNORMAL: '异常',
    FAILED: '异常',
    PAUSED: '暂停',
  };
  return map[normalized] || String(status || '');
}
