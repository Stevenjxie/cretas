import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const mainSource = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8');

/**
 * 盯调用点的用例。
 *
 * ariaDisabledSync 是个「装了才生效」的 app 级 plugin —— 实现全绿而 main.ts 里
 * 没人 `app.use` 它, 缺陷照旧存在, 且所有实现单测仍然全绿。本仓踩过同一形状
 * (`ByproductBatchMaterializer` 6 条单测在接线被短路后仍全绿, 而「没人调」正是
 * 当时在修的缺陷)。
 *
 * 所以这条只断言一件事: main.ts 真的装了它。
 */
describe('ariaDisabledSync 接线', () => {
  it('main.ts 导入了 plugins/ariaDisabledSync', () => {
    expect(mainSource).toMatch(/import\(['"]\.\/plugins\/ariaDisabledSync['"]\)/);
  });

  it('main.ts 真的 app.use 了它 (只 import 不装等于没修)', () => {
    expect(mainSource).toMatch(/app\.use\(\s*ariaDisabledSync\.default\s*\)/);
  });

  it('装在 Element Plus 注册之后 (它依赖 el-input-number 已可用)', () => {
    const elementPlusUse = mainSource.indexOf('app.use(ElementPlus.default');
    const ariaUse = mainSource.search(/app\.use\(\s*ariaDisabledSync\.default\s*\)/);

    expect(elementPlusUse).toBeGreaterThan(-1);
    expect(ariaUse).toBeGreaterThan(elementPlusUse);
  });
});
