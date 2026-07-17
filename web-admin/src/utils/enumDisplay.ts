export const COMMON_ENUM_LABELS: Record<string, string> = {
  PENDING: '待处理',
  FINANCE_APPROVED: '财务已审核',
  DRAFT: '草稿',
  COMPLETED: '已完成',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  BANK_TRANSFER: '银行转账',
  CASH: '现金',
  WECHAT: '微信',
  ALIPAY: '支付宝',
  CHECK: '支票',
  CREDIT: '赊账',
  POS: 'POS',
  OTHER: '其他',
};

export function enumLabel(
  code: unknown,
  localLabels: Record<string, string> = {},
  empty = '—',
): string {
  const normalized = String(code ?? '').trim();
  if (!normalized) return empty;
  return localLabels[normalized]
    || COMMON_ENUM_LABELS[normalized]
    || `未知状态（${normalized}）`;
}

