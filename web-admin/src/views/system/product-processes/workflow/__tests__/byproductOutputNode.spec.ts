import { describe, expect, it } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  BOM_OVERLAY_PREFIX,
  deriveBomOverlay,
  PACK_OVERLAY_SOURCE_HANDLE,
  stripBomOverlay,
} from '../bomOverlay';
import { validateWorkflow } from '../workflowModel';
import { evaluateWorkflowConnection } from '../workflowModel';
import type { ProductProcessWorkflowDefinition } from '../types';

/**
 * 副产: BOM 浮层 → 真实产出节点 (2026-08-07 阶段 2)。
 *
 * ## 这个文件是 bomOverlayByproduct.spec.ts 的翻转版, 不是新写的
 * 原文件断言的是浮层口径 (`bom-overlay:byp:*` 挂在终端产出下方)。方案 B 定稿后
 * 副产改成由**工序**派生的真实产出节点, 所以那些断言必须翻转 —— 但**意图逐条保留**:
 *
 * | 原断言 | 现在由谁承接 |
 * |---|---|
 * | 终端产出派生副产 cell | 「副产由工序派生」—— 刻意反向, 见下方注释 |
 * | 没有副产也派生空 cell(「没声明」≠「不能声明」) | 「每个工序上永远有『+ 副产』入口」 |
 * | 分母用产出 SKU 基本单位, 不硬编码 | 副产不再有分母(数量报工时填), 改钉「选的是物料不是产品 SKU」 |
 * | 边两端 handle 都用共享常量 | 真实边不用浮层 handle; 改钉「剩下的浮层 handle 组件里真有 <Handle>」 |
 * | 浮层被序列化剥离 | **反向**: 副产节点必须**留在**序列化结果里 |
 *
 * ## 为什么「工序派生」是刻意反向
 * 原口径说「副产是产出声明, 挂在终端产出上, 不挂工序」。那是浮层时代的说法 ——
 * 浮层只是展示物, 挂哪儿只影响画面。真实节点不同: 副产是**某一道工序**分流出来的产物
 * (剔骨工序出鸡架、炼油工序出肥油), 挂在终端成品上就表达不出它来自哪道工序,
 * 也接不进拓扑。所以真实节点时代必须挂工序。
 */

const SRC = resolve(__dirname, '..');
const read = (rel: string) => readFileSync(resolve(SRC, rel), 'utf-8');

const EDITOR = read('ProductProcessWorkflowEditor.vue');
const PROCESS_NODE = read('WorkflowProcessNode.vue');
const MATERIAL_NODE = read('WorkflowMaterialNode.vue');

describe('副产是真实产出节点, 不再是浮层', () => {
  it('deriveBomOverlay 不再派生任何副产节点或边', () => {
    const { nodes, edges } = deriveBomOverlay({
      workflowNodes: [
        { id: 'proc-1', kind: 'PROCESS', position: { x: 0, y: 200 }, data: { processName: '剔骨' } },
        {
          id: 'out-1',
          kind: 'FINISHED_GOOD',
          position: { x: 100, y: 200 },
          data: { name: '干式熟成鸡 400g', baseUnit: '袋' },
        },
      ],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    // 用 string 比较: OverlayNode 的 type 联合类型里已经没有 'bomByproduct' 了,
    // 直接写字面量会被 TS 判成"两个类型无交集"而编译不过 —— 但这条断言的价值恰恰在于
    // 运行时也确认一遍(类型收窄了不代表运行时真没派生)。
    expect(nodes.some((n) => String(n.type) === 'bomByproduct')).toBe(false);
    expect(nodes.some((n) => n.id.includes(':byp:'))).toBe(false);
    expect(edges.some((e) => e.id.includes(':byp:'))).toBe(false);
    // 浮层只剩「投入」两类
    expect(nodes.map((n) => n.type).sort()).toEqual(['bomAuxiliary', 'bomPackaging']);
  });

  it('副产节点【不】被序列化剥离 —— 与浮层时代正好相反', () => {
    // 浮层节点靠 id 前缀识别; 副产节点是普通 material 节点, 没有前缀, 所以留得下来。
    const nodes = [
      { id: 'material:output:7', data: { isByproduct: true } },
      { id: `${BOM_OVERLAY_PREFIX}aux:proc-1`, data: {} },
    ];
    expect(stripBomOverlay(nodes).map((n) => n.id)).toEqual(['material:output:7']);
  });

  it('工序 → 副产是合法的真实边(副产 kind 仍是 SEMI_FINISHED)', () => {
    expect(evaluateWorkflowConnection('PROCESS', 'SEMI_FINISHED')).toEqual({
      valid: true,
      direction: 'PROCESS_TO_MATERIAL',
    });
  });
});

/** 图校验: 两条 goal 明确点名的要求。二者当前都已成立 —— 这里钉住, 防止被改回去。 */
describe('图校验对副产的处理', () => {
  const definition = (extraNodes: unknown[] = [], extraPorts: unknown[] = []): ProductProcessWorkflowDefinition => ({
    schemaVersion: 1,
    nodes: [
      { id: 'raw-1', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 }, data: { name: '整鸡', skuId: 'RMT-1' } },
      {
        id: 'proc-1',
        kind: 'PROCESS',
        position: { x: 200, y: 0 },
        data: {
          processName: '剔骨',
          ports: [
            { id: 'input:1', direction: 'INPUT', materialNodeId: 'raw-1', unit: 'kg', ordinal: 0 },
            { id: 'output:1', direction: 'OUTPUT', materialNodeId: 'fin-1', unit: 'kg', ordinal: 0 },
            ...extraPorts,
          ],
        },
      },
      { id: 'fin-1', kind: 'FINISHED_GOOD', position: { x: 400, y: 0 }, data: { name: '鸡胸', skuId: 'SKU-1' } },
      ...extraNodes,
    ],
    edges: [
      { id: 'e1', source: 'raw-1', target: 'proc-1', targetHandle: 'input:1' },
      { id: 'e2', source: 'proc-1', sourceHandle: 'output:1', target: 'fin-1', targetHandle: 'input' },
    ],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  }) as any;

  it('一个工序多个产出被放行 —— 副产就是靠这条成立的', () => {
    const withByproduct = definition(
      [{
        id: 'material:output:9',
        kind: 'SEMI_FINISHED',
        position: { x: 400, y: 200 },
        data: { name: '鸡架', skuId: 'RMT-BYP', isByproduct: true },
      }],
      [{ id: 'output:2', direction: 'OUTPUT', materialNodeId: 'material:output:9', unit: 'kg', ordinal: 1 }],
    );
    withByproduct.edges.push({
      id: 'e3', source: 'proc-1', sourceHandle: 'output:2', target: 'material:output:9', targetHandle: 'input',
    });
    const errors = validateWorkflow(withByproduct, 'publish');
    // ⚠️ 断言的是"没有因为多产出而报错", 不是"完全没错" —— 后者会把无关缺陷也吞进来
    expect(errors.filter((e) => e.code === 'OUTPUT_CONTRACT_INVALID')).toEqual([]);
    expect(errors).toEqual([]);
  });

  it('副产没绑 SKU 时发布被拦 —— 走的是通用 SKU_REQUIRED, 不需要为副产开特例', () => {
    const unbound = definition(
      [{
        id: 'material:output:9',
        kind: 'SEMI_FINISHED',
        position: { x: 400, y: 200 },
        data: { name: '副产 1', skuId: '', isByproduct: true },
      }],
      [{ id: 'output:2', direction: 'OUTPUT', materialNodeId: 'material:output:9', unit: 'kg', ordinal: 1 }],
    );
    unbound.edges.push({
      id: 'e3', source: 'proc-1', sourceHandle: 'output:2', target: 'material:output:9', targetHandle: 'input',
    });
    const errors = validateWorkflow(unbound, 'publish');
    expect(errors.map((e) => e.code)).toContain('SKU_REQUIRED');
    expect(errors.find((e) => e.code === 'SKU_REQUIRED')?.nodeId).toBe('material:output:9');
  });

  it('草稿态不拦 —— 与其它未绑定节点同一口径(保存不拦, 发布才拦)', () => {
    const unbound = definition(
      [{
        id: 'material:output:9', kind: 'SEMI_FINISHED', position: { x: 400, y: 200 },
        data: { name: '副产 1', skuId: '', isByproduct: true },
      }],
      [],
    );
    expect(validateWorkflow(unbound, 'draft')).toEqual([]);
  });
});

describe('入口与选择器', () => {
  it('副产与主产出共用 addOutputToProcess —— 不是另起一个函数', () => {
    // goal: 「复用画布已有的『＋ 产出 Cell（分流）』入口, 不要新造入口」。
    expect(EDITOR).not.toMatch(/function addByproductToProcess/);
    expect(EDITOR).toMatch(/function addOutputToProcess\(processId: string, options: \{ byproduct\?: boolean \}/);
    expect(EDITOR).toContain("addOutputToProcess(slotProps.id, { byproduct: true })");
  });

  it('每个工序上都有「+ 副产」入口 —— 「没配副产」与「不能配副产」必须能区分', () => {
    // 这条继承自浮层时代的「没有副产也派生空 cell」。浮层没了, 但那个信息不能没:
    // 用户要能看出"这里可以加副产, 只是还没加"。承接它的是工序上常驻的入口按钮。
    expect(PROCESS_NODE).toContain('data-testid="add-byproduct-inline"');
    expect(PROCESS_NODE).toMatch(/emit\('addByproduct'\)/);
    // ⛔ 不许被 v-if 藏起来: 只允许按写权限收(canWrite), 不允许按"有没有副产"收
    const at = PROCESS_NODE.indexOf('data-testid="add-byproduct-inline"');
    const block = PROCESS_NODE.slice(Math.max(0, at - 400), at);
    expect(block).not.toMatch(/v-if="[^"]*byproduct[^"]*"/i);
  });

  it('副产选的是【物料档案】不是产品 SKU —— 这是硬外键决定的, 不是偏好', () => {
    // bom_recipe_items.material_type_id → raw_material_types(id) 是硬外键;
    // 报工的 ByproductBatchMaterializer 也按 materialTypeId 建 MaterialBatch。
    // 若这里改回产品 SKU 池, 选出来的 id 落库会直接违反外键。
    expect(MATERIAL_NODE).toContain('data-testid="byproduct-material-select"');
    expect(EDITOR).toContain('selectByproductMaterials(rows)');
    // ⛔ #2313 同型防复发: 不许把别的口径的列表直接赋给副产
    expect(EDITOR).not.toMatch(/byproductMaterialOptions\.value\s*=\s*(bomOverlay)?[Pp]ackaging/);
    expect(EDITOR).not.toMatch(/byproductMaterialOptions\.value\s*=\s*rawMaterialOptions/);
  });

  it('档案里没有副产物料时给解释与去处, 不是一个空下拉', () => {
    // 继承自已删的 ByproductBindingDialog.spec 的同名判据(防呆规则 5)。
    expect(MATERIAL_NODE).toContain('data-testid="byproduct-material-empty"');
    const at = MATERIAL_NODE.indexOf('data-testid="byproduct-material-empty"');
    const block = MATERIAL_NODE.slice(at, at + 400);
    expect(block).toMatch(/物料档案/);
    expect(block).toMatch(/这是副产/);
  });

  it('副产在画布上与主产出视觉可分 —— owner 明确要求颜色区分', () => {
    expect(MATERIAL_NODE).toMatch(/\.material-node\.byproduct\s*\{/);
    expect(MATERIAL_NODE).toContain("data.skuId ? 'success' : 'warning'");
    expect(MATERIAL_NODE).toMatch(/isByproduct\.value \? '副产 Cell'/);
  });
});

describe('浮层遗留物已清干净', () => {
  it('副产浮层的组件与弹窗都已删除', () => {
    expect(existsSync(resolve(SRC, 'WorkflowByproductNode.vue'))).toBe(false);
    expect(existsSync(resolve(SRC, 'ByproductBindingDialog.vue'))).toBe(false);
  });

  it('bom-byp-* 的 handle 常量已随浮层一起删除', () => {
    // ⚠️ 断言【声明形态】而不是「字符串不出现」—— 这个文件的注释里就写着那两个 handle 名
    // (作为"浮层为什么是脆的"的实证), 用 not.toContain 会把我自己的注释判成"还没删"。
    const overlay = read('bomOverlay.ts');
    expect(overlay).not.toMatch(/export const BYP_OVERLAY_\w+\s*=/);
    expect(overlay).not.toMatch(/sourceHandle:\s*BYP_OVERLAY/);
    expect(overlay).not.toMatch(/targetHandle:\s*BYP_OVERLAY/);
  });

  /**
   * 🔴 这条闸是被一个真实缺陷逼出来的, 不是补充覆盖。
   *
   * 浮层边的 sourceHandle 必须在组件里有同 id 的 <Handle>, 否则 vue-flow 找不到挂点,
   * **不报错、直接不渲染**。`bomOverlay.ts` 顶部注释早就写明了这个失败模式 —— 然后
   * `bom-byp-out` 就正好是这样: 它从未在 WorkflowMaterialNode.vue 里有过对应的 <Handle>,
   * 于是副产 cell 一直是飘在成品下方、没有连线的。原来那条「两端 handle 都用共享常量」
   * 的断言抓不到它 —— 它只比对 bomOverlay.ts 自己的两个常量, 从没问过组件那边有没有。
   *
   * 副产已改真实节点, 但**包材还是浮层**, 同样的坑还在。所以这条闸留给它。
   */
  it('剩下的浮层 handle 在组件里真有对应的 <Handle>(bom-byp-out 就是栽在这条上)', () => {
    expect(PACK_OVERLAY_SOURCE_HANDLE).toBe('bom-pack-out');
    expect(MATERIAL_NODE).toContain('PACK_OVERLAY_SOURCE_HANDLE');
    // 断言它真的挂在一个 <Handle> 上, 而不只是被 import 进来
    expect(MATERIAL_NODE).toMatch(/<Handle[\s\S]{0,220}:id="PACK_OVERLAY_SOURCE_HANDLE"/);
  });
});
