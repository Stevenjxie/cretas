import { describe, expect, it } from 'vitest';
import type { FactoryWarehouse, WarehouseType } from '@/api/factoryWarehouse';
import {
  defaultPurchaseReceiveWarehouseId,
  purchaseReceiveWarehouseOptions,
} from './purchaseReceiveWarehouse';

function warehouse(
  id: string,
  type: WarehouseType,
  isActive = true,
): FactoryWarehouse {
  return {
    id,
    factoryId: 'F006',
    code: `WH-${id}`,
    name: id,
    type,
    isActive,
  };
}

describe('purchaseReceiveWarehouseOptions', () => {
  it('allows raw, salted, logistics and external outsource warehouses', () => {
    const all = [
      warehouse('raw', 'RAW'),
      warehouse('salted', 'SALTED'),
      warehouse('logistics', 'LOGISTICS'),
      warehouse('external', 'OUTSOURCE'),
      warehouse('finished', 'FINISHED'),
      warehouse('inactive-external', 'OUTSOURCE', false),
    ];

    expect(purchaseReceiveWarehouseOptions(all, null).map((item) => item.id)).toEqual([
      'raw',
      'salted',
      'logistics',
      'external',
    ]);
  });

  it('keeps an active configured default visible even for a custom warehouse type', () => {
    const customDefault = warehouse('custom-default', 'TEMP');

    expect(
      purchaseReceiveWarehouseOptions([warehouse('raw', 'RAW')], customDefault)
        .map((item) => item.id),
    ).toEqual(['raw', 'custom-default']);
  });
});

describe('defaultPurchaseReceiveWarehouseId', () => {
  it('prefers the configured purchase inbound warehouse', () => {
    expect(
      defaultPurchaseReceiveWarehouseId(
        [warehouse('logistics', 'LOGISTICS')],
        warehouse('external', 'OUTSOURCE'),
      ),
    ).toBe('external');
  });

  it('falls back to logistics and then raw without silently choosing unrelated warehouses', () => {
    expect(
      defaultPurchaseReceiveWarehouseId([
        warehouse('finished', 'FINISHED'),
        warehouse('raw', 'RAW'),
        warehouse('logistics', 'LOGISTICS'),
      ], null),
    ).toBe('logistics');

    expect(
      defaultPurchaseReceiveWarehouseId([
        warehouse('finished', 'FINISHED'),
        warehouse('raw', 'RAW'),
      ], null),
    ).toBe('raw');
  });
});
