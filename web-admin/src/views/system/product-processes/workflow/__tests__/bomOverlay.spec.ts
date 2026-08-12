import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  BOM_OVERLAY_PREFIX,
  deriveBomOverlay,
  isDerivedBomOverlayConnection,
  selectObsoleteBomInputs,
  isBomOverlayNode,
  stripBomOverlay,
  stripBomOverlayEdges,
} from '../bomOverlay';

describe('BOM 浮层节点与工艺定义隔离', () => {
  it('浮层节点 id 带固定前缀', () => {
    expect(isBomOverlayNode({ id: `${BOM_OVERLAY_PREFIX}aux:p1` })).toBe(true);
  });

  it('工艺节点不被误判为浮层', () => {
    expect(isBomOverlayNode({ id: 'process-1' })).toBe(false);
    expect(isBomOverlayNode({ id: 'material-7' })).toBe(false);
  });

  it('stripBomOverlay 滤掉浮层, 原样保留工艺节点与顺序', () => {
    const input = [
      { id: 'material-1' },
      { id: `${BOM_OVERLAY_PREFIX}aux:process-1` },
      { id: 'process-1' },
      { id: `${BOM_OVERLAY_PREFIX}pack:out-1` },
      { id: 'out-1' },
    ];
    expect(stripBomOverlay(input).map((n) => n.id)).toEqual(['material-1', 'process-1', 'out-1']);
  });

  it('没有浮层时返回等值数组', () => {
    const input = [{ id: 'a' }, { id: 'b' }];
    expect(stripBomOverlay(input).map((n) => n.id)).toEqual(['a', 'b']);
  });
});

describe('stripBomOverlayEdges 滤掉浮层边(source 或 target 任一端是浮层)', () => {
  it('普通工艺边原样保留', () => {
    const input = [{ id: 'e1', source: 'material-1', target: 'process-1' }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual(['e1']);
  });

  it('source 是浮层的边被丢弃(辅料 cell → 工序方向)', () => {
    const input = [{ id: 'e-aux', source: `${BOM_OVERLAY_PREFIX}aux:p1`, target: 'process-1' }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual([]);
  });

  it('target 是浮层的边被丢弃(产出 → 包材 cell 方向)', () => {
    const input = [{ id: 'e-pack', source: 'out-1', target: `${BOM_OVERLAY_PREFIX}pack:out-1` }];
    expect(stripBomOverlayEdges(input).map((e) => e.id)).toEqual([]);
  });

  it('保留幸存边的顺序与原对象引用', () => {
    const survivor1 = { id: 'e1', source: 'material-1', target: 'process-1' };
    const overlayEdge = { id: 'e-aux', source: `${BOM_OVERLAY_PREFIX}aux:p1`, target: 'process-1' };
    const survivor2 = { id: 'e2', source: 'process-1', target: 'out-1' };
    const result = stripBomOverlayEdges([survivor1, overlayEdge, survivor2]);
    expect(result).toEqual([survivor1, survivor2]);
    expect(result[0]).toBe(survivor1);
    expect(result[1]).toBe(survivor2);
  });
});

describe('Vue Flow 连接门只放行派生浮层边', () => {
  it('放行辅料 cell 到所属工序的精确 handle 组合', () => {
    expect(isDerivedBomOverlayConnection({
      source: `${BOM_OVERLAY_PREFIX}aux:p1`,
      sourceHandle: 'bom-aux-out',
      target: 'p1',
      targetHandle: 'bom-aux-in',
    })).toBe(true);
  });

  /**
   * 🔴 2026-08-12: 这条原来断言的是**旧方向**(成品在 source、包材在 target)。
   * 包材连线在「包材挪到成品上方」那次改动里翻成了 source=包材 → target=成品,
   * deriveBomOverlay 跟着翻了, 这条白名单**没翻** ⇒ 恒 false ⇒ vue-flow 静默过滤,
   * 真机上「包材 Cell 一条线都没有」。
   *
   * 两侧各自都有测试、各自都绿 —— 因为它们测的是相反的方向, 谁也没跟谁对过。
   */
  it('放行包材 cell 到所属成品的精确 handle 组合(方向: 包材 → 成品)', () => {
    expect(isDerivedBomOverlayConnection({
      source: `${BOM_OVERLAY_PREFIX}pack:o1`,
      sourceHandle: 'bom-pack-out',
      target: 'o1',
      targetHandle: 'bom-pack-in',
    })).toBe(true);
  });

  it('旧方向(成品 → 包材)必须被拒 —— 否则等于两个方向都放行, 白名单就没有约束力', () => {
    expect(isDerivedBomOverlayConnection({
      source: 'o1',
      sourceHandle: 'bom-pack-out',
      target: `${BOM_OVERLAY_PREFIX}pack:o1`,
      targetHandle: 'bom-pack-in',
    })).toBe(false);
  });

  it.each([
    {
      source: `${BOM_OVERLAY_PREFIX}aux:p1`, sourceHandle: 'output',
      target: 'p1', targetHandle: 'bom-aux-in',
    },
    {
      source: `${BOM_OVERLAY_PREFIX}aux:p1`, sourceHandle: 'bom-aux-out',
      target: 'p2', targetHandle: 'bom-aux-in',
    },
    {
      source: 'o1', sourceHandle: 'bom-pack-out',
      target: `${BOM_OVERLAY_PREFIX}pack:o2`, targetHandle: 'bom-pack-in',
    },
    {
      source: 'material-1', sourceHandle: 'output',
      target: 'process-1', targetHandle: 'input',
    },
  ])('拒绝非派生拓扑 %#', (connection) => {
    expect(isDerivedBomOverlayConnection(connection)).toBe(false);
  });
});

describe('编辑器序列化不带浮层', () => {
  const source = readFileSync(
    resolve(__dirname, '../ProductProcessWorkflowEditor.vue'),
    'utf-8',
  );

  it('序列化工艺定义时先剥离浮层节点', () => {
    // 钉死「nodes: 后面必须经过 stripBomOverlay」, 换成裸 flowNodes 就红
    expect(source).toMatch(/nodes:\s*stripBomOverlay\(flowNodes\.value\)\.map\(serializeFlowNode\)/);
    expect(source).not.toMatch(/nodes:\s*flowNodes\.value\.map\(serializeFlowNode\)/);
  });

  it('序列化工艺定义时先剥离浮层边', () => {
    // 钉死「edges: 后面必须经过 stripBomOverlayEdges」, 换成裸 flowEdges 就红
    expect(source).toMatch(/edges:\s*stripBomOverlayEdges\(flowEdges\.value\)\.map\(serializeFlowEdge\)/);
    expect(source).not.toMatch(/edges:\s*flowEdges\.value\.map\(serializeFlowEdge\)/);
  });
});

const processNode = (id: string, x: number, y: number) =>
  ({ id, kind: 'PROCESS' as const, position: { x, y }, data: { processName: '腌制' } });
const outputNode = (id: string, x: number, y: number) =>
  ({ id, kind: 'FINISHED_GOOD' as const, position: { x, y }, data: { name: '酱鸭腿' } });

describe('从 BOM 派生浮层', () => {
  it('每道有辅料的工序派生一个辅料 cell, 挂在工序正上方', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400)],
      auxiliaryByProcess: {
        p1: { usageSupported: true, rows: [{ id: 'r1', materialName: '食盐', dosageText: '12 g/kg', markers: [] }] },
      },
      packagingByOutput: {},
    });
    const aux = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(aux).toBeTruthy();
    expect(aux!.position.y).toBeLessThan(400);
    expect(aux!.type).toBe('bomAuxiliary');
    expect(aux!.type === 'bomAuxiliary' && aux!.data.processName).toBe('腌制');
  });

  it('没有辅料的工序也派生 cell —— 空态必须可见', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400)],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    const aux = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(aux, '空态 cell 不能不渲染 —— 用户要看得见「未配」').toBeTruthy();
    expect(aux!.type === 'bomAuxiliary' && aux!.data.rows).toEqual([]);
  });

  it('每个终端产出派生一个包材 cell, 挂在产出【上方】(与辅料挂工序一致)', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [outputNode('o1', 900, 200)],
      auxiliaryByProcess: {},
      packagingByOutput: {
        o1: { rows: [{ id: 'r1', materialName: '真空袋', dosageText: '1 个/盒', markers: [] }] },
      },
    });
    const pack = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(pack).toBeTruthy();
    expect(pack!.position.x).toBe(900);
    expect(pack!.position.y).toBeLessThan(200);
    expect(pack!.type).toBe('bomPackaging');
    expect(pack!.type === 'bomPackaging' && pack!.data.outputName).toBe('酱鸭腿');
  });

  /**
   * 🔴 Steve 2026-08-11: 「辅料的位置一定要和工序的 cell 有一定距离, 确保自动布局是
   * 完好的好看的一个布局」。旧实现是 `y - 220` 固定偏移, 而工序 Cell 高度随内容变化
   * 很大(投入/产出/单位关系/副产全展开能到 600px+), 220 不够 → 辅料 Cell 压在工序上面。
   */
  it('辅料 Cell 的间距跟着【辅料自己】的实测高度走, 不是固定偏移、也不是工序的高度', () => {
    const tall = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 1000)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: {},
      nodeHeights: { [`${BOM_OVERLAY_PREFIX}aux:p1`]: 400 },
    }).nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`)!;
    const short = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 1000)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: {},
      nodeHeights: { [`${BOM_OVERLAY_PREFIX}aux:p1`]: 250 },
    }).nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`)!;

    // 辅料越高 → 要放得越高(它是从自己的 y 往下长的, 底边才对着工序)
    expect(tall.position.y).toBeLessThan(short.position.y);
    // 底边正好落在工序上方: y = 工序Y - (辅料高 + GAP)
    expect(short.position.y).toBeLessThanOrEqual(1000 - 250);
    expect(tall.position.y).toBeLessThanOrEqual(1000 - 400);
    // ⛔ 工序自己的高度不许参与 —— 传工序高度不该改变结果 (上一版就是拿错了这个,
    //    工序越高辅料被推得越远)
    const withProcessHeight = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 1000)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: {},
      nodeHeights: { [`${BOM_OVERLAY_PREFIX}aux:p1`]: 250, p1: 900 },
    }).nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`)!;
    expect(withProcessHeight.position.y).toBe(short.position.y);
  });

  /**
   * 🔴 实测量来自**上一帧**: 辅料 Cell 的内容(辅料行/状态提示/加辅料按钮)展开后会变高,
   * 测早了就偏小, 留白被吃掉 —— Steve 实测只剩 ~10px「靠得太近」。
   * 所以取 max(实测, 下限)。
   */
  it('实测高度偏小时用下限兜底 —— 不许因为测早了就贴到工序上', () => {
    const aux = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 1000)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: {},
      nodeHeights: { [`${BOM_OVERLAY_PREFIX}aux:p1`]: 85 },  // 实测偏小(真实渲染 123)
    }).nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`)!;
    // 即便实测只有 85, 留白也必须够: 工序顶边往上至少 200 + 72
    expect(aux.position.y).toBeLessThanOrEqual(1000 - 200);
  });

  it('拿不到实测高度时退回兜底高度 —— 首帧也不许重叠', () => {
    const aux = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 1000)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: {},
    }).nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`)!;
    expect(aux.position.y).toBeLessThan(1000 - 100);
  });

  /**
   * 🔴 2026-08-12 真机「包材 Cell 一条线都没有」的**根治性**断言。
   *
   * 上面两组测试各自都在, 各自都绿, 但测的是相反的方向 ——
   * deriveBomOverlay 产出 source=包材→target=成品, 而 isDerivedBomOverlayConnection
   * 还在按 source=成品→target=包材 放行, 于是 vue-flow 把边静默过滤, DOM 里一条都没有。
   *
   * 这一条把两边【对起来】: deriveBomOverlay 实际产出的每一条边, 白名单都必须放行。
   * 任何一侧再翻方向、改 handle 常量, 这里立刻红 —— 而不是等到真机上"线不见了"。
   */
  it('🔴 deriveBomOverlay 产出的每一条浮层边, 白名单都必须放行', () => {
    const { edges } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400), outputNode('o1', 900, 200)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: { o1: { rows: [] } },
    });
    expect(edges.length).toBeGreaterThanOrEqual(2);   // 辅料 + 包材, 少一条说明夹具退化了
    const rejected = edges.filter((edge) => !isDerivedBomOverlayConnection({
      source: edge.source,
      sourceHandle: edge.sourceHandle,
      target: edge.target,
      targetHandle: edge.targetHandle,
    }));
    expect(rejected.map((e) => e.id)).toEqual([]);
  });

  it('派生连线与普通 Workflow 连线使用同一实线样式且两端 handle 正确', () => {
    const { edges } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400), outputNode('o1', 900, 200)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: { o1: { rows: [] } },
    });
    const auxEdge = edges.find((e) => e.source === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(auxEdge!.target).toBe('p1');
    expect(auxEdge!.sourceHandle).toBe('bom-aux-out');
    expect(auxEdge!.targetHandle).toBe('bom-aux-in');
    // 2026-08-11: 「同一样式」现在包含**线型** —— 浮层边曾经是 smoothstep(直角折线),
    // 主流程边不设 type 走 vue-flow 默认曲线, 同屏两种线型, 辅料/包材那两根看着像
    // 别的系统画的 (Steve: 「希望和其他的线一样, 统一的就好」)。
    // 统一的表达 = **不设 type**, 跟主流程边继承同一个默认值。
    expect(auxEdge).not.toHaveProperty('type');
    expect(auxEdge!.style).toEqual({ stroke: '#1b65a8', strokeWidth: 2 });
    expect(auxEdge!.style).not.toHaveProperty('strokeDasharray');
    const packEdge = edges.find((e) => e.source === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(packEdge!.target).toBe('o1');
    expect(packEdge!.sourceHandle).toBe('bom-pack-out');
    expect(packEdge!.targetHandle).toBe('bom-pack-in');
    expect(packEdge).not.toHaveProperty('type');
    expect(packEdge!.style).toEqual({ stroke: '#1b65a8', strokeWidth: 2 });
  });

  it('原料与半成品节点不派生任何浮层', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [
        { id: 'm1', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 }, data: { name: '鸭腿' } },
        { id: 's1', kind: 'SEMI_FINISHED', position: { x: 0, y: 0 }, data: { name: '坯' } },
      ],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    expect(nodes).toEqual([]);
  });

  it('所有派生节点 id 都带浮层前缀', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400), outputNode('o1', 900, 200)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: { o1: { rows: [] } },
    });
    expect(nodes.length).toBeGreaterThan(0);
    expect(nodes.every((n) => n.id.startsWith(BOM_OVERLAY_PREFIX))).toBe(true);
  });

  it('没有 usageSupported 数据时安全默认为「未知」灰态, 不冒充「已确认不可换算」', () => {
    // must-fix #3: meta 缺失(数据未加载/加载失败/无配方/修订节点 id 不匹配, 这里无从
    // 区分)不能被当成"已确认该工序不可换算"—— 那是代码给不出证据的具体诊断
    // (禁止降级处理)。所以三态里必须是 null(未知), 不是 false(已确认为否)。
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400)],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    const aux = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(aux!.type === 'bomAuxiliary' && aux!.data.usageSupported).toBeNull();
  });

  it('缺失产出基本单位时占位「未配」, 不能是空串或 undefined 拼进字符串', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [{ id: 'o1', kind: 'FINISHED_GOOD', position: { x: 0, y: 0 }, data: { name: '酱鸭腿' } }],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    const pack = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(pack!.type === 'bomPackaging' && pack!.data.baseUnit).toBe('未配');
  });
});

/**
 * 🔴 2026-08-12 (Steve 真机): 换原料后点「加辅料」被 409 拦下
 * 「旧工艺中的原料投入在目标工艺中已不存在：2015胸肉」——
 * 而提示让去删的那个动作**全站没有界面**(DELETE items 后端有、前端 API 有、0 处调用)。
 *
 * selectObsoleteBomInputs 是补上的那个出口的判据。
 */
describe('🔴 selectObsoleteBomInputs —— 旧工艺遗留配方行的判据', () => {
  const live = new Set(['material:raw:NEW', 'material:semi:A']);

  it('绑着已不存在的画布节点 → 判为孤儿', () => {
    const items = [{ id: 1, materialName: '2015胸肉', workflowMaterialNodeId: 'material:raw:OLD' }];
    expect(selectObsoleteBomInputs(items, live).map((i) => i.id)).toEqual([1]);
  });

  it('绑着仍在画布上的节点 → 不动', () => {
    const items = [{ id: 2, materialName: '冻猪蹄', workflowMaterialNodeId: 'material:raw:NEW' }];
    expect(selectObsoleteBomInputs(items, live)).toEqual([]);
  });

  /**
   * ⚠️ 最要命的一条: 错误消息里只有**物料名**。照着名字删会连活的那行一起删 ——
   * 同一个物料完全可能一行是活的、一行是孤儿(换了投入口但料没换)。
   */
  it('🔴 同一个物料一行活一行孤儿 —— 只能删孤儿那行', () => {
    const items = [
      { id: 3, materialName: '2015胸肉', workflowMaterialNodeId: 'material:raw:OLD' },
      { id: 4, materialName: '2015胸肉', workflowMaterialNodeId: 'material:raw:NEW' },
    ];
    expect(selectObsoleteBomInputs(items, live).map((i) => i.id)).toEqual([3]);
  });

  it('没绑画布的行不是孤儿(手工加的料)', () => {
    const items = [{ id: 5, materialName: '盐', workflowMaterialNodeId: null }];
    expect(selectObsoleteBomInputs(items, live)).toEqual([]);
  });

  it('PACKAGING 不算 —— 它不绑投入口, 与后端 reconcileUpgradedInputSkeletons 的过滤一致', () => {
    const items = [{
      id: 6, materialName: '真空袋', materialCategory: 'PACKAGING',
      workflowMaterialNodeId: 'material:finished:GONE',
    }];
    expect(selectObsoleteBomInputs(items, live)).toEqual([]);
  });
});
