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
    expect(appNavigatorSource).toContain('return <FactoryAdminNavigator />');
    expect(appNavigatorSource).toContain('userRole === "quality_inspector"');
    expect(appNavigatorSource).toContain('return <QualityInspectorNavigator />');
  });

  it('keeps the authenticated fallback navigator available to other roles', () => {
    expect(appNavigatorSource).toContain('return <MainNavigator />');
  });
});
