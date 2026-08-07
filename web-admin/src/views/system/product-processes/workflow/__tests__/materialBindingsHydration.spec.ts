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

  it('hydration 不调用 mutate —— 否则打开一张图就会造出新版本', () => {
    // ⛔ 断言语法形态而不是"字符串不出现": 这个函数的注释里就写着 mutate() 三个字
    //    （解释它为什么不能用），用 not.toContain('mutate') 会被注释打红。
    expect(fn).not.toMatch(/^\s*mutate\(/m);
    expect(fn).not.toMatch(/[^.\w]mutate\(\s*\(\)\s*=>/);
  });

  it('hydration 不置 dirty、不 bump editSeq', () => {
    expect(fn).not.toMatch(/dirty\.value\s*=/);
    expect(fn).not.toMatch(/editSeq\s*\+=/);
  });

  it('值没变就不写 —— 幂等，避免每次加载都制造响应式改动', () => {
    expect(fn).toMatch(/JSON\.stringify\(current\) === JSON\.stringify\(next\)/);
    expect(fn).toMatch(/return;/);
  });

  it('只在真的变了时才刷新派生视图', () => {
    expect(fn).toMatch(/if \(changed\)/);
    expect(fn).toContain('refreshBomOverlay()');
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
