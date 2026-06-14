import {
  getPostLoginRoute,
  getRoleDisplayName,
  hasModuleAccess,
  hasPermission,
} from '../../../utils/navigationHelper';
import { transformBackendUser } from '../../../utils/roleMapping';

describe('navigationHelper', () => {
  it('routes operators directly to time clock for faster daily work', () => {
    const user = transformBackendUser({
      id: 1,
      username: 'operator',
      role: 'operator',
      factoryId: 'F006',
      permissions: ['processing_access'],
    });

    expect(getPostLoginRoute(user)).toEqual({
      screen: 'Attendance',
      params: { screen: 'TimeClock' },
    });
  });

  it('routes processing department admins to the processing dashboard', () => {
    const user = transformBackendUser({
      id: 2,
      username: 'processing.admin',
      role: 'department_admin',
      factoryId: 'F006',
      department: 'processing',
    });

    expect(getPostLoginRoute(user)).toEqual({
      screen: 'Main',
      params: {
        screen: 'ProcessingTab',
        params: { screen: 'ProcessingDashboard' },
      },
    });
  });

  it('routes unactivated users back to login', () => {
    const user = transformBackendUser({
      id: 3,
      username: 'inactive',
      role: 'unactivated',
      factoryId: 'F006',
    });

    expect(getPostLoginRoute(user)).toEqual({ screen: 'Login' });
  });

  it('checks array permissions for modules and named permissions', () => {
    const user = transformBackendUser({
      id: 4,
      username: 'warehouse',
      role: 'warehouse_worker',
      factoryId: 'F006',
      permissions: ['warehouse_access', 'warehouse:write'],
    });

    expect(hasModuleAccess(user, 'warehouse_access')).toBe(true);
    expect(hasPermission(user, 'warehouse:write')).toBe(true);
    expect(hasPermission(user, 'production:write')).toBe(false);
  });

  it('checks object-shaped permissions from existing frontend user data', () => {
    const user = transformBackendUser({
      id: 5,
      username: 'platform',
      createdAt: '2026-06-12T08:00:00Z',
      updatedAt: '2026-06-12T08:00:00Z',
      isActive: true,
      userType: 'platform',
      platformUser: {
        role: 'platform_admin',
        permissions: {
          modules: { platform_access: true },
          features: ['debug:read'],
        },
      },
    });

    expect(getPostLoginRoute(user)).toEqual({
      screen: 'Main',
      params: { screen: 'HomeTab' },
    });
    expect(hasModuleAccess(user, 'platform_access')).toBe(true);
    expect(hasPermission(user, 'debug:read')).toBe(true);
    expect(hasPermission(user, 'debug:write')).toBe(false);
  });

  it('returns role display names and falls back to the role code', () => {
    expect(getRoleDisplayName('operator')).not.toBe('operator');
    expect(getRoleDisplayName('production_manager')).toBe('production_manager');
  });
});
