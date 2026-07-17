import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from './permission';

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
}));

vi.mock('@/api/permissionApi', () => permissionApiMocks);

describe('permission store user module access overrides', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockReset().mockResolvedValue([]);
    permissionApiMocks.getFactoryOverride.mockReset().mockResolvedValue({});
    permissionApiMocks.getUserModuleAccess.mockReset().mockResolvedValue([]);
  });

  it('DENY override blocks the exact module and its legacy route module', () => {
    const store = usePermissionStore();
    store.setRole('factory_super_admin', 'F006', 'FACTORY', '1309');

    store.applyUserModuleAccess([
      {
        moduleCode: 'production_plan',
        displayName: '生产计划',
        category: '生产',
        permissionModule: 'production',
        roleDefaultAllowed: true,
        override: 'DENY',
        effectiveAllowed: false,
      },
    ]);

    expect(store.canAccess('production_plan')).toBe(false);
    expect(store.canAccess('production')).toBe(false);
  });

  it('GRANT override allows a module when role default denies it', () => {
    const store = usePermissionStore();
    store.setRole('unactivated', 'F006', 'FACTORY', '88');

    store.applyUserModuleAccess([
      {
        moduleCode: 'hr_employee',
        displayName: '员工/人事',
        category: '人事',
        permissionModule: 'hr',
        roleDefaultAllowed: false,
        override: 'GRANT',
        effectiveAllowed: true,
      },
    ]);

    expect(store.canAccess('hr_employee')).toBe(true);
    expect(store.canAccess('hr')).toBe(true);
  });

  it('missing override falls back to the role permission module', () => {
    const store = usePermissionStore();
    store.setRole('warehouse_manager', 'F006', 'FACTORY', '143');

    expect(store.canAccess('production_plan')).toBe(true);
    expect(store.canAccess('quality_inspection')).toBe(false);
  });

  it('deduplicates concurrent first-load requests and preserves the 30 second cache', async () => {
    let resolvePlatformPermissions: (value: never[]) => void = () => undefined;
    permissionApiMocks.getPlatformPermissions.mockReturnValueOnce(new Promise<never[]>((resolve) => {
      resolvePlatformPermissions = resolve;
    }));

    const store = usePermissionStore();
    store.setRole('factory_super_admin', 'F006', 'FACTORY', '1309', { skipDbLoad: true });

    const first = store.loadFromDb();
    const second = store.loadFromDb();

    expect(permissionApiMocks.getPlatformPermissions).toHaveBeenCalledTimes(1);
    expect(permissionApiMocks.getFactoryOverride).toHaveBeenCalledTimes(1);

    resolvePlatformPermissions([]);
    await Promise.all([first, second]);
    await store.loadFromDb();

    expect(store.isDbLoaded).toBe(true);
    expect(permissionApiMocks.getPlatformPermissions).toHaveBeenCalledTimes(1);
    expect(permissionApiMocks.getFactoryOverride).toHaveBeenCalledTimes(1);
    expect(permissionApiMocks.getUserModuleAccess).toHaveBeenCalledTimes(1);
  });
});
