/**
 * 仓储侧「点不动」三处死路的回归闸 —— 2026-08-17 真机走查实测。
 *
 * 三处都不是「功能没做」，是**界面在说一件它不做的事**：
 *   A. 「新建入库」按钮与「待收货任务」按钮 onPress 是同一行代码；
 *   B. 「手动输入」用 iOS 独有的 Alert.prompt，工人的 Android 上只会弹「当前平台不支持」；
 *   C. 「调拨」入口指向一个原型屏：库位写死、单位硬编码 kg、
 *      提交只改批次的 storageLocation，用户输入的数量完全不参与。
 *
 * ⚠️ 口径说明（这是代理判据，已标出来）：
 * 本仓 `unit/screens/*` 的既有惯例是**读源码做契约**，本文件沿用。
 * 它看不见运行时行为，只能钉住「源码里那个形状还在不在」。
 * A 那条特意写成**结构性**判据（比较导航目标有没有重复），不是匹配一句魔法字符串 ——
 * 因为真正的缺陷类型是「两个按钮跳同一个地方，其中一个的字是旧的」，
 * 而不是「某个字符串出现了」。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

const inboundList = read('screens/warehouse/inbound/WHInboundListScreen.tsx');
const scanScreen = read('screens/warehouse/shared/WHScanOperationScreen.tsx');
const inventoryList = read('screens/warehouse/inventory/WHInventoryListScreen.tsx');
const inventoryDetail = read('screens/warehouse/inventory/WHInventoryDetailScreen.tsx');
const inventoryNavigator = read('navigation/warehouse/WHInventoryStackNavigator.tsx');
const navigationTypes = read('types/navigation.ts');
const warehouseI18nMigration = read('../scripts/migrate-warehouse-i18n.js');

/** 剥掉行注释与块注释 —— 否则注释里提到的 navigate("X") 会被数进来。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
}

/** 取出源码里所有 `navigation.navigate("X")` / `CommonActions.navigate("X")` 的目标名。 */
function navTargets(src: string): string[] {
  const code = stripComments(src);
  const out: string[] = [];
  const re = /(?:navigation\.navigate|CommonActions\.navigate)\(\s*["']([A-Za-z0-9_]+)["']/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(code)) !== null) {
    const target = m[1];
    if (target) out.push(target);
  }
  return out;
}

describe('仓储入库列表: 快捷操作里不许有两个按钮跳同一个屏', () => {
  it('WHPurchaseReceiveList 只被跳一次', () => {
    const targets = navTargets(inboundList);

    // 🔴 阳性对照: 先证明这把尺子真的量到了东西。
    //    没有它, 「没有重复」和「一个 navigate 都没扫到」长得一模一样。
    expect(targets.length).toBeGreaterThan(0);
    expect(targets).toContain('WHPurchaseReceiveList');

    const dup = targets.filter((t) => t === 'WHPurchaseReceiveList');
    expect(dup).toHaveLength(1);
  });

  it('快捷操作区里没有两块跳同一处的标签牌', () => {
    // ⚠️ 口径收窄到 actionBar 那一段, 不扫全文件。
    //    第一版写的是「全文件不许有重复 navigate 目标」, 实测**误报** ——
    //    `WHInboundDetail` 从卡片点击和「查看详情」各跳一次, 那是合理的。
    //    一道会误报的闸最终会被关掉, 那时它覆盖率归零。
    const code = stripComments(inboundList);
    const start = code.indexOf('styles.actionBar');
    expect(start).toBeGreaterThan(0);        // 阳性对照: 真的定位到了那一段
    const region = code.slice(start, code.indexOf('搜索栏') > 0 ? code.indexOf('搜索栏') : start + 3000);

    const targets = navTargets(region);
    expect(targets.length).toBeGreaterThan(1);   // 阳性对照: 这一段里确实有多个按钮

    const counts = new Map<string, number>();
    targets.forEach((t) => counts.set(t, (counts.get(t) ?? 0) + 1));
    const repeated = [...counts.entries()].filter(([, n]) => n > 1).map(([t]) => t);
    expect(repeated).toEqual([]);
  });
});

describe('扫码作业: 手动输入必须三端可用', () => {
  it('不再依赖 iOS 独有的 Alert.prompt', () => {
    const code = stripComments(scanScreen);
    expect(code).not.toContain('Alert.prompt');
    expect(code).not.toContain('AlertWithPrompt');
    // 那句只有 Android/Web 用户才看得到的话必须消失
    expect(code).not.toContain('当前平台不支持手动输入弹窗');
  });

  it('改用组件自己的受控弹窗, 且空值事先拦住', () => {
    const code = stripComments(scanScreen);
    // 阳性对照: 弹窗三件套都在
    expect(code).toContain('wh-scan-manual-dialog');
    expect(code).toContain('wh-scan-manual-input');
    expect(code).toContain('wh-scan-manual-confirm');
    // 空值不提交 —— 事先拦住, 不是提交后报错
    expect(code).toContain('disabled={!manualText.trim()}');
    expect(code).toMatch(/if \(!text\) return;/);
  });

  it('屏幕上那句「如无法扫码, 可点击"手动输入"」仍然成立 (说的和做的是同一件事)', () => {
    const code = stripComments(scanScreen);
    // 提示还在
    expect(code).toContain('如无法扫码');
    // 且它指向的那个按钮确实会打开弹窗
    expect(code).toMatch(/onPress=\{handleManualInput\}/);
    expect(code).toMatch(/setManualVisible\(true\)/);
  });
});

describe('库存: 不许把用户送进只改 storageLocation 的原型调拨屏', () => {
  it('库存列表的快捷操作里没有 WHInventoryTransfer', () => {
    const code = stripComments(inventoryList);
    // 🔴 阳性对照: 快捷操作数组本身还在, 且别的入口没被误删
    expect(code).toContain('const quickActions: QuickAction[]');
    expect(code).toContain('WHInventoryCheck');
    expect(code).toContain('WHLocationManage');

    // ⚠️ 只看 quickActions 里 `screen:` 的取值 + 真实 navigate 目标, ⛔ 不做全文件子串匹配 ——
    //    第一版那么写会把别处**提到**这个名字的字符串也算进来(实测踩到)。
    const screens = [...code.matchAll(/screen:\s*["']([A-Za-z0-9_]+)["']/g)]
      .map((m) => m[1])
      .filter((s): s is string => Boolean(s));
    expect(screens.length).toBeGreaterThan(3);   // 阳性对照: 真的解析到了这个数组
    expect(screens).not.toContain('WHInventoryTransfer');
    expect(navTargets(inventoryList)).not.toContain('WHInventoryTransfer');
  });

  it('库存详情页也没有跳它的按钮', () => {
    const code = stripComments(inventoryDetail);
    // 阳性对照: 顶部操作区还在, 过期处理那个按钮没被误删
    expect(code).toContain('WHExpireHandle');

    expect(navTargets(inventoryDetail)).not.toContain('WHInventoryTransfer');
  });

  it('原型屏、route、导航类型和迁移脚本残留均已退役', () => {
    const retiredScreen = path.resolve(
      sourceRoot,
      'screens/warehouse/inventory/WHInventoryTransferScreen.tsx',
    );

    expect(fs.existsSync(retiredScreen)).toBe(false);
    expect(stripComments(inventoryNavigator)).not.toContain('WHInventoryTransfer');
    expect(stripComments(navigationTypes)).not.toMatch(/\bWHInventoryTransfer\s*:/);
    expect(stripComments(warehouseI18nMigration)).not.toContain('WHInventoryTransferScreen');
  });
});
