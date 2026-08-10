import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/factory-admin/inventory/PurchaseOrderCreateScreen.tsx'),
  'utf8',
);

describe('purchase order create selector contract', () => {
  it('uses one deterministic modal for material and unit selection', () => {
    expect(source).not.toContain('<Menu');
    expect(source).toContain('testID="purchase-order-picker-modal"');
    expect(source).toContain('testID="purchase-order-picker-search"');
    expect(source).toContain('testID={`purchase-material-option-${material.id}`}');
    expect(source).toContain('testID={`purchase-unit-option-${unit}`}');
  });

  it('keeps contextual option labels and accessible touch targets', () => {
    expect(source).toContain('material.code} · 基本单位 {material.unit');
    expect(source).toContain('getUnitOptionLabel(activePickerItem, unit)');
    expect(source).toContain('minHeight: 56');
    expect(source).toContain('accessibilityState={{ selected }}');
  });

  it('continues sending the required order date', () => {
    expect(source).toContain('orderDate: todayIso()');
  });
});
