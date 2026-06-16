import request from './request';
import type { ApiResponse } from '@/types/api';
import type { ModuleDefinition, PermissionLevel } from '@/config/moduleRegistry';

export interface ModulePermissionDto {
  moduleCode: string;
  permissionLevel: PermissionLevel;
  source?: 'super_admin' | 'user_override' | 'role_template' | 'hidden_default';
}

export interface RoleTemplateDto {
  roleCode: string;
  roleName: string;
  modules: ModulePermissionDto[];
}

export interface EffectiveUserPermissionDto {
  userId: string;
  roleCode: string;
  modules: ModulePermissionDto[];
}

export interface PermissionPreviewDto {
  userId: string;
  visibleModules: ModulePermissionDto[];
  deniedModules: ModulePermissionDto[];
  editableModules: ModulePermissionDto[];
}

export interface PermissionEmployeeDto {
  id: string | number;
  username?: string;
  fullName?: string;
  realName?: string;
  phone?: string;
  roleCode?: string;
  isActive?: boolean;
  [key: string]: unknown;
}

export async function listPermissionModules(factoryId: string): Promise<ModuleDefinition[]> {
  const res = await request.get<ApiResponse<ModuleDefinition[]>>(
    `/${factoryId}/permissions/modules`,
  );
  return ((res as unknown as ApiResponse<ModuleDefinition[]>).data) || [];
}

export async function listRoleTemplates(factoryId: string): Promise<RoleTemplateDto[]> {
  const res = await request.get<ApiResponse<RoleTemplateDto[]>>(
    `/${factoryId}/permissions/role-templates`,
  );
  return ((res as unknown as ApiResponse<RoleTemplateDto[]>).data) || [];
}

export async function updateRoleTemplate(
  factoryId: string,
  roleCode: string,
  modules: ModulePermissionDto[],
): Promise<RoleTemplateDto> {
  const res = await request.put<ApiResponse<RoleTemplateDto>>(
    `/${factoryId}/permissions/role-templates`,
    { roleCode, modules },
  );
  return (res as unknown as ApiResponse<RoleTemplateDto>).data as RoleTemplateDto;
}

export async function getUserEffectivePermissions(
  factoryId: string,
  userId: string,
): Promise<EffectiveUserPermissionDto> {
  const res = await request.get<ApiResponse<EffectiveUserPermissionDto>>(
    `/${factoryId}/permissions/users/${encodeURIComponent(userId)}/effective`,
  );
  return (res as unknown as ApiResponse<EffectiveUserPermissionDto>).data as EffectiveUserPermissionDto;
}

export async function getMyEffectivePermissions(
  factoryId: string,
): Promise<EffectiveUserPermissionDto> {
  const res = await request.get<ApiResponse<EffectiveUserPermissionDto>>(
    `/${factoryId}/permissions/me/effective`,
  );
  return (res as unknown as ApiResponse<EffectiveUserPermissionDto>).data as EffectiveUserPermissionDto;
}

export async function listPermissionEmployees(
  factoryId: string,
  params: { page?: number; size?: number; keyword?: string } = {},
): Promise<unknown> {
  const res = await request.get<ApiResponse<unknown>>(
    `/${factoryId}/permissions/employees`,
    { params },
  );
  return (res as unknown as ApiResponse<unknown>).data;
}

export async function createPermissionEmployee(
  factoryId: string,
  userData: Record<string, unknown>,
): Promise<PermissionEmployeeDto> {
  const res = await request.post<ApiResponse<PermissionEmployeeDto>>(
    `/${factoryId}/permissions/employees`,
    userData,
  );
  return (res as unknown as ApiResponse<PermissionEmployeeDto>).data as PermissionEmployeeDto;
}

export async function updatePermissionEmployee(
  factoryId: string,
  userId: string | number,
  userData: Record<string, unknown>,
): Promise<PermissionEmployeeDto> {
  const res = await request.put<ApiResponse<PermissionEmployeeDto>>(
    `/${factoryId}/permissions/employees/${encodeURIComponent(String(userId))}`,
    userData,
  );
  return (res as unknown as ApiResponse<PermissionEmployeeDto>).data as PermissionEmployeeDto;
}

export async function updateUserOverrides(
  factoryId: string,
  userId: string,
  modules: ModulePermissionDto[],
): Promise<EffectiveUserPermissionDto> {
  const res = await request.put<ApiResponse<EffectiveUserPermissionDto>>(
    `/${factoryId}/permissions/users/${encodeURIComponent(userId)}/overrides`,
    { userId, modules },
  );
  return (res as unknown as ApiResponse<EffectiveUserPermissionDto>).data as EffectiveUserPermissionDto;
}

export async function clearUserOverride(
  factoryId: string,
  userId: string,
  moduleCode: string,
): Promise<EffectiveUserPermissionDto> {
  const res = await request.delete<ApiResponse<EffectiveUserPermissionDto>>(
    `/${factoryId}/permissions/users/${encodeURIComponent(userId)}/overrides/${encodeURIComponent(moduleCode)}`,
  );
  return (res as unknown as ApiResponse<EffectiveUserPermissionDto>).data as EffectiveUserPermissionDto;
}

export async function previewUserPermissions(
  factoryId: string,
  userId: string,
): Promise<PermissionPreviewDto> {
  const res = await request.get<ApiResponse<PermissionPreviewDto>>(
    `/${factoryId}/permissions/users/${encodeURIComponent(userId)}/preview`,
  );
  return (res as unknown as ApiResponse<PermissionPreviewDto>).data as PermissionPreviewDto;
}
