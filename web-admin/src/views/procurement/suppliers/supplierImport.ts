import {
  parseExcelArrayBuffer,
  type ParsedTable,
} from '@/views/restaurant/supplier-delivery/supplierDeliveryImport';
import { isReadableSupplierAddress, isValidSupplierPhone, normalizeText } from './supplierModel';

export const SUPPLIER_IMPORT_FIELDS = [
  { key: 'name', label: '供应商名称', required: true },
  { key: 'contactPerson', label: '联系人', required: true },
  { key: 'phone', label: '联系电话', required: true },
  { key: 'email', label: '邮箱', required: false },
  { key: 'address', label: '地址', required: true },
  { key: 'bankAccount', label: '银行账户', required: false },
  { key: 'taxNumber', label: '税号', required: false },
  { key: 'notes', label: '备注', required: false },
  { key: 'supplierCode', label: '供应商编码', required: false },
  { key: 'status', label: '状态', required: false },
] as const;

export type SupplierImportField = typeof SUPPLIER_IMPORT_FIELDS[number]['key'];
export type SupplierColumnMapping = Record<string, SupplierImportField | ''>;

const ALIASES: Record<SupplierImportField, string[]> = {
  name: ['供应商名称', '供应商', '供货商', '供货单位', '公司名称'],
  contactPerson: ['联系人', '联络人', '负责人', '业务联系人'],
  phone: ['联系电话', '电话', '手机', '手机号', '联系手机', '座机'],
  email: ['邮箱', '电子邮箱', 'email', 'e-mail'],
  address: ['地址', '联系地址', '公司地址', '经营地址', '收件地址'],
  bankAccount: ['银行账户', '银行账号', '收款账号', '账户'],
  taxNumber: ['税号', '纳税人识别号', '统一社会信用代码'],
  notes: ['备注', '说明', '附注'],
  supplierCode: ['供应商编码', '供应商编号', '供货商编码'],
  status: ['状态', '合作状态', '启用状态'],
};

function normalizeHeader(header: string): string {
  return header.trim().toLowerCase().replace(/[\s_\-（）()]/g, '');
}

export function suggestSupplierMappings(
  headers: string[],
  mode: 'STANDARD' | 'SMART',
): SupplierColumnMapping {
  const used = new Set<SupplierImportField>();
  return Object.fromEntries(headers.map((header) => {
    const normalized = normalizeHeader(header);
    const candidate = SUPPLIER_IMPORT_FIELDS.find((field) => {
      if (used.has(field.key)) return false;
      if (mode === 'STANDARD') return normalizeHeader(field.label) === normalized;
      return ALIASES[field.key].some((alias) => normalizeHeader(alias) === normalized);
    });
    if (candidate) used.add(candidate.key);
    return [header, candidate?.key ?? ''];
  }));
}

export function missingRequiredMappings(mapping: SupplierColumnMapping): string[] {
  const mapped = new Set(Object.values(mapping));
  return SUPPLIER_IMPORT_FIELDS
    .filter((field) => field.required && !mapped.has(field.key))
    .map((field) => field.label);
}

export function mappedSupplierRows(
  table: ParsedTable,
  mapping: SupplierColumnMapping,
): Array<{ rowNumber: number; values: Record<string, string>; errors: string[]; ignored: boolean }> {
  return table.rows.map((raw, index) => {
    const source = raw as Record<string, unknown>;
    const values: Record<string, string> = {};
    Object.entries(mapping).forEach(([header, target]) => {
      if (target) values[target] = normalizeText(source[header]);
    });
    const ignored = Object.values(source).every((value) => !normalizeText(value));
    const errors: string[] = [];
    if (!ignored) {
      if (!values.name) errors.push('供应商名称不能为空');
      if (!values.contactPerson) errors.push('联系人不能为空');
      if (!values.phone) errors.push('联系电话不能为空');
      else if (!isValidSupplierPhone(values.phone)) errors.push('联系电话格式不正确');
      if (!values.address) errors.push('地址不能为空');
      else if (!isReadableSupplierAddress(values.address)) errors.push('地址必须包含可识别文字或数字');
    }
    return { rowNumber: index + 2, values, errors, ignored };
  });
}

export async function parseSupplierWorkbook(file: File): Promise<ParsedTable> {
  if (!/\.(xlsx|xls)$/i.test(file.name)) throw new Error('仅支持 .xlsx / .xls 文件');
  if (file.size <= 0) throw new Error('文件为空，请重新选择');
  if (file.size > 10 * 1024 * 1024) throw new Error('文件不能超过 10MB');
  return parseExcelArrayBuffer(await file.arrayBuffer());
}
