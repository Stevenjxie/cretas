import { afterEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';

import {
  fetchLoginToken,
  loginOrReuseSession,
} from '../../e2e-auth-helper';

const tempDirs: string[] = [];

function writeStorageState(): { file: string; token: string } {
  const dir = mkdtempSync(join(tmpdir(), 'cretas-e2e-auth-'));
  tempDirs.push(dir);
  const payload = Buffer.from(JSON.stringify({
    factoryId: 'F006',
    userId: 'user-1',
    username: 'f006_admin',
    role: 'factory_super_admin',
  })).toString('base64url');
  const token = `header.${payload}.signature`;
  const file = join(dir, 'storage-state.json');
  writeFileSync(file, JSON.stringify({
    origins: [{
      origin: 'https://example.invalid',
      localStorage: [{ name: 'cretas_access_token', value: token }],
    }],
  }));
  return { file, token };
}

afterEach(() => {
  vi.unstubAllGlobals();
  delete process.env.E2E_STORAGE_STATE;
  for (const dir of tempDirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('E2E auth credential boundary', () => {
  it('fails before the network when no password is configured', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchLoginToken('f006_admin', '', 'https://example.invalid/api/mobile'))
      .rejects.toThrow('未配置 f006_admin 的 E2E 口令');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reuses storageState directly instead of sending an empty-password login', async () => {
    const { file, token } = writeStorageState();
    process.env.E2E_STORAGE_STATE = file;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = await loginOrReuseSession(
      'f006_admin',
      '',
      'https://example.invalid/api/mobile',
      'credential-boundary-test',
    );

    expect(result.token).toBe(token);
    expect(result.loginData).toMatchObject({
      factoryId: 'F006',
      userId: 'user-1',
      username: 'f006_admin',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
