# Permission Settings Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone permission settings module where factory super admins can manage employees, create employees, assign role templates, override employee permissions, and control all left-sidebar second-level modules with three levels: hidden, read-only, editable.

**Architecture:** Keep the current role-based permission layer as the default template source, add normalized per-employee module override levels, expose a dedicated backend permission-settings API, and make the web-admin sidebar, router guard, and write actions consume one effective second-level module permission tree.

**Tech Stack:** Java Spring Boot, JPA, Flyway, Vue 3, Pinia, TypeScript, Element Plus, Vitest.

---

## File Structure Map

Backend files to modify:

- `backend/java/cretas-api/src/main/resources/db/flyway/V20261024_12__permission_settings_module.sql`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/permission/PermissionLevel.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/permission/ProductionModuleRegistry.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/UserModuleAccess.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/UserModuleAccessRepository.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/config/FactoryModuleConfigRepository.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/UserModuleAccessService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/UserModuleAccessServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/config/ModuleEnabledInterceptor.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PermissionSettingsController.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/PermissionSettingsService.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionSettingsServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/permission/PermissionSettingsDtos.java`

Frontend files to modify or add:

- `web-admin/src/api/permissionSettings.ts`
- `web-admin/src/config/moduleRegistry.ts`
- `web-admin/src/store/modules/permission.ts`
- `web-admin/src/store/modules/permission.settings.spec.ts`
- `web-admin/src/router/index.ts`
- `web-admin/src/router/guards.ts`
- `web-admin/src/components/layout/menuConfig.ts`
- `web-admin/src/components/layout/AppSidebar.vue`
- `web-admin/src/components/permissions/PermissionLevelSegment.vue`
- `web-admin/src/components/permissions/ModulePermissionTree.vue`
- `web-admin/src/components/permissions/EmployeeSelector.vue`
- `web-admin/src/components/permissions/RoleTemplateSelector.vue`
- `web-admin/src/components/permissions/EffectivePermissionBadge.vue`
- `web-admin/src/components/permissions/PermissionPreviewSidebar.vue`
- `web-admin/src/views/permissions/index.vue`
- `web-admin/src/views/permissions/employees/index.vue`
- `web-admin/src/views/permissions/role-templates/index.vue`
- `web-admin/src/views/permissions/employee-permissions/index.vue`
- `web-admin/src/views/permissions/preview/index.vue`

Verification commands:

- Backend compile: `cd backend/java/cretas-api; .\mvnw.cmd -q -DskipTests compile`
- Frontend unit tests: `cd web-admin; npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts`
- Frontend type/build verification: `cd web-admin; npm run build:check`

Known test-suite constraint:

- Full backend test execution is currently blocked by unrelated pre-existing test compilation errors in `ProductionPlanSettlementTest`, `PrintControllerSp12T8Test`, `AnalysisFlowIntegrationTest`, and `TestDataSetup`. Do not claim full backend tests pass until those files are fixed.

---

## Task 1: Add Normalized Permission Levels And Migration

- [ ] Create `PermissionLevel.java`.

Use one enum for the three UI levels and the current database symbols:

```java
package com.cretas.aims.permission;

import java.util.Locale;

public enum PermissionLevel {
    HIDDEN("-", "hidden"),
    READ("r", "read"),
    WRITE("rw", "write");

    private final String legacyCode;
    private final String apiCode;

    PermissionLevel(String legacyCode, String apiCode) {
        this.legacyCode = legacyCode;
        this.apiCode = apiCode;
    }

    public String legacyCode() {
        return legacyCode;
    }

    public String apiCode() {
        return apiCode;
    }

    public boolean canRead() {
        return this == READ || this == WRITE;
    }

    public boolean canWrite() {
        return this == WRITE;
    }

    public static PermissionLevel fromAny(String value) {
        if (value == null || value.isBlank()) {
            return HIDDEN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "rw", "w", "write", "editable", "grant" -> WRITE;
            case "r", "read", "readonly", "read_only" -> READ;
            case "-", "hidden", "deny", "none" -> HIDDEN;
            default -> HIDDEN;
        };
    }
}
```

- [ ] Add Flyway migration `V20261024_12__permission_settings_module.sql`.

The existing `user_module_access.access_type` has a `GRANT`/`DENY` check, so keep it for compatibility and add `permission_level` as the normalized source:

```sql
ALTER TABLE user_module_access
    ADD COLUMN IF NOT EXISTS permission_level VARCHAR(16);

UPDATE user_module_access
SET permission_level = CASE
    WHEN access_type = 'GRANT' THEN 'write'
    WHEN access_type = 'DENY' THEN 'hidden'
    ELSE 'hidden'
END
WHERE permission_level IS NULL;

ALTER TABLE user_module_access
    ALTER COLUMN permission_level SET DEFAULT 'hidden';

ALTER TABLE user_module_access
    ADD CONSTRAINT chk_user_module_access_permission_level
    CHECK (permission_level IN ('hidden', 'read', 'write'));

CREATE INDEX IF NOT EXISTS idx_user_module_access_factory_user_module
    ON user_module_access(factory_id, user_id, module_code);

INSERT INTO platform_role_permissions(role_code, module_code, permission_level)
VALUES
    ('factory_super_admin', 'permission_settings', 'rw'),
    ('factory_super_admin', 'permission_employee_management', 'rw'),
    ('factory_super_admin', 'permission_role_templates', 'rw'),
    ('factory_super_admin', 'permission_employee_overrides', 'rw'),
    ('factory_super_admin', 'permission_preview', 'rw'),
    ('platform_admin', 'permission_settings', 'rw'),
    ('platform_admin', 'permission_employee_management', 'rw'),
    ('platform_admin', 'permission_role_templates', 'rw'),
    ('platform_admin', 'permission_employee_overrides', 'rw'),
    ('platform_admin', 'permission_preview', 'rw'),
    ('permission_admin', 'permission_settings', 'rw'),
    ('permission_admin', 'permission_employee_management', 'rw'),
    ('permission_admin', 'permission_role_templates', 'rw'),
    ('permission_admin', 'permission_employee_overrides', 'rw'),
    ('permission_admin', 'permission_preview', 'rw')
ON CONFLICT (role_code, module_code) DO NOTHING;
```

- [ ] Update `UserModuleAccess.java`.

Add a `permissionLevel` field without removing `accessType`:

```java
@Column(name = "permission_level", length = 16)
private String permissionLevel;

public PermissionLevel getEffectivePermissionLevel() {
    if (permissionLevel != null && !permissionLevel.isBlank()) {
        return PermissionLevel.fromAny(permissionLevel);
    }
    return PermissionLevel.fromAny(accessType == null ? null : accessType.name());
}

public void setEffectivePermissionLevel(PermissionLevel level) {
    PermissionLevel safeLevel = level == null ? PermissionLevel.HIDDEN : level;
    this.permissionLevel = safeLevel.apiCode();
    this.accessType = safeLevel == PermissionLevel.HIDDEN ? AccessType.DENY : AccessType.GRANT;
}
```

- [ ] Compile backend.

Command:

```powershell
cd backend/java/cretas-api
.\mvnw.cmd -q -DskipTests compile
```

Expected result: command exits `0`.

- [ ] Commit.

```powershell
git add backend/java/cretas-api/src/main/resources/db/flyway/V20261024_12__permission_settings_module.sql backend/java/cretas-api/src/main/java/com/cretas/aims/permission/PermissionLevel.java backend/java/cretas-api/src/main/java/com/cretas/aims/entity/UserModuleAccess.java
git commit -m "feat: add normalized permission levels"
```

---

## Task 2: Expand The Second-Level Module Registry

- [ ] Extend `ProductionModuleRegistry.java` so backend owns the canonical module tree.

Use a record that includes first-level parent, second-level code, display name, route, sort order, and whether writes are supported:

```java
public record ModuleDefinition(
    String moduleCode,
    String displayName,
    String parentCode,
    String parentName,
    String routePath,
    int sortOrder,
    boolean writeSupported
) {}
```

Keep existing methods callable by adding adapter methods:

```java
public static List<ModuleDefinition> listAll() {
    return MODULES;
}

public static boolean isKnownModule(String moduleCode) {
    return MODULES.stream().anyMatch(module -> module.moduleCode().equals(moduleCode));
}

public static Optional<ModuleDefinition> findByCode(String moduleCode) {
    return MODULES.stream().filter(module -> module.moduleCode().equals(moduleCode)).findFirst();
}
```

Seed at least these second-level modules in `MODULES`, matching current sidebar route names and modules:

```java
new ModuleDefinition("permission_employee_management", "员工管理", "permission_settings", "权限设置", "/permissions/employees", 10, true),
new ModuleDefinition("permission_role_templates", "角色权限模板", "permission_settings", "权限设置", "/permissions/role-templates", 20, true),
new ModuleDefinition("permission_employee_overrides", "员工权限", "permission_settings", "权限设置", "/permissions/employee-permissions", 30, true),
new ModuleDefinition("permission_preview", "权限预览", "permission_settings", "权限设置", "/permissions/preview", 40, false)
```

Build the rest of `MODULES` by reading `web-admin/src/components/layout/menuConfig.ts` and adding one `ModuleDefinition` for each routed second-level child. Copy the child route path into `routePath`, the child title into `displayName`, the parent title into `parentName`, and the existing permission module into `parentCode`. Use `sortOrder` in increments of `10` within each parent group.

- [ ] Add a frontend mirror in `web-admin/src/config/moduleRegistry.ts`.

Define these types:

```ts
export type PermissionLevel = 'hidden' | 'read' | 'write'

export interface ModuleDefinition {
  moduleCode: string
  displayName: string
  parentCode: string
  parentName: string
  routePath: string
  sortOrder: number
  writeSupported: boolean
}
```

Export `PERMISSION_MODULE_REGISTRY` with the same module codes as backend. Keep `PRODUCTION_MODULE_REGISTRY` exports that existing imports use, by deriving them from the new registry where possible.

- [ ] Compile and run frontend registry tests once added in Task 5.

- [ ] Commit.

```powershell
git add backend/java/cretas-api/src/main/java/com/cretas/aims/permission/ProductionModuleRegistry.java web-admin/src/config/moduleRegistry.ts
git commit -m "feat: define second-level permission module registry"
```

---

## Task 3: Add Backend Effective Permission Services

- [ ] Update `UserModuleAccessRepository.java`.

Add these methods:

```java
Optional<UserModuleAccess> findByFactoryIdAndUserIdAndModuleCode(String factoryId, String userId, String moduleCode);

List<UserModuleAccess> findByFactoryIdAndUserId(String factoryId, String userId);
```

- [ ] Update `UserModuleAccessService.java`.

Add:

```java
PermissionLevel getEffectiveLevel(String factoryId, String userId, String roleCode, String moduleCode);

boolean canWriteModule(String factoryId, String userId, String roleCode, String moduleCode);
```

- [ ] Update `UserModuleAccessServiceImpl.java`.

Effective level rules:

1. `factory_super_admin` and `platform_admin` always get `WRITE`.
2. A per-user override wins when `permission_level` exists.
3. Factory role template from `factory_module_configs.role_module_override` is the next layer.
4. Global role default from `platform_role_permissions.permission_level` is the fallback.
5. Missing rows mean `HIDDEN`.

Core implementation:

```java
@Override
public PermissionLevel getEffectiveLevel(String factoryId, String userId, String roleCode, String moduleCode) {
    if ("factory_super_admin".equals(roleCode) || "platform_admin".equals(roleCode)) {
        return PermissionLevel.WRITE;
    }

    Optional<UserModuleAccess> override = userModuleAccessRepository
        .findByFactoryIdAndUserIdAndModuleCode(factoryId, userId, moduleCode);
    if (override.isPresent()) {
        return override.get().getEffectivePermissionLevel();
    }

    PermissionLevel factoryRoleLevel = factoryModuleConfigRepository
        .findByFactoryIdAndConfigVersion(factoryId, 1)
        .stream()
        .map(config -> config.getRoleModuleOverride().get(roleCode))
        .filter(Objects::nonNull)
        .map(roleModules -> roleModules.get(moduleCode))
        .filter(Objects::nonNull)
        .map(PermissionLevel::fromAny)
        .findFirst()
        .orElse(null);
    if (factoryRoleLevel != null) {
        return factoryRoleLevel;
    }

    return platformRolePermissionRepository
        .findByRoleCodeAndModuleCodeAndDeletedAtIsNull(roleCode, moduleCode)
        .map(permission -> PermissionLevel.fromAny(permission.getPermissionLevel()))
        .orElse(PermissionLevel.HIDDEN);
}

@Override
public boolean canAccessModule(String factoryId, String userId, String roleCode, String moduleCode) {
    return getEffectiveLevel(factoryId, userId, roleCode, moduleCode).canRead();
}

@Override
public boolean canWriteModule(String factoryId, String userId, String roleCode, String moduleCode) {
    return getEffectiveLevel(factoryId, userId, roleCode, moduleCode).canWrite();
}
```

- [ ] Update `ModuleEnabledInterceptor.java`.

For handlers protected by `@RequireModule`, use HTTP method to enforce read/write:

```java
private boolean isWriteMethod(HttpServletRequest request) {
    String method = request.getMethod();
    return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
}
```

Then:

```java
boolean allowed = isWriteMethod(request)
    ? userModuleAccessService.canWriteModule(factoryId, userId, roleCode, moduleCode)
    : userModuleAccessService.canAccessModule(factoryId, userId, roleCode, moduleCode);
```

Return HTTP `403` with a JSON body that includes `moduleCode` and the required level:

```json
{"code":403,"message":"No permission for module","moduleCode":"permission_employee_management","requiredLevel":"write"}
```

- [ ] Compile backend.

```powershell
cd backend/java/cretas-api
.\mvnw.cmd -q -DskipTests compile
```

Expected result: command exits `0`.

- [ ] Commit.

```powershell
git add backend/java/cretas-api/src/main/java/com/cretas/aims/repository/UserModuleAccessRepository.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/config/FactoryModuleConfigRepository.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/UserModuleAccessService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/UserModuleAccessServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/config/ModuleEnabledInterceptor.java
git commit -m "feat: enforce effective module permission levels"
```

---

## Task 4: Add Permission Settings Backend API

- [ ] Create `PermissionSettingsDtos.java`.

Use nested static DTOs to keep the endpoint contract in one file:

```java
package com.cretas.aims.dto.permission;

import java.util.List;

public final class PermissionSettingsDtos {
    private PermissionSettingsDtos() {}

    public record ModuleDto(String moduleCode, String displayName, String parentCode, String parentName, String routePath, int sortOrder, boolean writeSupported) {}
    public record RoleTemplateDto(String roleCode, String roleName, List<ModulePermissionDto> modules) {}
    public record ModulePermissionDto(String moduleCode, String permissionLevel, String source) {}
    public record UpdateRoleTemplateRequest(String roleCode, List<ModulePermissionDto> modules) {}
    public record UpdateUserOverridesRequest(String userId, List<ModulePermissionDto> modules) {}
    public record EffectiveUserPermissionDto(String userId, String roleCode, List<ModulePermissionDto> modules) {}
    public record PermissionPreviewDto(String userId, List<ModulePermissionDto> visibleModules, List<ModulePermissionDto> deniedModules, List<ModulePermissionDto> editableModules) {}
}
```

- [ ] Create `PermissionSettingsService.java`.

```java
List<ModuleDto> listModules(String factoryId);
List<RoleTemplateDto> listRoleTemplates(String factoryId);
RoleTemplateDto updateRoleTemplate(String factoryId, UpdateRoleTemplateRequest request);
EffectiveUserPermissionDto getUserEffectivePermissions(String factoryId, String userId);
EffectiveUserPermissionDto updateUserOverrides(String factoryId, UpdateUserOverridesRequest request);
EffectiveUserPermissionDto clearUserOverride(String factoryId, String userId, String moduleCode);
PermissionPreviewDto previewUserPermissions(String factoryId, String userId);
```

- [ ] Create `PermissionSettingsServiceImpl.java`.

Implementation details:

- `listModules` returns `ProductionModuleRegistry.listAll()` sorted by `parentCode`, then `sortOrder`.
- `listRoleTemplates` reads distinct role codes from existing factory users and `platform_role_permissions`.
- `updateRoleTemplate` updates `factory_module_configs.role_module_override` for the current factory, creating the version-1 `permission_settings` config row when it does not exist, and stores values as `PermissionLevel.fromAny(requestLevel).legacyCode()`.
- `getUserEffectivePermissions` returns every registry module with `permissionLevel` and `source` equal to `super_admin`, `user_override`, `role_template`, or `hidden_default`.
- `updateUserOverrides` upserts `UserModuleAccess` rows using `setEffectivePermissionLevel`.
- `clearUserOverride` soft-deletes or physically deletes the `(factoryId, userId, moduleCode)` `UserModuleAccess` row and then returns recalculated effective permissions.
- `previewUserPermissions` partitions effective modules into visible, denied, and editable lists.

- [ ] Create `PermissionSettingsController.java`.

Expose the module under a dedicated route:

```java
@RestController
@RequestMapping("/api/mobile/{factoryId}/permissions")
@RequireModule("permission_settings")
@RequireRole({"factory_super_admin", "platform_admin", "permission_admin"})
@RequiredArgsConstructor
public class PermissionSettingsController {
    private final PermissionSettingsService permissionSettingsService;

    @GetMapping("/modules")
    public ResponseEntity<?> listModules(@PathVariable String factoryId) {
        return ResponseEntity.ok(permissionSettingsService.listModules(factoryId));
    }

    @GetMapping("/role-templates")
    public ResponseEntity<?> listRoleTemplates(@PathVariable String factoryId) {
        return ResponseEntity.ok(permissionSettingsService.listRoleTemplates(factoryId));
    }

    @PutMapping("/role-templates")
    public ResponseEntity<?> updateRoleTemplate(@PathVariable String factoryId, @RequestBody UpdateRoleTemplateRequest request) {
        return ResponseEntity.ok(permissionSettingsService.updateRoleTemplate(factoryId, request));
    }

    @GetMapping("/users/{userId}/effective")
    public ResponseEntity<?> getUserEffectivePermissions(@PathVariable String factoryId, @PathVariable String userId) {
        return ResponseEntity.ok(permissionSettingsService.getUserEffectivePermissions(factoryId, userId));
    }

    @PutMapping("/users/{userId}/overrides")
    public ResponseEntity<?> updateUserOverrides(@PathVariable String factoryId, @PathVariable String userId, @RequestBody UpdateUserOverridesRequest request) {
        UpdateUserOverridesRequest normalized = new UpdateUserOverridesRequest(userId, request.modules());
        return ResponseEntity.ok(permissionSettingsService.updateUserOverrides(factoryId, normalized));
    }

    @DeleteMapping("/users/{userId}/overrides/{moduleCode}")
    public ResponseEntity<?> clearUserOverride(@PathVariable String factoryId, @PathVariable String userId, @PathVariable String moduleCode) {
        return ResponseEntity.ok(permissionSettingsService.clearUserOverride(factoryId, userId, moduleCode));
    }

    @GetMapping("/users/{userId}/preview")
    public ResponseEntity<?> previewUserPermissions(@PathVariable String factoryId, @PathVariable String userId) {
        return ResponseEntity.ok(permissionSettingsService.previewUserPermissions(factoryId, userId));
    }
}
```

- [ ] Compile backend.

```powershell
cd backend/java/cretas-api
.\mvnw.cmd -q -DskipTests compile
```

Expected result: command exits `0`.

- [ ] Commit.

```powershell
git add backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PermissionSettingsController.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/PermissionSettingsService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/PermissionSettingsServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/dto/permission/PermissionSettingsDtos.java
git commit -m "feat: add permission settings api"
```

---

## Task 5: Add Frontend API And Permission Store Logic

- [ ] Create `web-admin/src/api/permissionSettings.ts`.

```ts
import request from '@/utils/request'
import type { ModuleDefinition, PermissionLevel } from '@/config/moduleRegistry'

export interface ModulePermissionDto {
  moduleCode: string
  permissionLevel: PermissionLevel
  source?: 'super_admin' | 'user_override' | 'role_template' | 'hidden_default'
}

export interface RoleTemplateDto {
  roleCode: string
  roleName: string
  modules: ModulePermissionDto[]
}

export interface EffectiveUserPermissionDto {
  userId: string
  roleCode: string
  modules: ModulePermissionDto[]
}

export interface PermissionPreviewDto {
  userId: string
  visibleModules: ModulePermissionDto[]
  deniedModules: ModulePermissionDto[]
  editableModules: ModulePermissionDto[]
}

export function listPermissionModules(factoryId: string) {
  return request.get<ModuleDefinition[]>(`/mobile/${factoryId}/permissions/modules`)
}

export function listRoleTemplates(factoryId: string) {
  return request.get<RoleTemplateDto[]>(`/mobile/${factoryId}/permissions/role-templates`)
}

export function updateRoleTemplate(factoryId: string, roleCode: string, modules: ModulePermissionDto[]) {
  return request.put<RoleTemplateDto>(`/mobile/${factoryId}/permissions/role-templates`, { roleCode, modules })
}

export function getUserEffectivePermissions(factoryId: string, userId: string) {
  return request.get<EffectiveUserPermissionDto>(`/mobile/${factoryId}/permissions/users/${userId}/effective`)
}

export function updateUserOverrides(factoryId: string, userId: string, modules: ModulePermissionDto[]) {
  return request.put<EffectiveUserPermissionDto>(`/mobile/${factoryId}/permissions/users/${userId}/overrides`, { userId, modules })
}

export function clearUserOverride(factoryId: string, userId: string, moduleCode: string) {
  return request.delete<EffectiveUserPermissionDto>(`/mobile/${factoryId}/permissions/users/${userId}/overrides/${moduleCode}`)
}

export function previewUserPermissions(factoryId: string, userId: string) {
  return request.get<PermissionPreviewDto>(`/mobile/${factoryId}/permissions/users/${userId}/preview`)
}
```

- [ ] Update `web-admin/src/store/modules/permission.ts`.

Add second-level level helpers while keeping existing `canAccess(module)` and `canWrite(module)` working:

```ts
const moduleLevels = ref<Record<string, PermissionLevel>>({})

function normalizePermissionLevel(level: unknown): PermissionLevel {
  if (level === 'rw' || level === 'w' || level === 'write' || level === 'GRANT') return 'write'
  if (level === 'r' || level === 'read') return 'read'
  return 'hidden'
}

function applyEffectiveModules(modules: Array<{ moduleCode: string; permissionLevel: unknown }>) {
  moduleLevels.value = modules.reduce<Record<string, PermissionLevel>>((acc, item) => {
    acc[item.moduleCode] = normalizePermissionLevel(item.permissionLevel)
    return acc
  }, {})
}

function effectiveLevelFor(moduleCode?: string): PermissionLevel {
  if (!moduleCode) return 'write'
  if (isSuperAdmin.value) return 'write'
  return moduleLevels.value[moduleCode] ?? 'hidden'
}

function canAccessModuleCode(moduleCode?: string): boolean {
  return effectiveLevelFor(moduleCode) !== 'hidden'
}

function canWriteModuleCode(moduleCode?: string): boolean {
  return effectiveLevelFor(moduleCode) === 'write'
}
```

When loading permissions from the database, call the new effective user endpoint if the current user id and factory id exist. Keep fallback behavior for demo/offline use.

- [ ] Add `permission.settings.spec.ts`.

Cover these cases:

```ts
it('normalizes rw/r/- and hidden/read/write into three levels', () => {})
it('lets factory super admin access and write every second-level module', () => {})
it('hides a menu route when effective level is hidden', () => {})
it('allows read route access but returns false for write when level is read', () => {})
it('employee override wins over role template level', () => {})
```

- [ ] Run frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Commit.

```powershell
git add web-admin/src/api/permissionSettings.ts web-admin/src/store/modules/permission.ts web-admin/src/store/modules/permission.settings.spec.ts
git commit -m "feat: add frontend effective permission store"
```

---

## Task 6: Wire Sidebar And Router To Second-Level Module Codes

- [ ] Update `web-admin/src/components/layout/menuConfig.ts`.

Add `moduleCode?: string` to the menu item type. Add a top-level `权限设置` group with four children:

```ts
{
  title: '权限设置',
  icon: 'Lock',
  module: 'system',
  moduleCode: 'permission_settings',
  children: [
    { title: '员工管理', path: '/permissions/employees', module: 'system', moduleCode: 'permission_employee_management' },
    { title: '角色权限模板', path: '/permissions/role-templates', module: 'system', moduleCode: 'permission_role_templates' },
    { title: '员工权限', path: '/permissions/employee-permissions', module: 'system', moduleCode: 'permission_employee_overrides' },
    { title: '权限预览', path: '/permissions/preview', module: 'system', moduleCode: 'permission_preview' }
  ]
}
```

For existing children, fill `moduleCode` from `PERMISSION_MODULE_REGISTRY` by route path. Leave a child without `moduleCode` only when it is a visual separator or non-route parent.

- [ ] Update `AppSidebar.vue`.

Change the filtering function so second-level module code wins:

```ts
function canShowMenuItem(item: MenuItem): boolean {
  if (item.moduleCode) {
    return permissionStore.canAccessModuleCode(item.moduleCode)
  }
  if (item.module) {
    return permissionStore.canAccess(item.module)
  }
  return true
}
```

If a parent has children, show the parent only when at least one child is visible.

- [ ] Update `router/index.ts`.

Add permission routes:

```ts
{
  path: '/permissions',
  name: 'Permissions',
  component: () => import('@/views/permissions/index.vue'),
  redirect: '/permissions/employees',
  meta: { requiresAuth: true, module: 'system', moduleCode: 'permission_settings' },
  children: [
    { path: 'employees', name: 'PermissionEmployees', component: () => import('@/views/permissions/employees/index.vue'), meta: { requiresAuth: true, module: 'system', moduleCode: 'permission_employee_management' } },
    { path: 'role-templates', name: 'PermissionRoleTemplates', component: () => import('@/views/permissions/role-templates/index.vue'), meta: { requiresAuth: true, module: 'system', moduleCode: 'permission_role_templates' } },
    { path: 'employee-permissions', name: 'PermissionEmployeePermissions', component: () => import('@/views/permissions/employee-permissions/index.vue'), meta: { requiresAuth: true, module: 'system', moduleCode: 'permission_employee_overrides' } },
    { path: 'preview', name: 'PermissionPreview', component: () => import('@/views/permissions/preview/index.vue'), meta: { requiresAuth: true, module: 'system', moduleCode: 'permission_preview' } }
  ]
}
```

- [ ] Update `router/guards.ts`.

Before legacy first-level checks, enforce `moduleCode`:

```ts
const moduleCode = to.meta.moduleCode as string | undefined
if (moduleCode && !permissionStore.canAccessModuleCode(moduleCode)) {
  next('/403')
  return
}
```

- [ ] Run frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Commit.

```powershell
git add web-admin/src/components/layout/menuConfig.ts web-admin/src/components/layout/AppSidebar.vue web-admin/src/router/index.ts web-admin/src/router/guards.ts
git commit -m "feat: gate sidebar and routes by second-level permissions"
```

---

## Task 7: Build Shared Permission UI Components

- [ ] Create `PermissionLevelSegment.vue`.

Requirements:

- Three options only: `隐藏`, `只读`, `可编辑`.
- Disable `可编辑` when the module has `writeSupported === false`.
- Emit `update:modelValue`.

Core template:

```vue
<template>
  <el-segmented
    :model-value="modelValue"
    :options="options"
    size="small"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
```

- [ ] Create `ModulePermissionTree.vue`.

Requirements:

- Group modules by `parentName`.
- Show second-level rows with module name, route path, effective source badge, and `PermissionLevelSegment`.
- Include quick actions per parent: all hidden, all read, all editable.
- Emit `change` with the full module permission list after every row edit.

- [ ] Create `EmployeeSelector.vue`.

Requirements:

- Fetch employees through the existing employee API already used by `web-admin/src/views/system/users/list.vue`.
- Search by name, phone, username.
- Emit selected user id.

- [ ] Create `RoleTemplateSelector.vue`.

Requirements:

- Fetch role templates through `listRoleTemplates(factoryId)`.
- Show role name, role code, and editable module count.
- Emit selected role code.

- [ ] Create `EffectivePermissionBadge.vue`.

Map source to compact labels:

```ts
const labelMap = {
  super_admin: '超级管理员',
  user_override: '员工单独设置',
  role_template: '角色模板',
  hidden_default: '默认隐藏'
}
```

- [ ] Create `PermissionPreviewSidebar.vue`.

Requirements:

- Accept `visibleModules`, `deniedModules`, and `editableModules`.
- Group visible modules by `parentName`.
- Render a sidebar-style tree with level badges.
- Render compact denied and editable lists below the tree.

- [ ] Run frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Commit.

```powershell
git add web-admin/src/components/permissions/PermissionLevelSegment.vue web-admin/src/components/permissions/ModulePermissionTree.vue web-admin/src/components/permissions/EmployeeSelector.vue web-admin/src/components/permissions/RoleTemplateSelector.vue web-admin/src/components/permissions/EffectivePermissionBadge.vue web-admin/src/components/permissions/PermissionPreviewSidebar.vue
git commit -m "feat: add reusable permission controls"
```

---

## Task 8: Build The Permission Settings Views

- [ ] Create `web-admin/src/views/permissions/index.vue`.

Use a plain routed layout with tabs:

```vue
<template>
  <section class="permission-shell">
    <el-tabs :model-value="$route.path" @tab-change="go">
      <el-tab-pane label="员工管理" name="/permissions/employees" />
      <el-tab-pane label="角色权限模板" name="/permissions/role-templates" />
      <el-tab-pane label="员工权限" name="/permissions/employee-permissions" />
      <el-tab-pane label="权限预览" name="/permissions/preview" />
    </el-tabs>
    <router-view />
  </section>
</template>
```

- [ ] Create `employees/index.vue`.

Reuse behavior from `web-admin/src/views/system/users/list.vue`:

- Table columns: employee name, username, phone, role, status, created time.
- Toolbar actions: create employee, refresh.
- Create dialog: name, username/phone, password, role.
- Row actions: edit, activate/deactivate, open permissions.
- Disable create/edit/status buttons when `!permissionStore.canWriteModuleCode('permission_employee_management')`.

- [ ] Create `role-templates/index.vue`.

Requirements:

- Left column role selector.
- Right column `ModulePermissionTree`.
- Save button calls `updateRoleTemplate`.
- Save button disabled when `!permissionStore.canWriteModuleCode('permission_role_templates')`.
- On successful save, reload current user's effective permissions if the edited role equals the current user's role.

- [ ] Create `employee-permissions/index.vue`.

Requirements:

- Employee selector at top.
- `ModulePermissionTree` for selected employee effective permissions.
- Mark rows where source is `user_override`.
- Add a clear button on each user override row; it calls `clearUserOverride(factoryId, userId, moduleCode)` and reloads the effective permission list.
- Save button calls `updateUserOverrides`.
- Save button disabled when `!permissionStore.canWriteModuleCode('permission_employee_overrides')`.

- [ ] Create `preview/index.vue`.

Requirements:

- Employee selector.
- Call `previewUserPermissions(factoryId, userId)` after employee selection.
- Render `PermissionPreviewSidebar` using visible, denied, and editable lists from the API.
- No write actions on this page.

- [ ] Run frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Commit.

```powershell
git add web-admin/src/views/permissions
git commit -m "feat: add permission settings views"
```

---

## Task 9: Enforce Write Disabled States In Existing Module Pages

- [ ] Search for pages with create/edit/delete/export/import buttons under current sidebar routes.

Command:

```powershell
cd web-admin
rg "el-button|create|delete|新增|删除|编辑|保存|导入|导出" src/views src/components
```

- [ ] For every page that already has a `route.meta.moduleCode`, disable mutating actions when `!permissionStore.canWriteModuleCode(route.meta.moduleCode as string)`.

Use this local helper in each page:

```ts
const route = useRoute()
const permissionStore = usePermissionStore()
const canWriteCurrentModule = computed(() => permissionStore.canWriteModuleCode(route.meta.moduleCode as string | undefined))
```

Then bind:

```vue
<el-button :disabled="!canWriteCurrentModule">保存</el-button>
```

- [ ] Keep read-only pages fully viewable when level is `read`.

- [ ] Run frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Commit.

```powershell
git add web-admin/src
git commit -m "feat: respect read-only module permissions in actions"
```

---

## Task 10: Final Verification

- [ ] Backend compile.

```powershell
cd backend/java/cretas-api
.\mvnw.cmd -q -DskipTests compile
```

Expected result: command exits `0`.

- [ ] Frontend tests.

```powershell
cd web-admin
npm run test -- src/store/modules/permission.settings.spec.ts src/store/modules/permission.user-module-access.spec.ts
```

Expected result: both spec files pass.

- [ ] Frontend type/build verification.

```powershell
cd web-admin
npm run build:check
```

Expected result: command exits `0`.

- [ ] Manual acceptance checks in browser.

1. Login as `factory_super_admin`; confirm `权限设置` appears in left sidebar.
2. Open `权限设置 > 员工管理`; create one employee.
3. Open `角色权限模板`; set one second-level module to `隐藏`, one to `只读`, one to `可编辑`.
4. Login as an employee with that role; confirm hidden module disappears from sidebar.
5. Open a read-only module directly by URL; confirm page opens but create/edit/delete buttons are disabled.
6. Attempt write API on the read-only module; confirm backend returns `403`.
7. Add an employee override; confirm it wins over the role template.
8. Open `权限预览`; confirm it matches the actual visible sidebar.

- [ ] Final commit if verification caused fixes.

```powershell
git status --short
git add backend/java/cretas-api web-admin
git commit -m "test: verify permission settings module"
```

---

## Self-Review Checklist

- [ ] The permission settings module is standalone in sidebar and router.
- [ ] Factory super admin can create employees and edit all permission levels.
- [ ] Permission levels are exactly `hidden`, `read`, `write` in the UI.
- [ ] No `approval` level is shown or stored by the new UI.
- [ ] Sidebar filtering uses second-level module codes.
- [ ] Router direct access blocks hidden second-level modules.
- [ ] Read-only modules still allow viewing.
- [ ] Write actions are disabled in frontend for read-only modules.
- [ ] Backend blocks write HTTP methods for `@RequireModule` routes when effective level is not write.
- [ ] Existing `GRANT`/`DENY` user overrides still load through compatibility mapping.
- [ ] Existing first-level `canAccess` and `canWrite` callers continue to work.
- [ ] Backend compile passes.
- [ ] Frontend permission tests pass.
