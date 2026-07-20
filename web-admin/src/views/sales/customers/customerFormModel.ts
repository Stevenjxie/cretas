export const CUSTOMER_TYPE_OPTIONS = [
  { label: '经销商', value: 'DEALER' },
  { label: '零售商', value: 'RETAILER' },
  { label: '餐饮企业', value: 'RESTAURANT' },
  { label: '企业客户', value: 'ENTERPRISE' },
] as const;

export const CUSTOMER_ADVANCED_FIELDS = [
  'email', 'industry', 'customerStatus', 'importance', 'source', 'lastContactedAt',
  'defaultTaxRate', 'defaultInvoiceType', 'creditStatus', 'taxNumber', 'billingAddress',
  'bankName', 'bankAccount', 'creditLimit', 'paymentTerms', 'creditPeriodDays',
] as const;

export function customerTypeLabel(value: unknown): string {
  return CUSTOMER_TYPE_OPTIONS.find((option) => option.value === value)?.label || '未设置';
}

export function isSupportedCustomerPhone(value: unknown): boolean {
  const phone = String(value ?? '').trim();
  if (!phone) return false;
  const extension = String.raw`(?:\s*(?:转|ext\.?|x|#)\s*\d{1,8})?`;
  const mobile = new RegExp(String.raw`^(?:\+?86[-\s]?)?1[3-9]\d{9}${extension}$`, 'i');
  const landline = new RegExp(String.raw`^(?:\+?86[-\s]?)?(?:0\d{2,3}|\(0\d{2,3}\))[-\s]?\d{7,8}${extension}$`, 'i');
  const internationalCore = phone.replace(/[\s().-]/g, '').replace(/(?:转|ext\.?|x|#)\d{1,8}$/i, '');
  return mobile.test(phone) || landline.test(phone) || /^\+[1-9]\d{6,14}$/.test(internationalCore);
}

export function hasMeaningfulValue(value: unknown): boolean {
  if (value === 0 || value === false) return true;
  if (value == null) return false;
  return String(value).trim().length > 0;
}

export function countFilledCustomerAdvancedFields(
  form: Record<string, unknown>,
  fieldKeys: readonly string[] = CUSTOMER_ADVANCED_FIELDS,
): number {
  return fieldKeys.filter((key) => hasMeaningfulValue(form[key])).length;
}

export function normalizeCustomerPayload(
  form: Record<string, unknown>,
  extendedKeys: readonly string[],
): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    name: String(form.name ?? '').trim(),
    contactPerson: String(form.contactPerson ?? '').trim(),
    phone: String(form.phone ?? '').trim(),
    shippingAddress: String(form.shippingAddress ?? '').trim(),
    status: String(form.status ?? '').trim(),
  };
  const optionalKeys = [
    'email', 'type', 'industry', 'notes', 'customerStatus', 'importance', 'source',
    'lastContactedAt', 'defaultTaxRate', 'defaultInvoiceType', 'creditStatus', ...extendedKeys,
  ];
  for (const key of optionalKeys) {
    const value = form[key];
    if (!hasMeaningfulValue(value)) continue;
    payload[key] = typeof value === 'string' ? value.trim() : value;
  }
  return payload;
}

export function isCustomerAdvancedField(field: string): boolean {
  return (CUSTOMER_ADVANCED_FIELDS as readonly string[]).includes(field);
}
