import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/restaurant/procurement/ProcurementDeliveryConfirmScreen.tsx'),
  'utf8',
);
const managementNavigatorSource = fs.readFileSync(
  path.resolve(__dirname, '../../../navigation/factory-admin/FAManagementStackNavigator.tsx'),
  'utf8',
);

describe('ProcurementDeliveryConfirmScreen restaurant UX contract', () => {
  it('does not render internal supplier or department identifiers', () => {
    expect(source).not.toContain('供应商 ID');
    expect(source).not.toContain('requesterDeptId');
  });

  it('does not use a material id as the visible requisition material name', () => {
    expect(source).not.toContain('item.materialName || item.materialTypeId');
    expect(source).toContain('resolveRequisitionMaterialName');
  });

  it('clears the prior material selection whenever the search text changes', () => {
    expect(source).toMatch(/onChangeText=\{\(value\) => updateLine\(line\.key, \{[\s\S]*?rawMaterialTypeId: '',[\s\S]*?\}\)\}/);
    expect(source).toContain('{line.rawMaterialTypeId ? (');
  });

  it('uses pre-submit blocking guidance and accessible option targets', () => {
    expect(source).toContain('submitBlocker ||');
    expect(source).toContain('disabled={submitting || Boolean(submitBlocker)}');
    expect(source).toContain('minHeight: 52');
  });

  it('registers the screen only inside the restaurant-mode navigation block', () => {
    const restaurantBlock = managementNavigatorSource.match(/\{isRestaurantMode && \([\s\S]*?\n\s*\)\}/)?.[0] || '';
    expect(restaurantBlock).toContain('name="ProcurementDeliveryConfirm"');
    expect(managementNavigatorSource.indexOf('name="ProcurementDeliveryConfirm"'))
      .toBeGreaterThan(managementNavigatorSource.indexOf('{isRestaurantMode && ('));
  });
});
