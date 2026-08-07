import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf-8');

/**
 * Task 3 (2026-08-05 bom-canvas-phase3-2): 画布 BOM 抽屉真删除, 不是开关隐藏。
 *
 * 起因: owner 从画布「在右侧配置 BOM →」进去落到旧抽屉, 撞上一个调料保存不了的既有
 * 缺陷。抽屉三个调用方(缺 BOM 横幅按钮 / 生产不一致横幅按钮 / 物料 cell 的
 * config-bom 事件)都不是装饰 —— 用户点它们是因为真的需要去配置 BOM, 删掉抽屉
 * 却不给出口就是把"有帮助的提示"变成"死路", 违反防呆规则 5。所以三个调用点必须
 * 断言"真的导航去了某处", 不能只断言 openBomDrawer 消失(那样按钮被留成哑的也会通过)。
 */
describe('画布 BOM 抽屉已下线(真删除, 不是开关)', () => {
  it('画布不再挂载 BOM 抽屉(el-drawer + BomUnifiedPanel)', () => {
    expect(source).not.toMatch(/BOM \/ 配方配置/);
    expect(source).not.toContain('BomUnifiedPanel');
    expect(source).not.toContain('bomDrawerVisible');
    expect(source).not.toContain('openBomDrawer');
  });

  it('懒加载 wiring(bomUnifiedPanelLoader 的 import 与预加载调用)已移除', () => {
    expect(source).not.toContain('bomUnifiedPanelLoader');
    expect(source).not.toContain('preloadBomUnifiedPanel');
    expect(source).not.toContain('scheduleBomUnifiedPanelPreload');
  });

  /**
   * 三次收敛的最终状态(2026-08-07 方案 B 定稿):
   *   Phase 3-2 (08-05) 下抽屉 → 三个调用点改指 goToBomManagement(跳 BOM 菜单页)
   *   同日          → 「缺生效 BOM」横幅率先改成画布内 cell 引导
   *   本次   (08-07) → 剩下两个调用点也关死, goToBomManagement 整个删除
   *
   * ⛔ 原断言的**意图必须保留**: 「删掉出口却不给替代 = 把有帮助的提示变成死路」。
   * 所以下面不是简单地断言「跳转消失」——每一处都要求**画布内的替代路径真的在**。
   * 只断言 not.toMatch 的话, 按钮被删成一片空白也会通过。
   */
  it('画布内不再有任何跳去 BOM 菜单页的通道', () => {
    // ⚠️ 断言真实构造, 不要断言「源码里不出现这个词」——
    //    解释性注释里写下函数名是正常的, 用 toContain 会把注释也算成「还在用」。
    expect(source).not.toMatch(/(async\s+)?function goToBomManagement/);
    expect(source).not.toMatch(/goToBomManagement\s*\(/);
    expect(source).not.toMatch(/name:\s*'BomManagement'/);
    expect(source).not.toMatch(/@config-bom=/);
  });

  it('生产不一致横幅: 去掉「查看 BOM →」后必须说清去哪改', () => {
    const at = source.indexOf('的生效 BOM 与当前已启用 Workflow 不一致');
    expect(at).toBeGreaterThan(-1);
    const block = source.slice(at, at + 900);
    // 同上: 只禁按钮, 不禁注释里提到这四个字
    expect(block).not.toMatch(/>\s*查看 BOM/);
    // ⛔ 替代路径: 必须指向 cell, 否则用户读完横幅不知道下一步
    expect(block).toMatch(/辅料 \/ 包材 cell/);
  });

  it('物料节点不再声明 configBom 事件 —— 事件线和按钮要一起走, 不留哑事件', () => {
    const nodeSource = readFileSync(
      resolve(__dirname, '../WorkflowMaterialNode.vue'),
      'utf-8',
    );
    expect(nodeSource).not.toContain('configBom');
  });

  /** 从 start 截到下一个顶层声明为止, 近似取出整个函数体。 */
  function sliceFunction(text: string, start: number): string {
    const rest = text.slice(start + 1);
    const next = rest.search(/\n(?:async function |function |const |watch\(|onMounted\()/);
    return next === -1 ? text.slice(start) : text.slice(start, start + 1 + next);
  }

  it('包材编辑不再走抽屉 —— openPackagingEditor 打开的是新弹窗', () => {
    const fnStart = source.indexOf('function openPackagingEditor');
    expect(fnStart).toBeGreaterThan(-1);
    // 按函数边界取, 不用固定字符数: 之前写死 1500, 函数一变长断言就落到窗口外而变红,
    // 红的是取窗方式不是行为(2026-08-05 加 ensureDraft 时踩到)。
    const fn = sliceFunction(source, fnStart);
    expect(fn).not.toMatch(/openBomDrawer/);
    expect(fn).toMatch(/packagingDialogVisible\.value = true/);
  });

  /**
   * 2026-08-07 阶段 5 已执行(见 docs/superpowers/specs/2026-08-07-canvas-is-bom-design.md):
   * 上面那条原本断言「页面仍在仓库里」, 现在翻转成断言它们真的没了。
   *
   * 原断言里那条 `route.query.productTypeId` 防呆(#1236 系列)不能就这么消失 ——
   * 它保的是「从画布跳过去要落到**同一个产品**上」。方案 B 之后不再有跳转,
   * 因为配置就在画布自己身上; 这条防呆的继承者是「画布自己认得 route 上的产品」,
   * 由 WorkflowPublishIntegration.source.spec.ts 的 workflow-product-loading 一族守。
   * 这里只钉住页面确实删干净了。
   */
  it('阶段 5: BOM 页组件已从仓库删除', () => {
    for (const rel of [
      '../../../../production/bom/index.vue',
      '../../../../production/bom/tree.vue',
      '../../../../production/bom-unified/index.vue',
    ]) {
      expect(existsSync(resolve(__dirname, rel)), `${rel} 应已删除`).toBe(false);
    }
  });
});
