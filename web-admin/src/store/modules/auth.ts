/**
 * 认证状态管理
 *
 * Auth strategy:
 *   Tokens are stored in HttpOnly cookies (set by the server).
 *   JavaScript cannot read them — this is intentional (XSS protection).
 *   User info (non-sensitive) is kept in localStorage for fast hydration.
 *   `isAuthenticated` is derived from having user info in memory;
 *   if the cookie has expired the next API call will 401 and redirect to login.
 *
 * Note: This file must not import other store modules or API modules at the
 * top level to avoid circular dependencies. Use dynamic imports inside functions.
 */
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { User } from '@/types/auth';
import { isPlatformUser, isFactoryUser, ROLE_METADATA } from '@/types/auth';

// localStorage keys (only for non-sensitive user info)
const USER_KEY = 'cretas_user';
type DemoTenant = 'rest' | 'factory' | 'logistics';

const FACTORY_BRAND_SUBTITLE = '白垩纪AI';
const DEFAULT_BRAND_TITLE = '白垩纪AI Agent';
const DEFAULT_BRAND_LOGO_URL = '/logo.svg';
const FACTORY_BRAND_OVERRIDES: Record<string, { name: string; logoUrl: string }> = {
  LIUSHANMEN: {
    name: '六膳门',
    logoUrl: '/brands/liushanmen-logo.jpg',
  },
};

function getFactoryBrandOverride(id?: string) {
  return id ? FACTORY_BRAND_OVERRIDES[id] : undefined;
}

function resolveBusinessDomain(factoryType?: string, explicitDomain?: string): 'FACTORY' | 'RESTAURANT' | 'LOGISTICS' {
  const domain = explicitDomain?.toUpperCase();
  if (domain === 'RESTAURANT' || domain === 'FACTORY' || domain === 'LOGISTICS') return domain;
  const type = factoryType?.toUpperCase();
  if (type === 'LOGISTICS') return 'LOGISTICS';
  return type === 'RESTAURANT' || type === 'BRANCH' ? 'RESTAURANT' : 'FACTORY';
}

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null);
  const loading = ref(false);

  // 初始化时尝试从 localStorage 恢复用户信息 (non-sensitive)
  const storedUser = localStorage.getItem(USER_KEY);
  if (storedUser && storedUser !== 'undefined' && storedUser !== 'null') {
    try {
      const parsed = JSON.parse(storedUser);
      if (parsed && typeof parsed === 'object') {
        user.value = parsed;
      }
    } catch (e) {
      console.error('Failed to parse stored user:', e);
      localStorage.removeItem(USER_KEY);
    }
  } else if (storedUser) {
    localStorage.removeItem(USER_KEY);
  }

  // Getters
  // Since tokens are in HttpOnly cookies (not readable by JS), we check user info.
  // If the cookie expired, the next API call will 401 and the interceptor redirects to login.
  const isAuthenticated = computed(() => !!user.value);

  const currentRole = computed(() => {
    const u = user.value as User | null;
    if (!u) return 'unactivated';
    if (isPlatformUser(u)) return u.platformUser?.role || 'unactivated';
    if (isFactoryUser(u)) return u.factoryUser?.role || 'unactivated';
    return 'unactivated';
  });

  const factoryId = computed(() => {
    const u = user.value as User | null;
    if (!u) return '';
    if (isFactoryUser(u)) return u.factoryUser?.factoryId || '';
    return '';
  });

  const factoryType = computed(() => {
    const u = user.value as User | null;
    if (!u) return '';
    if (isFactoryUser(u)) return u.factoryUser?.factoryType || 'FACTORY';
    return '';
  });

  const businessDomain = computed(() => {
    const u = user.value as User | null;
    if (!u || !isFactoryUser(u)) return '';
    return resolveBusinessDomain(u.factoryUser?.factoryType, u.factoryUser?.businessDomain);
  });

  const factoryName = computed(() => {
    const u = user.value as User | null;
    if (!u || !isFactoryUser(u)) return '';
    const factoryUser = u.factoryUser;
    return getFactoryBrandOverride(factoryUser?.factoryId)?.name
      || factoryUser?.factoryName
      || factoryUser?.factoryId
      || '';
  });

  const factoryLogoUrl = computed(() => {
    const u = user.value as User | null;
    if (!u || !isFactoryUser(u)) return DEFAULT_BRAND_LOGO_URL;
    const factoryUser = u.factoryUser;
    return getFactoryBrandOverride(factoryUser?.factoryId)?.logoUrl
      || factoryUser?.factoryLogoUrl
      || DEFAULT_BRAND_LOGO_URL;
  });

  const brandTitle = computed(() => factoryName.value || DEFAULT_BRAND_TITLE);
  const brandSubtitle = computed(() => factoryName.value ? FACTORY_BRAND_SUBTITLE : '');
  const isLiushanmenFactory = computed(() => factoryId.value === 'LIUSHANMEN' || factoryName.value === '六膳门');

  const roleMetadata = computed(() => {
    const role = currentRole.value;
    const metadata = ROLE_METADATA[role] || ROLE_METADATA['viewer'];
    if (businessDomain.value === 'RESTAURANT' && role === 'factory_super_admin') {
      return {
        ...metadata,
        displayName: '餐饮管理员',
        description: '拥有餐饮门店经营、采购、库存、财务和智能分析权限',
        department: 'restaurant',
      };
    }
    if (businessDomain.value === 'LOGISTICS' && role === 'dispatcher') {
      return {
        ...metadata,
        displayName: '物流调度员',
        description: '负责车辆、司机、门店订单和智能排班',
        department: 'logistics',
      };
    }
    return metadata;
  });

  const isPlatform = computed(() => isPlatformUser(user.value as User | null));
  const isFactory = computed(() => isFactoryUser(user.value as User | null));
  const userLevel = computed(() => roleMetadata.value?.level ?? 99);
  const department = computed(() => roleMetadata.value?.department ?? 'none');

  // Actions
  function setUser(userData: User) {
    user.value = userData;
    localStorage.setItem(USER_KEY, JSON.stringify(userData));
  }

  function clearAuth() {
    user.value = null;
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem('cretas_access_token');
    // Note: HttpOnly cookies are cleared by the server on logout
  }

  async function login(username: string, password: string): Promise<boolean> {
    loading.value = true;
    try {
      // 动态导入 API，避免循环依赖
      const { login: loginApi } = await import('@/api/auth');
      const response = await loginApi({ username, password });

      if (response.success && response.data) {
        applyLoginData(response.data);
        return true;
      }
      return false;
    } catch (error) {
      console.error('Login failed:', error);
      return false;
    } finally {
      loading.value = false;
    }
  }

  // 把后端登录响应 (扁平字段) 落地为 token + user. login 与 demoLogin 共用.
  function applyLoginData(data: any) {
    // Store token in localStorage as fallback for when HttpOnly cookies
    // don't work (e.g. certain browser policies, cross-origin scenarios).
    if (data.token || data.accessToken) {
      localStorage.setItem('cretas_access_token', data.token || data.accessToken);
    }
    const userData: User = {
      id: data.userId,
      username: data.username,
      email: '',
      isActive: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      userType: 'factory',
      factoryUser: {
        role: data.role,
        factoryId: data.factoryId,
        factoryName: data.factoryName,
        factoryLogoUrl: data.factoryLogoUrl,
        factoryType: data.factoryType || 'FACTORY',
        businessDomain: resolveBusinessDomain(data.factoryType || 'FACTORY', data.businessDomain),
        permissions: data.permissions || [],
      }
    } as User;
    setUser(userData);
  }

  // 演示账号免密登录 (路演扫码). tenant = 'rest' | 'factory' | 'logistics'
  // 三个 tenant 都走同一条后端路径: POST /api/mobile/auth/demo-login?tenant=...
  // (Phase 6a, 2026-07-11: logistics 分支原先是前端硬编码的假 token, 现由后端
  // MobileAuthService.demoLogin("logistics") 签发真实 JWT, 锁定 factoryId=DEMO_LOGISTICS —
  // 与 rest/factory 完全同构, 不再需要前端特判.)
  async function demoLogin(tenant: DemoTenant): Promise<boolean> {
    loading.value = true;
    try {
      const { demoLogin: demoApi } = await import('@/api/auth');
      const response = await demoApi(tenant);
      if (response.success && response.data) {
        applyLoginData(response.data);
        return true;
      }
      return false;
    } catch (error) {
      console.error('Demo login failed:', error);
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function logout() {
    try {
      // 动态导入 API，避免循环依赖
      // The backend clears HttpOnly cookies via Set-Cookie: Max-Age=0
      const { logout: logoutApi } = await import('@/api/auth');
      await logoutApi();
    } catch (error) {
      console.error('Logout API failed:', error);
    } finally {
      clearAuth();
    }
  }

  async function fetchCurrentUser(): Promise<boolean> {
    try {
      // 动态导入 API，避免循环依赖
      // Auth cookie is sent automatically via withCredentials
      const { getCurrentUser } = await import('@/api/auth');
      const response = await getCurrentUser();
      if (response.success && response.data) {
        setUser(response.data);
        return true;
      }
      return false;
    } catch (error) {
      console.error('Failed to fetch current user:', error);
      return false;
    }
  }

  function hasRole(roles: string | string[]): boolean {
    const role = currentRole.value;
    if (Array.isArray(roles)) {
      return roles.includes(role);
    }
    return role === roles;
  }

  function hasMinLevel(minLevel: number): boolean {
    return userLevel.value <= minLevel;
  }

  return {
    // State
    user,
    loading,

    // Getters
    isAuthenticated,
    currentRole,
    factoryId,
    factoryName,
    factoryLogoUrl,
    factoryType,
    businessDomain,
    brandTitle,
    brandSubtitle,
    isLiushanmenFactory,
    roleMetadata,
    isPlatform,
    isFactory,
    userLevel,
    department,

    // Actions
    setUser,
    clearAuth,
    login,
    demoLogin,
    logout,
    fetchCurrentUser,
    hasRole,
    hasMinLevel
  };
});
