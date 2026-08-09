import axios from 'axios';

import { checkAppMinVersion } from './appVersionCheck';

jest.mock('expo-constants', () => ({
  __esModule: true,
  default: {
    expoConfig: { version: '1.0.3' },
    nativeAppVersion: '1.0.3',
  },
}));

describe('checkAppMinVersion', () => {
  const getSpy = jest.spyOn(axios, 'get');

  beforeEach(() => {
    getSpy.mockReset();
  });

  afterAll(() => {
    getSpy.mockRestore();
  });

  it('allows a supported native version', async () => {
    getSpy.mockResolvedValue({
      data: {
        success: true,
        data: {
          currentVersion: '1.0.3',
          latestVersion: '1.0.5',
          minimumVersion: '1.0.3',
          updateRequired: false,
          updateAvailable: true,
        },
      },
    });

    await expect(checkAppMinVersion()).resolves.toEqual({
      status: 'supported',
      currentVersion: '1.0.3',
    });
  });

  it('returns a non-bypassable update result with the official download URL', async () => {
    getSpy.mockResolvedValue({
      data: {
        success: true,
        data: {
          currentVersion: '1.0.3',
          latestVersion: '1.1.0',
          minimumVersion: '1.0.4',
          updateRequired: true,
          downloadUrl: 'https://dl.cretaceousfuture.com/cretas-v1.1.0.apk',
          releaseNotes: '修复移动端权限边界',
        },
      },
    });

    await expect(checkAppMinVersion()).resolves.toEqual({
      status: 'update_required',
      currentVersion: '1.0.3',
      minimumVersion: '1.0.4',
      latestVersion: '1.1.0',
      downloadUrl: 'https://dl.cretaceousfuture.com/cretas-v1.1.0.apk',
      releaseNotes: '修复移动端权限边界',
    });
  });

  it('defensively blocks when the minimum version is newer even if the flag drifts', async () => {
    getSpy.mockResolvedValue({
      data: {
        success: true,
        data: {
          latestVersion: '1.0.4',
          minimumVersion: '1.0.4',
          updateRequired: false,
          downloadUrl: 'https://dl.cretaceousfuture.com/cretas-v1.0.4.apk',
        },
      },
    });

    const result = await checkAppMinVersion();

    expect(result.status).toBe('update_required');
  });

  it('preserves offline access when the anonymous version endpoint is unavailable', async () => {
    getSpy.mockRejectedValue(new Error('network unavailable'));

    await expect(checkAppMinVersion()).resolves.toEqual({
      status: 'unavailable',
      currentVersion: '1.0.3',
    });
  });
});
