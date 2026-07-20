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
  usageScopes?: string[] | null;
  conversionFamily?: string | null;
}

export interface UnitCatalogItem {
  code: string;
  label: string;
  dimension: string;
  baseCode: string;
  displayScale: number;
  usageScopes?: string[];
  conversionFamily?: string | null;
  active?: boolean;
}

/**
 * The scientific catalog owns the canonical code/label pair. Historic global
 * rows may carry a stale label (for example box=箱), so merging by label can
 * accidentally hide both box=盒 and case=箱. Reconcile by code first and only
 * retain historic aliases that do not belong to another canonical code.
 */
export function mergeSystemUnitSources(
  configuredUnits: SystemUnit[],
  catalogItems: UnitCatalogItem[],
): SystemUnit[] {
  const merged = configuredUnits.map((unit) => ({ ...unit }));
  const canonicalLabelOwners = new Map(
    catalogItems.map((item) => [normalizeUnitIdentity(item.label), normalizeUnitIdentity(item.code)]),
  );

  for (const item of catalogItems) {
    const canonicalCode = normalizeUnitIdentity(item.code);
    const existingIndex = merged.findIndex(
      (unit) => normalizeUnitIdentity(unit.unitCode) === canonicalCode,
    );
    if (existingIndex < 0) {
      merged.push({
        unitCode: item.code,
        unitName: item.label,
        unitSymbol: item.code,
        baseUnit: item.baseCode,
        category: item.dimension,
        decimalPlaces: item.displayScale,
        isActive: true,
        isSystem: true,
        usageScopes: item.usageScopes,
        conversionFamily: item.conversionFamily,
      });
      continue;
    }

    const existing = merged[existingIndex];
    const canonicalName = normalizeUnitIdentity(item.label);
    const aliasesJson = [...new Set(unitAliases(existing))].filter((alias) => {
      const normalized = normalizeUnitIdentity(alias);
      if (!normalized || normalized === canonicalCode || normalized === canonicalName) return false;
      const owner = canonicalLabelOwners.get(normalized);
      return !owner || owner === canonicalCode;
    });
    merged[existingIndex] = {
      ...existing,
      unitCode: item.code,
      unitName: item.label,
      unitSymbol: item.code,
      aliases: null,
      aliasesJson,
      baseUnit: item.baseCode,
      category: item.dimension,
      decimalPlaces: item.displayScale,
      usageScopes: item.usageScopes,
      conversionFamily: item.conversionFamily,
    };
  }

  return merged;
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

export async function listSystemUnits(factoryId: string, usageScope?: string) {
  const [configured, catalog] = await Promise.all([
    get<SystemUnit[]>(`/${factoryId}/system-config/units`),
    get<UnitCatalogItem[]>(`/${factoryId}/units/catalog`, {
      params: usageScope ? { usageScope } : undefined,
    }),
  ]);
  const catalogItems = catalog.data || [];
  const allowedCodes = new Set(catalogItems.map((item) => normalizeUnitIdentity(item.code)));
  const configuredUnits = usageScope
    ? (configured.data || []).filter((unit) => allowedCodes.has(normalizeUnitIdentity(unit.unitCode)))
    : (configured.data || []);
  const merged = mergeSystemUnitSources(configuredUnits, catalogItems);
  for (const alias of COMMON_DISPLAY_ALIASES) {
    if ((!usageScope || allowedCodes.has(normalizeUnitIdentity(alias.unitCode.split(':')[0])))
      && !merged.some((unit) => normalizeUnitIdentity(unit.unitName) === normalizeUnitIdentity(alias.unitName))) {
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
