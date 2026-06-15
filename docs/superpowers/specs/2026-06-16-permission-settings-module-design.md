# Permission Settings Module Design

## Goal

Build a standalone `权限设置` module for factory administrators to manage employees and module permissions in one place.

The module must make it easy to:

- Create and manage employee accounts.
- Configure default permissions by role template.
- Override permissions for individual employees.
- Control second-level sidebar modules with three permission levels: hidden, read-only, editable.
- Preview the final menu and access level an employee will see after login.

Only `factory_super_admin`, `platform_admin`, and `permission_admin` may access this module.

## Scope

### In Scope

- Add a top-level sidebar module named `权限设置`.
- Add four pages under it:
  - `员工管理`
  - `角色模板`
  - `员工权限`
  - `权限预览`
- Manage permissions at second-level module granularity, grouped by first-level sidebar category.
- Support three levels for every second-level module:
  - `hidden`: menu hidden and direct route/API access denied.
  - `read`: list/detail/view allowed, write actions denied.
  - `write`: create/update/delete/import/configure allowed.
- Resolve final access as role template plus employee override.
- Keep employee overrides visible and reversible.
- Enforce permissions in both frontend route/sidebar rendering and backend API guards.

### Out of Scope

- Approval-specific permission (`可审批`) is intentionally deferred.
- Data-scope permissions such as all/dept/self are not changed by this module.
- Field-level permissions are not included.
- Audit log UI is not included, though backend writes should remain compatible with existing audit logging if present.

## User Experience

### Module Navigation

`权限设置` appears as its own first-level sidebar item. It should not be buried inside `系统管理`.

Children:

1. `员工管理`
2. `角色模板`
3. `员工权限`
4. `权限预览`

The design should be dense and operational, not a marketing-style page. It is an admin tool used repeatedly.

### 员工管理

Purpose: manage all employee accounts.

Capabilities:

- Search by username, name, phone, department, role.
- Filter by active/inactive status.
- Create employee.
- Edit employee profile.
- Enable/disable employee.
- Reset password.
- Jump to `员工权限` for the selected employee.

Create employee flow:

1. Basic information: username, password or generated default, name, phone, department. Email remains optional and follows the current user API behavior; this design does not require new email storage.
2. Role template: required.
3. Permission review: show inherited second-level permissions from the selected role.
4. Optional overrides: allow changing specific modules before saving.

After creation, the account inherits the selected role template unless explicit employee overrides are saved.

### 角色模板

Purpose: define default permissions for a role.

Layout:

- Left side: role list.
- Main area: grouped second-level module tree/matrix.
- Each module row has a segmented control:
  - `隐藏`
  - `只读`
  - `可编辑`

Behavior:

- Changing a role template affects employees who inherit that module from the role.
- Employees with explicit overrides keep their override.
- The UI must show how many employees currently use the selected role.
- Before saving, show a short confirmation if the change reduces access for active employees.

### 员工权限

Purpose: manage exceptions per employee.

Layout:

- Left side: employee selector/list with role, department, status.
- Main area: same grouped second-level module list.

Each module row shows:

- Role default level.
- Employee override value:
  - `继承`
  - `隐藏`
  - `只读`
  - `可编辑`
- Final effective level.

Overrides are visually highlighted. Clearing an override returns the module to role-template inheritance.

### 权限预览

Purpose: make permissions understandable before asking the employee to log in.

Capabilities:

- Select employee.
- Show final sidebar tree exactly as the employee should see it.
- Show second-level module badges: hidden/read/write.
- Show a short list of denied modules.
- Show a short list of editable modules.

This page is read-only.

## Permission Model

### Levels

Use a normalized three-level model:

- `hidden`
- `read`
- `write`

Mapping to existing permission levels:

- `hidden` maps to `-`
- `read` maps to `r`
- `write` maps to `rw`

The current `w` write-only level should not be exposed in the new UI. Existing `w` data can be normalized to `write` in display, because most admin users expect edit permission to include view permission.

### Resolution

Effective permission:

1. Start from role template permission for `(roleCode, moduleCode)`.
2. Apply employee override for `(factoryId, userId, moduleCode)` if present.
3. Apply factory type/module availability filters.
4. Deny if the module is disabled for the factory.

Employee override always wins over role template.

### Authorization Roles

Only these roles can enter or write the permission settings module:

- `factory_super_admin`
- `platform_admin`
- `permission_admin`

Read-only access to the permission settings module is not provided to normal managers.

## Module Registry

The module list must come from a unified registry, not hardcoded separately in the page.

Each second-level module definition should include:

- `moduleCode`
- `displayName`
- `category`
- `permissionModule`
- `routePath`
- `sidebarParent`
- `supportsRead`
- `supportsWrite`
- `sortOrder`

Create a unified second-level module registry and make the existing frontend `PRODUCTION_MODULE_REGISTRY` and backend `ProductionModuleRegistry` consume or mirror that registry. The registry must include all sidebar second-level modules, not only production-oriented modules.

The registry must include the new permission module entries:

- `permission_employee_management`
- `permission_role_templates`
- `permission_employee_overrides`
- `permission_preview`

Their `permissionModule` is `system` for this version.

## Backend Design

### Existing Concepts To Reuse

- `platform_role_permissions`: role-level default matrix.
- `user_module_access`: employee-level override.
- `PermissionServiceImpl`: permission resolver.
- `ModuleEnabledInterceptor`: backend module guard.
- `UserModuleAccessController`: employee override APIs.
- `UserController`: employee management APIs.

### Required Changes

1. Add/extend a backend module registry for all second-level sidebar modules.
2. Add role-template APIs that read/write role defaults using the registry.
3. Extend employee override APIs to support the normalized three-level values.
4. Ensure backend route/API guards can enforce second-level module access.
5. Ensure direct URL/API access is denied when a module is `hidden`.
6. Ensure write APIs are denied when effective level is `read`.

### Suggested APIs

Registry:

- `GET /api/mobile/{factoryId}/permissions/modules`

Role templates:

- `GET /api/mobile/{factoryId}/permissions/role-templates`
- `GET /api/mobile/{factoryId}/permissions/role-templates/{roleCode}`
- `PUT /api/mobile/{factoryId}/permissions/role-templates/{roleCode}`

Employee overrides:

- `GET /api/mobile/{factoryId}/permissions/users/{userId}`
- `PUT /api/mobile/{factoryId}/permissions/users/{userId}/modules/{moduleCode}`
- `DELETE /api/mobile/{factoryId}/permissions/users/{userId}/modules/{moduleCode}`

Preview:

- `GET /api/mobile/{factoryId}/permissions/users/{userId}/preview`

All write APIs require `system:read_write` and an allowed admin role.

## Frontend Design

### Route Structure

Add:

- `/permissions/employees`
- `/permissions/role-templates`
- `/permissions/employee-permissions`
- `/permissions/preview`

The sidebar title is `权限设置`.

### Components

Shared components:

- `PermissionLevelSegment`
- `ModulePermissionTree`
- `EmployeeSelector`
- `RoleTemplateSelector`
- `EffectivePermissionBadge`
- `PermissionPreviewSidebar`

The permission matrix should be grouped and searchable. It should avoid showing one huge table without filtering.

Recommended controls:

- Segmented control for `隐藏 / 只读 / 可编辑`.
- Toggle or clear button for employee override inheritance.
- Search input for module name.
- Category tabs or grouped collapsible sections for first-level module groups.

### New Employee Wizard

The `员工管理` page opens a drawer or dialog wizard:

1. Basic information.
2. Select role template.
3. Review inherited permissions.
4. Optional overrides.

The final step shows a concise summary before submit.

## Data Migration

Add a Flyway migration to seed missing role-template rows for new second-level modules.

For `factory_super_admin`, `platform_admin`, and `permission_admin`, seed every permission-settings module as `rw`.

For all other roles, seed permission-settings modules as `-`.

Existing role defaults should not be overwritten blindly. Use idempotent upserts that only insert missing rows, or use explicit update only for the new permission-settings modules.

## Error Handling

When access is denied, return a clear message:

- Hidden module: `当前账号无权访问该模块，请联系超级工厂管理员开通权限。`
- Read-only write attempt: `当前账号只有只读权限，不能执行新增、修改或删除。`
- Module disabled: keep existing module-disabled message, but mention the module display name.

Frontend should show the message from the backend and avoid generic failure text.

## Testing

### Unit Tests

Frontend:

- Role template level maps to sidebar visibility.
- Employee override wins over role template.
- `hidden` hides second-level route/menu.
- `read` disables write buttons.
- `write` enables write buttons.

Backend:

- Role template resolves default level.
- Employee override wins.
- Admin roles can manage permissions.
- Non-admin roles cannot manage permission settings.
- Read-only users cannot call write APIs.

### E2E Tests

Use `factory_super_admin`:

1. Open `权限设置`.
2. Create a new employee with a role template.
3. Grant one second-level module as read-only.
4. Login or simulate as the employee.
5. Verify the module appears but write controls are unavailable.
6. Change employee override to hidden.
7. Verify sidebar hides the module and direct route is denied.

## Acceptance Criteria

- A super factory admin can create a new employee from `权限设置 -> 员工管理`.
- A super factory admin can configure second-level module defaults by role.
- A super factory admin can override a specific employee's second-level module permission.
- The UI supports exactly three levels for now: hidden, read-only, editable.
- Employee overrides clearly show inheritance vs override.
- Permission preview shows the final effective sidebar and access level.
- Sidebar rendering, direct route access, and backend API access all follow the same effective permission.
- Existing roles and users continue to work if no new overrides are configured.
