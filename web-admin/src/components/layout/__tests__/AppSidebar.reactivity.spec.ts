/**
 * WS6 — sidebar menu reactivity regression tests.
 *
 * Bug: the left sidebar menu sometimes did not update until a manual page
 * refresh. AppSidebar.vue computes the visible menu (`filteredMenu`) from
 * reactive sources via `canSeeMenuItem`:
 *   - authStore.factoryType
 *   - permissionStore.currentRole
 *   - permissionStore.canAccess(module)  → permissionStore.currentPermissions (computed)
 *   - the async-fetched disabled-modules set
 *
 * Two reactivity gaps caused stale menus:
 *   1. `canSeeMenuItem` short-circuited (early returns + `&&`), so on the first
 *      evaluation some items never *read* `canAccess` / `currentRole`, meaning
 *      `filteredMenu` never registered a dependency on the permission state for
 *      those items → it could fail to re-run when permissions finished loading.
 *   2. The disabled-modules fetch ran in onMounted and bailed (no retry) if
 *      factoryId was empty at mount.
 *
 * AppSidebar.vue imports a static `<img src="/logo.svg">` asset that the vue
 * SFC transform turns into an unresolvable module under vite-node, so we cannot
 * cheaply `mount()` it in a unit test. Instead we replicate the EXACT reactive
 * read shape of `canSeeMenuItem` + `filteredMenu` against the REAL auth +
 * permission Pinia stores and assert the computed re-evaluates automatically
 * when the stores change — which is precisely the contract the fix restores.
 *
 * To guard against the component drifting from this replica, we also assert the
 * component source reads every reactive source eagerly (no short-circuit before
 * the reads) and uses a watch (not onMounted) for the disabled-modules fetch.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { computed, nextTick } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { menuConfig, financeManagerMenu, type MenuItem } from '../menuConfig';
import type { User } from '@/types/auth';

function factoryUser(role: string, factoryId = 'F001', factoryType = 'FACTORY'): User {
  return {
    id: 1,
    username: 'u',
    email: '',
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    userType: 'factory',
    factoryUser: { role, factoryId, factoryType, permissions: [] },
  } as User;
}

/**
 * Faithful replica of AppSidebar's `canSeeMenuItem` + `filteredMenu`.
 * Mirrors the fixed eager-read shape so the test fails if reactivity regresses.
 */
function buildFilteredMenu() {
  const authStore = useAuthStore();
  const permissionStore = usePermissionStore();
  const disabledSidebarModules = computed(() => new Set<string>()); // no disabled modules in this harness

  function canSeeMenuItem(item: MenuItem): boolean {
    const factoryType = authStore.factoryType;
    const disabledSet = disabledSidebarModules.value;
    const currentRole = permissionStore.currentRole;
    const canAccess = permissionStore.canAccess(item.module);

    if (item.hideForFactoryTypes?.includes(factoryType)) return false;
    if (disabledSet.has(item.module)) return false;
    if (!item.roles || item.roles.length === 0) return canAccess;
    return item.roles.includes(currentRole) && canAccess;
  }

  return computed(() => {
    if (authStore.currentRole === 'finance_manager') return financeManagerMenu;
    return menuConfig
      .filter((item) => canSeeMenuItem(item))
      .map((item) => {
        if (!item.children) return item;
        const filteredChildren = item.children.filter((child) => canSeeMenuItem(child));
        return { ...item, children: filteredChildren };
      })
      .filter((item) => !item.children || item.children.length > 0);
  });
}

const topPaths = (menu: MenuItem[]) => menu.map((m) => m.path);

describe('sidebar filteredMenu — reactivity (WS6, no manual refresh)', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it('re-evaluates when permission role changes after first evaluation (no remount)', async () => {
    const auth = useAuthStore();
    const perm = usePermissionStore();
    const filteredMenu = buildFilteredMenu();

    // Start limited: warehouse_manager → warehouse 'rw', finance '-'.
    auth.setUser(factoryUser('warehouse_manager'));
    perm.setRole('warehouse_manager', 'F001', 'FACTORY');
    await nextTick();

    const before = topPaths(filteredMenu.value);
    expect(before).toContain('/warehouse');
    expect(before).not.toContain('/finance');

    // Promote AFTER the computed already evaluated once. The original bug was
    // the menu staying stale; the computed must recompute automatically.
    auth.setUser(factoryUser('factory_super_admin'));
    perm.setRole('factory_super_admin', 'F001', 'FACTORY');
    await nextTick();

    const after = topPaths(filteredMenu.value);
    expect(after).toContain('/finance'); // newly visible, no refresh
    expect(after).toContain('/system');
    expect(after).toContain('/warehouse');
  });

  it('re-evaluates when async DB permissions load and grant a module', async () => {
    const perm = usePermissionStore();
    const auth = useAuthStore();
    const filteredMenu = buildFilteredMenu();

    // viewer hardcoded fallback has system '-' (no system access).
    auth.setUser(factoryUser('viewer'));
    perm.setRole('viewer', 'F001', 'FACTORY');
    await nextTick();
    expect(topPaths(filteredMenu.value)).not.toContain('/system');

    // Simulate the async loadFromDb() resolving with a DB override that grants
    // system access for this role — the computed reads currentPermissions
    // (which depends on dbPermissions + isDbLoaded), so it must re-run.
    perm.dbPermissions = {
      dashboard: 'r', production: 'r', warehouse: 'r', quality: 'r',
      procurement: 'r', sales: 'r', hr: 'r', equipment: 'r',
      finance: 'r', system: 'rw', analytics: 'r', scheduling: 'r',
      restaurant: 'r', rd: 'r',
    };
    perm.isDbLoaded = true;
    await nextTick();

    expect(topPaths(filteredMenu.value)).toContain('/system'); // appears after DB load
  });

  it('re-evaluates when factoryType changes (RESTAURANT hides manufacturing groups)', async () => {
    const auth = useAuthStore();
    const perm = usePermissionStore();
    const filteredMenu = buildFilteredMenu();

    auth.setUser(factoryUser('factory_super_admin', 'F001', 'FACTORY'));
    perm.setRole('factory_super_admin', 'F001', 'FACTORY');
    await nextTick();
    expect(topPaths(filteredMenu.value)).toContain('/production');
    expect(topPaths(filteredMenu.value)).not.toContain('/restaurant');

    auth.setUser(factoryUser('factory_super_admin', 'R001', 'RESTAURANT'));
    perm.setRole('factory_super_admin', 'R001', 'RESTAURANT');
    await nextTick();

    const after = topPaths(filteredMenu.value);
    expect(after).not.toContain('/production'); // hideForFactoryTypes RESTAURANT
    expect(after).toContain('/restaurant');
  });

  it('finance_manager simplified menu reacts to role transition both ways', async () => {
    const auth = useAuthStore();
    const perm = usePermissionStore();
    const filteredMenu = buildFilteredMenu();

    auth.setUser(factoryUser('finance_manager'));
    perm.setRole('finance_manager', 'F001', 'FACTORY');
    await nextTick();
    expect(filteredMenu.value).toBe(financeManagerMenu);

    auth.setUser(factoryUser('factory_super_admin'));
    perm.setRole('factory_super_admin', 'F001', 'FACTORY');
    await nextTick();
    expect(filteredMenu.value).not.toBe(financeManagerMenu);
    expect(topPaths(filteredMenu.value)).toContain('/system');
  });
});

// ── Guard: component source must keep the fixed reactive shape ──────────────
describe('AppSidebar.vue source — keeps WS6 reactive shape', () => {
  // vitest cwd is the web-admin package root.
  const src = readFileSync(
    resolve(process.cwd(), 'src/components/layout/AppSidebar.vue'),
    'utf-8',
  );

  it('disabled-modules fetch is driven by a watch on factoryId, not bare onMounted', () => {
    // The race fix: watch(() => authStore.factoryId, ..., { immediate: true }).
    expect(src).toMatch(/watch\(\s*\(\)\s*=>\s*authStore\.factoryId/);
    // onMounted must no longer be the trigger for the disabled-modules fetch.
    expect(src).not.toMatch(/onMounted\(async/);
  });

  it('canSeeMenuItem reads factoryType / disabled set / currentRole / canAccess eagerly (before any return)', () => {
    const fnStart = src.indexOf('function canSeeMenuItem');
    expect(fnStart).toBeGreaterThan(-1);
    const fnBody = src.slice(fnStart, fnStart + 700);
    const firstReturn = fnBody.indexOf('return');
    const readBlock = fnBody.slice(0, firstReturn);
    // All four reactive reads must occur before the first `return`.
    expect(readBlock).toContain('authStore.factoryType');
    expect(readBlock).toContain('disabledSidebarModules.value');
    expect(readBlock).toContain('permissionStore.currentRole');
    expect(readBlock).toContain('permissionStore.canAccess(item.module)');
  });
});
