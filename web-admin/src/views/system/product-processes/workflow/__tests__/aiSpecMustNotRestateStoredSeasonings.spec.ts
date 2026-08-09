import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(process.cwd(), 'src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue'),
  'utf-8',
);

/**
 * AI 生成工序流程时，**已经配好的调料克数不许经模型转述**。
 *
 * ## 缺陷长什么样（这条闸盯的就是它）
 *
 * 这条路是：
 * ```
 * 库里的真值 → 喂给 LLM → LLM 复述成 spec → 编译器照 spec 建图 → 替换整张画布
 *                              ↑ 克数在这一步经过了模型
 * ```
 * `buildWorkflowFromSpec` 从零重建 `nodes: Node[] = []`（全部新 id），原来直接
 * `processData.materialBindings = acceptedSeasonings` —— 也就是**采信模型的复述**。
 *
 * 模型把 12.5 说成 12、或者因为用户只问「加一道杀菌」而没重述另外 21 道工序的调料，
 * 这些都**不是无效行**，`seasoningRejections` 抓不到。用户看到一张「看起来对」的
 * 画布就保存了，扣料与成本跟着错，而且没有任何痕迹。
 *
 * 📌 判据：**写下去的应该是画布上存着的真值，不是模型的复述。**
 * （同一形状在后端补丁路已修：`ProductProcessWorkflowConfigTool#costBearingFieldsUnchanged`。）
 *
 * ## 为什么用源码级断言
 *
 * 本仓这一区的既有测试（`materialBindingsHydration.spec.ts` 等）都是源码级 —— 4700 行
 * SFC 挂载代价太高。⛔ 源码级断言的风险是「文本在、但没接上」，所以下面几条钉的是
 * **位置关系**（快照必须取在 mutate 之前）而不只是「某个字符串出现过」。
 */
describe('AI 建流程不得用模型复述覆盖已存的调料克数', () => {
  const fn = (() => {
    const at = EDITOR.indexOf('async function buildWorkflowFromSpec');
    expect(at, 'buildWorkflowFromSpec 应存在').toBeGreaterThan(-1);
    return EDITOR.slice(at);
  })();

  it('🔴 承重: 成本字段快照必须取在 mutate 之前 —— 之后画布已被清空，取不到了', () => {
    const snapshotAt = fn.indexOf('storedCostBearing');
    const mutateAt = fn.indexOf('mutate(() =>');

    expect(snapshotAt, '应有 storedCostBearing 快照').toBeGreaterThan(-1);
    expect(mutateAt, '应有 mutate 重建块').toBeGreaterThan(-1);
    // 这条是位置关系，不是「出现过」——把快照挪到 mutate 里面就会红。
    expect(snapshotAt).toBeLessThan(mutateAt);
  });

  it('🔴 承重: 已配过调料的工序，写下去的是快照不是模型给的 acceptedSeasonings', () => {
    // 「有存量」那一支必须赋 storedBindings；⛔ 不许在这一支里赋 acceptedSeasonings。
    const branch = fn.slice(fn.indexOf('if (storedBindings) {'));
    const branchBody = branch.slice(0, branch.indexOf('} else if'));

    expect(branchBody).toContain('materialBindings = storedBindings');
    expect(branchBody).not.toContain('materialBindings = acceptedSeasonings');
  });

  it('🔴 承重: 模型提议的克数改动只提示、不自动应用', () => {
    const branch = fn.slice(fn.indexOf('if (storedBindings) {'));
    const branchBody = branch.slice(0, branch.indexOf('} else if'));

    // 有存量且模型说的不一样时，必须进 seasoningRejections 告诉用户，
    // ⛔ 不许静默 —— 静默的话用户以为 AI 照做了。
    expect(branchBody).toContain('sameSeasoningRows');
    expect(branchBody).toContain('seasoningRejections.push');
  });

  it('按 workProcessId 匹配, ⛔ 不按工序名 —— 名字会被 AI 改掉', () => {
    const snapshotBlock = fn.slice(fn.indexOf('const storedCostBearing'), fn.indexOf('mutate(() =>'));
    expect(snapshotBlock).toContain('workProcessId');
    // 按名字匹配会在「卤制」->「卤制(改名)」时静默丢失继承。
    expect(snapshotBlock).not.toContain('processName ===');
    expect(snapshotBlock).not.toContain('normProcessName');
  });

  it('同一工序在原画布出现多次 -> ⛔ 不猜, 但也不静默', () => {
    const snapshotBlock = fn.slice(fn.indexOf('const storedCostBearing'), fn.indexOf('mutate(() =>'));
    // 分不清哪份该给谁时删掉快照(不猜)……
    expect(snapshotBlock).toContain('ambiguousWorkProcessIds');
    expect(snapshotBlock).toContain('storedCostBearing.delete');
    // ……但必须有一支把这件事告诉用户。
    const ambiguousBranch = fn.slice(fn.indexOf('ambiguousWorkProcessIds.has'));
    expect(ambiguousBranch.slice(0, 400)).toContain('seasoningRejections.push');
  });

  it('注射量与调料同一口径 —— 有存量就保留, 模型改了只提示', () => {
    const injectionBlock = fn.slice(fn.indexOf('const storedInjection'));
    expect(injectionBlock.slice(0, 600)).toContain('injectionAmount = storedInjection');
    expect(injectionBlock.slice(0, 600)).toContain('seasoningRejections.push');
  });

  it('新工序(画布上没配过)仍然采用模型给的 —— ⛔ 别把继承做成「永远不让 AI 配」', () => {
    // 这条是反向保护: 没有可保留的真值时, AI 配的调料要能落到画布上,
    // 否则新建流程时 AI 说的调料全被吞掉, 用户会以为 AI 没听懂。
    const elseBranch = fn.slice(fn.indexOf('// 新工序(画布上没配过)'));
    expect(elseBranch.slice(0, 300)).toContain('materialBindings = acceptedSeasonings');
  });
});
