import {
  getFactoryType,
  hasProductionCapability,
  isRestaurant,
} from '../../../utils/factoryType';
import { transformBackendUser } from '../../../utils/roleMapping';

const factoryUser = (factoryType?: string) => transformBackendUser({
  id: 1,
  username: 'factory.user',
  userType: 'factory',
  role: 'operator',
  factoryId: 'F006',
  factoryType,
});

describe('factoryType', () => {
  it('defaults null and platform users to FACTORY', () => {
    const platformUser = transformBackendUser({
      id: 2,
      username: 'platform.user',
      userType: 'platform',
      role: 'platform_admin',
    });

    expect(getFactoryType(null)).toBe('FACTORY');
    expect(getFactoryType(platformUser)).toBe('FACTORY');
  });

  it('normalizes factory type and identifies restaurants', () => {
    const restaurant = factoryUser('restaurant');
    const factory = factoryUser('FACTORY');

    expect(getFactoryType(restaurant)).toBe('RESTAURANT');
    expect(isRestaurant(restaurant)).toBe(true);
    expect(isRestaurant(factory)).toBe(false);
  });

  it('allows production only for factories and central kitchens', () => {
    expect(hasProductionCapability(factoryUser('FACTORY'))).toBe(true);
    expect(hasProductionCapability(factoryUser('CENTRAL_KITCHEN'))).toBe(true);
    expect(hasProductionCapability(factoryUser('RESTAURANT'))).toBe(false);
    expect(hasProductionCapability(factoryUser('HEADQUARTERS'))).toBe(false);
    expect(hasProductionCapability(factoryUser('BRANCH'))).toBe(false);
  });
});
