/**
 * Permission Matrix API client (Layer 1 + Layer 2)
 *
 * Paths:
 * - GET  /api/admin/role-permissions             L1 read (any auth user)
 * - PUT  /api/platform/role-permissions/{r}/{m}  L1 write (platform_admin only)
 * - GET  /api/mobile/{fId}/canvas/role-module-override   L2 read
 * - PUT  /api/mobile/{fId}/canvas/role-module-override/{r}/{m}?level=  L2 write
 *
 * See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §5.1.4
 */
import request from './request';
import type { ApiResponse } from '@/types/api';

export type PermissionLevel = 'rw' | 'r' | 'w' | '-';
export type UserModuleAccessType = 'GRANT' | 'DENY';

export interface PlatformPermission {
  id: number;
  roleCode: string;
  moduleCode: string;
  permissionLevel: PermissionLevel;
  updatedBy?: number | null;
  updatedAt?: string;
  createdAt?: string;
}

/** Layer 2 factory override map shape: { role: { module: level } } */
export type RoleModuleOverride = Record<string, Record<string, PermissionLevel>>;

export interface UserModuleAccessView {
  moduleCode: string;
  displayName: string;
  category: string;
  permissionModule: string;
  roleDefaultAllowed: boolean;
  override: UserModuleAccessType | null;
  effectiveAllowed: boolean;
}

/** L1: Read full platform-level matrix (374 rows = 22 roles × 17 modules). */
export async function getPlatformPermissions(): Promise<PlatformPermission[]> {
  // Uses absolute path (bypasses baseURL /api/mobile) per request.ts adminGet pattern.
  const res = await request.get<ApiResponse<PlatformPermission[]>>(
    '/api/admin/role-permissions',
    { baseURL: '' },
  );
  // Interceptor unwraps { success, data, message }
  return ((res as unknown as ApiResponse<PlatformPermission[]>).data) || [];
}

/** L1: Update single cell. Circular lockout guards on backend (400 on violation). */
export async function updatePlatformPermission(
  role: string,
  module: string,
  level: PermissionLevel,
): Promise<PlatformPermission> {
  const res = await request.put<ApiResponse<PlatformPermission>>(
    `/api/platform/role-permissions/${encodeURIComponent(role)}/${encodeURIComponent(module)}?level=${encodeURIComponent(level)}`,
    {},
    { baseURL: '' },
  );
  return (res as unknown as ApiResponse<PlatformPermission>).data as PlatformPermission;
}

/** L2: Read factory-level override. Returns {} if none set. */
export async function getFactoryOverride(factoryId: string): Promise<RoleModuleOverride> {
  const res = await request.get<ApiResponse<RoleModuleOverride>>(
    `/${factoryId}/canvas/role-module-override`,
  );
  return ((res as unknown as ApiResponse<RoleModuleOverride>).data) || {};
}

/** L2: Update factory override. Pass null as level to clear (fall back to L1). */
export async function updateFactoryOverride(
  factoryId: string,
  role: string,
  module: string,
  level: PermissionLevel | null,
): Promise<void> {
  const qs = level === null ? '' : `?level=${encodeURIComponent(level)}`;
  await request.put<ApiResponse<void>>(
    `/${factoryId}/canvas/role-module-override/${encodeURIComponent(role)}/${encodeURIComponent(module)}${qs}`,
    {},
  );
}

export async function getUserModuleAccess(
  factoryId: string,
  userId: string | number,
  options: { silent?: boolean } = {},
): Promise<UserModuleAccessView[]> {
  const res = await request.get<ApiResponse<UserModuleAccessView[]>>(
    `/${factoryId}/canvas/user-module-access/${encodeURIComponent(String(userId))}`,
    options.silent ? ({ _silent: true } as never) : undefined,
  );
  return ((res as unknown as ApiResponse<UserModuleAccessView[]>).data) || [];
}

export async function updateUserModuleAccess(
  factoryId: string,
  userId: string | number,
  module: string,
  action: UserModuleAccessType,
): Promise<void> {
  await request.put<ApiResponse<void>>(
    `/${factoryId}/canvas/user-module-access/${encodeURIComponent(String(userId))}/${encodeURIComponent(module)}?action=${encodeURIComponent(action)}`,
    {},
  );
}

export async function clearUserModuleAccess(
  factoryId: string,
  userId: string | number,
  module: string,
): Promise<void> {
  await request.delete<ApiResponse<void>>(
    `/${factoryId}/canvas/user-module-access/${encodeURIComponent(String(userId))}/${encodeURIComponent(module)}`,
  );
}
