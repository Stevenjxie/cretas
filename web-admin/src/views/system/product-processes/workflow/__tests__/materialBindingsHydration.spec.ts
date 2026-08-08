import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(process.cwd(), 'src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue'),
  'utf-8',
);

/**
 * 阶段 3（版本合一）第 1 步：投入明细进工艺定义。
 *
 * ## 这一步最危险的失败模式，以及闸盯的就是它
 * hydration 发生在**加载期**。如果它走 `mutate()`，用户只是打开一张图就变成
 * 「有未保存改动」，保存后造出一个内容与旧版等价的新工艺版本 —— 版本线会因为
 * 「看了一眼」而增长。方案 B 要的是「**改**画布才产生新版本」，正好相反。
 *
 * 所以这里钉三件事：
 *   1. hydration 不走 mutate（不置 dirty、不 bump editSeq）
 *   2. hydration 是幂等的（值没变就不写）
 *   3. 权威数值来自 binding 的原始字段，不是展示用的 dosageText
 */
describe('materialBindings hydration 不得把「打开」变成「改动」', () => {
  const fn = (() => {
    const at = EDITOR.indexOf('function hydrateMaterialBindingsIntoDefinition');
    expect(at, 'hydration 函数应存在').toBeGreaterThan(-1);
    const end = EDITOR.indexOf('function serializeFlowNode', at);
    return EDITOR.slice(at, end);
  })();

  it('hydration 不调用 mutate —— mutate 会无条件置 dirty, 加载期用不得', () => {
    // ⛔ 断言语法形态而不是"字符串不出现": 这个函数的注释里就写着 mutate() 三个字
    //    （解释它为什么不能用），用 not.toContain('mutate') 会被注释打红。
    expect(fn).not.toMatch(/^\s*mutate\(/m);
    expect(fn).not.toMatch(/[^.\w]mutate\(\s*\(\)\s*=>/);
  });

  /**
   * 🔴 这条断言原来写的是「hydration 一律不置 dirty」，**它测错了东西**。
   *
   * 那条闸绿着，而「改克数产生新工艺版本」在用户实际路径上是断的：
   * 用户改完辅料 → 写 BOM 表 → 重载 → hydration 灌新值 → 图不 dirty →
   * 「保存草稿」仍是灰的 → **永远不会产生新版本**。
   * 数据进了定义、revisionHash 也覆盖它，中间却没人把图标记成改动。
   *
   * 判据：**「不许发生」的断言要限定条件**。无条件的 not.toMatch 会把
   * 「该发生的那一半」一起挡住，而且挡得悄无声息 —— 闸是绿的。
   */
  it('加载期不置 dirty —— 打开一张图不算改动', () => {
    const guarded = fn.slice(fn.indexOf('if (options.afterUserEdit)'));
    const beforeGuard = fn.slice(0, fn.indexOf('if (options.afterUserEdit)'));
    expect(beforeGuard, '守卫之前不许有任何置 dirty').not.toMatch(/dirty\.value\s*=/);
    expect(beforeGuard, '守卫之前不许 bump editSeq').not.toMatch(/editSeq\s*\+=/);
    expect(guarded, '置 dirty 必须在 afterUserEdit 守卫之内').toMatch(/dirty\.value\s*=\s*true/);
  });

  it('用户改完辅料之后必须置 dirty —— 否则新版本永远产生不了', () => {
    expect(fn).toMatch(/if \(options\.afterUserEdit\)/);
    expect(fn).toMatch(/dirty\.value = true/);
    expect(fn).toMatch(/editSeq \+= 1/);
  });

  it('值没变就不写 —— 幂等，避免每次加载都制造响应式改动', () => {
    expect(fn).toMatch(/JSON\.stringify\(current\) === JSON\.stringify\(next\)/);
    expect(fn).toMatch(/return;/);
  });

  it('只在真的变了时才刷新派生视图和置 dirty', () => {
    // 一个字都没变时提前 return —— 否则「重载了一次但内容一样」也会被当成用户改动,
    // 用户会莫名其妙看到「有未保存改动」。
    expect(fn).toMatch(/if \(!changed\) return;/);
    expect(fn).toContain('refreshBomOverlay()');
    // 置 dirty 必须在这个 return 之后 —— 顺序错了守卫就形同虚设
    expect(fn.indexOf('if (!changed) return;'))
      .toBeLessThan(fn.indexOf('dirty.value = true'));
  });
});

/**
 * 🔴 2026-08-08 补的口径漏洞：包材也要进定义。
 *
 * 真机实测发现：改**辅料**克数 → 版本跳；改**包材**用量 → BOM 表变了，版本不跳。
 * 因为阶段 3-1 只把辅料/调料搬进了工艺定义，包材没搬 —— 于是
 * 「画布是什么样 BOM 就是什么样，只有一个版本号」在包材这一维上不成立。
 */
describe('包材同样进工艺定义（否则改包材不产生新版本）', () => {
  const fn = (() => {
    const at = EDITOR.indexOf('function hydrateMaterialBindingsIntoDefinition');
    return EDITOR.slice(at, EDITOR.indexOf('function serializeFlowNode', at));
  })();

  it('hydrate 包材到终端产出节点', () => {
    expect(fn).toContain('packagingBindings');
    expect(fn).toMatch(/node\.type !== 'material'/);
    expect(fn).toMatch(/data\.packagingBindings = next/);
  });

  it('包材与辅料共用同一套 dirty 口径 —— 同一个 changed 标志', () => {
    // ⛔ 不许给包材单开一条不置 dirty 的路径, 那就是把漏洞换个地方留着。
    const packAt = fn.indexOf('data.packagingBindings = next');
    const dirtyAt = fn.indexOf('dirty.value = true');
    expect(packAt).toBeGreaterThan(-1);
    expect(packAt).toBeLessThan(dirtyAt);
    expect(fn.slice(packAt, packAt + 80)).toMatch(/changed = true/);
  });

  it('权威数值取 standardQuantity，不取展示串', () => {
    const at = EDITOR.indexOf('packagingBindingsByOutput[target.nodeId] = packagingItems');
    expect(at).toBeGreaterThan(-1);
    const block = EDITOR.slice(at, at + 600);
    expect(block).toContain('standardQuantity: item.standardQuantity');
    expect(block).not.toContain('dosageText');
    expect(block).not.toContain('naturalHint');
  });
});

describe('权威数值来自原始字段，不是展示字符串', () => {
  it('materialBindings 读 dosagePerKgG / subsequentPotRatio，不读 dosageText', () => {
    const at = EDITOR.indexOf('materialBindingsByProcess[nodeId] = process.bindings');
    expect(at, '收集权威字段的代码应存在').toBeGreaterThan(-1);
    const block = EDITOR.slice(at, at + 800);
    expect(block).toContain('dosagePerKgG: binding.dosagePerKgG');
    expect(block).toContain('subsequentPotRatio: binding.subsequentPotRatio');
    // ⛔ dosageText 是拼好的展示串（"2 g/kg"），数值已经丢了。
    //    拿它当数据就是把展示层的格式化结果反向当成权威 —— 一旦格式变了数据就变了。
    expect(block).not.toContain('dosageText');
  });

  it('没有 materialTypeId 的行被丢掉 —— 它是指向物料档案的唯一键', () => {
    const at = EDITOR.indexOf('materialBindingsByProcess[nodeId] = process.bindings');
    const block = EDITOR.slice(at, at + 400);
    expect(block).toMatch(/filter\(\(binding\) => binding\.materialTypeId != null/);
  });
});

describe('序列化：materialBindings 必须进 nodesJson（这是进 revisionHash 的机制）', () => {
  it('serializeFlowNode 整份拷贝 node.data，不做字段白名单', () => {
    // 后端 WorkflowRevisionSnapshotService#hash 算的是整个 nodesJson。
    // 只要 materialBindings 在 node.data 里且序列化不筛字段，它就自动进 hash ——
    // **不需要改哈希公式**，既有 revision 的 nodesJson 不变、hash 也就不变。
    const at = EDITOR.indexOf('function serializeFlowNode');
    const fn = EDITOR.slice(at, at + 500);
    expect(fn).toContain('toPlainWorkflowValue(node.data || {})');
    // 若将来有人在这里加字段白名单，materialBindings 会被静默丢掉、克数改了却不产生新版本。
    expect(fn).not.toMatch(/pick\(|allowedKeys|WHITELIST/);
  });

  it('工序节点不再被 stripBomOverlay 过滤 —— 它是真实节点，一直都是', () => {
    // 阶段 3 的 strip 只负责「不持久化派生展示物」（辅料/包材 cell）。
    // 数据本身已经搬到真实工序节点上，strip 不再承载任何数据 —— 这是定稿里
    // 「去掉 stripBomOverlay」与「但不是简单地把浮层节点塞进图」两句话的交集。
    const overlay = readFileSync(
      resolve(process.cwd(), 'src/views/system/product-processes/workflow/bomOverlay.ts'),
      'utf-8',
    );
    expect(overlay).toMatch(/isBomOverlayNode/);
    // strip 判据仍是 id 前缀 —— 真实工序节点没有前缀，永远不会被它滤掉
    expect(overlay).toMatch(/startsWith\(BOM_OVERLAY_PREFIX\)/);
  });
});
