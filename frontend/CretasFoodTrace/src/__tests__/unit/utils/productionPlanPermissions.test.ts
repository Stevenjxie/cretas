import { canCreateProductionPlan } from '../../../utils/productionPlanPermissions';

describe('production plan creator roles', () => {
  it.each(['factory_super_admin', 'department_admin', 'production_manager'])(
    'allows %s to create a plan',
    (role) => expect(canCreateProductionPlan(role, false)).toBe(true),
  );

  it('keeps platform read-only and unrelated roles blocked', () => {
    expect(canCreateProductionPlan('production_manager', true)).toBe(false);
    expect(canCreateProductionPlan('viewer', false)).toBe(false);
  });
});
