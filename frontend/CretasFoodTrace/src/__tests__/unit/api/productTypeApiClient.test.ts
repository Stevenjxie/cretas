import MockAdapter from 'axios-mock-adapter';

import { productTypeApiClient } from '../../../services/api/productTypeApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

const FACTORY_ID = 'LIUSHANMEN';
const OPTIONS_URL = `/api/mobile/${FACTORY_ID}/product-types/options`;

describe('productTypeApiClient finished SKU picker contract', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = createApiMock();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  it('keeps only active finished products from the current factory options endpoint', async () => {
    mock.onGet(OPTIONS_URL).reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 'finished-active',
            code: 'CP001',
            name: '澳洲谷饲盐葱横膈膜',
            unit: '盒',
            specification: '1.1kg',
            productCategory: 'FINISHED_PRODUCT',
            isActive: true,
          },
          {
            id: 'semi-active',
            code: 'PT001',
            name: '盐葱横膈膜/滚揉',
            unit: 'kg',
            productCategory: 'SEMI_FINISHED',
            isActive: true,
          },
          {
            id: 'finished-inactive',
            code: 'CP002',
            name: '已停用成品',
            unit: '盒',
            productCategory: 'FINISHED_PRODUCT',
            isActive: false,
          },
        ],
      },
      message: 'ok',
    });

    await expect(
      productTypeApiClient.getActiveFinishedProductOptions(FACTORY_ID),
    ).resolves.toEqual([
      expect.objectContaining({
        id: 'finished-active',
        code: 'CP001',
        productCategory: 'FINISHED_PRODUCT',
      }),
    ]);
    expect(mock.history.get).toHaveLength(1);
    expect(mock.history.get[0]?.url).toBe(OPTIONS_URL);
  });

  it('fails closed when the backend option has no product category', async () => {
    mock.onGet(OPTIONS_URL).reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 'legacy-unknown',
            code: 'LEGACY001',
            name: '未分类产品',
            isActive: true,
          },
        ],
      },
      message: 'ok',
    });

    await expect(
      productTypeApiClient.getActiveFinishedProductOptions(FACTORY_ID),
    ).resolves.toEqual([]);
  });
});
