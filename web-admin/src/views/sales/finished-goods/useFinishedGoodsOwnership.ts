import { ref, watch, type ComputedRef } from 'vue';
import { get } from '@/api/request';
import type { Customer } from '@/api/customer';

export interface FinishedGoodsOwnershipSource {
  ownership?: unknown;
  ownerCustomerId?: unknown;
}

export type CustomerLookupState = 'idle' | 'loading' | 'loaded' | 'failed';

export interface OwnershipPresentation {
  isCustomerOwned: boolean;
  ownershipLabel: '公司库存' | '客户专属';
  customerLabel: string;
  tagType: 'success' | 'warning' | 'danger';
}

export function ownerCustomerIdOf(row: FinishedGoodsOwnershipSource): string | null {
  return typeof row.ownerCustomerId === 'string' && row.ownerCustomerId.trim()
    ? row.ownerCustomerId.trim()
    : null;
}

export function isCustomerOwnedBatch(row: FinishedGoodsOwnershipSource): boolean {
  return ownerCustomerIdOf(row) !== null
    || String(row.ownership || '').toUpperCase() === 'CUSTOMER_OWNED';
}

export function customerIdentityLabel(customer: Customer): string {
  const name = customer.name?.trim();
  const code = customer.customerCode?.trim();
  if (name && code) return `${name}（${code}）`;
  return name || code || '客户资料未命名';
}

export function ownershipPresentation(
  row: FinishedGoodsOwnershipSource,
  customer: Customer | null | undefined,
  lookupState: CustomerLookupState,
): OwnershipPresentation {
  if (!isCustomerOwnedBatch(row)) {
    return {
      isCustomerOwned: false,
      ownershipLabel: '公司库存',
      customerLabel: '不限定客户',
      tagType: 'success',
    };
  }

  if (!ownerCustomerIdOf(row)) {
    return {
      isCustomerOwned: true,
      ownershipLabel: '客户专属',
      customerLabel: '未记录归属客户',
      tagType: 'danger',
    };
  }

  if (lookupState === 'loaded' && customer) {
    return {
      isCustomerOwned: true,
      ownershipLabel: '客户专属',
      customerLabel: customerIdentityLabel(customer),
      tagType: 'warning',
    };
  }

  if (lookupState === 'failed') {
    return {
      isCustomerOwned: true,
      ownershipLabel: '客户专属',
      customerLabel: '客户资料加载失败，请刷新',
      tagType: 'danger',
    };
  }

  return {
    isCustomerOwned: true,
    ownershipLabel: '客户专属',
    customerLabel: '客户资料加载中…',
    tagType: 'warning',
  };
}

export function useFinishedGoodsOwnership(factoryId: ComputedRef<string>) {
  const customerById = ref<Record<string, Customer | null>>({});
  const loadingCustomerIds = ref<Set<string>>(new Set());

  watch(factoryId, () => {
    customerById.value = {};
    loadingCustomerIds.value = new Set();
  }, { flush: 'sync' });

  function lookupState(row: FinishedGoodsOwnershipSource): CustomerLookupState {
    const customerId = ownerCustomerIdOf(row);
    if (!customerId) return 'idle';
    if (loadingCustomerIds.value.has(customerId)) return 'loading';
    if (Object.prototype.hasOwnProperty.call(customerById.value, customerId)) {
      return customerById.value[customerId] ? 'loaded' : 'failed';
    }
    return 'idle';
  }

  function presentation(row: FinishedGoodsOwnershipSource): OwnershipPresentation {
    const customerId = ownerCustomerIdOf(row);
    return ownershipPresentation(
      row,
      customerId ? customerById.value[customerId] : undefined,
      lookupState(row),
    );
  }

  async function loadOwnerCustomers(rows: FinishedGoodsOwnershipSource[]): Promise<void> {
    const requestedFactoryId = factoryId.value;
    if (!requestedFactoryId) return;

    const customerIds = [...new Set(
      rows
        .filter(isCustomerOwnedBatch)
        .map(ownerCustomerIdOf)
        .filter((id): id is string => id !== null),
    )].filter((id) => {
      const hasSuccessfulLookup = Object.prototype.hasOwnProperty.call(customerById.value, id)
        && customerById.value[id] !== null;
      return !hasSuccessfulLookup && !loadingCustomerIds.value.has(id);
    });

    await Promise.all(customerIds.map(async (customerId) => {
      loadingCustomerIds.value = new Set([...loadingCustomerIds.value, customerId]);
      try {
        const response = await get<Customer>(
          `/${requestedFactoryId}/customers/${customerId}`,
          { _silent: true },
        );
        if (factoryId.value !== requestedFactoryId) return;
        customerById.value = {
          ...customerById.value,
          [customerId]: response.success && response.data ? response.data : null,
        };
      } catch {
        if (factoryId.value === requestedFactoryId) {
          customerById.value = { ...customerById.value, [customerId]: null };
        }
      } finally {
        const nextLoadingIds = new Set(loadingCustomerIds.value);
        nextLoadingIds.delete(customerId);
        loadingCustomerIds.value = nextLoadingIds;
      }
    }));
  }

  return { loadOwnerCustomers, presentation };
}
