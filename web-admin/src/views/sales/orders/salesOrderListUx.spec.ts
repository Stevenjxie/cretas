import { describe, expect, it } from 'vitest';
import {
  matchesSalesOrderListFilters,
  salesOrderLifecycleCounts,
  salesOrderLifecycleOf,
  salesOrderPaymentStateOf,
  salesOrderPrimaryActionOf,
  salesOrderShipmentStateOf,
} from './salesOrderListUx';

describe('salesOrderListUx', () => {
  it.each([
    ['DRAFT', 'todo'],
    ['FINANCE_REJECTED', 'todo'],
    ['CONFIRMED', 'reviewing'],
    ['PENDING_FINANCE_REVIEW', 'reviewing'],
    ['FINANCE_APPROVED', 'ready'],
    ['PROCESSING', 'fulfilling'],
    ['PARTIAL_DELIVERED', 'fulfilling'],
    ['COMPLETED', 'completed'],
    ['CANCELLED', 'closed'],
    ['legacy_unknown', 'todo'],
  ])('maps %s into exactly one lifecycle bucket', (status, expected) => {
    expect(salesOrderLifecycleOf({ status })).toBe(expected);
  });

  it('counts mutually exclusive lifecycle buckets from the same row set', () => {
    const counts = salesOrderLifecycleCounts([
      { status: 'DRAFT' },
      { status: 'CONFIRMED' },
      { status: 'FINANCE_APPROVED' },
      { status: 'PARTIAL_DELIVERED' },
      { status: 'COMPLETED' },
      { status: 'CANCELLED' },
    ]);
    expect(counts).toEqual({
      all: 6,
      todo: 1,
      reviewing: 1,
      ready: 1,
      fulfilling: 1,
      completed: 1,
      closed: 1,
    });
  });

  it('derives shipment state from business status before price-sensitive fields', () => {
    expect(salesOrderShipmentStateOf({ status: 'PARTIAL_DELIVERED' })).toBe('PARTIAL');
    expect(salesOrderShipmentStateOf({ status: 'COMPLETED' })).toBe('SHIPPED');
    expect(salesOrderShipmentStateOf({ status: 'PROCESSING', totalAmount: null })).toBe('UNSHIPPED');
    expect(salesOrderShipmentStateOf({ status: 'PROCESSING', totalAmount: 100, actualShippedAmount: 25 })).toBe('PARTIAL');
  });

  it('uses server paymentStatus and only falls back to amounts for legacy rows', () => {
    expect(salesOrderPaymentStateOf({ paymentStatus: 'PAID', paidAmount: null })).toBe('PAID');
    expect(salesOrderPaymentStateOf({ paymentStatus: 'PARTIAL' })).toBe('PARTIAL');
    expect(salesOrderPaymentStateOf({ totalAmount: 100, paidAmount: 0 })).toBe('UNPAID');
    expect(salesOrderPaymentStateOf({ totalAmount: 100, paidAmount: 40 })).toBe('PARTIAL');
  });

  it('combines lifecycle, shipment and payment filters without duplicating rows', () => {
    const row = {
      status: 'PARTIAL_DELIVERED',
      paymentStatus: 'UNPAID',
    };
    expect(matchesSalesOrderListFilters(row, {
      lifecycle: 'fulfilling', shipment: 'PARTIAL', payment: 'UNPAID',
    })).toBe(true);
    expect(matchesSalesOrderListFilters(row, {
      lifecycle: 'completed', shipment: 'PARTIAL', payment: 'UNPAID',
    })).toBe(false);
  });

  it.each([
    ['DRAFT', 'submit-review'],
    ['PENDING_FINANCE_REVIEW', 'view-review'],
    ['FINANCE_APPROVED', 'create-delivery'],
    ['APPROVED', 'view-detail'],
    ['PARTIAL_DELIVERED', 'continue-delivery'],
    ['COMPLETED', 'view-detail'],
  ])('selects one primary next action for %s', (status, expected) => {
    expect(salesOrderPrimaryActionOf({ status })).toBe(expected);
  });
});
