/**
 * 缺陷 B — 领料批次选择器把「当前工序的单位」套给了所有批次.
 *
 * prod 实测 (F006, 2026-08-17): 批次 MT-20260817-8499(成品盒) / MT-20260817-8502(封膜卷) /
 * MT-20260817-8508(外箱片) 库里 quantityUnit 分别是 盒/卷/片, 界面上却统统显示
 * "剩余 N kg"(当前工序单位)。
 *
 * 排查发现这个显示缺陷有两层, 不是一层:
 *  1. 组件把 `unit` prop(工序单位) 套给每一行 —— 这是需求描述里点名的那一层.
 *  2. `MaterialBatch.unit`(前端契约字段) 实际映射自 Java 端
 *     `MaterialBatchMapper.java:77 dto.setUnit(batch.getMaterialType().getUnit())`,
 *     即【原料类型】的默认单位, 不是这一批的单位; `remainingQuantity` 真正对应的是
 *     `MaterialBatch.quantityUnit`(DB 列 quantity_unit NOT NULL, 批次自己的字段)。
 *     如果只把 (1) 修成读 `unit`, 会把"套错成工序单位"换成"套错成物料类型默认单位"——
 *     同一类缺陷换了个马甲, 这份测试用 unit≠quantityUnit 的桩数据把这一层也钉住。
 *
 * 断言跑在产品真实入口 (渲染 MaterialBatchPicker 本体, 只桩 API), 不是直接调 helper.
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import MaterialBatchPicker from '../../../components/processing/MaterialBatchPicker';
import { materialBatchApiClient, MaterialBatch } from '../../../services/api/materialBatchApiClient';

jest.mock('../../../services/api/materialBatchApiClient', () => {
  const actual = jest.requireActual('../../../services/api/materialBatchApiClient');
  return {
    ...actual,
    materialBatchApiClient: {
      getBatchesByStatus: jest.fn(),
      getBatchById: jest.fn(),
    },
  };
});

const mockedMaterialBatchApi = materialBatchApiClient as jest.Mocked<typeof materialBatchApiClient>;

/** F006 实测三个批次 —— unit(物料类型默认单位) 与 quantityUnit(批次自己的单位) 刻意不同,
 *  用来同时钉住"套工序单位"和"套物料类型默认单位"两种坏法。*/
function buildBatches(): MaterialBatch[] {
  const base = {
    factoryId: 'F006',
    materialTypeId: 'MT-TYPE-1',
    inboundQuantity: 200,
    reservedQuantity: 0,
    usedQuantity: 0,
    unitPrice: 3.5,
    totalCost: 700,
    supplierId: 'SUP-1',
    inboundDate: '2026-08-17',
    status: 'available' as const,
    createdAt: '2026-08-17T00:00:00Z',
  };
  return [
    {
      ...base,
      id: 'MT-20260817-8499',
      batchNumber: 'MT-20260817-8499',
      materialName: '成品盒',
      remainingQuantity: 99,
      unit: 'kg',          // 物料类型默认单位 (对这一批是错的)
      quantityUnit: '盒',   // 批次自己的单位 (对的那个)
    },
    {
      ...base,
      id: 'MT-20260817-8502',
      batchNumber: 'MT-20260817-8502',
      materialName: '封膜',
      remainingQuantity: 40,
      unit: 'kg',
      quantityUnit: '卷',
    },
    {
      ...base,
      id: 'MT-20260817-8508',
      batchNumber: 'MT-20260817-8508',
      materialName: '外箱',
      remainingQuantity: 15,
      unit: 'kg',
      quantityUnit: '片',
    },
  ];
}

async function renderExpanded(batches: MaterialBatch[]) {
  mockedMaterialBatchApi.getBatchesByStatus.mockResolvedValue({
    success: true, code: 200, message: 'ok', data: batches,
  });

  const onChange = jest.fn();
  render(
    <MaterialBatchPicker
      unit="kg"
      factoryId="F006"
      value={[]}
      onChange={onChange}
      forceIndependentQuantity
    />,
  );

  // 展开选择器 (触发 loadBatches).
  fireEvent.press(screen.getByLabelText('展开/折叠领料批次选择'));

  await waitFor(() => {
    expect(screen.getByText('MT-20260817-8499')).toBeTruthy();
  });

  return { onChange };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('MaterialBatchPicker 领料批次显示单位 (缺陷 B)', () => {
  it('每一行显示【这一批自己的单位】, 不是工序单位, 也不是物料类型默认单位', async () => {
    await renderExpanded(buildBatches());

    // 阳性: 三行分别显示它们各自的 quantityUnit.
    expect(screen.getByTestId('material-batch-remaining-MT-20260817-8499')).toHaveTextContent(
      '剩余 99 盒',
    );
    expect(screen.getByTestId('material-batch-remaining-MT-20260817-8502')).toHaveTextContent(
      '剩余 40 卷',
    );
    expect(screen.getByTestId('material-batch-remaining-MT-20260817-8508')).toHaveTextContent(
      '剩余 15 片',
    );

    // 阴性对照: 不许再出现「剩余 N kg」这种套错单位的旧读数(无论是工序单位还是
    // 物料类型默认单位, 两者这里都是 kg —— 单条阴性对照同时钉住两种坏法).
    expect(screen.queryByText('剩余 99 kg')).toBeNull();
    expect(screen.queryByText('剩余 40 kg')).toBeNull();
    expect(screen.queryByText('剩余 15 kg')).toBeNull();
  });

  it('选中批次后, 数量输入框后缀单位同样是批次自己的单位', async () => {
    await renderExpanded(buildBatches());

    // 选中"封膜"那一批 (quantityUnit=卷).
    fireEvent.press(screen.getByLabelText('选择批次 MT-20260817-8502'));

    await waitFor(() => {
      expect(screen.getByTestId('material-batch-qty-unit-MT-20260817-8502')).toHaveTextContent('卷');
    });
    expect(screen.queryByTestId('material-batch-qty-unit-MT-20260817-8502')).not.toHaveTextContent('kg');
  });

  it('批次没有 quantityUnit 时诚实兜底成工序单位 (不瞎猜、不留白)', async () => {
    const batches = buildBatches();
    // 第一批模拟"确实没有 quantityUnit"的边缘情况 (DB 约束理论上不该发生, 但契约层要防呆).
    const noUnitBatch: MaterialBatch = { ...batches[0]!, quantityUnit: undefined, unit: undefined };
    await renderExpanded([noUnitBatch, ...batches.slice(1)]);

    expect(screen.getByTestId('material-batch-remaining-MT-20260817-8499')).toHaveTextContent(
      '剩余 99 kg',
    );
  });
});
