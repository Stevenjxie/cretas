import { readFileSync } from 'node:fs';
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

  it('三个旧调用点都改指同一个跳转函数, 而不是被摘掉或留空', () => {
    // :105 「缺少生效 BOM」横幅按钮 —— 用户实际点过的那个
    expect(source).toMatch(/@click="goToBomManagement\(bomMissingProducts\[0\]\?\.id\)"/);
    // :128 生产不一致横幅按钮
    expect(source).toMatch(/@click="goToBomManagement\(bomProductionMismatchProducts\[0\]\?\.id\)"/);
    // :247 物料 cell 的 config-bom 事件
    expect(source).toMatch(/@config-bom="\(\) => goToBomManagement\(\)"/);
  });

  /** 从 start 截到下一个顶层声明为止, 近似取出整个函数体。 */
  function sliceFunction(text: string, start: number): string {
    const rest = text.slice(start + 1);
    const next = rest.search(/\n(?:async function |function |const |watch\(|onMounted\()/);
    return next === -1 ? text.slice(start) : text.slice(start, start + 1 + next);
  }

  it('goToBomManagement 真的调用 router.push 导航到 BOM 菜单页, 并带上 productTypeId', () => {
    const fnStart = source.indexOf('async function goToBomManagement');
    expect(fnStart).toBeGreaterThan(-1);
    const fn = source.slice(fnStart, fnStart + 1400);
    expect(fn).toMatch(/router\.push\(/);
    expect(fn).toMatch(/name:\s*'BomManagement'/);
    expect(fn).toMatch(/query:\s*\{\s*productTypeId:/);
  });

  it('包材编辑不再走抽屉 —— openPackagingEditor 打开的是新弹窗', () => {
    const fnStart = source.indexOf('function openPackagingEditor');
    expect(fnStart).toBeGreaterThan(-1);
    // 按函数边界取, 不用固定字符数: 之前写死 1500, 函数一变长断言就落到窗口外而变红,
    // 红的是取窗方式不是行为(2026-08-05 加 ensureDraft 时踩到)。
    const fn = sliceFunction(source, fnStart);
    expect(fn).not.toMatch(/openBomDrawer/);
    expect(fn).toMatch(/packagingDialogVisible\.value = true/);
  });

  it('BOM 菜单页组件(bom-unified/index.vue)与 BomContent 本体仍留在仓库里 —— 只下画布入口, 不删共享机器', () => {
    const bomUnifiedSource = readFileSync(
      resolve(__dirname, '../../../../production/bom-unified/index.vue'),
      'utf-8',
    );
    expect(bomUnifiedSource).toContain('BomContent');
    const bomIndexSource = readFileSync(
      resolve(__dirname, '../../../../production/bom/index.vue'),
      'utf-8',
    );
    // #1236 系列防呆: BOM 页要能从 ?productTypeId= 直接定位到画布传来的产品,
    // 这是 goToBomManagement 跳转能落到正确产品的前提 —— 不是本任务改的, 但必须仍然成立。
    expect(bomIndexSource).toContain('route.query.productTypeId');
  });
});
