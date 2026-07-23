/**
 * AI 读写分离 (2026-07-23): requiredPermission 权限码 → 中文名映射。
 * 与权限管理页文案一致。未知码回落显示原始 code。
 */
const PERMISSION_NAME_MAP: Record<string, string> = {
  'inventory:write': '库存·写',
  'warehouse:read_write': '仓储·读写',
  'finance:read_write': '财务·读写',
  'production:write': '生产·写',
  'restaurant:read_write': '餐饮·读写',
  'system:read_write': '系统·读写',
  'quality:write': '质检·写',
  'procurement:write': '采购·写',
  'sales:write': '销售·写',
  'hr:write': '人事·写',
};

/** 权限码转中文显示名；未收录的码原样返回。 */
export function permissionDisplayName(code: string | null | undefined): string {
  if (!code) return '';
  return PERMISSION_NAME_MAP[code] ?? code;
}

export { PERMISSION_NAME_MAP };
