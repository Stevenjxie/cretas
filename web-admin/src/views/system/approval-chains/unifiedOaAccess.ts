const UNIFIED_OA_CONFIG_ROLES = new Set([
  'factory_super_admin',
  'platform_admin',
  'permission_admin',
]);

/** Mirrors the CanvasEditor route role matrix without widening that route. */
export function canConfigureUnifiedOaForRole(role: unknown): boolean {
  return typeof role === 'string' && UNIFIED_OA_CONFIG_ROLES.has(role);
}
