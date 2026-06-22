# 缺上游依赖 → 创建并返回（Create-and-Return Upstream）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做一套可复用的「上游依赖为空 → 权限受控地去创建 → 链式返回」前端机制，并在生产计划弹窗「产品行无数据」场景落地。

**Architecture:** 在现有 `_returnTo` query + `ReturnBanner.vue` 之上封装：(1) composable `useCreateAndReturn`（导航+返回+按模块权限判断）；(2) 空状态组件 `<UpstreamMissingHint>`（有权限→跳转按钮，无权限→联系提示）；(3) 共享 `OrderItemsEditor.vue`（销售订单行编辑器，新建弹窗与详情页 DRAFT 编辑共用）。后端 0 改动（复用 `PUT /sales/orders/:id`）。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus + Pinia + vue-router；测试 Vitest 4 + @vue/test-utils + jsdom。

**Spec:** `docs/superpowers/specs/2026-06-22-create-and-return-upstream-pattern-design.md`

**工作目录:** 在隔离 worktree 内（分支 `feat/create-and-return-upstream`，off origin/main）。所有命令在 `web-admin/` 下跑。

---

## 文件结构

| 文件 | 责任 |
|---|---|
| `web-admin/src/composables/useCreateAndReturn.ts` | **新增**。`canReach(module,{write})` + `goCreate(path,{reopen})`。薄封装，无业务状态。 |
| `web-admin/src/composables/__tests__/useCreateAndReturn.spec.ts` | **新增**。单测：权限判断 + `_returnTo` 编码 + reopen 意图。 |
| `web-admin/src/components/common/UpstreamMissingHint.vue` | **新增**。空状态：有权限渲染按钮（emit `action`），无权限渲染联系文案。 |
| `web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts` | **新增**。组件测试：两种权限态渲染。 |
| `web-admin/src/views/sales/orders/components/OrderItemsEditor.vue` | **新增**。受控产品行编辑器（产品下拉+数量/单位/单价/税率/规格+增删行）。`v-model:items` + `:products` + emit `product-change`。 |
| `web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts` | **新增**。组件测试：增删行、v-model 双向、product-change 事件、产品空时渲染插槽。 |
| `web-admin/src/views/sales/orders/detail.vue` | **改**。DRAFT 订单加「编辑产品行」弹窗（用 OrderItemsEditor + UpstreamMissingHint）+ `PUT` 保存 + `editItems=1` 自动打开。 |
| `web-admin/src/views/production/plans/list.vue` | **改**。空产品行放 UpstreamMissingHint + `goAddOrderItems` + `onMounted` 读 query 重开弹窗（统一 `reopenPlan`/`salesOrderId+action`）。 |
| `web-admin/src/views/sales/orders/list.vue` | **改（最后/可隔离）**。新建弹窗的行编辑改用 OrderItemsEditor（消除重复，避免漂移）；现有创建测试须仍通过。 |

**构建顺序**：底层先（composable → hint → editor），再到三个页面接入，最后 e2e。每个 Task 自测通过即 commit。

---

## Task 1: `useCreateAndReturn` composable

**Files:**
- Create: `web-admin/src/composables/useCreateAndReturn.ts`
- Test: `web-admin/src/composables/__tests__/useCreateAndReturn.spec.ts`

- [ ] **Step 1: 写失败测试**

```ts
// web-admin/src/composables/__tests__/useCreateAndReturn.spec.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

// permissionStore mock —— 按模块判断
const mockCanAccess = vi.fn();
const mockCanWrite = vi.fn();
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: mockCanAccess, canWrite: mockCanWrite }),
}));

// vue-router mock —— 捕获 push 参数 + 提供当前 fullPath
const mockPush = vi.fn();
let mockFullPath = '/production/plans';
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => ({ get fullPath() { return mockFullPath; } }),
}));

import { useCreateAndReturn } from '../useCreateAndReturn';

describe('useCreateAndReturn', () => {
  beforeEach(() => {
    mockPush.mockReset();
    mockCanAccess.mockReset();
    mockCanWrite.mockReset();
    mockFullPath = '/production/plans';
  });

  it('canReach: 默认查 canAccess', () => {
    mockCanAccess.mockReturnValue(true);
    const { canReach } = useCreateAndReturn();
    expect(canReach('sales')).toBe(true);
    expect(mockCanAccess).toHaveBeenCalledWith('sales');
  });

  it('canReach: write=true 查 canWrite', () => {
    mockCanWrite.mockReturnValue(false);
    const { canReach } = useCreateAndReturn();
    expect(canReach('sales', { write: true })).toBe(false);
    expect(mockCanWrite).toHaveBeenCalledWith('sales');
  });

  it('goCreate: 默认用当前 fullPath 作 _returnTo', () => {
    const { goCreate } = useCreateAndReturn();
    goCreate('/system/products');
    expect(mockPush).toHaveBeenCalledWith(
      '/system/products?_returnTo=' + encodeURIComponent('/production/plans'),
    );
  });

  it('goCreate: reopen 意图覆盖 _returnTo', () => {
    const { goCreate } = useCreateAndReturn();
    goCreate('/sales/orders/SO1?editItems=1', { reopen: '/production/plans?reopenPlan=1&planSO=SO1' });
    expect(mockPush).toHaveBeenCalledWith(
      '/sales/orders/SO1?editItems=1&_returnTo=' +
        encodeURIComponent('/production/plans?reopenPlan=1&planSO=SO1'),
    );
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/composables/__tests__/useCreateAndReturn.spec.ts`
Expected: FAIL（`useCreateAndReturn` 模块不存在）

- [ ] **Step 3: 写实现**

```ts
// web-admin/src/composables/useCreateAndReturn.ts
import { useRoute, useRouter } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';

/**
 * 缺上游依赖 → 创建并返回 通用机制。
 * 复用现有 _returnTo query + ReturnBanner.vue（挂在 AppLayout）。
 * 详见 spec: docs/superpowers/specs/2026-06-22-create-and-return-upstream-pattern-design.md
 */
export function useCreateAndReturn() {
  const router = useRouter();
  const route = useRoute();
  const permissionStore = usePermissionStore();

  /** 用户能否到达目标模块页（按模块判断；write=true 时需写权限）。 */
  function canReach(module: string, opts?: { write?: boolean }): boolean {
    // permission store 的 canAccess/canWrite 形参类型为 ModuleName 联合；这里用 string 入参，运行时一致。
    return opts?.write
      ? permissionStore.canWrite(module as never)
      : permissionStore.canAccess(module as never);
  }

  /**
   * 跳到 targetPath，并附加 _returnTo（默认=当前 fullPath；reopen 用于返回后需重开弹窗的页面）。
   * targetPath 可自带 query（如 ?editItems=1），会正确追加 _returnTo。
   */
  function goCreate(targetPath: string, opts?: { reopen?: string }): void {
    const back = encodeURIComponent(opts?.reopen ?? route.fullPath);
    const sep = targetPath.includes('?') ? '&' : '?';
    router.push(`${targetPath}${sep}_returnTo=${back}`);
  }

  return { canReach, goCreate };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/composables/__tests__/useCreateAndReturn.spec.ts`
Expected: PASS（4 passed）

- [ ] **Step 5: commit**

```bash
git add web-admin/src/composables/useCreateAndReturn.ts web-admin/src/composables/__tests__/useCreateAndReturn.spec.ts
git commit -m "feat(web-admin): useCreateAndReturn composable (缺上游→创建并返回 机制)" -- web-admin/src/composables/useCreateAndReturn.ts web-admin/src/composables/__tests__/useCreateAndReturn.spec.ts
```

---

## Task 2: `<UpstreamMissingHint>` 空状态组件

**Files:**
- Create: `web-admin/src/components/common/UpstreamMissingHint.vue`
- Test: `web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts`

- [ ] **Step 1: 写失败测试**

```ts
// web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts
import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';

// 用可变量控制 canReach 返回
let mockCanReach = false;
vi.mock('@/composables/useCreateAndReturn', () => ({
  useCreateAndReturn: () => ({ canReach: () => mockCanReach, goCreate: vi.fn() }),
}));

import UpstreamMissingHint from '../UpstreamMissingHint.vue';

const baseProps = {
  description: '该订单暂无产品行',
  targetModule: 'sales',
  actionText: '去添加产品行',
  contactText: '请联系销售补充',
};

describe('UpstreamMissingHint', () => {
  it('有权限 → 渲染按钮且点击 emit action', async () => {
    mockCanReach = true;
    const wrapper = mount(UpstreamMissingHint, {
      props: { ...baseProps, requireWrite: true },
      global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' } } },
    });
    expect(wrapper.text()).toContain('去添加产品行');
    expect(wrapper.text()).not.toContain('请联系销售补充');
    await wrapper.find('button').trigger('click');
    expect(wrapper.emitted('action')).toBeTruthy();
  });

  it('无权限 → 渲染联系文案，无按钮', () => {
    mockCanReach = false;
    const wrapper = mount(UpstreamMissingHint, {
      props: baseProps,
      global: { stubs: { 'el-button': { template: '<button><slot/></button>' } } },
    });
    expect(wrapper.text()).toContain('请联系销售补充');
    expect(wrapper.find('button').exists()).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/components/common/__tests__/UpstreamMissingHint.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 写实现**

```vue
<!-- web-admin/src/components/common/UpstreamMissingHint.vue -->
<script setup lang="ts">
/**
 * UpstreamMissingHint — 上游依赖缺失的空状态引导（fool-proof Rule 5）。
 * 有目标模块权限 → 显示「去创建」按钮（emit action）；无权限 → 显示「联系谁」提示。
 * 配合 useCreateAndReturn 使用，详见 spec 2026-06-22-create-and-return-upstream-pattern-design.md
 */
import { computed } from 'vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';

const props = withDefaults(defineProps<{
  description: string;
  targetModule: string;
  actionText: string;
  contactText: string;
  requireWrite?: boolean;
}>(), { requireWrite: false });

const emit = defineEmits<{ (e: 'action'): void }>();

const { canReach } = useCreateAndReturn();
const allowed = computed(() => canReach(props.targetModule, { write: props.requireWrite }));
</script>

<template>
  <div class="upstream-missing-hint">
    <span class="upstream-missing-hint__desc">{{ description }}</span>
    <el-button
      v-if="allowed"
      type="primary"
      link
      size="small"
      @click="emit('action')"
    >{{ actionText }} →</el-button>
    <span v-else class="upstream-missing-hint__contact">{{ contactText }}</span>
  </div>
</template>

<style scoped>
.upstream-missing-hint {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 0; font-size: 13px;
}
.upstream-missing-hint__desc { color: var(--el-color-warning-dark-2, #e6a23c); }
.upstream-missing-hint__contact { color: var(--el-text-color-secondary, #909399); }
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/components/common/__tests__/UpstreamMissingHint.spec.ts`
Expected: PASS（2 passed）

- [ ] **Step 5: commit**

```bash
git add web-admin/src/components/common/UpstreamMissingHint.vue web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts
git commit -m "feat(web-admin): UpstreamMissingHint 空状态组件 (权限门控的缺上游引导)" -- web-admin/src/components/common/UpstreamMissingHint.vue web-admin/src/components/common/__tests__/UpstreamMissingHint.spec.ts
```

---

## Task 3: `OrderItemsEditor.vue` 共享产品行编辑器

> 受控组件。`v-model:items` 双向；`:products` 提供产品下拉；选产品时 emit `product-change(index)`（父级跑合同价/价格记忆等逻辑，本编辑器不内置）。产品为空时渲染默认插槽（放 UpstreamMissingHint）。
> 字段对齐后端 `SalesOrderItemDTO`：`productTypeId / productName / quantity / unit / unitPrice / taxRate / specification`。

**Files:**
- Create: `web-admin/src/views/sales/orders/components/OrderItemsEditor.vue`
- Test: `web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts`

- [ ] **Step 1: 写失败测试**

```ts
// web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import OrderItemsEditor from '../OrderItemsEditor.vue';

const products = [
  { id: 'P1', name: '卤牛腱', unit: 'kg' },
  { id: 'P2', name: '卤猪蹄', unit: '份' },
];
const stubs = {
  'el-table': { template: '<table><slot/></table>' },
  'el-table-column': { template: '<td><slot :row="$attrs.row"/></td>' },
  'el-select': { props: ['modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value); $emit(\'change\',$event.target.value)"><slot/></select>' },
  'el-option': { props: ['value','label'], template: '<option :value="value">{{label}}</option>' },
  'el-input-number': { props: ['modelValue'], template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))"/>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
};

describe('OrderItemsEditor', () => {
  it('渲染传入的行 + 增行', async () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [{ productTypeId: 'P1', quantity: 10, unit: 'kg', unitPrice: 5, taxRate: 13 }], products },
      global: { stubs },
    });
    // 点「添加一行」→ emit update:items 长度变 2
    await wrapper.find('button').trigger('click');
    const emitted = wrapper.emitted('update:items');
    expect(emitted).toBeTruthy();
    const last = emitted![emitted!.length - 1][0] as unknown[];
    expect(last.length).toBe(2);
  });

  it('产品为空 → 渲染默认插槽内容', () => {
    const wrapper = mount(OrderItemsEditor, {
      props: { items: [], products: [] },
      slots: { 'empty-products': '<div class="np">无产品引导</div>' },
      global: { stubs },
    });
    expect(wrapper.find('.np').exists()).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web-admin && npx vitest run src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 写实现**

```vue
<!-- web-admin/src/views/sales/orders/components/OrderItemsEditor.vue -->
<script setup lang="ts">
/**
 * OrderItemsEditor — 销售订单产品行受控编辑器（新建弹窗 + 详情页 DRAFT 编辑共用）。
 * 字段对齐后端 SalesOrderItemDTO。选产品时 emit product-change(index) 供父级跑价格逻辑。
 */
import { computed } from 'vue';

export interface OrderItemRow {
  productTypeId: string;
  productName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  taxRate?: number;
  specification?: string;
  [k: string]: unknown;
}
interface ProductOption { id: string; name: string; unit?: string; [k: string]: unknown; }

const props = defineProps<{ items: OrderItemRow[]; products: ProductOption[] }>();
const emit = defineEmits<{
  (e: 'update:items', v: OrderItemRow[]): void;
  (e: 'product-change', index: number): void;
}>();

const rows = computed(() => props.items);
function emitRows(next: OrderItemRow[]) { emit('update:items', next); }

function emptyRow(): OrderItemRow { return { productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxRate: 13 }; }
function addRow() { emitRows([...rows.value, emptyRow()]); }
function removeRow(idx: number) {
  const next = rows.value.slice();
  next.splice(idx, 1);
  emitRows(next.length ? next : [emptyRow()]);
}
function onProductChange(idx: number, productTypeId: string) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r } : r));
  next[idx].productTypeId = productTypeId;
  const p = props.products.find((x) => x.id === productTypeId);
  if (p) { next[idx].productName = p.name; if (!next[idx].unit) next[idx].unit = p.unit ?? '份'; }
  emitRows(next);
  emit('product-change', idx);
}
function patch(idx: number, key: keyof OrderItemRow, val: unknown) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r, [key]: val } : r));
  emitRows(next);
}

const hasProducts = computed(() => props.products.length > 0);
</script>

<template>
  <div class="order-items-editor">
    <slot v-if="!hasProducts" name="empty-products" />
    <el-table v-else :data="rows" border size="small">
      <el-table-column label="产品" min-width="160">
        <template #default="{ row, $index }">
          <el-select :model-value="row.productTypeId" placeholder="选择产品"
            @update:model-value="(v: string) => onProductChange($index, v)">
            <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="120">
        <template #default="{ row, $index }">
          <el-input-number :model-value="row.quantity" :min="0" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'quantity', v)" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="90">
        <template #default="{ row, $index }">
          <el-input :model-value="row.unit" @update:model-value="(v: string) => patch($index, 'unit', v)" />
        </template>
      </el-table-column>
      <el-table-column label="单价(未税)" width="120">
        <template #default="{ row, $index }">
          <el-input-number :model-value="row.unitPrice" :min="0" :precision="2" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'unitPrice', v)" />
        </template>
      </el-table-column>
      <el-table-column label="税率%" width="100">
        <template #default="{ row, $index }">
          <el-input-number :model-value="row.taxRate" :min="0" :max="100" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'taxRate', v)" />
        </template>
      </el-table-column>
      <el-table-column label="规格" min-width="120">
        <template #default="{ row, $index }">
          <el-input :model-value="row.specification" @update:model-value="(v: string) => patch($index, 'specification', v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button type="danger" link size="small" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="hasProducts" size="small" style="margin-top: 8px;" @click="addRow">+ 添加一行</el-button>
  </div>
</template>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd web-admin && npx vitest run src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts`
Expected: PASS（2 passed）

- [ ] **Step 5: commit**

```bash
git add web-admin/src/views/sales/orders/components/OrderItemsEditor.vue web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts
git commit -m "feat(web-admin): OrderItemsEditor 共享产品行编辑器" -- web-admin/src/views/sales/orders/components/OrderItemsEditor.vue web-admin/src/views/sales/orders/components/__tests__/OrderItemsEditor.spec.ts
```

---

## Task 4: 销售订单详情页 — DRAFT 编辑产品行

> 给 `detail.vue` 加：DRAFT 订单显示「编辑产品行」按钮 → 弹窗用 `OrderItemsEditor`；产品为空时弹窗内放 `UpstreamMissingHint`（跳 `/system/products`）；保存 `PUT /:factoryId/sales/orders/:id`；URL 带 `editItems=1` 时自动打开。

**Files:**
- Modify: `web-admin/src/views/sales/orders/detail.vue`

参考已知锚点：订单读取 `get(/:factoryId/sales/orders/:id)`（detail.vue:220）、产品行只读表（约 detail.vue:1135 `order.items`）、已有 `post` 动作模式（detail.vue:308-323）。

- [ ] **Step 1: 加 import + 状态 + 产品加载**

在 `<script setup>` 顶部 import 区加：

```ts
import { useRoute } from 'vue-router';
import OrderItemsEditor, { type OrderItemRow } from './components/OrderItemsEditor.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { get, put } from '@/api/request'; // 若文件已 import get，则只补 put
```

在已有 `factoryId` / `orderId` / `order` 声明附近加：

```ts
const route = useRoute();
const { goCreate } = useCreateAndReturn();

const editItemsVisible = ref(false);
const editItemsRows = ref<OrderItemRow[]>([]);
const editItemsSaving = ref(false);
const products = ref<Array<{ id: string; name: string; unit?: string }>>([]);

const isDraft = computed(() => String((order.value as any)?.status || '').toUpperCase() === 'DRAFT');

async function loadProductsForEdit() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/products`, { params: { page: 1, size: 500 } });
    const list = (res?.data?.content ?? res?.data ?? []) as any[];
    products.value = list.map((p) => ({ id: String(p.id), name: String(p.name ?? p.productName ?? ''), unit: p.unit }));
  } catch { products.value = []; }
}
```

> 注：`/${factoryId}/products` 为产品类型列表端点（与 `sales/orders/list.vue` 的 `loadProducts` 同源）。实现时对照 `list.vue:750 loadProducts` 的真实 URL/响应解构，保持一致。

- [ ] **Step 2: 写打开/保存逻辑**

```ts
function openEditItems() {
  const existing = Array.isArray((order.value as any)?.items) ? (order.value as any).items : [];
  editItemsRows.value = existing.length
    ? existing.map((it: any) => ({
        productTypeId: String(it.productTypeId ?? ''),
        productName: it.productName,
        quantity: Number(it.quantity ?? 0),
        unit: it.unit ?? '份',
        unitPrice: Number(it.unitPrice ?? 0),
        taxRate: Number(it.taxRate ?? 13),
        specification: it.specification ?? '',
      }))
    : [{ productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxRate: 13 }];
  editItemsVisible.value = true;
}

function goCreateProductType() {
  // route.fullPath 已含 _returnTo=生产计划 → 返回链自动 计划←订单←产品
  goCreate('/system/products');
}

async function saveEditItems() {
  if (!factoryId.value || !orderId.value) return;
  const items = editItemsRows.value.filter((r) => r.productTypeId);
  if (items.length === 0) { ElMessage.warning('请至少添加一个产品行'); return; }
  editItemsSaving.value = true;
  try {
    const res = await put(`/${factoryId.value}/sales/orders/${orderId.value}`, {
      items,
      version: (order.value as any)?.version,
    });
    if (res?.success !== false) {
      ElMessage.success('产品行已保存');
      editItemsVisible.value = false;
      await loadOrder(); // 复用页面已有的订单刷新函数（detail.vue 已有，名称对照实际）
    }
  } finally { editItemsSaving.value = false; }
}
```

> `loadOrder` / `ElMessage` 用页面已有的；若刷新函数名不同（如 `fetchOrder`），改成实际名。`put` 来自 `@/api/request`（与 `get`/`post` 同源）。

- [ ] **Step 3: 模板加按钮 + 弹窗**

在产品行只读表上方（约 detail.vue:1135 表格前）加：

```vue
<div v-if="isDraft" style="margin-bottom: 8px;">
  <el-button type="primary" size="small" @click="openEditItems">编辑产品行</el-button>
</div>
<el-alert
  v-else-if="!(order.items && order.items.length)"
  type="info" :closable="false"
  title="该订单非草稿状态且无产品行，请联系销售在草稿阶段补充。" />
```

在模板末尾加弹窗：

```vue
<el-dialog v-model="editItemsVisible" :title="`编辑产品行 — ${order.orderNumber || ''}`" width="820px">
  <OrderItemsEditor v-model:items="editItemsRows" :products="products">
    <template #empty-products>
      <UpstreamMissingHint
        description="本工厂暂无产品类型，无法添加产品行"
        target-module="system"
        action-text="去创建产品类型"
        contact-text="请联系管理员先创建产品类型"
        @action="goCreateProductType" />
    </template>
  </OrderItemsEditor>
  <template #footer>
    <el-button @click="editItemsVisible = false">取消</el-button>
    <el-button type="primary" :loading="editItemsSaving" @click="saveEditItems">保存</el-button>
  </template>
</el-dialog>
```

- [ ] **Step 4: editItems=1 自动打开 + 进入时加载产品**

在 `onMounted`（或订单加载完成后）加：

```ts
onMounted(async () => {
  // ...保留已有逻辑...
  await loadProductsForEdit();
  if (route.query.editItems === '1' && isDraft.value) openEditItems();
});
```

> 若 `order` 是异步加载，确保 `openEditItems` 在 `order` 就绪后调用（放到 loadOrder 的 then 里判断 `route.query.editItems`）。

- [ ] **Step 5: 类型检查 + 构建该文件无新错**

Run: `cd web-admin && npx vue-tsc --noEmit 2>&1 | grep "sales/orders/detail.vue" || echo "NO NEW ERRORS in detail.vue"`
Expected: 仅既有错误（与本改动无关）或 NO NEW ERRORS。对照 `git stash` 前后该文件错误数确认未新增（参见 Task 5 同法）。

- [ ] **Step 6: commit**

```bash
git add web-admin/src/views/sales/orders/detail.vue
git commit -m "feat(web-admin): 销售订单详情 DRAFT 可编辑产品行 (+ 产品为空时引导建产品)" -- web-admin/src/views/sales/orders/detail.vue
```

---

## Task 5: 生产计划弹窗 — 空产品行引导 + 返回重开

**Files:**
- Modify: `web-admin/src/views/production/plans/list.vue`

已知锚点：`useRouter`（list.vue:3,54）、`handleCreate`（580，开弹窗+重置）、`handleSalesOrderSelect`（338）、`selectedOrderItems`（313）、`planForm`（239）、`onMounted`（371）、`dialogVisible`（237）。

- [ ] **Step 1: 加 import + composable**

import 区补：

```ts
import { useRoute } from 'vue-router';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
```

`const router = useRouter();`（已有）旁加：

```ts
const route = useRoute();
const { goCreate } = useCreateAndReturn();
```

- [ ] **Step 2: 加跳转函数**

```ts
function goAddOrderItems(soId: string) {
  if (!soId) return;
  goCreate(`/sales/orders/${soId}?editItems=1`, {
    reopen: `/production/plans?reopenPlan=1&planSO=${soId}`,
  });
}
```

- [ ] **Step 3: 模板 — 空产品行处放 hint**

在「产品行」下拉的表单项内，下拉无数据时渲染（与现有 `selectedOrderItems` 联动；放在产品行 `el-form-item` 末尾）：

```vue
<UpstreamMissingHint
  v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId && selectedOrderItems.length === 0"
  description="该订单暂无产品行，无法据此排产"
  target-module="sales"
  require-write
  action-text="去销售订单添加产品行"
  contact-text="请联系销售或管理员为该订单补充产品行后再排产"
  @action="goAddOrderItems(planForm.sourceOrderId)" />
```

- [ ] **Step 4: onMounted 读 query 重开弹窗（统一入口）**

把 `onMounted` 改为（保留原有调用）：

```ts
onMounted(() => {
  loadData();
  loadProductTypes();
  loadReferenceData();
  loadCustomers();
  loadReportModeDefault();
  maybeReopenFromQuery();
});

async function maybeReopenFromQuery() {
  const q = route.query;
  const soId = (q.reopenPlan === '1' && typeof q.planSO === 'string' && q.planSO)
    || (q.action === 'create' && typeof q.salesOrderId === 'string' && q.salesOrderId)
    || '';
  if (!soId) return;
  await handleCreate();                 // 开弹窗 + 重置（内部已 preload 可选销售订单）
  planForm.value.sourceType = 'CUSTOMER_ORDER';
  planForm.value.sourceOrderId = String(soId);
  handleSalesOrderSelect(String(soId)); // 载入(此时已非空的)产品行 + 回填客户/产品
  // 清掉 query，避免刷新重复触发
  const { reopenPlan: _a, planSO: _b, salesOrderId: _c, action: _d, ...rest } = q as Record<string, unknown>;
  router.replace({ query: rest as any });
}
```

> 若 `handleCreate` 内 preload 销售订单是异步的，确保 `await` 后 `selectableSalesOrders` 已就绪再 `handleSalesOrderSelect`；必要时在 handleCreate 内 await 那个 preload。

- [ ] **Step 5: 验证未引入新类型错误**

```bash
cd web-admin
npx vue-tsc --noEmit 2>&1 | grep -c "production/plans/list.vue" > /tmp/after.txt
git stash
npx vue-tsc --noEmit 2>&1 | grep -c "production/plans/list.vue" > /tmp/before.txt
git stash pop
echo "before=$(cat /tmp/before.txt) after=$(cat /tmp/after.txt)"
```
Expected: `after` ≤ `before`（未新增本文件错误）

- [ ] **Step 6: commit**

```bash
git add web-admin/src/views/production/plans/list.vue
git commit -m "feat(web-admin): 生产计划空产品行引导跳转 + 返回自动重开弹窗 (统一 salesOrderId/reopenPlan)" -- web-admin/src/views/production/plans/list.vue
```

---

## Task 6: 新建销售订单弹窗改用 OrderItemsEditor（消除重复，最后做、可隔离）

> 目的：让新建弹窗与详情页编辑共用同一个行编辑器，避免漂移（用户「复用/干净」诉求）。**风险**：新建弹窗的行编辑带额外能力（合同价 `fetchContractPrice`、价格记忆 `fetchPriceMemory`、abaca、箱数等）。做法：弹窗保留这些父级逻辑，把「行的展示+增删+产品选择」交给 OrderItemsEditor，父级监听 `@product-change(index)` 触发价格逻辑。
> **若执行时发现耦合过深、回归风险大 → 跳过本 Task**（前 5 个 Task 已交付完整功能；本 Task 仅为代码整洁，spec §5.3 已授权回退）。

**Files:**
- Modify: `web-admin/src/views/sales/orders/list.vue`

- [ ] **Step 1: 确认现有创建测试基线通过**

Run: `cd web-admin && npx vitest run src/components/__tests__/CreateDialog4Mode.spec.ts src/views/sales 2>&1 | tail -15`
Expected: 记录当前 PASS 基线（改动后须仍 PASS）

- [ ] **Step 2: 在新建弹窗中以 OrderItemsEditor 替换内联行表**

将 `form.value.items` 通过 `v-model:items` 绑到 `<OrderItemsEditor :products="products" @product-change="onCreateItemProductChange" />`；`onCreateItemProductChange(idx)` 内调用现有 `fetchContractPrice`/`fetchPriceMemory`/`ensureTrailingEmptyRow`（对照 list.vue:805-825）。保留 abaca/箱数等列：若这些是 OrderItemsEditor 未覆盖的列，作为 OrderItemsEditor 的具名插槽 `#extra-columns` 由父级注入（实现时给 OrderItemsEditor 加一个 `<slot name="extra-columns"/>` 在操作列前）。

> 这是本计划唯一“改动既有复杂文件”的任务。严格保持现有创建行为。

- [ ] **Step 3: 跑创建相关测试 + 类型检查**

Run: `cd web-admin && npx vitest run src/components/__tests__/CreateDialog4Mode.spec.ts src/views/sales 2>&1 | tail -15`
Expected: 与 Step 1 基线相同（全 PASS，无回归）

- [ ] **Step 4: commit**

```bash
git add web-admin/src/views/sales/orders/list.vue
git commit -m "refactor(web-admin): 新建销售订单弹窗改用 OrderItemsEditor (消除产品行编辑重复)" -- web-admin/src/views/sales/orders/list.vue
```

---

## Task 7: e2e headed 验证（F006 租户）

> 按 `.claude/rules/playwright-headed-mode.md`：headless: false，viewport 1920×1080，lang zh-CN。用 F006 测试租户，不碰 LIUSHANMEN 真客户。本 Task 为人工/脚本验证，非自动 CI gate。

- [ ] **Step 1: 起前端**

Run: `cd web-admin && npm run dev`（或对 prod 用 MCP headed 浏览）

- [ ] **Step 2: 多模块管理员场景**

1. 生产计划 → 新建 → 来源=销售订单 → 选一张**无产品行**的订单 → 见「去销售订单添加产品行 →」按钮
2. 点 → 跳到 SO 详情（顶部 ReturnBanner 显示「返回生产计划」）→ DRAFT 订单点「编辑产品行」
3. 若产品下拉空 → 见「去创建产品类型 →」→ 跳产品页 → 建产品 → ReturnBanner 返回 SO 详情
4. 加产品行 → 保存 → ReturnBanner 返回生产计划 → **弹窗自动重开且该订单产品行可选**
   - 截图存档每一步（中文无方块）

- [ ] **Step 3: 纯生产权限用户场景**

用只有生产权限的 F006 账号：同场景 → 「产品行无数据」处**只显示「请联系销售补充」，无跳转按钮**。截图存档。

- [ ] **Step 4: 写验证报告**

落 `docs/audits/` 一份，含 Headed Mode Verification block（headless:false / viewport / locale / 中文真显示 等，按 playwright-headed-mode 规则）。

---

## Self-Review（已核对）

- **Spec 覆盖**：§4.1→Task1；§4.2→Task2；§5.3→Task3/Task6；§5.2→Task4；§5.1→Task5；§9 测试→各 Task 单测 + Task7 e2e；§7 backlog 不实现（spec 已声明）。✓
- **Placeholder 扫描**：无 TBD/TODO；新组件均给完整代码；既有大文件改动给精确锚点 + 新增代码块 + 回归 gate。✓
- **类型一致**：`canReach(module,{write})` / `goCreate(path,{reopen})` / `UpstreamMissingHint` props(`targetModule`/`requireWrite`/`actionText`/`contactText`+emit `action`) / `OrderItemsEditor`(`v-model:items` + `:products` + emit `product-change`/`update:items`) 跨 Task 一致。✓
- **风险隔离**：唯一高风险（改既有创建弹窗）放 Task6 末位且授权可跳过，不阻断核心功能。✓

---

## 注意事项（执行者必读）

- 工作在 worktree `feat/create-and-return-upstream`（off origin/main）。**不在主目录干活。**
- commit 用 `git commit -m "..." -- <files>`（`--only` 模式）防并发 session 污染 scope。
- 后端 0 改动。SO 行编辑仅对 DRAFT（对齐后端 `PUT` 约束 + `@RequirePermission("sales:read_write")` 权威闸）。
- 🔒 涉及 prod 部署 / merge 由 Opus organizer 终审（本计划执行者只做到 PR）。
- 部署前从 main 部署 web-admin（`deploy-web-admin.sh --env prod`），见 server-operations。
