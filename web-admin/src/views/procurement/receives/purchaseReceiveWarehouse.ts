import type { FactoryWarehouse, WarehouseType } from '@/api/factoryWarehouse';

/**
 * 采购收货允许直接落库的仓型。
 *
 * OUTSOURCE 是委外/外仓：物料仍归本工厂所有，只是实物存放在外部协作方，
 * 后端 WarehouseInventoryGuardService 允许 RAW 采购收货进入该仓型。
 */
export const PURCHASE_RECEIVABLE_WAREHOUSE_TYPES: WarehouseType[] = [
  'RAW',
  'SALTED',
  'LOGISTICS',
  'OUTSOURCE',
];

export function purchaseReceiveWarehouseOptions(
  warehouses: FactoryWarehouse[],
  configuredDefault: FactoryWarehouse | null,
): FactoryWarehouse[] {
  const options = warehouses.filter(
    (warehouse) =>
      warehouse.isActive !== false
      && PURCHASE_RECEIVABLE_WAREHOUSE_TYPES.includes(warehouse.type),
  );

  if (
    configuredDefault
    && configuredDefault.isActive !== false
    && !options.some((warehouse) => warehouse.id === configuredDefault.id)
  ) {
    options.push(configuredDefault);
  }

  return options;
}

export function defaultPurchaseReceiveWarehouseId(
  warehouses: FactoryWarehouse[],
  configuredDefault: FactoryWarehouse | null,
): string {
  if (configuredDefault?.isActive !== false && configuredDefault?.id) {
    return configuredDefault.id;
  }

  const logistics = warehouses.find(
    (warehouse) => warehouse.type === 'LOGISTICS' && warehouse.isActive !== false,
  );
  if (logistics) return logistics.id;

  const raw = warehouses.find(
    (warehouse) => warehouse.type === 'RAW' && warehouse.isActive !== false,
  );
  return raw?.id ?? '';
}
