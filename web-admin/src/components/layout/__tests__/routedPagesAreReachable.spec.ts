import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { menuConfig } from '../menuConfig';

/**
 * 有路由但没有菜单入口的页面 = 用户点不到 = 等于没做。
 *
 * 🔴 2026-08-07 实测: `dashboard/ai-value`(小蓝店长第 ③ 块「AI 价值汇总」)
 * 在 `router/index.ts` 里声明齐全 —— title / icon / module 一样不缺 ——
 * 但 `menuConfig.ts` 里**零命中**。页面一直在，用户却点不到。
 *
 * 判据: **「代码在那儿」不等于「用户到得了」**。只看路由会以为它通了；
 * 判可达性必须两侧一起看。
 *
 * ⚠️ 本闸只管**顶层业务页**(路由 meta 带 title 且不是详情页/参数页)。
 * 详情页(`/xxx/:id`)、错误页、登录页天然不该进菜单，显式排除。
 */

function collectMenuPaths(nodes: any[], out: Set<string> = new Set()): Set<string> {
  for (const node of nodes ?? []) {
    if (node?.path) out.add(String(node.path));
    if (node?.children) collectMenuPaths(node.children, out);
  }
  return out;
}

function locateRouterFile(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    const candidate = join(dir, 'web-admin/src/router/index.ts');
    try {
      readFileSync(candidate, 'utf8');
      return candidate;
    } catch {
      const local = join(dir, 'src/router/index.ts');
      try {
        readFileSync(local, 'utf8');
        return local;
      } catch { /* keep walking up */ }
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('找不到 router/index.ts —— 本闸不静默跳过');
}

/** 天然不该进菜单的路由。**加白名单要写理由**，否则这道闸会被逐条豁免掉。 */
const NOT_IN_MENU_BY_DESIGN = new Set<string>([
  'dashboard',                      // 首页, menuConfig 里是 '/dashboard'
  'dashboard/production-progress',  // 从首页卡片进入, 非独立菜单项
  // 组件是 `widget-demo.vue` —— 开发用演示页, 刻意不给客户入口。
  // ⚠️ 我第一版把这条写成了组件名 `dashboard/widget-demo`, 而路由路径是
  //    `dashboard/widgets` —— 白名单没生效, 闸照样红。**白名单要按路由路径写。**
  'dashboard/widgets',
]);

describe('有路由的顶层业务页必须有菜单入口', () => {
  const src = readFileSync(locateRouterFile(), 'utf8');
  const menuPaths = collectMenuPaths(menuConfig as any[]);

  // 只取 dashboard/* 这一层 —— 本闸的成因就出在这里, 先把它守住,
  // 不一次性扩到全仓(那会一次红几十条, 变成噪音然后被整体豁免)。
  const routed = [...src.matchAll(/path:\s*'(dashboard\/[a-z0-9-]+)'/g)].map((m) => m[1]);

  it('至少解析到几条 dashboard 路由 —— 正则失效时先红, 而不是静默通过 0 条', () => {
    expect(routed.length).toBeGreaterThanOrEqual(3);
  });

  routed
    .filter((p) => !NOT_IN_MENU_BY_DESIGN.has(p))
    .forEach((p) => {
      it(`/${p} 有菜单入口`, () => {
        expect(
          menuPaths.has(`/${p}`),
          `路由 /${p} 没有菜单入口 —— 用户点不到它。`
            + '要么在 menuConfig 里加一项, 要么在 NOT_IN_MENU_BY_DESIGN 里写明为什么不该有。',
        ).toBe(true);
      });
    });
});
