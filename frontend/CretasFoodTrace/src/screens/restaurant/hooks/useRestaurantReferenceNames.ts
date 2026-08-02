import { useEffect, useState } from 'react';

import { materialTypeApiClient } from '../../../services/api/materialTypeApiClient';
import { productTypeApiClient } from '../../../services/api/productTypeApiClient';
import { logger } from '../../../utils/logger';

type NameMap = Record<string, string>;
type ReferenceItem = { id: string; name: string };

export function extractRestaurantReferenceItems(value: unknown): ReferenceItem[] {
  if (Array.isArray(value)) return value as ReferenceItem[];
  if (!value || typeof value !== 'object') return [];
  const record = value as Record<string, unknown>;
  if (Array.isArray(record.data)) return record.data as ReferenceItem[];
  if (record.data && typeof record.data === 'object') {
    const nestedData = record.data as Record<string, unknown>;
    if (Array.isArray(nestedData.content)) return nestedData.content as ReferenceItem[];
  }
  if (Array.isArray(record.content)) return record.content as ReferenceItem[];
  return [];
}

export function resolveRestaurantReferenceName(
  providedName: string | undefined,
  referenceId: string | undefined,
  names: NameMap,
  fallback: string,
): string {
  const normalizedName = providedName?.trim();
  if (normalizedName) return normalizedName;
  if (referenceId && names[referenceId]) return names[referenceId];
  return fallback;
}

export function useRestaurantReferenceNames(includeProducts = false) {
  const [materialNames, setMaterialNames] = useState<NameMap>({});
  const [productNames, setProductNames] = useState<NameMap>({});

  useEffect(() => {
    let alive = true;

    const load = async () => {
      const requests: Promise<unknown>[] = [materialTypeApiClient.getMaterialTypes()];
      if (includeProducts) {
        requests.push(productTypeApiClient.getProductTypes({ page: 1, limit: 1000 }));
      }

      const results = await Promise.allSettled(requests);
      if (!alive) return;

      const materialsResult = results[0];
      const productsResult = results[1];
      if (!materialsResult) return;

      if (materialsResult.status === 'fulfilled') {
        const materials = extractRestaurantReferenceItems(materialsResult.value);
        setMaterialNames(Object.fromEntries(materials.filter(item => item.id && item.name).map(item => [item.id, item.name])));
      } else {
        logger.warn('Restaurant material reference names failed to load', materialsResult.reason);
      }

      if (includeProducts && productsResult?.status === 'fulfilled') {
        const products = extractRestaurantReferenceItems(productsResult.value);
        setProductNames(Object.fromEntries(products.filter(item => item.id && item.name).map(item => [item.id, item.name])));
      } else if (includeProducts && productsResult?.status === 'rejected') {
        logger.warn('Restaurant product reference names failed to load', productsResult.reason);
      }
    };

    load();
    return () => { alive = false; };
  }, [includeProducts]);

  return { materialNames, productNames };
}
