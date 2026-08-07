import { describe, expect, it } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * BOM 配置已并入画布(辅料/包材/副产 cell + 冷启动 ensureDraft + 草稿生效横幅),
 * 菜单入口于 2026-08-05 摘除。
 *
 * 2026-08-07 阶段 5(方案 B「画布即 BOM」)推翻了原来的「只下入口, 不删机器」:
 * 页面留着的两条理由都已消失 —— 诊断横幅改指画布内的 cell(不再需要查看通道),
 * 而留着的旧页面会持续污染后续分析(owner 原话: 代码还在就会被当成还在用)。
 * 所以页面与组件真删。
 *
 * ⛔ 但**原断言的意图必须保留**: 「不能让既有深链 404」。删路由会让书签落空, 所以
 * 三条老地址改成 redirect 到画布。下面同时钉住两侧 —— 组件真的没了 **且** 老地址
 * 真的还能落地。只断言「文件不在了」的话, 深链 404 这个回归没有任何东西挡得住。
 */
const read = (rel: string) => readFileSync(resolve(__dirname, rel), 'utf-8');

const MENU = read('../../../../../components/layout/menuConfig.ts');
const ROUTER = read('../../../../../router/index.ts');
const EDITOR = read('../ProductProcessWorkflowEditor.vue');

describe('旧 BOM 菜单入口已摘', () => {
  it('菜单不再列出 BOM/配方维护', () => {
    expect(MENU).not.toMatch(/title:\s*'BOM\/配方维护'/);
  });

  it('菜单不再列出 BOM版本管理', () => {
    expect(MENU).not.toMatch(/title:\s*'BOM版本管理'/);
  });

  // ⚠️ 断言必须钉精确路径: /production/bom/ecns(工程变更通知) 与
  // /production/bom-achievement(达成率分析) 是另外的功能, 前缀匹配会把它们一起误摘。
  it('菜单里不再有 BOM 配方页本身的条目(前缀相同的其它功能不受影响)', () => {
    expect(MENU).not.toMatch(/path:\s*'\/production\/bom'/);
    expect(MENU).not.toMatch(/path:\s*'\/production\/bom\/versions'/);
    expect(MENU).toMatch(/path:\s*'\/production\/bom\/ecns'/);
  });
});

describe('阶段 5: 页面真删, 但老地址仍然落地', () => {
  it('BOM 页组件本体已从仓库删除', () => {
    // owner 拍板: 下架的页面直接删, 留着会污染后续分析。
    expect(existsSync(resolve(__dirname, '../../../../production/bom/index.vue'))).toBe(false);
    expect(existsSync(resolve(__dirname, '../../../../production/bom/tree.vue'))).toBe(false);
    expect(existsSync(resolve(__dirname, '../../../../production/bom-unified/index.vue'))).toBe(
      false,
    );
  });

  it('路由不再指向已删的 BOM 页组件', () => {
    expect(ROUTER).not.toMatch(/views\/production\/bom\/index\.vue/);
    expect(ROUTER).not.toMatch(/views\/production\/bom\/tree\.vue/);
    expect(ROUTER).not.toMatch(/views\/production\/bom-unified/);
  });

  // ⛔ 这条是原「既有深链不能 404」的继承者, 不是新加的。删页面时最容易漏掉的就是它:
  //    菜单摘了不代表用户的书签也没了。
  it('三条老地址仍可访问 —— 重定向到画布, 而不是 404', () => {
    for (const path of ["path: 'bom'", "path: 'bom/versions'", "path: 'bom/tree'"]) {
      const at = ROUTER.indexOf(path);
      expect(at, `${path} 应仍在路由表里(作为 redirect)`).toBeGreaterThan(-1);
      expect(
        ROUTER.slice(at, at + 260),
        `${path} 必须重定向到画布, 否则老书签直接 404`,
      ).toMatch(/redirect:\s*'\/system\/product-processes'/);
    }
  });

  it('ECN 路由不受牵连 —— 它是另一件事', () => {
    expect(ROUTER).toMatch(/path:\s*'bom\/ecns'/);
    expect(ROUTER).toMatch(/views\/production\/bom\/EcnList\.vue/);
  });

  /**
   * 2026-08-07 推翻: 原来这条要求诊断横幅保留跳 BOM 页的通道, 理由是「画布没有替代品」。
   * 方案 B 定稿后替代品有了 —— 用量与锅序就在工序的辅料 / 包材 cell 上, 横幅改成指向它。
   * 断言跟着翻转, 但**不许翻成什么都不断言**: 仍要钉住替代路径真的在。
   */
  it('诊断横幅不再跳 BOM 页, 改为指向画布内的 cell', () => {
    expect(EDITOR).not.toMatch(/goToBomManagement\s*\(/);
    const at = EDITOR.indexOf('的生效 BOM 与当前已启用 Workflow 不一致');
    expect(at).toBeGreaterThan(-1);
    expect(EDITOR.slice(at, at + 900)).toMatch(/辅料 \/ 包材 cell/);
  });
});

describe('画布内已闭环的入口不再把用户支走', () => {
  it('缺 BOM 横幅不再有「去 BOM 配置」按钮 —— 冷启动直接点 cell 即可', () => {
    const at = EDITOR.indexOf('暂未读取到生效 BOM');
    expect(at).toBeGreaterThan(-1);
    const block = EDITOR.slice(at, at + 700);
    expect(block).not.toMatch(/去 BOM 配置/);
    // 换成的引导必须真的指向 cell, 否则等于把话说了一半
    expect(block).toMatch(/辅料 \/ 包材 \/ 副产 cell/);
  });

  it('草稿横幅不再有「去 BOM 页查看」 —— 生效按钮就在同一行', () => {
    const at = EDITOR.indexOf('bom-draft-notice');
    expect(at).toBeGreaterThan(-1);
    const block = EDITOR.slice(at, at + 1200);
    expect(block).not.toMatch(/去 BOM 页查看/);
    expect(block).toMatch(/bom-draft-activate/);
  });
});
