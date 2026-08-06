import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { PERMISSION_MATRIX } from './permission';

/**
 * fallback 矩阵不许与 L1 权威矛盾。
 *
 * 2026-08-06 实测 prod: `restaurant_manager` 的 `restaurantHr` 在 L1 是 '-',
 * 而 fallback 写着 'rw'; `sales_manager` 的 `restaurant` 在 L1 是 'rw',
 * fallback 写着 '-'(会把整个餐饮板块关掉)。一共 7 格相反, 且**永远不会红** ——
 * 因为 `permission.restaurant-departments.spec.ts` 把三个权限 API 全 mock 成空,
 * 断言的正是 fallback 自己; 而 prod 上 L1 加载成功后 fallback 整份被跳过
 * (permission.ts 的 source 选择是**整体二选一**, 不是逐键)。
 *
 * 两边各说各话, 没有任何测试把它们对起来 —— 这条就是那座桥。
 *
 * ⚠️ fallback 不是死代码: 它在 DB 权限**竞态/离线/接口失败**的窗口里是真实生效值
 * (permission.ts 里那句 "fallback to hardcoded for race/offline/error")。
 * 所以「反正生产走 L1」不能作为放着不管的理由。
 *
 * 权威取的是**同仓的 Flyway 迁移**而不是手抄的 prod 快照 —— 手抄的快照会自己过期,
 * 而且没人知道该什么时候刷新。到底是哪一份由下面的标记查找决定, 这里刻意不写死版本号
 * (写死过一次, 当天就过期了)。
 */

const FLYWAY_REL = 'backend/java/cretas-api/src/main/resources/db/flyway';

/**
 * 权威不是某个写死的文件名, 而是**带标记的最高版本迁移**。
 *
 * 一开始我写死了 V20261029_52, 然后当天就加了新迁移改其中一格 ——
 * 写死文件名的守卫会继续对着旧值比, 而且是**绿着**比错的。所以改成按标记找:
 * 迁移里写 `L1-AUTHORITY: restaurant-department-matrix`, 谁版本高谁是权威。
 *
 * ⚠️ 代价(有意): 改这张表必须**完整重述 20 行**并带上标记, 不能只写一条 UPDATE。
 * 这一点写在迁移文件的注释里。
 *
 * 不用 `new URL(..., import.meta.url)` —— vitest 下 `import.meta.url` 不保证是
 * file: scheme(实测报 "The URL must be of scheme file"); 也不写死相对层数
 * (测试挪个目录就悄悄失效)。找不到就抛, 不静默跳过。
 */
const AUTHORITY_MARKER = 'L1-AUTHORITY: restaurant-department-matrix';

function locateFlywayDir(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    const candidate = join(dir, FLYWAY_REL);
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(`找不到 flyway 目录: ${FLYWAY_REL} (从 ${process.cwd()} 向上找了 6 层)`);
}

function locateAuthority(): string {
  const dir = locateFlywayDir();
  const carriers = readdirSync(dir)
    .filter((f: string) => f.endsWith('.sql'))
    .filter((f: string) => readFileSync(join(dir, f), 'utf8').includes(AUTHORITY_MARKER))
    .sort();
  if (carriers.length === 0) {
    throw new Error(`没有迁移带 "${AUTHORITY_MARKER}" 标记 —— 权威丢了, 不是「没问题」`);
  }
  return join(dir, carriers[carriers.length - 1]);
}

const SEED_PATH = locateAuthority();

/** ('role_code', 'module_code', 'level') —— 只认这个三元组形状。 */
const ROW_RE = /\(\s*'([a-z_]+)'\s*,\s*'(\w+)'\s*,\s*'(rw|r|w|-)'\s*\)/g;

function parseSeed(): Array<{ role: string; module: string; level: string }> {
  const sql = readFileSync(SEED_PATH, 'utf8');
  // 注释里出现的字面量不算权威, 只有会执行的 VALUES 行算。
  const executable = sql
    .split('\n')
    .filter((line: string) => !line.trimStart().startsWith('--'))
    .join('\n');
  return [...executable.matchAll(ROW_RE)].map(([, role, module, level]) => ({ role, module, level }));
}

describe('餐饮部门 fallback 矩阵 vs L1 权威 (带标记的最高版本迁移)', () => {
  const rows = parseSeed();

  it('解析到 seed 的全部 20 行 —— 迁移被改名/移动/重写时这条先红, 而不是静默跳过 0 行', () => {
    expect(rows).toHaveLength(20);
    expect(new Set(rows.map((r) => r.role))).toEqual(
      new Set(['restaurant_manager', 'sales_manager', 'finance_manager', 'hr_admin']),
    );
  });

  // 用显式 forEach 而不是 it.each('$role.$module', ...) —— 后者的 $ 插值在这里
  // 渲染成 "undefined 应为 '-'", CI 里看不出是哪一格红的。
  rows.forEach(({ role, module, level }) => {
    it(`${role}.${module} 应为 '${level}'`, () => {
      const fallback = PERMISSION_MATRIX[role] as Record<string, string> | undefined;
      expect(fallback, `PERMISSION_MATRIX 里没有角色 ${role}`).toBeDefined();
      expect(
        fallback![module],
        `fallback 的 ${role}.${module} 与 L1 权威不一致 —— `
          + 'DB 权限加载失败/未完成的窗口里用户拿到的就是这个值',
      ).toBe(level);
    });
  });
});
