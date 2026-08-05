import { describe, expect, it } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * BOM 配置已并入画布(辅料/包材/副产 cell + 冷启动 ensureDraft + 草稿生效横幅),
 * 菜单入口于 2026-08-05 摘除。
 *
 * ⛔ 只下入口, 不删机器 —— 与 Phase 3-2 下抽屉同一条口径:
 * 路由与页面组件保留, 既不让既有深链 404, 也留出「BOM 与 Workflow 不一致」
 * 诊断横幅的查看通道。断言两侧都钉住, 否则「摘入口」很容易被误做成「删页面」。
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

describe('只下入口, 不删机器', () => {
  it('路由仍保留 BomManagement —— 既有深链不能 404', () => {
    expect(ROUTER).toMatch(/name:\s*'BomManagement'/);
  });

  it('BOM 页组件本体仍在仓库里', () => {
    expect(existsSync(resolve(__dirname, '../../../../production/bom/index.vue'))).toBe(true);
  });

  it('「BOM 与 Workflow 不一致」诊断横幅保留查看通道 —— 画布没有替代品', () => {
    expect(EDITOR).toMatch(/bomProductionMismatchProducts\[0\]\?\.id/);
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
