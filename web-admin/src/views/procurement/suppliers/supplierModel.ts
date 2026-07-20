import type { FormItemRule } from 'element-plus';
import type { SupplierRecord, SupplierSavePayload } from '@/api/supplierManagement';

export const SUPPLIER_ADDRESS_MAX_LENGTH = 500;

const SUPPLIER_PHONE = /^(?:(?:\+?86[- ]?)?1[3-9]\d{9}|(?:\+?86[- ]?)?0\d{2,3}[- ]?\d{7,8}(?:[- ]?(?:\d{1,6}|(?:ext\.?|分机|转)\s*\d{1,6}))?)$/i;

export function normalizeText(value: unknown): string {
  return String(value ?? '').trim();
}

export function isValidSupplierPhone(value: unknown): boolean {
  const phone = normalizeText(value);
  return SUPPLIER_PHONE.test(phone);
}

export function isReadableSupplierAddress(value: unknown): boolean {
  const address = normalizeText(value);
  return address.length > 0
    && address.length <= SUPPLIER_ADDRESS_MAX_LENGTH
    && /[\p{L}\p{N}]/u.test(address);
}

export function supplierProfileComplete(supplier: Partial<SupplierRecord>): boolean {
  if (supplier.profileComplete === false) return false;
  return Boolean(
    normalizeText(supplier.name)
    && normalizeText(supplier.contactPerson)
    && isValidSupplierPhone(supplier.phone || supplier.contactPhone)
    && isReadableSupplierAddress(supplier.address),
  );
}

export function normalizeSupplierPayload(input: SupplierSavePayload): SupplierSavePayload {
  return {
    ...input,
    name: normalizeText(input.name),
    contactPerson: normalizeText(input.contactPerson),
    phone: normalizeText(input.phone),
    address: normalizeText(input.address),
    email: normalizeText(input.email),
    bankAccount: normalizeText(input.bankAccount),
    taxNumber: normalizeText(input.taxNumber),
    notes: normalizeText(input.notes),
  };
}

function requiredRule(label: string): FormItemRule {
  return {
    required: true,
    whitespace: true,
    message: `请输入${label}`,
    trigger: ['blur', 'change'],
    transform: normalizeText,
  };
}

export const supplierFormRules: Record<string, FormItemRule[]> = {
  name: [requiredRule('供应商名称')],
  contactPerson: [requiredRule('联系人')],
  phone: [
    requiredRule('联系电话'),
    {
      validator: (_rule, value, callback) => {
        if (!isValidSupplierPhone(value)) {
          callback(new Error('请输入大陆手机号或带区号座机，可带分机'));
          return;
        }
        callback();
      },
      trigger: ['blur', 'change'],
    },
  ],
  address: [
    requiredRule('地址'),
    {
      validator: (_rule, value, callback) => {
        if (!isReadableSupplierAddress(value)) {
          callback(new Error(`请输入可识别的地址（不超过 ${SUPPLIER_ADDRESS_MAX_LENGTH} 字）`));
          return;
        }
        callback();
      },
      trigger: ['blur', 'change'],
    },
  ],
};

export function supplierStatus(record: Pick<SupplierRecord, 'status' | 'isActive'>): 'ACTIVE' | 'INACTIVE' {
  return record.status === 'ACTIVE' || record.isActive === true ? 'ACTIVE' : 'INACTIVE';
}

export function supplierStatusLabel(record: Pick<SupplierRecord, 'status' | 'isActive'>): string {
  return supplierStatus(record) === 'ACTIVE' ? '合作中' : '暂停合作';
}
