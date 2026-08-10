import { describe, expect, it } from 'vitest';
import type { WarehouseReceivingTask } from '@/api/purchaseReceive';
import {
  filterReceivingTasks,
  receivingLifecycleCounts,
  receivingTaskLifecycle,
} from '../warehouseInboundLifecycle';

function task(overrides: Record<string, unknown>): WarehouseReceivingTask {
  return {
    taskId: 'task-1',
    sourceType: 'PURCHASE',
    purchaseOrderId: 'po-1',
    orderNumber: 'PO-1',
    supplierId: 'supplier-1',
    status: 'WAITING_RECEIVE',
    statusLabel: '待收货',
    activeReceiptCount: 0,
    receiptConflict: false,
    items: [{
      materialTypeId: 'm-1', materialName: '白砂糖', orderedQuantity: 10,
      receivedQuantity: 0, activeDraftAllocatedQuantity: 0,
      remainingReceivableQuantity: 10, unit: 'kg',
    }],
    ...overrides,
  } as WarehouseReceivingTask;
}

describe('warehouse inbound lifecycle', () => {
  it('classifies every open task into one mutually exclusive lifecycle', () => {
    const pending = task({ taskId: 'pending' });
    const processing = task({ taskId: 'processing', status: 'RECEIVING', activeReceiptCount: 1 });
    const partial = task({
      taskId: 'partial',
      items: [{
        materialTypeId: 'm-1', materialName: '白砂糖', orderedQuantity: 10,
        receivedQuantity: 4, activeDraftAllocatedQuantity: 0,
        remainingReceivableQuantity: 6, unit: 'kg',
      }],
    });

    expect(receivingTaskLifecycle(pending)).toBe('WAITING_RECEIVE');
    expect(receivingTaskLifecycle(processing)).toBe('RECEIVING');
    expect(receivingTaskLifecycle(partial)).toBe('PARTIAL');
    expect(receivingLifecycleCounts([pending, processing, partial])).toEqual({
      ALL: 3, WAITING_RECEIVE: 1, RECEIVING: 1, PARTIAL: 1,
    });
  });

  it('gives partial receipt priority over an active draft to avoid duplicate tabs', () => {
    const partialWithDraft = task({
      status: 'RECEIVING',
      activeReceiptCount: 1,
      items: [{
        materialTypeId: 'm-1', materialName: '白砂糖', orderedQuantity: 10,
        receivedQuantity: 3, activeDraftAllocatedQuantity: 2,
        remainingReceivableQuantity: 5, unit: 'kg',
      }],
    });
    expect(receivingTaskLifecycle(partialWithDraft)).toBe('PARTIAL');
    expect(filterReceivingTasks([partialWithDraft], 'RECEIVING')).toEqual([]);
    expect(filterReceivingTasks([partialWithDraft], 'PARTIAL')).toEqual([partialWithDraft]);
  });

  it('uses the arrival notice partial status even when it has no material lines yet', () => {
    const arrival = {
      taskId: 'arrival-1', sourceType: 'CUSTOMER_MATERIAL_ARRIVAL', sourceId: 'arrival-1',
      sourceNumber: 'WSI-1', inboundReason: 'GIFT', status: 'PARTIALLY_RECEIVED',
      statusLabel: '已部分收货', activeReceiptCount: 0, receiptConflict: false, items: [],
    } as WarehouseReceivingTask;
    expect(receivingTaskLifecycle(arrival)).toBe('PARTIAL');
  });
});
