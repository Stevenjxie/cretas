# BOM 画布融合 Phase 3-1：辅料/包材 cell 上画布

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把辅料和包材做成画布上的独立 cell —— 每道工序挂一个辅料 cell，每个终端产出挂一个包材 cell，用量与关键属性直接印在 cell 上。

**Architecture:** 这两类 cell 是**从 BOM 数据派生的浮层节点**，不是工艺定义的一部分。它们渲染成 Vue Flow 节点，但在序列化回工艺定义时被滤掉。原型见 https://claude.ai/code/artifact/31729220-1a43-4b16-a1e9-fc2f38bcca7d

**Tech Stack:** Vue 3 + TypeScript + Vue Flow (`@vue-flow/core`) + Element Plus + Vitest。

## Global Constraints

- ⛔ **最重要的一条：辅料/包材 cell 绝不能进入工艺定义。** 设计要求「改辅料克数只动 BOM 草稿，不产生新工艺版本」。工艺节点一改就会改 revision hash，导致所有钉了旧修订的 BOM 需要重新对齐。序列化点在 `ProductProcessWorkflowEditor.vue:1837`（`nodes: flowNodes.value.map(serializeFlowNode)`），浮层节点必须在那里被排除。
- 禁止降级处理：不返回假数据。`null`/缺失要显示为「未配」「待归集」，不能显示 0 或空白冒充已配置。
- TypeScript 禁 `as any`；确需绕过用 `@ts-expect-error` + 原因注释。
- 不改后端。本计划纯前端 —— 辅料/包材的读写 API 已存在（`bomSeasoningApi`、`bomRecipeApi`、`ensureDraft`）。
- 不写数据库 migration。
- 并发提交纪律：`git commit -m "msg" -- <明确路径>`，提交后 `git show --name-only HEAD` 核对。
- 前端 vitest **无既有失败基线**，`npx vitest run src/views/system/product-processes` 与 `src/views/production/bom` 必须全绿。

## 现状（已实测，不要重新假设）

| 事实 | 位置 |
|---|---|
| 画布用 Vue Flow，自定义节点走 `<template #node-xxx>` 插槽 | `ProductProcessWorkflowEditor.vue:190-269` |
| 已有两个节点组件 | `WorkflowMaterialNode.vue` / `WorkflowProcessNode.vue` |
| 工艺定义序列化点 | `ProductProcessWorkflowEditor.vue:1837` |
| 反序列化点（definition → flowNodes） | `:1809` |
| **每工序辅料卡片已存在**，含 dosage/锅序/替代 | `views/production/bom/seasoning/ProcessSeasoningCard.vue`（158 行） |
| 辅料工作台（数据加载 + 冲突处理 + 乐观锁） | `views/production/bom/seasoning/BomAuxiliaryWorkspace.vue`（524 行） |
| 画布右侧已有 BOM 抽屉，挂 `bom-unified/index.vue`（68 行薄壳） | `:559` |
| `ensureDraft` 端点与前端封装已有 | `api/bom.ts:340`、`bomDraftLifecycle.ts` |
| BOM 页现有 **4** 个 tab：RAW / AUXILIARY / PACKAGING / **BYPRODUCT** | `bom/index.vue:2328-2331` |

⚠️ 本计划**不下线**抽屉和 BOM 页写入口 —— 那是 Phase 3-2。本期结束时两条路并存，画布是新增入口而不是唯一入口。这样每个任务都能独立回滚。

---

## File Structure

| 文件 | 责任 | 状态 |
|---|---|---|
| `workflow/bomOverlay.ts` | 纯函数：从 BOM 数据 + 工艺节点派生浮层节点与连线；判定浮层节点 | 新建 |
| `workflow/bomOverlayMarkers.ts` | 标记体系：从一行 BOM 数据算出该显示哪些标记 | 新建 |
| `workflow/WorkflowAuxiliaryNode.vue` | 辅料 cell 渲染 | 新建 |
| `workflow/WorkflowPackagingNode.vue` | 包材 cell 渲染 | 新建 |
| `workflow/ProductProcessWorkflowEditor.vue` | 接线：插槽注册、浮层注入、序列化排除、版本抬头 | 修改 |

---

### Task 1: 浮层节点绝不进入工艺定义（先建这道闸）

这是整个 Phase 3 的地基。**先把闸建好再造 cell**，否则后面每加一个 cell 都在赌它没污染工艺定义。

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/bomOverlay.ts`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts`

**Interfaces:**
- Produces:
  - `const BOM_OVERLAY_PREFIX = 'bom-overlay:'`
  - `function isBomOverlayNode(node: { id: string }): boolean`
  - `function stripBomOverlay<T extends { id: string }>(nodes: T[]): T[]`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import { BOM_OVERLAY_PREFIX, isBomOverlayNode, stripBomOverlay } from '../bomOverlay';

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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts
```

预期：4 个全 FAIL（模块不存在）。

- [ ] **Step 3: 写实现**

新建 `bomOverlay.ts`：

```typescript
/**
 * BOM 浮层节点 —— 辅料 / 包材 cell。
 *
 * ⛔ 这些节点【不是工艺定义的一部分】。设计要求「改辅料克数只动 BOM 草稿，
 * 不产生新工艺版本」，而工艺节点一改就会改 revision hash，导致所有钉了旧修订
 * 的 BOM 需要重新对齐。所以浮层节点必须在序列化回工艺定义时被滤掉
 * （见 ProductProcessWorkflowEditor.vue 的 serializeFlowNode 调用点）。
 */
export const BOM_OVERLAY_PREFIX = 'bom-overlay:';

export function isBomOverlayNode(node: { id: string }): boolean {
  return node.id.startsWith(BOM_OVERLAY_PREFIX);
}

export function stripBomOverlay<T extends { id: string }>(nodes: T[]): T[] {
  return nodes.filter((node) => !isBomOverlayNode(node));
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
npx vitest run src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts
```

预期：4 passed。

- [ ] **Step 5: 接进序列化点**

在 `ProductProcessWorkflowEditor.vue` 中：

1. import：`import { stripBomOverlay } from './bomOverlay';`
2. 找到 `:1837` 附近的 `nodes: flowNodes.value.map(serializeFlowNode)`，改为：

```typescript
    // ⛔ 浮层节点(辅料/包材 cell)是 BOM 数据的投影, 不属于工艺定义。
    // 混进去会改 revision hash → 改一克盐就让所有 BOM 需要重新对齐。
    nodes: stripBomOverlay(flowNodes.value).map(serializeFlowNode),
```

3. 同样检查 `:987` 和 `:845` 两处 `.map((node) => ...)`：读它们的上下文，判断是否也在构造工艺定义或校验输入。**是则一并加 `stripBomOverlay`，否则不动**。在报告里逐个说明你的判断依据。

- [ ] **Step 6: 写序列化隔离的回归测试**

新增到同一个 spec 文件（源码断言，因为该函数深埋在组件里）：

```typescript
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

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
});
```

- [ ] **Step 7: 变异验证（必做）**

把 Step 5 改回 `nodes: flowNodes.value.map(serializeFlowNode)`，重跑 spec，确认那条断言**变红**；还原，确认复绿。把前后输出贴进报告。

没有这一步，我们不知道这道闸会不会响。

- [ ] **Step 8: 回归 + 提交**

```bash
npx vitest run src/views/system/product-processes
npx vue-tsc --noEmit -p tsconfig.json
git commit -m "feat(workflow): BOM 浮层节点与工艺定义隔离闸" -- \
  web-admin/src/views/system/product-processes/workflow/bomOverlay.ts \
  web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts \
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue
git show --name-only HEAD
```

预期：vitest 全绿，vue-tsc 无新增错误。

---

### Task 2: 标记体系（默认状态不标，只标异常）

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/bomOverlayMarkers.ts`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlayMarkers.spec.ts`

**Interfaces:**
- Consumes: 无
- Produces：
  - `interface BomRowMarker { glyph: string; kind: string; title: string }`
  - `function markersForAuxiliaryRow(row: AuxiliaryRowInput): BomRowMarker[]`
  - `function markersForPackagingRow(row: PackagingRowInput): BomRowMarker[]`

**设计规则（原型已定，不要改）**：**默认状态不标，只标异常。** 共享成本是默认 → 不标；不共享才标 `◑`。不按锅序是默认 → 不标；勾了才标 `◷`。扫一眼就知道哪几行有特殊规则。

标记表：

| glyph | kind | 含义 | 触发条件 |
|---|---|---|---|
| `◷` | `pot` | 按锅序 | `subsequentPotRatio != null` |
| `⊘` | `free` | 不计入成本 | `countInSeasoning === false` |
| `⇄` | `sub` | 有替代物料 | `substituteCount > 0` |
| `◑` | `excl` | 成本只算部分产出 | `costScope` 存在且 `!== 'SHARED'` |
| `⊞` | `portion` | 按份数投料 | `perPortion === true` |
| `○` | `opt` | 配方可选项 | `isOptional === true` |
| `▤` | `lvl` | 包装层级 | `packagingSpecId != null` |

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import { markersForAuxiliaryRow, markersForPackagingRow } from '../bomOverlayMarkers';

const glyphs = (markers: { glyph: string }[]) => markers.map((m) => m.glyph);

describe('辅料行标记', () => {
  it('全默认状态不产生任何标记', () => {
    expect(markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: true,
      substituteCount: 0, costScope: 'SHARED',
    })).toEqual([]);
  });

  it('按锅序标 ◷ 并在 title 带出比例', () => {
    const markers = markersForAuxiliaryRow({
      subsequentPotRatio: 0.6, countInSeasoning: true,
      substituteCount: 0, costScope: 'SHARED',
    });
    expect(glyphs(markers)).toEqual(['◷']);
    expect(markers[0].title).toContain('60');
  });

  it('不计入成本标 ⊘', () => {
    expect(glyphs(markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: false,
      substituteCount: 0, costScope: 'SHARED',
    }))).toEqual(['⊘']);
  });

  it('有替代标 ⇄ 并在 title 带出数量', () => {
    const markers = markersForAuxiliaryRow({
      subsequentPotRatio: null, countInSeasoning: true,
      substituteCount: 2, costScope: 'SHARED',
    });
    expect(glyphs(markers)).toEqual(['⇄']);
    expect(markers[0].title).toContain('2');
  });

  it('成本不共享才标 ◑ —— SHARED 与缺失都不标', () => {
    const base = { subsequentPotRatio: null, countInSeasoning: true, substituteCount: 0 };
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: 'SHARED' }))).toEqual([]);
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: null }))).toEqual([]);
    expect(glyphs(markersForAuxiliaryRow({ ...base, costScope: 'OUTPUT_EXCLUSIVE' }))).toEqual(['◑']);
  });

  it('多个条件同时成立时全部标出, 顺序稳定', () => {
    expect(glyphs(markersForAuxiliaryRow({
      subsequentPotRatio: 0.6, countInSeasoning: true,
      substituteCount: 1, costScope: 'OUTPUT_GROUP',
    }))).toEqual(['◷', '⇄', '◑']);
  });
});

describe('包材行标记', () => {
  it('全默认不标', () => {
    expect(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: false, packagingSpecId: null,
    })).toEqual([]);
  });

  it('可选 / 按份 / 层级各自标出', () => {
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: true, perPortion: false, packagingSpecId: null,
    }))).toEqual(['○']);
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: true, packagingSpecId: null,
    }))).toEqual(['⊞']);
    expect(glyphs(markersForPackagingRow({
      substituteCount: 0, isOptional: false, perPortion: false, packagingSpecId: 'spec-1',
    }))).toEqual(['▤']);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__/bomOverlayMarkers.spec.ts
```

预期：全 FAIL（模块不存在）。

- [ ] **Step 3: 写实现**

按上表实现两个函数。要点：

- 顺序固定为表格中的顺序（`◷ ⊘ ⇄ ◑` / `⇄ ○ ⊞ ▤`），测试依赖顺序稳定
- `costScope` 为 `null`/`undefined`/`'SHARED'` 一律不标
- `title` 是 hover 提示，必须带具体值：锅序带百分比、替代带数量、层级带层级名（无则写「包装层级」）
- 不要 export 一个「所有标记」的常量给调用方自己筛 —— 判定逻辑集中在这两个函数里

- [ ] **Step 4: 跑测试确认通过**

预期：全 passed。

- [ ] **Step 5: 变异验证（必做）**

把「默认状态不标」这条规则破坏掉 —— 例如让 `costScope === 'SHARED'` 也返回 `◑`，重跑，确认 `成本不共享才标 ◑` 那条**变红**。还原复绿。贴前后输出。

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(workflow): BOM 行标记体系 —— 默认状态不标, 只标异常" -- \
  web-admin/src/views/system/product-processes/workflow/bomOverlayMarkers.ts \
  web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlayMarkers.spec.ts
git show --name-only HEAD
```

---

### Task 3: 从 BOM 数据派生浮层节点与连线

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/bomOverlay.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts`

**Interfaces:**
- Consumes: Task 1 的 `BOM_OVERLAY_PREFIX`；Task 2 的两个 markers 函数
- Produces:
  - `function deriveBomOverlay(input: BomOverlayInput): { nodes: OverlayNode[]; edges: OverlayEdge[] }`

**布局规则（原型已定）**：辅料 cell 在它服务的工序**正上方**；包材 cell 在它服务的终端产出**右侧**。连线是虚线。

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import { BOM_OVERLAY_PREFIX, deriveBomOverlay } from '../bomOverlay';

const processNode = (id: string, x: number, y: number) =>
  ({ id, kind: 'PROCESS' as const, position: { x, y }, data: { processName: '腌制' } });
const outputNode = (id: string, x: number, y: number) =>
  ({ id, kind: 'FINISHED_GOOD' as const, position: { x, y }, data: { name: '酱鸭腿' } });

describe('从 BOM 派生浮层', () => {
  it('每道有辅料的工序派生一个辅料 cell, 挂在工序正上方', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400)],
      auxiliaryByProcess: { p1: [{ materialName: '食盐', dosageText: '12 g/kg', markers: [] }] },
      packagingByOutput: {},
    });
    const aux = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(aux).toBeTruthy();
    expect(aux!.position.y).toBeLessThan(400);
    expect(aux!.type).toBe('bomAuxiliary');
  });

  it('没有辅料的工序也派生 cell —— 空态必须可见', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400)],
      auxiliaryByProcess: {},
      packagingByOutput: {},
    });
    const aux = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(aux, '空态 cell 不能不渲染 —— 用户要看得见「未配」').toBeTruthy();
    expect(aux!.data.rows).toEqual([]);
  });

  it('每个终端产出派生一个包材 cell, 挂在产出右侧', () => {
    const { nodes } = deriveBomOverlay({
      workflowNodes: [outputNode('o1', 900, 200)],
      auxiliaryByProcess: {},
      packagingByOutput: { o1: [{ materialName: '真空袋', dosageText: '1 个/盒', markers: [] }] },
    });
    const pack = nodes.find((n) => n.id === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(pack).toBeTruthy();
    expect(pack!.position.x).toBeGreaterThan(900);
    expect(pack!.type).toBe('bomPackaging');
  });

  it('派生的连线是虚线且两端正确', () => {
    const { edges } = deriveBomOverlay({
      workflowNodes: [processNode('p1', 300, 400), outputNode('o1', 900, 200)],
      auxiliaryByProcess: { p1: [] },
      packagingByOutput: { o1: [] },
    });
    const auxEdge = edges.find((e) => e.source === `${BOM_OVERLAY_PREFIX}aux:p1`);
    expect(auxEdge!.target).toBe('p1');
    expect(auxEdge!.animated || auxEdge!.style?.strokeDasharray).toBeTruthy();
    const packEdge = edges.find((e) => e.target === `${BOM_OVERLAY_PREFIX}pack:o1`);
    expect(packEdge!.source).toBe('o1');
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
      auxiliaryByProcess: { p1: [] },
      packagingByOutput: { o1: [] },
    });
    expect(nodes.length).toBeGreaterThan(0);
    expect(nodes.every((n) => n.id.startsWith(BOM_OVERLAY_PREFIX))).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
npx vitest run src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts
```

预期：新增 6 条 FAIL，Task 1 的 4 条仍 PASS。

- [ ] **Step 3: 写实现**

要点：

- 只对 `kind === 'PROCESS'` 派生辅料 cell，只对 `kind === 'FINISHED_GOOD'` 派生包材 cell
- **没有辅料的工序也要派生空 cell** —— 空态可见是防呆要求，不是可选项
- 辅料 cell 位置：`{ x: processNode.position.x, y: processNode.position.y - AUX_OFFSET_Y }`，`AUX_OFFSET_Y` 取 220
- 包材 cell 位置：`{ x: outputNode.position.x + PACK_OFFSET_X, y: outputNode.position.y }`，`PACK_OFFSET_X` 取 220
- 连线用 `style: { strokeDasharray: '5 4' }` 表达虚线
- 节点 `type` 分别是 `'bomAuxiliary'` / `'bomPackaging'`（对应 Task 4/5 的插槽名）

- [ ] **Step 4: 跑测试确认通过**

预期：10 条全绿。

- [ ] **Step 5: 变异验证（必做）**

把「空工序也派生 cell」破坏掉（改成有辅料才派生），重跑，确认 `没有辅料的工序也派生 cell` 变红。还原复绿。贴输出。

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(workflow): 从 BOM 数据派生辅料/包材浮层节点与虚线" -- \
  web-admin/src/views/system/product-processes/workflow/bomOverlay.ts \
  web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlay.spec.ts
git show --name-only HEAD
```

---

### Task 4: 辅料 cell 组件

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/WorkflowAuxiliaryNode.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowAuxiliaryNode.spec.ts`

**Interfaces:**
- Consumes: Task 2 的 marker 类型、Task 3 派生的 `data.rows`
- Produces: 一个 Vue Flow 自定义节点组件，props `{ id: string; data: AuxiliaryCellData }`，emit `{ 'add-row': []; 'edit-row': [rowId: string]; 'open-detail': [] }`

**渲染契约（原型已定）**：

- 抬头：`辅料 cell`；标题：`<工序名> · 辅料`
- 副标题：`N 种`；有任一行带锅序时改为 `N 种 · 报工需录锅数`
- 每行：物料名 + 标记位 + 用量（用量右对齐、等宽数字）
- 空态：不显示行，显示 `0 种 · 未配`，用警示色
- 灰态：`standardUsageSupported === false` 时整个 cell 灰掉，显示原因与去处，不可点「加辅料」

- [ ] **Step 1: 写失败测试**

用 `@vue/test-utils` 挂载组件（本仓已有该依赖，参考 `WorkflowProcessNode.spec.ts` 的写法）：

```typescript
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowAuxiliaryNode from '../WorkflowAuxiliaryNode.vue';

const baseData = {
  processName: '卤制',
  usageSupported: true,
  rows: [
    { id: 'r1', materialName: '八角', dosageText: '2 g/kg',
      markers: [{ glyph: '◷', kind: 'pot', title: '首锅 100% · 后续 60%' }] },
    { id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] },
  ],
};

describe('辅料 cell', () => {
  it('渲染工序名与行数', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    expect(w.text()).toContain('卤制');
    expect(w.text()).toContain('2 种');
  });

  it('有锅序时副标题说出对报工的后果', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    expect(w.text(), '技术员勾一个开关车间就多两栏, 必须写出来').toContain('报工需录锅数');
  });

  it('无锅序时不说报工', () => {
    const data = { ...baseData, rows: [{ id: 'r2', materialName: '生抽', dosageText: '15 g/kg', markers: [] }] };
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data } });
    expect(w.text()).not.toContain('报工需录锅数');
  });

  it('标记渲染出 glyph 且 title 可查', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    const marker = w.find('[data-testid="aux-marker-pot"]');
    expect(marker.exists()).toBe(true);
    expect(marker.attributes('title')).toContain('60');
  });

  it('空态显示「未配」而不是空白', () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: { ...baseData, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.find('[data-testid="aux-empty"]').exists()).toBe(true);
  });

  it('灰态说明原因且不给加辅料入口', () => {
    const w = mount(WorkflowAuxiliaryNode, {
      props: { id: 'x', data: { ...baseData, usageSupported: false, rows: [] } },
    });
    expect(w.text()).toContain('换算契约');
    expect(w.find('[data-testid="aux-add"]').exists()).toBe(false);
  });

  it('可配时给加辅料入口并 emit', async () => {
    const w = mount(WorkflowAuxiliaryNode, { props: { id: 'x', data: baseData } });
    await w.find('[data-testid="aux-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
npx vitest run src/views/system/product-processes/workflow/__tests__/WorkflowAuxiliaryNode.spec.ts
```

预期：7 条全 FAIL。

- [ ] **Step 3: 写组件**

按渲染契约实现。样式参考 `WorkflowProcessNode.vue` 的既有节点观感（边框、左侧色条、圆角），辅料用琥珀色系区分。必须带 `data-testid`：`aux-add` / `aux-empty` / `aux-marker-<kind>`。

- [ ] **Step 4: 跑测试确认通过**

预期：7 passed。

- [ ] **Step 5: 变异验证（必做）**

把「有锅序时副标题说报工」那条去掉，重跑，确认对应用例变红；还原复绿。贴输出。

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(workflow): 辅料 cell 组件 —— 用量与锅序后果直接印在方块上" -- \
  web-admin/src/views/system/product-processes/workflow/WorkflowAuxiliaryNode.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowAuxiliaryNode.spec.ts
git show --name-only HEAD
```

---

### Task 5: 包材 cell 组件

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/WorkflowPackagingNode.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowPackagingNode.spec.ts`

**Interfaces:**
- Consumes: Task 2 的 marker 类型、Task 3 派生的 `data.rows`
- Produces: props `{ id: string; data: PackagingCellData }`，emit `{ 'add-row': []; 'edit-row': [rowId: string] }`

**渲染契约**：

- 抬头 `包材 cell`；标题 `<产出名> · 包材`
- 副标题必须带**分母**：`N 种 · 每 1 <SKU 基本单位>成品`
- 多层包装时副标题改为 `分 N 层 · 每 1 <单位>成品`
- 每行：物料名 + 标记位 + 用量（含分母，如 `0.05 个/kg`）
- **换算过的用量必须能看到原始表达**：`0.05 个/kg` 的 title 要写 `= 1 个 / 20 kg`
- 空态：`0 种 · 未配` + `缺包材，本条工艺发布不了`

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkflowPackagingNode from '../WorkflowPackagingNode.vue';

const data = {
  outputName: '鸭油',
  baseUnit: 'kg',
  rows: [{
    id: 'r1', materialName: '塑料桶 20L', dosageText: '0.05 个/kg',
    naturalHint: '= 1 个 / 20 kg', markers: [],
  }],
};

describe('包材 cell', () => {
  it('副标题带出分母', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    expect(w.text(), '「1 个/盒」和「1 个/kg」不是一回事, 分母必须写出来').toContain('每 1 kg');
  });

  it('换算过的用量保留原始表达', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data } });
    const cell = w.find('[data-testid="pack-qty-r1"]');
    expect(cell.attributes('title'), '「0.05 个」对仓管毫无意义, 「1 桶装 20kg」才是他认识的').toContain('20 kg');
  });

  it('多层包装副标题说层数', () => {
    const layered = { ...data, rows: [
      { id: 'a', materialName: '内袋', dosageText: '1 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '内袋' }] },
      { id: 'b', materialName: '外箱', dosageText: '0.125 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '1 箱 8 盒' }] },
    ] };
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: layered } });
    expect(w.text()).toContain('分 2 层');
  });

  it('空态说出发布后果', () => {
    const w = mount(WorkflowPackagingNode, { props: { id: 'x', data: { ...data, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.text(), '缺包材整条工艺发布不了, 这个后果要提前说').toContain('发布不了');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__/WorkflowPackagingNode.spec.ts
```

预期：4 条全 FAIL（组件不存在）。

- [ ] **Step 3: 写组件**

按上面的渲染契约实现。样式与辅料 cell 同一套骨架（边框、左侧色条、圆角），包材用暗莓色系区分。必须带 `data-testid`：`pack-add` / `pack-empty` / `pack-qty-<rowId>` / `pack-marker-<kind>`。

副标题的分母取自 `data.baseUnit`，**不要写死「盒」** —— 按重量卖的副产品基本单位是 kg。行内用量的 `title` 属性放 `naturalHint`（原始表达），没有 `naturalHint` 时不设 title 而不是设成空串。

- [ ] **Step 4: 跑测试确认通过**

```bash
npx vitest run src/views/system/product-processes/workflow/__tests__/WorkflowPackagingNode.spec.ts
```

预期：4 passed。

- [ ] **Step 5: 变异验证（必做）**

把副标题里的分母去掉（例如把 `每 1 {{ data.baseUnit }}` 改成固定的 `每 1 份`），重跑，确认 `副标题带出分母` 那条**变红**；还原，确认复绿。把前后输出贴进报告。

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(workflow): 包材 cell 组件 —— 分母与原始表达都必须可见" -- \
  web-admin/src/views/system/product-processes/workflow/WorkflowPackagingNode.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/WorkflowPackagingNode.spec.ts
git show --name-only HEAD
```

---

### Task 6: 接进画布

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlayIntegration.source.spec.ts`（新建）

**Interfaces:**
- Consumes: Task 1/3 的 `deriveBomOverlay` / `stripBomOverlay`，Task 4/5 的两个组件
- Produces: 画布上真实渲染出辅料/包材 cell

- [ ] **Step 0: ⛔ 两个 cell 组件缺 `<Handle>`，虚线会挂不上（已实测）**

Vue Flow 的边挂在 handle 上。既有节点都声明了（`WorkflowMaterialNode.vue:14-15`：`<Handle type="target" :position="Position.Left" id="input" />` / `type="source"`），但 Task 4/5 产出的两个 cell 组件 **`Handle` 命中数为 0** —— Task 3 派生的虚线会连不上，边根本渲染不出来。

补法（按连线方向定 handle 类型与位置）：

| 组件 | 需要的 handle | 理由 |
|---|---|---|
| `WorkflowAuxiliaryNode.vue` | `type="source"` + `Position.Bottom`，id 与 Task 3 派生边的 `sourceHandle` 一致 | 辅料 cell 在工序上方，边从它底部出发指向工序 |
| `WorkflowPackagingNode.vue` | `type="target"` + `Position.Left`，id 与派生边的 `targetHandle` 一致 | 包材 cell 在产出右侧，边从产出进入它左侧 |

**先读 Task 3 在 `bomOverlay.ts` 里实际生成的边**（是否设了 `sourceHandle`/`targetHandle`，值是什么），再决定 handle 的 `id`。两边对不上时边同样不渲染，而且不报错——只是画面上没有线。

⚠️ 两个 cell 的单测挂载时没有 VueFlow provider（沿用 `WorkflowMaterialNode.spec.ts` 的做法）。加 `<Handle>` 后单测可能需要 stub，**不要为了让单测过而把 Handle 去掉** —— 那是拿掉功能迁就测试。

- [ ] **Step 0b: 权限态 —— 无写权限不给编辑入口**

两个 cell 组件都没有 `canWrite` prop（brief 的接口只给了 `{ id, data }`），所以「加辅料 / 加包材」按钮对只读用户也会显示。画布本身有 `canEdit`。接线时把它传下去并据此隐藏入口，或在 Step 1 的插槽里包一层判断。

- [ ] **Step 1: 注册插槽**

在 `<VueFlow>` 内，参照既有的 `<template #node-material>` 写法，新增：

```vue
          <template #node-bomAuxiliary="slotProps">
            <WorkflowAuxiliaryNode
              :id="slotProps.id"
              :data="slotProps.data"
              @add-row="openAuxiliaryEditor(slotProps.data.processNodeId)"
              @edit-row="(rowId) => openAuxiliaryEditor(slotProps.data.processNodeId, rowId)"
            />
          </template>
          <template #node-bomPackaging="slotProps">
            <WorkflowPackagingNode
              :id="slotProps.id"
              :data="slotProps.data"
              @add-row="openPackagingEditor(slotProps.data.outputNodeId)"
              @edit-row="(rowId) => openPackagingEditor(slotProps.data.outputNodeId, rowId)"
            />
          </template>
```

**编辑入口先复用既有能力**：`openAuxiliaryEditor` 直接打开现有的 `SeasoningBindingDialog`（`views/production/bom/seasoning/SeasoningBindingDialog.vue`，413 行，已含替代物料与等价系数），`openPackagingEditor` 打开 BOM 抽屉并定位到包材。**本期不重写编辑面** —— 先让 cell 可见可用，编辑面替换留给 Phase 3-2。

- [ ] **Step 1b: ⛔ 每次 hydrate 之后必须重新派生浮层（审查发现的硬要求）**

Task 1 把剥离放进了多用途的 `currentDefinition()`。审查查实有**三个非持久化消费方**拿剥离后的载荷去 `hydrate()`（整体替换 `flowNodes`/`flowEdges`）：

| 消费方 | 位置 | 后果 |
|---|---|---|
| `undo()` | `:1888-1892`（经 `remember()` 快照） | **按一次 Ctrl+Z，画布上所有浮层 cell 消失** |
| `handleAutoLayout()` | `:2838-2842` | 点一次自动布局，浮层全没 |
| `reconcileLoadedUnits()` | `:1465-1483` | 后台单位对齐时静默抹掉 |

这不是后端泄漏（闸仍然是严的），是可用性断裂。**修法：把「重新派生浮层」做成 hydrate 的必经步骤**，而不是在三个调用点各补一次 —— 后者漏一个就又是同样的 bug，而且将来第四个 hydrate 调用方还会再踩。

写一个 `hydrateWithOverlay(definition)`（或在 `hydrate` 末尾统一调用派生），让「hydrate 之后浮层一定在」成为结构性保证。

配套断言（放 `bomOverlayIntegration.source.spec.ts`）：

```typescript
  it('每个 hydrate 调用点之后浮层都会被重新派生', () => {
    // 逐个点补是漏的源头 —— 派生必须长在 hydrate 里
    const hydrateBody = source.slice(source.indexOf('function hydrate'), source.indexOf('function hydrate') + 1200);
    expect(hydrateBody).toMatch(/deriveBomOverlay|refreshBomOverlay/);
  });
```

**变异验证**：把 hydrate 里的派生调用去掉，确认该断言变红；还原复绿。

- [ ] **Step 2: 注入浮层**

在 BOM 数据加载完成后调用 `deriveBomOverlay`，把结果合并进 `flowNodes` / `flowEdges`。**每次重新派生前先 `stripBomOverlay` 清掉上一批**，否则会累积重复节点。

- [ ] **Step 3: 写集成断言**

```typescript
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf-8');

describe('画布接入 BOM 浮层', () => {
  it('注册了两类 cell 的插槽', () => {
    expect(source).toContain('#node-bomAuxiliary');
    expect(source).toContain('#node-bomPackaging');
  });

  it('注入前先清旧浮层, 避免累积', () => {
    expect(source).toMatch(/stripBomOverlay\(flowNodes\.value\)/);
  });

  it('序列化仍然剥离浮层(Task 1 的闸没被绕过)', () => {
    expect(source).toMatch(/nodes:\s*stripBomOverlay\(flowNodes\.value\)\.map\(serializeFlowNode\)/);
  });
});
```

- [ ] **Step 4: 跑全量前端回归**

```bash
cd web-admin
npx vitest run src/views/system/product-processes
npx vitest run src/views/production/bom
npx vue-tsc --noEmit -p tsconfig.json
```

预期：全绿（前端无既有失败基线），vue-tsc 无新增错误。

- [ ] **Step 5: 真浏览器验证（本任务必做，不可省）**

源码断言证明不了画布真的画出来了。启动 dev server，打开一个有工艺和 BOM 的产品，**截图**确认：

1. 每道工序上方有辅料 cell，虚线连到工序
2. 每个终端产出右侧有包材 cell
3. 没配辅料的工序显示「未配」而不是不渲染
4. 拖动工序节点后，它的辅料 cell 跟着动（或至少不错位到画面外）
5. **保存草稿后重新加载，工艺定义里没有多出浮层节点** —— 这条是 Task 1 那道闸的真实验证

把截图与第 5 条的核对方式写进报告。

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(workflow): 辅料/包材 cell 接进画布" -- \
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/bomOverlayIntegration.source.spec.ts
git show --name-only HEAD
```

---

## 收尾验收

- [ ] `npx vitest run src/views/system/product-processes` 全绿
- [ ] `npx vitest run src/views/production/bom` 全绿
- [ ] `npx vue-tsc --noEmit` 无新增错误
- [ ] 浏览器实测：辅料/包材 cell 可见，空态可见，虚线正确
- [ ] **保存草稿后工艺定义里没有浮层节点**（Task 1 的闸真实生效）
- [ ] 每个新增测试都做过变异验证，前后输出在报告里

**不做的事**：不下线抽屉、不下线 BOM 页写入口、不重写编辑面、不碰后端、不写 migration、不推 origin、不部署。那些是 Phase 3-2。
