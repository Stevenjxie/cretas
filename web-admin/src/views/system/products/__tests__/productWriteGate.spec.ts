import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from '@/store/modules/permission';

const pageSource = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
}));
vi.mock('@/api/permissionApi', () => permissionApiMocks);

function storeAs(role: string) {
  const store = usePermissionStore();
  store.setRole(role, 'F006', 'FACTORY', '1309');
  return store;
}

/**
 * 客户 (张权) 2026-08-05:「无法新建产品了」—— SKU 管理页右上角只剩 导出 / 导入 / SKU组装,
 * 「新增 SKU」整个不见了。报告人角色是**调度**。
 *
 * 根因是前后端判了两个不同的权限:
 *   后端 ProductTypeController 的**每一个**写端点都是 {"production:read_write","rd:read_write"}
 *   前端 products/index.vue 却按 canWrite('system') 决定显不显示
 * 而调度是 production/rd 可写、system 只读 → 后端本来允许他建产品, 界面把入口藏了。
 *
 * 第一组用例钉住「这个角色差别是真实存在的」(不是我臆想的), 第二组钉住页面取对了键。
 */
describe('SKU 管理页的写入闸必须与后端一致 (客户 2026-08-05「无法新建产品了」)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getFactoryOverride.mockRejectedValue(new Error('offline'));
    permissionApiMocks.getUserModuleAccess.mockRejectedValue(new Error('offline'));
  });

  it('调度确实 production/rd 可写、system 只读 —— 取哪个键结果完全相反', () => {
    const store = storeAs('dispatcher');
    expect(store.canWrite('production')).toBe(true);
    expect(store.canWrite('rd')).toBe(true);
    // 这一条就是缺陷的成因: 页面原来取的是它
    expect(store.canWrite('system')).toBe(false);
  });

  it('对产品主数据只读的角色仍然进不来 —— 修的是取错键, 不是把闸拆了', () => {
    // 仓管: production 'r' / rd '-' —— 看得到产品, 不能建。
    // (刻意不用 operator: 它 production 可写, 拿它当"只读"例子会让这条断言恒真而失去意义)
    const store = storeAs('warehouse_manager');
    expect(store.canWrite('production')).toBe(false);
    expect(store.canWrite('rd')).toBe(false);
  });

  it('页面按后端那两个模块判, 不再按 system', () => {
    expect(pageSource).toContain("permissionStore.canWrite('production')");
    expect(pageSource).toContain("permissionStore.canWrite('rd')");
    expect(pageSource).not.toContain("const canWrite = computed(() => permissionStore.canWrite('system'))");
  });

  it('「导入」也是写操作, 不能对只读角色敞着 —— 后端同样要 production/rd 可写', () => {
    // 原来这个按钮没有任何闸: 只读角色点了必然 403 (/import/preview 与 /import/confirm 都是写端点)
    expect(pageSource).toMatch(/<el-button[^>]*v-if="canWrite"[^>]*@click="handleImport"/);
  });
});
