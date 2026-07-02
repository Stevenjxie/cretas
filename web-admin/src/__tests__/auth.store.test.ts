/**
 * Unit tests for the auth Pinia store.
 *
 * Tests state management (setUser, clearAuth), computed getters
 * (isAuthenticated, currentRole, factoryId), and role-checking helpers.
 * No network calls -- login/logout API calls are not tested here.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import type { FactoryUser, PlatformUser } from '@/types/auth';

// ── Fixtures ────────────────────────────────────────────────────

function makeFactoryUser(overrides: Partial<FactoryUser> & { factoryUser?: Partial<FactoryUser['factoryUser']> } = {}): FactoryUser {
  return {
    id: 1,
    username: 'factory_admin1',
    email: 'admin@cretas.com',
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    userType: 'factory',
    factoryUser: {
      role: 'factory_super_admin',
      factoryId: 'F001',
      factoryType: 'FACTORY',
      permissions: ['admin'],
      ...overrides.factoryUser,
    },
    ...overrides,
  } as FactoryUser;
}

function makePlatformUser(overrides: Partial<PlatformUser> = {}): PlatformUser {
  return {
    id: 100,
    username: 'platform_admin',
    email: 'platform@cretas.com',
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    userType: 'platform',
    platformUser: {
      role: 'platform_admin',
      permissions: ['all'],
    },
    ...overrides,
  } as PlatformUser;
}

// ── Tests ───────────────────────────────────────────────────────

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  // ── login sets user state ─────────────────────────────────

  describe('setUser', () => {
    it('sets user state from factory user', () => {
      const store = useAuthStore();
      const user = makeFactoryUser();

      store.setUser(user);

      expect(store.user).not.toBeNull();
      expect(store.user?.username).toBe('factory_admin1');
      expect(store.user?.userType).toBe('factory');
    });

    it('persists user to localStorage', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      const stored = localStorage.getItem('cretas_user');
      expect(stored).not.toBeNull();
      const parsed = JSON.parse(stored!);
      expect(parsed.username).toBe('factory_admin1');
    });

    it('sets user state from platform user', () => {
      const store = useAuthStore();
      store.setUser(makePlatformUser());

      expect(store.user?.userType).toBe('platform');
    });
  });

  // ── logout clears user state ──────────────────────────────

  describe('clearAuth', () => {
    it('clears user state and localStorage', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());
      expect(store.user).not.toBeNull();

      store.clearAuth();

      expect(store.user).toBeNull();
      expect(localStorage.getItem('cretas_user')).toBeNull();
    });
  });

  // ── isAuthenticated ───────────────────────────────────────

  describe('isAuthenticated', () => {
    it('returns true when user exists', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      expect(store.isAuthenticated).toBe(true);
    });

    it('returns false when no user', () => {
      const store = useAuthStore();

      expect(store.isAuthenticated).toBe(false);
    });

    it('returns false after clearAuth', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());
      store.clearAuth();

      expect(store.isAuthenticated).toBe(false);
    });
  });

  // ── currentRole ───────────────────────────────────────────

  describe('currentRole', () => {
    it('extracts factory_super_admin role', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'factory_super_admin', factoryId: 'F001', permissions: [] },
      }));

      expect(store.currentRole).toBe('factory_super_admin');
    });

    it('extracts workshop_supervisor role', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'workshop_supervisor', factoryId: 'F001', permissions: [] },
      }));

      expect(store.currentRole).toBe('workshop_supervisor');
    });

    it('extracts platform_admin role', () => {
      const store = useAuthStore();
      store.setUser(makePlatformUser());

      expect(store.currentRole).toBe('platform_admin');
    });

    it('returns unactivated when no user', () => {
      const store = useAuthStore();

      expect(store.currentRole).toBe('unactivated');
    });
  });

  // ── factoryId ─────────────────────────────────────────────

  describe('factoryId', () => {
    it('extracts correct factoryId from factory user', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'factory_super_admin', factoryId: 'F002', permissions: [] },
      }));

      expect(store.factoryId).toBe('F002');
    });

    it('returns empty string for platform user', () => {
      const store = useAuthStore();
      store.setUser(makePlatformUser());

      expect(store.factoryId).toBe('');
    });

    it('returns empty string when no user', () => {
      const store = useAuthStore();

      expect(store.factoryId).toBe('');
    });
  });

  // ── factoryType ───────────────────────────────────────────

  describe('factoryType', () => {
    it('extracts FACTORY type', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'factory_super_admin', factoryId: 'F001', factoryType: 'FACTORY', permissions: [] },
      }));

      expect(store.factoryType).toBe('FACTORY');
    });

    it('extracts RESTAURANT type', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'factory_super_admin', factoryId: 'R001', factoryType: 'RESTAURANT', permissions: [] },
      }));

      expect(store.factoryType).toBe('RESTAURANT');
    });
  });

  // ── hasRole ───────────────────────────────────────────────

  describe('businessDomain', () => {
    it('maps BRANCH factoryType to RESTAURANT domain', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: { role: 'factory_super_admin', factoryId: 'R_BRANCH_001', factoryType: 'BRANCH', permissions: [] },
      }));

      expect(store.businessDomain).toBe('RESTAURANT');
    });

    it('prefers explicit backend businessDomain', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser({
        factoryUser: {
          role: 'factory_super_admin',
          factoryId: 'R001',
          factoryType: 'FACTORY',
          businessDomain: 'RESTAURANT',
          permissions: [],
        },
      }));

      expect(store.businessDomain).toBe('RESTAURANT');
    });
  });

  describe('hasRole', () => {
    it('returns true for matching single role', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      expect(store.hasRole('factory_super_admin')).toBe(true);
    });

    it('returns false for non-matching role', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      expect(store.hasRole('warehouse_manager')).toBe(false);
    });

    it('returns true when role is in array', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      expect(store.hasRole(['factory_super_admin', 'dispatcher'])).toBe(true);
    });

    it('returns false when role is not in array', () => {
      const store = useAuthStore();
      store.setUser(makeFactoryUser());

      expect(store.hasRole(['warehouse_manager', 'operator'])).toBe(false);
    });
  });

  // ── localStorage hydration ────────────────────────────────

  describe('localStorage hydration', () => {
    it('restores user from localStorage on store creation', () => {
      const user = makeFactoryUser();
      localStorage.setItem('cretas_user', JSON.stringify(user));

      // Creating a new pinia + store should hydrate from localStorage
      setActivePinia(createPinia());
      const store = useAuthStore();

      expect(store.user).not.toBeNull();
      expect(store.user?.username).toBe('factory_admin1');
      expect(store.isAuthenticated).toBe(true);
    });

    it('handles invalid JSON in localStorage gracefully', () => {
      localStorage.setItem('cretas_user', 'not-valid-json{{{');

      setActivePinia(createPinia());
      const store = useAuthStore();

      expect(store.user).toBeNull();
      // The invalid entry should be cleaned up
      expect(localStorage.getItem('cretas_user')).toBeNull();
    });

    it('handles "undefined" string in localStorage', () => {
      localStorage.setItem('cretas_user', 'undefined');

      setActivePinia(createPinia());
      const store = useAuthStore();

      expect(store.user).toBeNull();
    });

    it('handles "null" string in localStorage', () => {
      localStorage.setItem('cretas_user', 'null');

      setActivePinia(createPinia());
      const store = useAuthStore();

      expect(store.user).toBeNull();
    });
  });
});
