const SALES_AMOUNT_EDGE_PATTERN = /^\s*#?amount\s*>\s*(\d+(?:\.\d+)?)\s*$/i;

/**
 * 解析 WorkflowEngine 实际读取的销售金额分支条件。
 * 非该形态的自定义 SpEL 返回 null，避免编辑器误覆盖高级规则。
 */
export function parseSalesApprovalAmountThreshold(condition: unknown): number | null {
  if (typeof condition !== 'string') return null;
  const matched = condition.match(SALES_AMOUNT_EDGE_PATTERN);
  if (!matched) return null;
  const value = Number(matched[1]);
  return Number.isFinite(value) && value >= 0 ? value : null;
}
/** 构造持久化 WorkflowEngine 使用的 graph edge condition。 */
export function buildSalesApprovalAmountCondition(threshold: number): string {
  if (!Number.isFinite(threshold) || threshold < 0) {
    throw new Error('销售订单审批金额阈值必须是大于或等于 0 的数字');
  }
  return `#amount > ${threshold}`;
}
