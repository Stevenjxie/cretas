import { transitLedgerApiClient } from '../../../services/api/transitLedgerApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

jest.mock('../../../utils/factoryIdHelper', () => ({
  requireFactoryId: jest.fn((factoryId?: string) => factoryId || 'F006'),
}));

const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>;

describe('transitLedgerApiClient', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('lists pending warehouse receipt confirmations through RN contract route', async () => {
    const response = {
      success: true,
      data: [{ id: 'PLAN-1', direction: 'FINISHED_GOODS_RECEIPT', status: 'PENDING_CONFIRMATION' }],
    };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(transitLedgerApiClient.listPending('F006')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith(
      '/api/mobile/F006/warehouse/transit-ledgers',
      { params: { status: 'PENDING_CONFIRMATION' } },
    );
  });

  it('confirms by productionPlanId and preserves per-output receipt lines', async () => {
    const response = {
      success: true,
      data: { productionPlanId: 'PLAN-1', warehouseReceivedQuantity: 95 },
    };
    mockedApiClient.post.mockResolvedValue(response);

    await expect(transitLedgerApiClient.confirm('PLAN-1', {
      outputLines: [{
        productTypeId: 'FG-A',
        batchNumber: 'FG-A-001',
        receivedQuantity: 8,
        quantityUnit: 'kg',
      }],
      note: 'scale checked',
    }, 'F006')).resolves.toBe(response);

    expect(mockedApiClient.post).toHaveBeenCalledWith(
      '/api/mobile/F006/warehouse/transit-ledgers/PLAN-1/confirm',
      {
        outputLines: [{
          productTypeId: 'FG-A',
          batchNumber: 'FG-A-001',
          receivedQuantity: 8,
          quantityUnit: 'kg',
        }],
        note: 'scale checked',
      },
    );
  });
});
