import { describe, expect, it } from 'vitest';
import {
  actionableDeliveryCount,
  deliveryActionState,
  deliveryCapacityByLine,
  deliveryMoney,
  deliveryTransportAggregate,
  formatBusinessDateTime,
  productionActionState,
  resolveDeliveryAddress,
  shipmentValidationError,
} from '../salesOrderGuards';

const orderItems = [{ id: 726, productTypeId: 'SKU-BOX', quantity: 5, deliveredQuantity: 0, unit: 'box' }];

describe('M10 销售订单动作和发运门禁', () => {
  it('生产动作覆盖无计划、生产中、已完成，取消计划会释放动作', () => {
    expect(productionActionState(orderItems, [])).toMatchObject({ label: '开始生产', disabled: false });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'IN_PROGRESS' }]))
      .toMatchObject({ label: '生产中', disabled: true });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'COMPLETED' }]))
      .toMatchObject({ label: '已生产', disabled: true });
    expect(productionActionState(orderItems, [{ sourceOrderItemId: 726, plannedQuantity: 5, status: 'CANCELLED' }]))
      .toMatchObject({ label: '开始生产', disabled: false });
  });

  it('母发货单按每行有效安排量限制，取消子单释放母单容量', () => {
    const partial = [{ id: 'M1', recordRole: 'MASTER', status: 'PARTIALLY_SCHEDULED', items: [
      { salesOrderItemId: 726, productTypeId: 'SKU-BOX', deliveredQuantity: 2 },
    ] }];
    expect(deliveryCapacityByLine(orderItems, partial)[0]).toMatchObject({ ordered: 5, arranged: 2, remaining: 3 });
    expect(deliveryActionState(orderItems, partial)).toMatchObject({ label: '新建发货单', disabled: false });

    const full = [{ id: 'M1', recordRole: 'MASTER', status: 'FULLY_SCHEDULED', items: [
      { salesOrderItemId: 726, productTypeId: 'SKU-BOX', deliveredQuantity: 5 },
    ] }];
    expect(deliveryActionState(orderItems, full)).toMatchObject({ label: '已全部安排发货', disabled: true });
    expect(deliveryCapacityByLine(orderItems, [{ ...full[0], status: 'CANCELLED' }])[0].remaining).toBe(5);
  });

  it('红色角标只统计待处理叶子发运，签收后聚合为已签收', () => {
    const rows = [
      { id: 'M1', recordRole: 'MASTER', status: 'FULLY_SCHEDULED' },
      { id: 'S1', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'SHIPPED' },
      { id: 'S2', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'DELIVERED' },
      { id: 'S3', parentDeliveryId: 'M1', recordRole: 'SHIPMENT', status: 'CANCELLED' },
    ];
    expect(actionableDeliveryCount(rows)).toBe(1);
    expect(deliveryTransportAggregate(rows)).toBe('PARTIALLY_RECEIVED');
    expect(actionableDeliveryCount(rows.map((row) => row.id === 'S1' ? { ...row, status: 'DELIVERED' } : row))).toBe(0);
    expect(deliveryTransportAggregate(rows.map((row) => row.id === 'S1' ? { ...row, status: 'DELIVERED' } : row))).toBe('RECEIVED');
  });

  it('物流配送在发运前 fail-closed，自送/自提显式免填运单号', () => {
    expect(shipmentValidationError({ deliveryMethod: 'LOGISTICS', plannedShipmentDate: '2026-07-21' }))
      .toBe('物流配送必须填写物流公司');
    expect(shipmentValidationError({ deliveryMethod: 'LOGISTICS', plannedShipmentDate: '2026-07-21', logisticsCompany: '顺丰' }))
      .toBe('物流配送必须填写物流/运单号');
    expect(shipmentValidationError({ deliveryMethod: 'SELF_PICKUP', plannedShipmentDate: '2026-07-21' })).toBe('');
  });

  it('地址优先订单再客户，部分发货金额按本次数量与税率计算', () => {
    expect(resolveDeliveryAddress('订单地址', '客户地址')).toBe('订单地址');
    expect(resolveDeliveryAddress('', '客户地址')).toBe('客户地址');
    expect(resolveDeliveryAddress('', '')).toBe('');
    expect(deliveryMoney([{ deliveredQuantity: 2, unitPrice: 20, taxRate: 13 }]))
      .toEqual({ untaxed: 40, tax: 5.2, taxIncluded: 45.2 });
  });

  it('审批/业务时间本地化到秒并标注周几', () => {
    expect(formatBusinessDateTime('2026-07-20T12:30:10.619754')).toBe('2026-07-20（周一）12:30:10');
  });
});
