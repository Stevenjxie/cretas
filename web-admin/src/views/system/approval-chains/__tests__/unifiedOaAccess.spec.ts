import { describe, expect, it } from 'vitest';
import { canConfigureUnifiedOaForRole } from '../unifiedOaAccess';

describe('legacy approval-chain unified OA access', () => {
  it.each(['factory_super_admin', 'platform_admin', 'permission_admin'])(
    'allows the Canvas route role %s',
    (role) => expect(canConfigureUnifiedOaForRole(role)).toBe(true),
  );

  it.each(['dispatcher', 'finance_manager', 'warehouse_manager', '', null])(
    'does not expose a dead-end Canvas link to %s',
    (role) => expect(canConfigureUnifiedOaForRole(role)).toBe(false),
  );
});
