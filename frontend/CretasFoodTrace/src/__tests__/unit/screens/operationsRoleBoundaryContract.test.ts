import fs from 'fs';
import path from 'path';

const screenSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/operations/OperationsHomeScreen.tsx'),
  'utf8',
);
const navigatorSource = fs.readFileSync(
  path.resolve(__dirname, '../../../navigation/OperationsNavigator.tsx'),
  'utf8',
);
const permissionSource = fs.readFileSync(
  path.resolve(__dirname, '../../../utils/permissionHelper.ts'),
  'utf8',
);
const reusedOrderSources = [
  'SalesOrderListScreen.tsx',
  'SalesOrderDetailScreen.tsx',
  'PurchaseOrderListScreen.tsx',
  'PurchaseOrderDetailScreen.tsx',
  'FinishedGoodsListScreen.tsx',
].map((file) => fs.readFileSync(
  path.resolve(__dirname, `../../../screens/factory-admin/inventory/${file}`),
  'utf8',
));

describe('operations coordinator mobile boundary', () => {
  it('supports application create, view and pre-approval withdrawal', () => {
    expect(screenSource).toContain('发起申请');
    expect(screenSource).toContain('提交审批');
    expect(screenSource).toContain("item.status === 'PENDING_APPROVAL'");
  });

  it('matches the backend role matrix: operations write, adjacent modules read', () => {
    expect(permissionSource).toContain('operations_coordinator: {');
    expect(permissionSource).toContain("operations: 'read_write'");
    expect(permissionSource).toContain("warehouse: 'read'");
    expect(permissionSource).toContain("procurement: 'read'");
    expect(permissionSource).toContain("sales: 'read'");
  });

  it('states that warehouse owns material and quantity receipt facts', () => {
    expect(screenSource).toContain('这里只申请，不处理入库');
    expect(screenSource).toContain('审批通过后再由仓管核对实物');
    expect(screenSource).not.toContain('label="物料');
    expect(screenSource).not.toContain('label="数量');
  });

  it('lists pending applications without reusing the warehouse receivable filter', () => {
    expect(screenSource).toContain('listCustomerMaterialArrivals(false)');
    expect(screenSource).toContain("notice.status === 'PENDING_APPROVAL'");
    expect(screenSource).toContain("{ value: 'pending', label: '待审批' }");
  });

  it('keeps cross-business access read-oriented without registering create screens', () => {
    expect(navigatorSource).toContain('ProductionPlanManagementScreen');
    expect(navigatorSource).toContain('SalesOrderListScreen');
    expect(navigatorSource).toContain('PurchaseOrderListScreen');
    expect(navigatorSource).toContain('FinishedGoodsListScreen');
    expect(navigatorSource).not.toContain('SalesOrderCreateScreen');
    expect(navigatorSource).not.toContain('PurchaseOrderCreateScreen');
    reusedOrderSources.forEach((source) => {
      expect(source).toContain('isMobileBusinessObserver(user)');
      expect(source).toContain('!isOperationsReadOnly');
    });
  });
});
