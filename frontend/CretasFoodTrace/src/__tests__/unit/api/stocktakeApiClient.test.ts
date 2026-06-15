import { stocktakeApiClient } from '../../../services/api/stocktakeApiClient';
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

describe('stocktakeApiClient', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGetCurrentFactoryId.mockImplementation((factoryId?: string) => factoryId || 'F006');
  });

  it('initiates a stocktake through the factory-scoped route', async () => {
    const body = { warehouseId: 'WH-1', periodMonth: '2026-06', notes: 'cycle count' };
    const response = { success: true, data: { id: 'ST-1' } };
    mockedApiClient.post.mockResolvedValue(response);

    await expect(stocktakeApiClient.initiate(body, 'F123')).resolves.toBe(response);

    expect(mockedApiClient.post).toHaveBeenCalledWith('/api/mobile/F123/stocktakes', body);
  });

  it('lists stocktakes with query params', async () => {
    const params = { status: 'COUNTING', page: 0, size: 20 };
    const response = { success: true, data: { content: [] } };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(stocktakeApiClient.list(params, 'F123')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F123/stocktakes', { params });
  });

  it('gets stocktake detail with item lines', async () => {
    const response = { success: true, data: { id: 'ST-1', items: [] } };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(stocktakeApiClient.getDetail('ST-1', 'F123')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F123/stocktakes/ST-1');
  });

  it('updates counted item quantities', async () => {
    const items = [{ itemId: 'ITEM-1', actualQty: 0 }];
    const response = { success: true, data: null };
    mockedApiClient.put.mockResolvedValue(response);

    await expect(stocktakeApiClient.updateItems('ST-1', items, 'F123')).resolves.toBe(response);

    expect(mockedApiClient.put).toHaveBeenCalledWith('/api/mobile/F123/stocktakes/ST-1/items', items);
  });

  it('submits a stocktake for approval', async () => {
    const response = { success: true, data: null };
    mockedApiClient.post.mockResolvedValue(response);

    await expect(stocktakeApiClient.submit('ST-1', 'F123')).resolves.toBe(response);

    expect(mockedApiClient.post).toHaveBeenCalledWith('/api/mobile/F123/stocktakes/ST-1/submit');
  });

  it('lists factory warehouses for stocktake launch', async () => {
    const response = { success: true, data: [] };
    mockedApiClient.get.mockResolvedValue(response);

    await expect(stocktakeApiClient.listWarehouses('F123')).resolves.toBe(response);

    expect(mockedApiClient.get).toHaveBeenCalledWith('/api/mobile/F123/factory/warehouses');
  });

  it('throws before calling the API when no factoryId can be resolved', async () => {
    mockedGetCurrentFactoryId.mockReturnValue('');

    await expect(stocktakeApiClient.getDetail('ST-1')).rejects.toThrow('factoryId');

    expect(mockedApiClient.get).not.toHaveBeenCalled();
  });
});
