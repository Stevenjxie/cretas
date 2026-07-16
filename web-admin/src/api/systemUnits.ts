import { get, post } from '@/api/request';

export type SystemUnitCategory = 'WEIGHT' | 'VOLUME' | 'COUNT' | 'LENGTH' | 'TEMPERATURE';

export interface SystemUnit {
  id?: string;
  unitCode: string;
  unitName: string;
  unitSymbol?: string | null;
  aliases?: string[] | string | null;
  aliasesJson?: string[] | null;
  baseUnit?: string | null;
  conversionFactor?: number | null;
  category?: SystemUnitCategory | string | null;
  decimalPlaces?: number | null;
  isBaseUnit?: boolean | null;
  isActive?: boolean | null;
  isSystem?: boolean | null;
  sortOrder?: number | null;
}

interface UnitCatalogItem {
  code: string;
  label: string;
  dimension: string;
  baseCode: string;
  displayScale: number;
}

const COMMON_DISPLAY_ALIASES: SystemUnit[] = [
  { unitCode: 'pcs:只', unitName: '只', unitSymbol: '只', aliasesJson: ['件', '个', 'pcs'], category: 'COUNT', isActive: true, isSystem: true },
  { unitCode: 'kg:公斤', unitName: '公斤', unitSymbol: 'kg', aliasesJson: ['千克', 'kg'], category: 'WEIGHT', isActive: true, isSystem: true },
  { unitCode: 'g:克', unitName: '克', unitSymbol: 'g', aliasesJson: ['g'], category: 'WEIGHT', isActive: true, isSystem: true },
];

export interface CreateSystemUnitPayload {
  unitCode: string;
  unitName: string;
  unitSymbol?: string;
  category: SystemUnitCategory;
  baseUnit?: string;
  conversionFactor?: number;
  decimalPlaces?: number;
  isBaseUnit?: boolean;
  isActive?: boolean;
  isSystem?: boolean;
  sortOrder?: number;
}

export async function listSystemUnits(factoryId: string) {
  const [configured, catalog] = await Promise.all([
    get<SystemUnit[]>(`/${factoryId}/system-config/units`),
    get<UnitCatalogItem[]>(`/${factoryId}/units/catalog`),
  ]);
  const merged: SystemUnit[] = [...(configured.data || [])];
  for (const item of catalog.data || []) {
    if (!merged.some((unit) => normalizeUnitIdentity(unit.unitCode) === normalizeUnitIdentity(item.code)
      || normalizeUnitIdentity(unit.unitName) === normalizeUnitIdentity(item.label))) {
      merged.push({
        unitCode: item.code,
        unitName: item.label,
        unitSymbol: item.code,
        category: item.dimension,
        decimalPlaces: item.displayScale,
        isActive: true,
        isSystem: true,
      });
    }
  }
  for (const alias of COMMON_DISPLAY_ALIASES) {
    if (!merged.some((unit) => normalizeUnitIdentity(unit.unitName) === normalizeUnitIdentity(alias.unitName))) {
      merged.push(alias);
    }
  }
  return { ...configured, success: configured.success && catalog.success, data: merged };
}

export function createSystemUnit(factoryId: string, payload: CreateSystemUnitPayload) {
  return post<SystemUnit>(`/${factoryId}/system-config/units`, payload);
}

export function normalizeUnitIdentity(value?: string | null): string {
  return (value || '').normalize('NFKC').trim().toLocaleLowerCase().replace(/\s+/g, '');
}

export function unitAliases(unit: SystemUnit): string[] {
  const aliases = unit.aliasesJson || (Array.isArray(unit.aliases)
    ? unit.aliases
    : typeof unit.aliases === 'string'
      ? unit.aliases.split(/[,，;；]/)
      : []);
  return [unit.unitCode, unit.unitName, unit.unitSymbol || '', ...aliases].filter(Boolean);
}

export function findDuplicateUnit(units: SystemUnit[], values: Array<string | null | undefined>): SystemUnit | null {
  const identities = new Set(values.map(normalizeUnitIdentity).filter(Boolean));
  if (identities.size === 0) return null;
  return units.find((unit) => unitAliases(unit).some((alias) => identities.has(normalizeUnitIdentity(alias)))) || null;
}

export function defaultUnitCode(name: string): string {
  return name.normalize('NFKC').trim().replace(/\s+/g, '_').slice(0, 20);
}
