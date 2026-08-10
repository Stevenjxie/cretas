import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  BOM_OVERLAY_PREFIX,
  deriveBomOverlay,
  isDerivedBomOverlayConnection,
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

  it('放行成品到所属包材 cell 的精确 handle 组合', () => {
    expect(isDerivedBomOverlayConnection({
      source: 'o1',
      sourceHandle: 'bom-pack-out',
      target: `${BOM_OVERLAY_PREFIX}pack:o1`,
      targetHandle: 'bom-pack-in',
    })).toBe(true);
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

  it('每个终端产出派生一个包材 cell, 挂在产出右侧', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [outputNode('o1', 900, 200)],
      auxiliaryByProcess: {},
      packagingByOutput: {
        o1: { rows: [{ id: 'r1', materialName: '真空袋', dosageText: '1 个/盒', markers: [] }] },
      },
    });
    const pack = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(pack).toBeTruthy();
    expect(pack!.position.x).toBeGreaterThan(900);
    expect(pack!.type).toBe('bomPackaging');
    expect(pack!.type === 'bomPackaging' && pack!.data.outputName).toBe('酱鸭腿');
  });

  it('派生的连线是虚线且两端正确, handle id 与 cell 组件的 <Handle> 一致', () => {
    const { edges } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400), outputNode('o1', 900, 200)],
      auxiliaryByProcess: { p1: { usageSupported: true, rows: [] } },
      packagingByOutput: { o1: { rows: [] } },
    });
    const auxEdge = edges.find((e) => e.source === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(auxEdge!.target).toBe('p1');
    expect(auxEdge!.sourceHandle).toBe('bom-aux-out');
    expect(auxEdge!.targetHandle).toBe('bom-aux-in');
    expect(auxEdge!.type).toBe('smoothstep');
    expect(auxEdge!.style.strokeWidth).toBe(2);
    expect(auxEdge!.animated || auxEdge!.style?.strokeDasharray).toBeTruthy();
    const packEdge = edges.find((e) => e.target === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(packEdge!.source).toBe('o1');
    expect(packEdge!.sourceHandle).toBe('bom-pack-out');
    expect(packEdge!.targetHandle).toBe('bom-pack-in');
    expect(packEdge!.type).toBe('smoothstep');
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
