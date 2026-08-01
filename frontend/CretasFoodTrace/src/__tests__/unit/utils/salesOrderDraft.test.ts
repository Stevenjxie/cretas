import type { ProductPackagingSpec } from '../../../services/api/productTypeApiClient';
import { optionalTaxRate, packagingSpecsForUnit, salesUnitOptions } from '../../../utils/salesOrderDraft';

const specs: ProductPackagingSpec[] = [
  { id: 'box-12', name: '12盒/箱', packageUnit: '箱', baseUnit: '盒', conversionFactor: 12, defaultSpec: true, active: true, sortOrder: 1 },
  { id: 'box-24', name: '24盒/箱', packageUnit: '箱', baseUnit: '盒', conversionFactor: 24, defaultSpec: false, active: true, sortOrder: 2 },
  { id: 'old', name: '旧规格', packageUnit: '件', baseUnit: '盒', conversionFactor: 1, defaultSpec: false, active: false, sortOrder: 3 },
];

describe('sales order draft contracts', () => {
  it('offers the SKU base unit and active packaging units only', () => {
    expect(salesUnitOptions('盒', specs)).toEqual(['盒', '箱']);
    expect(packagingSpecsForUnit('箱', specs).map((spec) => spec.id)).toEqual(['box-12', 'box-24']);
  });

  it('keeps a blank tax rate undefined so the backend customer default applies', () => {
    expect(optionalTaxRate('')).toBeUndefined();
    expect(optionalTaxRate('13')).toBe(13);
    expect(optionalTaxRate('101')).toBeUndefined();
  });
});
