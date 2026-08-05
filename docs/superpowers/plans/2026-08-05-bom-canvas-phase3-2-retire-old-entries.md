# BOM 画布融合 Phase 3-2：画布成为唯一配置入口

> ## ⏸️ 已搁置 —— 不执行（2026-08-05 owner 拍板）
>
> **决定：旧入口（画布 BOM 抽屉 + BOM 页写入口）保留，作为画布之外的第二个入口。**
>
> 理由：两条路写同一份数据、走同一套 API，画布 cell 从该数据派生，不冲突；辅料侧本来就有乐观锁与冲突处理。
> 而且 Phase 3-1 从未真机验证过（沙箱连不上后端），留着旧入口等于真机出问题时用户有路可走 ——
> 比本计划设计的「开关回退」更稳妥，因为不需要改代码。
>
> **本文保留的价值是那条依赖发现**（见「现状」表）：**包材编辑目前只有抽屉这一个入口**，
> 辅料已独立成 `SeasoningBindingDialog` 而包材仍调 `openBomDrawer`。将来若要拆抽屉，
> 必须先做 Task 1（画布上的包材弹窗），不能直接摘。
>
> 若将来重启本计划：先重新核实「现状」表的每一行，代码形状会变。

---

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让画布成为 BOM 的唯一配置入口 —— 下线画布里的 BOM 抽屉与 BOM 页的写入口，BOM 页保留为只读。

**Architecture:** 先补齐画布上缺的那块编辑能力（包材弹窗），再下线旧入口。下线做成**一行可回退**：隐藏入口而不是删组件，真机出问题能立刻退回。

**Tech Stack:** Vue 3 + TypeScript + Element Plus + Vitest。

## Global Constraints

- ⛔ **下线必须可回退。** 用一个集中的开关控制旧入口的可见性，不要删组件、不要删路由。Phase 3-1 尚未经过真机验证（沙箱无法连通后端），一旦真机暴露问题，回退旧入口必须是改一个值而不是 revert 一堆 commit。
- 禁止降级处理：不返回假数据。缺失显示「未配」「待补全」，不显示 0 或空白。
- TypeScript 禁 `as any`。
- 不改后端，不写 migration。
- 并发提交纪律：`git commit -m "msg" -- <明确路径>`，提交后 `git show --name-only HEAD` 核对。
- 前端 vitest 无既有失败基线：`npx vitest run src/views/system/product-processes src/views/production/bom` 必须全绿（当前 416/416，48 文件）。

## 现状（已实测，不要重新假设）

| 事实 | 位置 |
|---|---|
| **辅料编辑已不依赖抽屉** —— 直接开 `SeasoningBindingDialog` | `ProductProcessWorkflowEditor.vue` `openAuxiliaryEditor` |
| **包材编辑只有抽屉这一个入口** —— `openBomDrawer(skuId,'PACKAGING')` | 同上 `openPackagingEditor` |
| 抽屉挂 `bom-unified/index.vue`（薄壳，2 个 tab，接受 `initialCategory`） | `bomUnifiedPanelLoader.ts` |
| BOM 页 4 个 tab：RAW / AUXILIARY / PACKAGING / BYPRODUCT | `bom/index.vue` |
| 包材表单字段：物料、每 1 份成品用量、可选、替代物料 + 等价系数、包装层级 | `bom/index.vue:536-587, 1233-1327` |
| **包材用量落库在 `standardQuantity`**（表单字段名叫 `naturalQuantity`，提交前被复制过去） | `bom/index.vue:1286, 1327` |

⚠️ 最后一条是 Phase 1 踩过的坑：字段名与落库列不一致。**新弹窗必须沿用同一套读写口径**，不要"顺手改成看起来更对的名字"。

---

## File Structure

| 文件 | 责任 | 状态 |
|---|---|---|
| `workflow/PackagingBindingDialog.vue` | 画布上的包材编辑弹窗 | 新建 |
| `workflow/bomEntryFlags.ts` | 旧入口可见性开关（唯一真相） | 新建 |
| `workflow/ProductProcessWorkflowEditor.vue` | 包材入口改指新弹窗；抽屉按开关隐藏 | 修改 |
| `production/bom/index.vue` | 写入口按开关隐藏，保留只读 | 修改 |

---

### Task 1: 画布上的包材编辑弹窗

拆抽屉之前必须先有它，否则包材直接变成只能看不能改。

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/PackagingBindingDialog.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/PackagingBindingDialog.spec.ts`

**Interfaces:**
- Consumes: 无（自包含）
- Produces: props `{ modelValue: boolean; factoryId: string; recipeId: string; outputName: string; baseUnit: string; row?: PackagingRowPayload | null }`，emit `{ 'update:modelValue'; saved: []; conflict: [] }`

- [ ] **Step 1: 先读现有包材表单，列出它到底做了什么**

不要凭这份计划的字段表就动手。打开 `web-admin/src/views/production/bom/index.vue`，把包材新增/编辑路径完整读一遍，在报告里列出：

1. 表单字段与各自的校验规则
2. **提交时 payload 的确切形状** —— 特别是 `naturalQuantity` 与 `standardQuantity` 的关系（表单叫前者，落库是后者）
3. 编辑回填时从哪个字段读回
4. 替代物料的等价系数在什么条件下必填
5. 调用的是哪个 API 函数

这一步的产出是报告里的一张表。**跳过它直接写弹窗，等于重演 Phase 1 里"读了个没人写的字段"那次事故。**

- [ ] **Step 2: 写失败测试**

用 `@vue/test-utils`（房内写法见同目录 `__tests__/WorkflowPackagingNode.spec.ts`）。至少覆盖：

```typescript
describe('包材编辑弹窗', () => {
  it('用量输入框的分母来自传入的 baseUnit, 不是写死的「盒」', () => {
    // 按重量卖的副产品 baseUnit 是 kg, 写死会算错
  });

  it('用量为空或非正数时不允许保存', () => {
    // 禁止降级: 不能默默存 0
  });

  it('跨单位替代物料未填等价系数时不允许保存', () => {
    // 系统不猜换算关系
  });

  it('同单位替代默认 1:1 且只读', () => {});

  it('编辑既有行时回填的是落库字段而不是表单同名字段', () => {
    // Phase 1 事故: 表单叫 naturalQuantity, 落库在 standardQuantity
  });

  it('保存失败时不 emit saved, 并把后端 message 原样显示', () => {
    // 禁止降级: 不吞错误、不显示「操作失败」这种 generic 文案
  });
});
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/system/product-processes/workflow/__tests__/PackagingBindingDialog.spec.ts
```

预期：全 FAIL（组件不存在）。

- [ ] **Step 4: 写弹窗**

复用 Step 1 查到的 API 与 payload 形状。样式与 `SeasoningBindingDialog.vue` 保持一致（同一套 el-dialog 骨架、同样的冲突处理与乐观锁写法）。

**不要新建 API 函数** —— 用 BOM 页已经在用的那个。

- [ ] **Step 5: 跑测试确认通过**

- [ ] **Step 6: 变异验证（必做）**

把「分母来自 baseUnit」改成写死 `'盒'`，确认对应用例变红；还原复绿。贴前后输出。

- [ ] **Step 7: 接进画布并提交**

把 `openPackagingEditor` 从 `openBomDrawer(skuId,'PACKAGING')` 改为打开新弹窗，并把 `rowId` 真正用上（这是 Phase 3-1 遗留的 should-fix：点某一行只能开抽屉页签，跳不到那一行）。

```bash
npx vitest run src/views/system/product-processes src/views/production/bom
npx vue-tsc --noEmit -p tsconfig.json
git commit -m "feat(workflow): 画布上的包材编辑弹窗, 不再依赖 BOM 抽屉" -- \
  web-admin/src/views/system/product-processes/workflow/PackagingBindingDialog.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/PackagingBindingDialog.spec.ts \
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue
git show --name-only HEAD
```

---

### Task 2: 旧入口开关（可回退的下线）

**Files:**
- Create: `web-admin/src/views/system/product-processes/workflow/bomEntryFlags.ts`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/bomEntryFlags.spec.ts`

**Interfaces:**
- Produces: `export const LEGACY_BOM_WRITE_ENTRIES_ENABLED: boolean`（单一真相）

- [ ] **Step 1: 写模块**

```typescript
/**
 * 旧 BOM 写入口(画布抽屉 + BOM 页新增/编辑/删除)的可见性开关。
 *
 * ⛔ 为什么是开关而不是删除:
 * 画布 cell(Phase 3-1)尚未经过真机验证 —— 当时沙箱无法连通后端, 只做了
 * 组件级 harness 验证。真机若暴露问题, 回退旧入口必须是改这一个值,
 * 而不是 revert 一串 commit。
 *
 * 真机验证通过、且画布入口稳定运行一段时间后, 再删除本文件与被它
 * 挡住的代码 —— 那时删除是安全的, 现在不是。
 */
export const LEGACY_BOM_WRITE_ENTRIES_ENABLED = false;
```

- [ ] **Step 2: 写测试**

```typescript
import { describe, expect, it } from 'vitest';
import { LEGACY_BOM_WRITE_ENTRIES_ENABLED } from '../bomEntryFlags';

describe('旧 BOM 写入口开关', () => {
  it('默认关闭', () => {
    expect(LEGACY_BOM_WRITE_ENTRIES_ENABLED).toBe(false);
  });

  it('是布尔常量而不是函数或对象 —— 回退时只需改一个值', () => {
    expect(typeof LEGACY_BOM_WRITE_ENTRIES_ENABLED).toBe('boolean');
  });
});
```

- [ ] **Step 3: 跑测试并提交**

```bash
npx vitest run src/views/system/product-processes/workflow/__tests__/bomEntryFlags.spec.ts
git commit -m "feat(bom): 旧写入口开关 —— 下线做成一行可回退" -- \
  web-admin/src/views/system/product-processes/workflow/bomEntryFlags.ts \
  web-admin/src/views/system/product-processes/workflow/__tests__/bomEntryFlags.spec.ts
git show --name-only HEAD
```

---

### Task 3: 下线画布抽屉

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Test: `web-admin/src/views/system/product-processes/workflow/__tests__/legacyBomEntryRetired.source.spec.ts`（新建）

- [ ] **Step 1: 确认抽屉已无必需调用方**

```bash
cd web-admin/src
grep -rn "openBomDrawer" views/system/product-processes/workflow/
```

Task 1 之后包材入口应已改指新弹窗。若仍有调用方，**先弄清它是什么再动手**，不要直接摘。在报告里列出所有命中及其处置。

- [ ] **Step 2: 按开关隐藏抽屉入口**

隐藏「配置 BOM」按钮与抽屉本身（`v-if="LEGACY_BOM_WRITE_ENTRIES_ENABLED"`）。**保留组件与懒加载逻辑** —— 开关翻回 `true` 就能恢复。

- [ ] **Step 3: 写断言**

```typescript
describe('画布 BOM 抽屉已下线', () => {
  it('抽屉入口受开关控制而不是被删除', () => {
    expect(source).toContain('LEGACY_BOM_WRITE_ENTRIES_ENABLED');
    expect(source, '组件要留着, 翻开关就能恢复').toContain('BomUnifiedPanel');
  });

  it('包材编辑不再走抽屉', () => {
    const fn = source.slice(source.indexOf('function openPackagingEditor'), source.indexOf('function openPackagingEditor') + 800);
    expect(fn).not.toMatch(/openBomDrawer/);
  });
});
```

- [ ] **Step 4: 跑测试 + 变异 + 提交**

变异：把 `openPackagingEditor` 改回调 `openBomDrawer`，确认第二条断言变红；还原复绿。

```bash
npx vitest run src/views/system/product-processes src/views/production/bom
npx vue-tsc --noEmit -p tsconfig.json
git commit -m "feat(workflow): 下线画布 BOM 抽屉入口(开关可回退)" -- \
  web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue \
  web-admin/src/views/system/product-processes/workflow/__tests__/legacyBomEntryRetired.source.spec.ts
git show --name-only HEAD
```

---

### Task 4: BOM 页写入口下线，保留只读

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue`
- Test: `web-admin/src/views/production/bom/__tests__/BomPageReadOnly.source.spec.ts`（新建）

**必须保留的**：成本汇总、ECN 变更履历、导出、四个 tab 的**查看**能力、版本历史。
**要隐藏的**：新增/编辑/删除按钮、AI 批量导入、以及任何触发写操作的入口。

- [ ] **Step 1: 先枚举写入口**

```bash
cd web-admin/src/views/production/bom
grep -nE "@click=\"handle(Add|Edit|Delete)|@click=\"open.*Dialog|type=\"primary\"" index.vue | head -40
```

在报告里列出每一个命中及其判定（写入口 / 只读操作 / 导航）。**不要凭按钮颜色判断** —— 有些 primary 按钮是导出或跳转。

- [ ] **Step 2: 按开关隐藏，并给只读用户一个去处**

隐藏写入口时，**不能只是让按钮消失**。按仓库的防呆规则 5（dead-end 改导航），在 tab 区域加一条常驻提示，说明配置已迁到画布并给跳转：

```
原辅料与包材的配置已迁至「产品工序配置」画布 → [去画布配置]
```

跳转带上当前 `productTypeId`，让用户落在同一个产品上。**没有跳转的下线是把用户扔在死路上。**

- [ ] **Step 3: 写断言**

```typescript
describe('BOM 页写入口已下线, 只读保留', () => {
  it('写入口受开关控制', () => {
    expect(source).toContain('LEGACY_BOM_WRITE_ENTRIES_ENABLED');
  });

  it('只读能力全部保留', () => {
    for (const kept of ['cost-summary', 'versionHistoryVisible', 'exportToExcel']) {
      expect(source, `${kept} 是只读能力, 不该被下线波及`).toContain(kept);
    }
  });

  it('给出去画布的跳转而不是让按钮凭空消失', () => {
    expect(source).toContain('ProductProcesses');
    expect(source).toMatch(/已迁至|去画布/);
  });
});
```

- [ ] **Step 4: 跑全量 + 变异 + 提交**

变异：把跳转提示去掉，确认第三条断言变红；还原复绿。

```bash
npx vitest run src/views/production/bom src/views/system/product-processes
npx vue-tsc --noEmit -p tsconfig.json
git commit -m "feat(bom): BOM 页写入口下线, 保留只读并给出画布跳转" -- \
  web-admin/src/views/production/bom/index.vue \
  web-admin/src/views/production/bom/__tests__/BomPageReadOnly.source.spec.ts
git show --name-only HEAD
```

---

## 收尾验收

- [ ] `npx vitest run src/views/system/product-processes src/views/production/bom` 全绿
- [ ] `npx vue-tsc --noEmit` 无新增错误
- [ ] 包材可以在画布上完整编辑（新增 / 改用量 / 替代物料 / 删除），不经抽屉
- [ ] BOM 页仍能看成本汇总、ECN、导出、版本历史
- [ ] BOM 页给出「去画布配置」的跳转，不是空页面
- [ ] **把 `LEGACY_BOM_WRITE_ENTRIES_ENABLED` 翻成 `true`，确认旧入口全部原样恢复** —— 这是回退路径的实测，不是可选项

**不做的事**：不删组件、不删路由、不改后端、不写 migration、不推 origin、不部署。

## 真机验证仍然欠着

Phase 3-1 与本期都只做了组件级验证，沙箱连不上后端。**在真机点过之前，「画布可以替代旧入口」这句话没有被证明。** 开关的存在就是承认这一点 —— 它不是为了将来某个假想问题，而是为了一件已知没验的事。
