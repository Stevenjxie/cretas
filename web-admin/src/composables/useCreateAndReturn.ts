import { useRoute, useRouter } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';
import type { ModuleName } from '@/store/modules/permission';

/**
 * 缺上游依赖 → 创建并返回 通用机制。
 * 复用现有 _returnTo query + ReturnBanner.vue（挂在 AppLayout）。
 * spec: docs/superpowers/specs/2026-06-22-create-and-return-upstream-pattern-design.md
 */
export function useCreateAndReturn() {
  const router = useRouter();
  const route = useRoute();
  const permissionStore = usePermissionStore();

  /** 用户能否到达目标模块页（按模块判断；write=true 时需写权限）。 */
  function canReach(module: string, opts?: { write?: boolean }): boolean {
    return opts?.write
      ? permissionStore.canWrite(module as ModuleName)
      : permissionStore.canAccess(module as ModuleName);
  }

  /**
   * 跳到 targetPath，附加 _returnTo（默认=当前 fullPath；reopen 用于返回后需重开弹窗的页面）。
   * targetPath 可自带 query（如 ?editItems=1），会正确追加 _returnTo。
   */
  function goCreate(targetPath: string, opts?: { reopen?: string }): void {
    const back = encodeURIComponent(opts?.reopen ?? route.fullPath);
    const sep = targetPath.includes('?') ? '&' : '?';
    router.push(`${targetPath}${sep}_returnTo=${back}`);
  }

  return { canReach, goCreate };
}
