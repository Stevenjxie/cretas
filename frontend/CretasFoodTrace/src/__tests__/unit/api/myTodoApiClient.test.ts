import { myTodoApiClient, todoApprovalApiClient } from '../../../services/api/myTodoApiClient';
import { apiClient } from '../../../services/api/apiClient';
import { getCurrentFactoryId } from '../../../utils/factoryIdHelper';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
  },
}));

jest.mock('../../../utils/factoryIdHelper', () => ({
  getCurrentFactoryId: jest.fn((factoryId?: string) => factoryId || 'F006'),
}));

const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>;
const mockedGetCurrentFactoryId = getCurrentFactoryId as jest.MockedFunction<typeof getCurrentFactoryId>;

describe('myTodoApiClient', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGetCurrentFactoryId.mockImplementation((factoryId?: string) => factoryId || 'F006');
  });

  it('GETs my todos through the factory-scoped OA route', async () => {
    const response = { success: true, data: [] };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(myTodoApiClient.getMyTodos('F123')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F123/my-todos');
  });

  it('GETs my todo count through the factory-scoped OA count route', async () => {
    const response = { success: true, data: 7 };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(myTodoApiClient.getMyTodoCount('F123')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F123/my-todos/count');
  });

  it('falls back to the current factoryId when no factoryId is provided', async () => {
    const response = { success: true, data: [] };
    mockedApiClient.get.mockResolvedValue(response);

    await myTodoApiClient.getMyTodos();

    expect(mockedGetCurrentFactoryId).toHaveBeenCalledWith(undefined);
    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F006/my-todos');
  });

  it('throws before calling the API when no factoryId can be resolved', async () => {
    mockedGetCurrentFactoryId.mockReturnValue('');

    await expect(myTodoApiClient.getMyTodos()).rejects.toThrow('factoryId');

    expect(mockedApiClient.get).not.toHaveBeenCalled();
  });
});

describe('todoApprovalApiClient', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGetCurrentFactoryId.mockImplementation((factoryId?: string) => factoryId || 'F006');
    mockedApiClient.post.mockResolvedValue({ success: true, data: null });
    mockedApiClient.put.mockResolvedValue({ success: true, data: null });
  });

  it.each([
    [
      'purchase finance approve',
      () => todoApprovalApiClient.purchaseFinanceApprove('PO-1', 'ok', 'F123'),
      'post',
      '/api/mobile/F123/purchase/orders/PO-1/finance-approve',
      { notes: 'ok' },
    ],
    [
      'purchase finance reject',
      () => todoApprovalApiClient.purchaseFinanceReject('PO-1', 'missing invoice', 'F123'),
      'post',
      '/api/mobile/F123/purchase/orders/PO-1/finance-reject',
      { notes: 'missing invoice' },
    ],
    [
      'sales finance approve',
      () => todoApprovalApiClient.salesFinanceApprove('SO-1', 'ok', 'F123'),
      'post',
      '/api/mobile/F123/sales/orders/SO-1/finance-approve',
      { notes: 'ok' },
    ],
    [
      'sales finance reject',
      () => todoApprovalApiClient.salesFinanceReject('SO-1', 'wrong price', 'F123'),
      'post',
      '/api/mobile/F123/sales/orders/SO-1/finance-reject',
      { notes: 'wrong price' },
    ],
    [
      'price anomaly approve',
      () => todoApprovalApiClient.priceAnomalyApprove('DN-1', 'F123'),
      'post',
      '/api/mobile/F123/warehouse/supplier-delivery-notes/DN-1/price-anomaly/approve',
      undefined,
    ],
    [
      'price anomaly reject',
      () => todoApprovalApiClient.priceAnomalyReject('DN-1', 'price mismatch', 'F123'),
      'post',
      '/api/mobile/F123/warehouse/supplier-delivery-notes/DN-1/price-anomaly/reject',
      { notes: 'price mismatch' },
    ],
    [
      'stocktake approve',
      () => todoApprovalApiClient.stocktakeApprove('ST-1', 'F123'),
      'post',
      '/api/mobile/F123/stocktakes/ST-1/approve',
      undefined,
    ],
    [
      'stocktake reject',
      () => todoApprovalApiClient.stocktakeReject('ST-1', 'count mismatch', 'F123'),
      'post',
      '/api/mobile/F123/stocktakes/ST-1/reject',
      { notes: 'count mismatch' },
    ],
    [
      'return finance approve',
      () => todoApprovalApiClient.returnFinanceApprove('RO-1', 'F123'),
      'post',
      '/api/mobile/F123/return-orders/RO-1/finance-approve',
      undefined,
    ],
    [
      'return finance reject',
      () => todoApprovalApiClient.returnFinanceReject('RO-1', 'return mismatch', 'F123'),
      'post',
      '/api/mobile/F123/return-orders/RO-1/finance-reject',
      { notes: 'return mismatch' },
    ],
    [
      'payment mark-paid',
      () => todoApprovalApiClient.paymentMarkPaid('PAY-1', 'F123'),
      'put',
      '/api/mobile/F123/payment-requests/PAY-1/mark-paid',
      {},
    ],
  ] as const)('calls the %s endpoint', async (_name, call, method, expectedPath, expectedBody) => {
    await call();

    if (expectedBody === undefined) {
      expect(mockedApiClient[method]).toHaveBeenCalledWith(expectedPath);
    } else {
      expect(mockedApiClient[method]).toHaveBeenCalledWith(expectedPath, expectedBody);
    }
  });

  it('throws before approval API calls when no factoryId can be resolved', async () => {
    mockedGetCurrentFactoryId.mockReturnValue('');

    await expect(todoApprovalApiClient.paymentMarkPaid('PAY-1')).rejects.toThrow('factoryId');

    expect(mockedApiClient.put).not.toHaveBeenCalled();
  });
});
