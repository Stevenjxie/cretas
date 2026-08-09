import { apiClient } from '../../../services/api/apiClient';
import { warehouseReceivingApiClient } from '../../../services/api/warehouseReceivingApiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

const FACTORY_ID = 'F006';
const BASE = `/api/mobile/${FACTORY_ID}/warehouse/receiving`;
const mockGet = apiClient.get as jest.Mock;
const mockPost = apiClient.post as jest.Mock;

beforeEach(() => jest.clearAllMocks());

describe('warehouseReceivingApiClient', () => {
  it('queries only open customer-material arrival tasks', async () => {
    mockGet.mockResolvedValue({ success: true, data: [] });

    await warehouseReceivingApiClient.listCustomerMaterialArrivalTasks(undefined, FACTORY_ID);

    expect(mockGet).toHaveBeenCalledWith(`${BASE}/tasks`, {
      params: {
        arrivalNoticeId: undefined,
        sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
      },
    });
  });

  it('filters an exact shared notice for cross-role takeover', async () => {
    mockGet.mockResolvedValue({ success: true, data: [] });

    await warehouseReceivingApiClient.listCustomerMaterialArrivalTasks(
      { arrivalNoticeId: 'notice-1' },
      FACTORY_ID,
    );

    expect(mockGet).toHaveBeenCalledWith(`${BASE}/tasks`, {
      params: {
        arrivalNoticeId: 'notice-1',
        sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
      },
    });
  });

  it('posts the receipt to the dedicated unordered-inbound endpoint', async () => {
    mockPost.mockResolvedValue({ success: true, data: { id: 'batch-1' } });
    const payload = {
      idempotencyKey: 'rn-notice-1-attempt-1',
      materialTypeId: 'material-1',
      warehouseId: 'warehouse-1',
      receivedQuantity: 0.01,
      unit: 'kg',
      completeNotice: false,
    };

    await warehouseReceivingApiClient.receiveCustomerMaterialArrival(
      'notice-1',
      payload,
      FACTORY_ID,
    );

    expect(mockPost).toHaveBeenCalledWith(
      `${BASE}/arrival-notices/notice-1/receipts`,
      payload,
    );
  });
});
