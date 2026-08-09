import fs from 'fs';
import path from 'path';

const appNavigatorSource = fs.readFileSync(
  path.resolve(__dirname, '../../../navigation/AppNavigator.tsx'),
  'utf8',
);

describe('AppNavigator authenticated role access contract', () => {
  it('does not replace non-warehouse roles with a root-level web-only screen', () => {
    expect(appNavigatorSource).not.toContain('WebOnlyRoleScreen');
    expect(appNavigatorSource).not.toContain('WAREHOUSE_ROLES');
  });

  it('keeps restaurant admins and quality inspectors on their mobile navigators', () => {
    expect(appNavigatorSource).toContain('isRestaurant(user) ? <FactoryAdminNavigator />');
    expect(appNavigatorSource).toContain('userRole === "quality_inspector"');
    expect(appNavigatorSource).toContain('return <QualityInspectorNavigator />');
  });

  it('routes operations and legacy production managers to explicit mobile boundaries', () => {
    expect(appNavigatorSource).toContain('userRole === "operations_coordinator"');
    expect(appNavigatorSource).toContain('return <OperationsNavigator />');
    expect(appNavigatorSource).toContain('userRole === "production_manager"');
    expect(appNavigatorSource).toContain('return <ProductionManagerNavigator />');
  });

  it('keeps warehouse workers on a task-focused navigator distinct from managers', () => {
    expect(appNavigatorSource).toContain('userRole === "warehouse_manager"');
    expect(appNavigatorSource).toContain('return <WarehouseManagerNavigator />');
    expect(appNavigatorSource).toContain('userRole === "warehouse_worker"');
    expect(appNavigatorSource).toContain('return <WarehouseWorkerNavigator />');
  });

  it('routes factory bosses to view and approval while preserving restaurant admin flows', () => {
    expect(appNavigatorSource).toContain('return isRestaurant(user) ? <FactoryAdminNavigator /> : <BossNavigator />');
  });

  it('uses a locked fallback for unknown roles', () => {
    const explicitMainRoles = appNavigatorSource.indexOf('userRole === "platform_admin"');
    const fallback = appNavigatorSource.lastIndexOf('return <RestrictedRoleNavigator />');
    expect(explicitMainRoles).toBeGreaterThan(-1);
    expect(fallback).toBeGreaterThan(explicitMainRoles);
    expect(appNavigatorSource.slice(fallback)).not.toContain('return <MainNavigator />');
  });
});
