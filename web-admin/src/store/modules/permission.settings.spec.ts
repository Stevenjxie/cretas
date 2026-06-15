import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from './permission';

describe('permission settings effective module levels', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('normalizes legacy and api levels into hidden/read/write', () => {
    const store = usePermissionStore();
    store.setRole('viewer', 'F006', 'FACTORY', '101');

    store.applyEffectiveModules([
      { moduleCode: 'permission_employee_management', permissionLevel: 'rw' },
      { moduleCode: 'permission_role_templates', permissionLevel: 'r' },
      { moduleCode: 'permission_employee_overrides', permissionLevel: '-' },
    ]);

    expect(store.effectiveLevelFor('permission_employee_management')).toBe('write');
    expect(store.effectiveLevelFor('permission_role_templates')).toBe('read');
    expect(store.effectiveLevelFor('permission_employee_overrides')).toBe('hidden');
  });

  it('lets factory super admin access and write every second-level module', () => {
    const store = usePermissionStore();
    store.setRole('factory_super_admin', 'F006', 'FACTORY', '1');

    expect(store.canAccessModuleCode('permission_employee_management')).toBe(true);
    expect(store.canWriteModuleCode('permission_preview')).toBe(true);
  });

  it('hides a menu route when effective level is hidden', () => {
    const store = usePermissionStore();
    store.setRole('viewer', 'F006', 'FACTORY', '101');
    store.applyEffectiveModules([
      { moduleCode: 'permission_employee_management', permissionLevel: 'hidden' },
    ]);

    expect(store.canAccessModuleCode('permission_employee_management')).toBe(false);
  });

  it('allows read route access but denies write when level is read', () => {
    const store = usePermissionStore();
    store.setRole('viewer', 'F006', 'FACTORY', '101');
    store.applyEffectiveModules([
      { moduleCode: 'permission_role_templates', permissionLevel: 'read' },
    ]);

    expect(store.canAccessModuleCode('permission_role_templates')).toBe(true);
    expect(store.canWriteModuleCode('permission_role_templates')).toBe(false);
  });

  it('employee override wins over role template level', () => {
    const store = usePermissionStore();
    store.setRole('viewer', 'F006', 'FACTORY', '101');
    store.applyEffectiveModules([
      { moduleCode: 'hr_employee', permissionLevel: 'hidden' },
    ]);

    expect(store.effectiveLevelFor('hr_employee')).toBe('hidden');
  });
});
