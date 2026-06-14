import {
  getUserDisplayName,
  getUserRole,
  hasRole,
  isFactoryAdmin,
  isPlatformAdmin,
  transformBackendUser,
} from '../../../utils/roleMapping';

describe('roleMapping', () => {
  it('transforms backend factory users and generates role permissions', () => {
    const user = transformBackendUser({
      id: 42,
      username: 'workshop.lead',
      email: 'lead@example.com',
      realName: 'Workshop Lead',
      roleCode: 'factory_super_admin',
      factoryId: 'F006',
      factoryType: 'FACTORY',
      department: 'processing',
      position: 'lead',
      createdAt: '2026-06-12T08:00:00Z',
      updatedAt: '2026-06-12T08:30:00Z',
    });

    expect(user.userType).toBe('factory');
    expect(user.id).toBe('42');
    expect(user.fullName).toBe('Workshop Lead');
    expect(user.isActive).toBe(true);
    expect(user.factoryUser?.role).toBe('factory_super_admin');
    expect(user.factoryUser?.factoryId).toBe('F006');
    expect(user.factoryUser?.permissions).toEqual(
      expect.arrayContaining(['admin_access', 'processing_access', 'logistics_access'])
    );
    expect(getUserRole(user)).toBe('factory_super_admin');
    expect(getUserDisplayName(user)).toBe('Workshop Lead');
    expect(hasRole(user, 'factory_super_admin')).toBe(true);
    expect(isFactoryAdmin(user)).toBe(true);
    expect(isPlatformAdmin(user)).toBe(false);
  });

  it('transforms backend platform users and keeps explicit permissions', () => {
    const user = transformBackendUser({
      id: 7,
      username: 'platform.admin',
      role: 'platform_admin',
      userType: 'platform',
      permissions: ['platform_access'],
      isActive: false,
    });

    expect(user.userType).toBe('platform');
    expect(user.username).toBe('platform.admin');
    expect(user.isActive).toBe(false);
    expect(user.platformUser?.role).toBe('platform_admin');
    expect(user.platformUser?.permissions).toEqual(['platform_access']);
    expect(getUserRole(user)).toBe('platform_admin');
    expect(getUserDisplayName(user)).toBe('platform.admin');
    expect(hasRole(user, 'platform_admin')).toBe(true);
    expect(isFactoryAdmin(user)).toBe(false);
  });

  it('fills missing permissions for users already in frontend format', () => {
    const user = transformBackendUser({
      id: 9,
      username: 'operator',
      email: 'operator@example.com',
      createdAt: '2026-06-12T08:00:00Z',
      updatedAt: '2026-06-12T08:30:00Z',
      isActive: true,
      userType: 'factory',
      factoryUser: {
        role: 'operator',
        factoryId: 'F006',
        permissions: [],
      },
    });

    expect(user.factoryUser?.permissions).toEqual(['processing_access']);
    expect(getUserRole(user)).toBe('operator');
    expect(getUserDisplayName(user)).toBe('operator');
    expect(isFactoryAdmin(user)).toBe(false);
  });

  it('recognizes legacy admin roles used by helper checks', () => {
    const platformDeveloper = transformBackendUser({
      id: 11,
      username: 'developer',
      userType: 'platform',
      platformUser: {
        role: 'developer',
        permissions: [],
      },
    });
    const permissionAdmin = transformBackendUser({
      id: 12,
      username: 'permission.admin',
      userType: 'factory',
      factoryUser: {
        role: 'permission_admin',
        factoryId: 'F006',
        permissions: [],
      },
    });

    expect(isPlatformAdmin(platformDeveloper)).toBe(true);
    expect(isFactoryAdmin(permissionAdmin)).toBe(true);
    expect(hasRole(permissionAdmin, 'factory_super_admin')).toBe(false);
  });
});
