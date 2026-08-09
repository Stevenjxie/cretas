import { apiClient } from '../../../services/api/apiClient';
import { operationsApiClient } from '../../../services/api/operationsApiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

const FACTORY_ID = 'F006';
const BASE = `/api/mobile/${FACTORY_ID}/operations/customer-material-arrivals`;
const mockGet = apiClient.get as jest.Mock;
const mockPost = apiClient.post as jest.Mock;

beforeEach(() => jest.clearAllMocks());

describe('operationsApiClient', () => {
  it('lists open customer-material arrival notices', async () => {
    mockGet.mockResolvedValue({
      success: true,
      data: [{ id: 'notice-1', status: 'OPEN', receiptCount: 0 }],
      message: 'ok',
    });

    const result = await operationsApiClient.listCustomerMaterialArrivals(true, FACTORY_ID);

    expect(result.success).toBe(true);
    expect(result.data).toHaveLength(1);
    expect(mockGet).toHaveBeenCalledWith(BASE, { params: { openOnly: true } });
  });

  it('creates only the coordination notice payload', async () => {
    mockPost.mockResolvedValue({
      success: true,
      data: { id: 'notice-2', status: 'PENDING_APPROVAL', receiptCount: 0 },
      message: 'created',
    });

    const payload = {
      customerId: 'customer-1',
      expectedArrivalAt: '2026-08-10T09:30:00',
      contactName: '张师傅',
      contactPhone: '13800000000',
      remark: '上午到厂',
    };
    const result = await operationsApiClient.createCustomerMaterialArrival(payload, FACTORY_ID);

    expect(result.data.status).toBe('PENDING_APPROVAL');
    expect(mockPost).toHaveBeenCalledWith(BASE, payload);
    expect(JSON.stringify(mockPost.mock.calls[0][1])).not.toContain('quantity');
    expect(JSON.stringify(mockPost.mock.calls[0][1])).not.toContain('materialId');
  });

  it('uses the dedicated cancel endpoint', async () => {
    mockPost.mockResolvedValue({
      success: true,
      data: { id: 'notice-1', status: 'CANCELLED', receiptCount: 0 },
      message: 'cancelled',
    });

    const result = await operationsApiClient.cancelCustomerMaterialArrival('notice-1', FACTORY_ID);

    expect(result.data.status).toBe('CANCELLED');
    expect(mockPost).toHaveBeenCalledWith(`${BASE}/notice-1/cancel`, {});
  });
});
