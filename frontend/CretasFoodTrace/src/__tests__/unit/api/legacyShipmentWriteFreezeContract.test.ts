import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const readSource = (relativeFromSrc: string): string =>
  fs.readFileSync(path.join(srcRoot, relativeFromSrc), 'utf8');

describe('legacy shipment write freeze contract', () => {
  it('keeps the shipment client read-only', () => {
    const source = readSource('services/api/shipmentApiClient.ts');

    expect(source).toContain('getShipments');
    expect(source).not.toMatch(/\b(createShipment|updateShipment|updateStatus|deleteShipment|uploadSignature)\b/);
  });

  it('removes screens and routes that wrote the legacy shipment table', () => {
    const navigator = readSource('navigation/warehouse/WHOutboundStackNavigator.tsx');
    const navigationTypes = readSource('types/navigation.ts');
    const removedScreens = ['WHPackingScreen.tsx', 'WHLoadingScreen.tsx', 'WHShippingConfirmScreen.tsx'];

    for (const route of ['WHPacking', 'WHLoading', 'WHShippingConfirm']) {
      expect(navigator).not.toContain(route);
      expect(navigationTypes).not.toContain(route);
    }
    for (const file of removedScreens) {
      expect(fs.existsSync(path.join(srcRoot, 'screens/warehouse/outbound', file))).toBe(false);
    }
  });

  it('keeps the inventory-aware warehouse delivery confirmation path', () => {
    const source = readSource('services/api/warehouseDeliveryApiClient.ts');

    expect(source).toContain('this.warehousePath(factoryId) + `/${deliveryId}/confirm`');
  });
});
