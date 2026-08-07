/**
 * ⑤ 非对话出口 / ⑥ 动态办公室：发现层的「今天先做」入口。
 *
 * 🔴 这两道守卫是**不同的失败模式**，必须各自能独立红：
 *   1) 没权限 → 渲染出来就是个点进去 403 的入口；
 *   2) 后端登记的路径在前端**根本不存在** → 点了白屏。
 *      本轮实测到三个「路由了但够不着」的页面，这不是假想的风险。
 *
 * ⚠️ 这里量的是过滤逻辑本身，不是组件挂载 —— 组件那一层的 spec 把权限 API 全
 * mock 成空过（见 memory：断言的是 fallback 矩阵，与 prod 相反且永不变红）。
 */
import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import type { RestaurantFindingNextStep } from '@/api/restaurant';

/** 与 DashboardRestaurant.vue 里 nextSteps computed 逐字同构的过滤器。 */
function filterNextSteps(
  steps: RestaurantFindingNextStep[],
  canAccess: (m: string) => boolean,
  resolves: (t: string) => boolean,
): RestaurantFindingNextStep[] {
  return (steps ?? []).filter((step) => {
    if (!step?.target || !step?.module) return false;
    if (!canAccess(step.module)) return false;
    return resolves(step.target);
  });
}

const STEP: RestaurantFindingNextStep = {
  code: 'DISH_PUZZLE_HIGH_MARGIN_LOW_VOLUME',
  subjectName: '白灼虾',
  label: '去核对配方与成本',
  target: '/restaurant/recipes',
  module: 'restaurantOps',
};

const ALLOW = () => true;
const RESOLVES = () => true;

describe('发现层「今天先做」入口的两道守卫', () => {
  it('两道都过 → 渲染', () => {
    expect(filterNextSteps([STEP], ALLOW, RESOLVES)).toHaveLength(1);
  });

  it('🔴 没有该模块权限 → 不渲染（否则是个点进去 403 的入口）', () => {
    expect(filterNextSteps([STEP], (m) => m !== 'restaurantOps', RESOLVES)).toEqual([]);
  });

  it('🔴 路径在前端解析不出来 → 不渲染（点了白屏，比没按钮更糟）', () => {
    expect(filterNextSteps([STEP], ALLOW, () => false)).toEqual([]);
  });

  it('字段残缺一律不渲染 —— 宁可不给入口', () => {
    const broken = [
      { ...STEP, target: '' },
      { ...STEP, module: '' },
    ] as RestaurantFindingNextStep[];
    expect(filterNextSteps(broken, ALLOW, RESOLVES)).toEqual([]);
    expect(filterNextSteps(undefined as never, ALLOW, RESOLVES)).toEqual([]);
  });
});

describe('后端登记的落点真的打得开', () => {
  /**
   * 🔴 这条才是对着「够不着」量的。
   *
   * ⛔ 路径来源是后端 `FindingNavigation`；这里列出来是**契约镜像**，加落点要同步
   * 加一行。硬编码是有意的：让它在有人加了后端落点却没有对应前端页面时红掉。
   *
   * ⚠️ 不 import 路由模块 —— 那会把整棵 store 树拖进来。按文本解析 + 实地查组件
   * 文件是否存在，因为「路由声明齐全但组件文件不在」正是本轮实测到的失败形状。
   */
  const BACKEND_TARGETS = ['/restaurant/recipes', '/restaurant/wastage'];

  function locateRouterFile(): string {
    let dir = resolve(process.cwd());
    for (let i = 0; i < 6; i += 1) {
      for (const candidate of [join(dir, 'web-admin/src/router/index.ts'), join(dir, 'src/router/index.ts')]) {
        try {
          readFileSync(candidate, 'utf8');
          return candidate;
        } catch { /* keep walking up */ }
      }
      const parent = dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
    throw new Error('找不到 router/index.ts —— 本闸不静默跳过');
  }

  const routerFile = locateRouterFile();
  const src = readFileSync(routerFile, 'utf8');
  const srcRoot = dirname(dirname(routerFile));

  BACKEND_TARGETS.forEach((target) => {
    it(`${target} 有路由且组件文件真的在`, () => {
      const child = target.split('/').filter(Boolean).pop()!;
      // path: 'recipes' 之后紧跟的 component 行 —— 只看这一段, 不跨路由块。
      // ⚠️ 必须用 String.raw: 普通模板字面量会把 `\s` 吃成 `s`, 正则永不匹配。
      const block = new RegExp(
        String.raw`path:\s*'${child}'[\s\S]{0,240}?component:\s*\(\)\s*=>\s*import\('@/(views/[^']+)'\)`,
      );
      const hit = src.match(block);
      expect(hit, `路由表里找不到 ${target} 对应的 path/component —— 后端登记了一个打不开的落点`)
        .toBeTruthy();

      const file = join(srcRoot, hit![1]);
      expect(
        existsSync(file),
        `${target} 的路由指向 ${hit![1]}, 但这个文件不存在 —— 点进去是白屏`,
      ).toBe(true);
    });
  });
});
