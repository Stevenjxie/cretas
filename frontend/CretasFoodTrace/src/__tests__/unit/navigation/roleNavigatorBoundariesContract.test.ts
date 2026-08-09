import fs from 'fs';
import path from 'path';

const read = (relativePath: string) => fs.readFileSync(
  path.resolve(__dirname, `../../../${relativePath}`),
  'utf8',
);

describe('role-specific mobile navigator boundaries', () => {
  it('keeps the factory boss on overview, real approvals, analysis and account only', () => {
    const source = read('navigation/BossNavigator.tsx');
    expect(source).toContain('BossOverviewStack');
    expect(source).toContain('OATodoStackNavigator');
    expect(source).toContain('SmartBIStackNavigator');
    expect(source).toContain('MobileAccountScreen');
    expect(source).not.toContain('FAManagementStackNavigator');
    expect(source).not.toContain('SalesOrderCreateScreen');
    expect(source).not.toContain('PurchaseOrderCreateScreen');
  });

  it('keeps warehouse workers on task stacks without manager home, AI or profile management', () => {
    const source = read('navigation/WarehouseWorkerNavigator.tsx');
    expect(source).toContain('WHInboundStackNavigator');
    expect(source).toContain('WHOutboundStackNavigator');
    expect(source).toContain('WHInventoryStackNavigator');
    expect(source).toContain('MobileAccountScreen');
    expect(source).not.toContain('WHHomeStackNavigator');
    expect(source).not.toContain('WHProfileStackNavigator');
    expect(source).not.toContain('AIChatScreen');
  });

  it('locks unknown roles instead of granting a generic business workspace', () => {
    const source = read('navigation/RestrictedRoleNavigator.tsx');
    expect(source).toContain('不会自动开放通用业务入口');
    expect(source).toContain('退出登录');
  });
});
