import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { whitelistApiClient } from '../../../services/api/whitelistApiClient';

describe('whitelistApiClient account invitation contract', () => {
  let mock: MockAdapter;
  const factoryId = 'LIUSHANMEN';
  const base = `/api/mobile/${factoryId}/whitelist`;

  beforeEach(() => {
    mock = createApiMock();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  it('posts a single invite using backend role and name field names', async () => {
    mock.onPost(base).reply(config => {
      expect(JSON.parse(config.data)).toEqual({
        phoneNumber: '13800138051',
        name: '六扇门质检员',
        role: 'quality_inspector',
        notes: 'QC账号',
      });
      return [200, {
        success: true,
        message: 'ok',
        data: {
          successCount: 1,
          failedCount: 0,
          successPhones: ['13800138051'],
          failedEntries: [],
        },
      }];
    });

    const result = await whitelistApiClient.addWhitelist({
      factoryId,
      phoneNumber: '13800138051',
      realName: '六扇门质检员',
      presetRole: 'quality_inspector',
      notes: 'QC账号',
    });

    expect(result).toEqual({ success: true, message: '邀请已发送' });
  });

  it('builds backend batch payload with entries instead of legacy whitelists', () => {
    expect(whitelistApiClient.createBatchRequest(
      [
        {
          phoneNumber: '13800138052',
          realName: '报工一组',
          role: 'yield_operator',
        },
      ],
      'yield_operator',
      'production',
    )).toEqual({
      entries: [{ phoneNumber: '13800138052', name: '报工一组' }],
      role: 'yield_operator',
      department: 'production',
    });
  });

  it('omits an empty batch name so the employee can provide it during onboarding', () => {
    expect(whitelistApiClient.createBatchRequest(
      [
        {
          phoneNumber: '13800138053',
          realName: '   ',
          role: 'quality_inspector',
        },
      ],
      'quality_inspector',
      'quality',
    )).toEqual({
      entries: [{ phoneNumber: '13800138053' }],
      role: 'quality_inspector',
      department: 'quality',
    });
  });
});
