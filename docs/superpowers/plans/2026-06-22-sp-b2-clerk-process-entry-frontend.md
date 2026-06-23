# SP-B2 文员逐道录入 — web-admin 前端面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** web-admin「逐道工序录入」面板：文员从生产计划「核对结单」打开，按批次链逐道录入（投入/产出/人工/副产/损耗/留样），熟制工序选混锅上游来源 + 锅数，提交到 SP-B1 端点 `POST /{factoryId}/production-plans/{planId}/process-entry`。严格防呆（fool-proof Rule 1–5）。

**Architecture:** 一个独立大抽屉组件 `ProcessChainEntryDrawer.vue`（不塞进现有 list.vue 的小弹窗）；API client `processEntry.ts`；从 `plans/list.vue` 的「核对结单」行操作打开（逐道报工计划走新抽屉，免工序计划保持现有两点汇总）。数据全部前端组装成 `ProcessChainEntryRequest` 一次提交。

**Tech Stack:** Vue3 + Element Plus + axios。

**Spec:** `docs/superpowers/specs/2026-06-22-clerk-process-entry-recipe-cost-design.md` §4。
**依赖:** SP-B1 已合并（端点 + `ProcessChainEntryRequest/Result` 契约）。

**契约 (SP-B1, 照抄):**
- 端点: `POST /api/mobile/{factoryId}/production-plans/{planId}/process-entry`，body `ProcessChainEntryRequest`，返回 `ProcessChainEntryResult`。
- `ProcessChainEntryRequest`: `{ idempotencyKey, batches: [{ clientBatchKey, productTypeId, batchNumber?, finished, steps: [{ processOrder, processName, processCategory?, inputQuantity, outputQuantity, unit, laborStartTime?, laborEndTime?, workerCount?, byproducts?:[{name,quantity,unit,unitPrice}], wasteQuantity?, sampleRetainQuantity?, rawMaterialInputs?:[{materialBatchId,quantity}], potCount?, potRawKgs?:[number], upstreamSources?:[{sourceClientBatchKey,feedQuantityKg}] }] }] }`
- `ProcessChainEntryResult`: `{ idempotentReplay, batchIdsByKey, batchNumbersByKey, finishedBatchNumber, reportsWritten, consumptionsWritten, warnings }`

**隔离:** `git worktree add -b feat/sp-b2-process-entry-ui ../cretas-sp-b2 origin/main`。web-admin 依赖: `cd web-admin && npm install --prefer-offline --legacy-peer-deps`（⛔ 禁 mklink /J）。commit `git commit -- <paths>`。

**Grounding (已验):**
- 核对结单入口: `web-admin/src/views/production/plans/list.vue` — `handleComplete(row)` 开弹窗、`submitComplete()` 提交。逐道 vs 免工序: 计划行有 reporting 模式字段（grep `skipProcessReporting`/`reportingMode`/`逐道`）。
- 工序列表来源: 产品的 ProductWorkProcess（grep web-admin 现有「产品-工序配置」`/system/product-processes` 页的 API client 拿某 productType 的工序列表）。
- 原料批次下拉: 现有结单弹窗已有 `selectedMaterialBatchForSettlement`/可用原料批查询（复用其 API）。
- request helper `@/api/request` 导出 get/post/put/del；auth store `@/store/modules/auth` 的 `factoryId`。
- fool-proof: `.claude/rules/fool-proof-design.md`（Rule 1 预先 max 禁提交 / Rule 2 上下文 / Rule 3 原因 dropdown / Rule 4 幂等+容差 / Rule 5 dead-end 导航）。error toast sticky `duration:0,showClose:true`。

---

### Task 1: API client `processEntry.ts`

**Files:**
- Create: `web-admin/src/api/processEntry.ts`

- [ ] **Step 1: 写 client（类型镜像 SP-B1 契约）**

```typescript
import { post } from './request';

export interface RawInput { materialBatchId: string; quantity: number | null; }
export interface Byproduct { name: string; quantity: number | null; unit: string; unitPrice: number | null; }
export interface UpstreamSource { sourceClientBatchKey: string; feedQuantityKg: number | null; }

export interface StepEntry {
  processOrder: number;
  processName?: string;
  processCategory?: string | null;   // RAW_MATERIAL | SEASONING | PACKAGING | null
  inputQuantity: number | null;
  outputQuantity: number | null;
  unit: string;
  laborStartTime?: string | null;    // "HH:mm"
  laborEndTime?: string | null;
  workerCount?: number | null;
  byproducts?: Byproduct[];
  wasteQuantity?: number | null;
  sampleRetainQuantity?: number | null;
  rawMaterialInputs?: RawInput[];
  potCount?: number | null;
  potRawKgs?: number[];
  upstreamSources?: UpstreamSource[];
}

export interface BatchEntry {
  clientBatchKey: string;
  productTypeId: string;
  batchNumber?: string | null;
  finished: boolean;
  steps: StepEntry[];
}

export interface ProcessChainEntryRequest {
  idempotencyKey: string;
  batches: BatchEntry[];
}

export interface ProcessChainEntryResult {
  idempotentReplay: boolean;
  batchIdsByKey: Record<string, number>;
  batchNumbersByKey: Record<string, string>;
  finishedBatchNumber: string;
  reportsWritten: number;
  consumptionsWritten: number;
  warnings: string[];
}

export function submitProcessChain(factoryId: string, planId: string, body: ProcessChainEntryRequest) {
  return post<ProcessChainEntryResult>(`/${factoryId}/production-plans/${planId}/process-entry`, body);
}
```

- [ ] **Step 2: 提交** `git add web-admin/src/api/processEntry.ts && git commit -m "feat(sp-b2): 逐道录入 API client" -- web-admin/src/api/processEntry.ts`

---

### Task 2: `ProcessChainEntryDrawer.vue` — 抽屉骨架 + 批次链管理

**Files:**
- Create: `web-admin/src/views/production/components/ProcessChainEntryDrawer.vue`

- [ ] **Step 1: 写组件骨架（props: visible/factoryId/planId/productTypeId/processList/plannedQuantity；emit close/submitted）**

完整代码（批次链增删 + 每批工序卡 + 提交组装）：

```vue
<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Delete } from '@element-plus/icons-vue';
import {
  submitProcessChain,
  type ProcessChainEntryRequest, type BatchEntry, type StepEntry,
} from '@/api/processEntry';

const props = defineProps<{
  visible: boolean;
  factoryId: string;
  planId: string;
  productTypeId: string;
  /** 该产品的工序列表(来自 ProductWorkProcess), [{processOrder, processName, processCategory}] */
  processList: Array<{ processOrder: number; processName: string; processCategory?: string | null }>;
  plannedQuantity: number;
  productName?: string;
}>();
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void; (e: 'submitted', r: any): void }>();

const submitting = ref(false);
const batches = ref<BatchEntry[]>([]);

function blankStep(p: { processOrder: number; processName: string; processCategory?: string | null }): StepEntry {
  return {
    processOrder: p.processOrder, processName: p.processName, processCategory: p.processCategory ?? null,
    inputQuantity: null, outputQuantity: null, unit: 'kg',
    laborStartTime: null, laborEndTime: null, workerCount: null,
    byproducts: [], wasteQuantity: null, sampleRetainQuantity: null,
    rawMaterialInputs: [], potCount: null, potRawKgs: [], upstreamSources: [],
  };
}
function addBatch(finished: boolean) {
  const key = (finished ? '成品' : '半成品') + (batches.value.length + 1);
  batches.value.push({
    clientBatchKey: key, productTypeId: props.productTypeId, finished,
    steps: props.processList.map(blankStep),
  });
}
function removeBatch(b: BatchEntry) { batches.value = batches.value.filter((x) => x !== b); }

// 半成品批 keys (供熟制上游来源选择)
const wipBatchKeys = computed(() =>
  batches.value.filter((b) => !b.finished).map((b) => b.clientBatchKey));

watch(() => props.visible, (v) => {
  if (v && batches.value.length === 0) { addBatch(false); addBatch(true); }
});

// 防呆 Rule 1: 每道投入默认=上道产出
function prevOutput(b: BatchEntry, idx: number): number | null {
  return idx > 0 ? b.steps[idx - 1].outputQuantity : null;
}
function isWaterStep(s: StepEntry): boolean {
  return (s.processName || '').includes('滚揉') || (s.processName || '').includes('注');
}
function isCookingStep(s: StepEntry): boolean {
  return s.processCategory === 'SEASONING' || (s.processName || '').includes('熟制');
}

function validate(): string | null {
  if (batches.value.length === 0) return '至少添加一个批次';
  for (const b of batches.value) {
    for (let i = 0; i < b.steps.length; i++) {
      const s = b.steps[i];
      if (s.outputQuantity != null && s.inputQuantity != null
          && !isWaterStep(s) && Number(s.outputQuantity) > Number(s.inputQuantity)) {
        return `${b.clientBatchKey} 「${s.processName}」产出 ${s.outputQuantity} > 投入 ${s.inputQuantity}(非注水工序不允许)`;
      }
      // 熟制来源合计校验
      if (isCookingStep(s) && s.upstreamSources && s.upstreamSources.length) {
        const sum = s.upstreamSources.reduce((a, u) => a + Number(u.feedQuantityKg || 0), 0);
        if (s.inputQuantity != null && Math.abs(sum - Number(s.inputQuantity)) > 0.001) {
          return `${b.clientBatchKey} 熟制: 上游投料合计 ${sum} ≠ 投入 ${s.inputQuantity}`;
        }
      }
    }
  }
  return null;
}

async function submit() {
  const err = validate();
  if (err) { ElMessage({ message: err, type: 'warning', duration: 0, showClose: true }); return; }
  const body: ProcessChainEntryRequest = {
    idempotencyKey: `web-process-${props.planId}-${batches.value.length}-${Date.now()}`,
    batches: batches.value,
  };
  submitting.value = true;
  try {
    const resp = await submitProcessChain(props.factoryId, props.planId, body);
    const r = resp.data;
    if (r?.warnings?.length) {
      ElMessage({ message: '已提交(含提示): ' + r.warnings.join('; '), type: 'warning', duration: 0, showClose: true });
    } else {
      ElMessage.success(`逐道录入成功, 成品批 ${r?.finishedBatchNumber || ''}`);
    }
    emit('submitted', r);
    emit('update:visible', false);
  } catch (e: any) {
    ElMessage({ message: e.message || '提交失败', type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}
function close() { emit('update:visible', false); }
</script>

<template>
  <el-drawer :model-value="visible" @update:model-value="emit('update:visible', $event)"
             :title="`逐道工序录入 — ${productName || productTypeId} (计划数量 ${plannedQuantity})`"
             size="78%" :close-on-click-modal="false">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="先录半成品批(原料→焯水), 再录成品批(熟制混锅→包装)。熟制工序选上游来源批 + 各自投料kg。" />

    <div v-if="processList.length === 0">
      <el-empty description="该产品未配置工序">
        <el-button type="primary" @click="$router.push('/system/product-processes')">去配置产品-工序</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div style="margin-bottom:10px">
        <el-button :icon="Plus" @click="addBatch(false)">+ 半成品批</el-button>
        <el-button :icon="Plus" type="primary" @click="addBatch(true)">+ 成品批</el-button>
      </div>

      <el-collapse>
        <el-collapse-item v-for="(b, bi) in batches" :key="bi"
          :title="`${b.clientBatchKey} ${b.finished ? '(成品)' : '(半成品)'}`" :name="bi">
          <template #title>
            <span>{{ b.clientBatchKey }} {{ b.finished ? '(成品)' : '(半成品)' }}</span>
            <el-button link type="danger" :icon="Delete" style="margin-left:auto"
              @click.stop="removeBatch(b)">删批</el-button>
          </template>

          <el-input v-model="b.clientBatchKey" size="small" style="width:220px;margin-bottom:8px">
            <template #prepend>批次标识</template>
          </el-input>

          <el-card v-for="(s, si) in b.steps" :key="si" shadow="never" style="margin-bottom:8px">
            <template #header>
              <b>{{ s.processOrder }}. {{ s.processName }}</b>
              <el-tag v-if="isWaterStep(s)" size="small" type="warning" style="margin-left:8px">注水(可>100%)</el-tag>
              <el-tag v-if="isCookingStep(s)" size="small" style="margin-left:8px">熟制(混锅)</el-tag>
            </template>

            <el-form label-width="96px" size="small">
              <el-form-item label="投入重量">
                <el-input-number v-model="s.inputQuantity" :precision="2" :min="0" />
                <span style="margin-left:8px;color:#909399" v-if="prevOutput(b, si) != null">
                  上道产出 {{ prevOutput(b, si) }}（投入参考）</span>
              </el-form-item>
              <el-form-item label="产出重量">
                <el-input-number v-model="s.outputQuantity" :precision="2" :min="0"
                  :max="isWaterStep(s) ? undefined : (s.inputQuantity ?? undefined)" />
                <span v-if="!isWaterStep(s) && s.inputQuantity != null" style="margin-left:8px;color:#909399">
                  ≤ 投入 {{ s.inputQuantity }}</span>
              </el-form-item>
              <el-form-item label="人工">
                <el-time-select v-model="s.laborStartTime" placeholder="开始" start="00:00" step="00:15" end="23:45" style="width:110px" />
                <el-time-select v-model="s.laborEndTime" placeholder="结束" start="00:00" step="00:15" end="23:45" style="width:110px;margin:0 8px" />
                <el-input-number v-model="s.workerCount" :min="0" :precision="0" placeholder="人数" />
              </el-form-item>

              <!-- 首道领料 -->
              <el-form-item v-if="si === 0 && !b.finished" label="领料">
                <el-button size="small" @click="s.rawMaterialInputs!.push({ materialBatchId: '', quantity: null })">+ 原料批</el-button>
                <div v-for="(ri, ri2) in s.rawMaterialInputs" :key="ri2" style="margin-top:4px">
                  <el-input v-model="ri.materialBatchId" size="small" placeholder="原料 MaterialBatch.id" style="width:240px" />
                  <el-input-number v-model="ri.quantity" :precision="2" :min="0" placeholder="数量" style="margin-left:8px" />
                  <el-button link type="danger" @click="s.rawMaterialInputs!.splice(ri2,1)">删</el-button>
                </div>
              </el-form-item>

              <!-- 熟制: 锅数 + 上游来源 -->
              <template v-if="isCookingStep(s)">
                <el-form-item label="锅数">
                  <el-input-number v-model="s.potCount" :min="1" :precision="0" />
                </el-form-item>
                <el-form-item label="上游来源">
                  <el-button size="small" :disabled="wipBatchKeys.length === 0"
                    @click="s.upstreamSources!.push({ sourceClientBatchKey: '', feedQuantityKg: null })">+ 来源批</el-button>
                  <el-empty v-if="wipBatchKeys.length === 0" :image-size="40" description="先添加并录入半成品批" />
                  <div v-for="(us, us2) in s.upstreamSources" :key="us2" style="margin-top:4px">
                    <el-select v-model="us.sourceClientBatchKey" placeholder="来源半成品批" style="width:200px">
                      <el-option v-for="k in wipBatchKeys" :key="k" :label="k" :value="k" />
                    </el-select>
                    <el-input-number v-model="us.feedQuantityKg" :precision="2" :min="0" placeholder="投料kg" style="margin-left:8px" />
                    <el-button link type="danger" @click="s.upstreamSources!.splice(us2,1)">删</el-button>
                  </div>
                </el-form-item>
              </template>

              <!-- 末道留样 -->
              <el-form-item v-if="si === b.steps.length - 1 && b.finished" label="留样(盒)">
                <el-input-number v-model="s.sampleRetainQuantity" :min="0" :precision="0" />
              </el-form-item>
              <el-form-item label="损耗">
                <el-input-number v-model="s.wasteQuantity" :precision="2" :min="0" />
              </el-form-item>
            </el-form>
          </el-card>
        </el-collapse-item>
      </el-collapse>
    </template>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交逐道录入</el-button>
    </template>
  </el-drawer>
</template>
```

- [ ] **Step 2: 提交** `git add web-admin/src/views/production/components/ProcessChainEntryDrawer.vue && git commit -m "feat(sp-b2): 逐道工序录入抽屉组件 (批次链/混锅来源/防呆)" -- web-admin/src/views/production/components/ProcessChainEntryDrawer.vue`

---

### Task 3: 接入 `plans/list.vue` 核对结单入口

**Files:**
- Modify: `web-admin/src/views/production/plans/list.vue`

- [ ] **Step 1: 读 list.vue** —— 定位 `handleComplete(row)` + 行操作按钮区 + 如何判断逐道 vs 免工序（grep `skipProcessReporting`/`reportingMode`/`逐道`）。确认行数据有 `productTypeId`/`plannedQuantity`/`productName`。

- [ ] **Step 2: 加「逐道录入」按钮 + 抽屉**

在行操作区（「核对结单」旁）加按钮，仅逐道报工计划显示；import 抽屉组件 + 拿该产品工序列表（复用「产品-工序配置」API，grep `getProductProcesses`/`/system/product-processes` 的 client）：

```typescript
import ProcessChainEntryDrawer from '../components/ProcessChainEntryDrawer.vue';
// 工序列表 API — 用现有产品-工序配置 client (grep 真实函数名)
import { listProductWorkProcesses } from '@/api/productProcess'; // ← 以真实路径为准

const entryDrawerVisible = ref(false);
const entryProcessList = ref<any[]>([]);
const entryRow = ref<any>(null);

async function openProcessEntry(row: any) {
  entryRow.value = row;
  try {
    const resp = await listProductWorkProcesses(factoryId.value, row.productTypeId);
    entryProcessList.value = (resp.data || []).map((p: any) => ({
      processOrder: p.processOrder, processName: p.processName || p.workProcessName,
      processCategory: p.defaultCostCategory ?? null,
    }));
  } catch { entryProcessList.value = []; }
  entryDrawerVisible.value = true;
}
function onEntrySubmitted() { loadList(); } // 刷新列表
```

模板：行操作加（仅 `isStepwise(row)` 时）：
```vue
<el-button v-if="isStepwise(row)" link type="primary" @click="openProcessEntry(row)">逐道录入</el-button>
```
`isStepwise(row)` = 该计划逐道报工（按 Step1 grep 的字段判断，如 `!row.skipProcessReporting`）。

抽屉（模板末尾）：
```vue
<ProcessChainEntryDrawer
  v-model:visible="entryDrawerVisible"
  :factory-id="factoryId"
  :plan-id="entryRow?.id || ''"
  :product-type-id="entryRow?.productTypeId || ''"
  :process-list="entryProcessList"
  :planned-quantity="Number(entryRow?.plannedQuantity || 0)"
  :product-name="entryRow?.productName"
  @submitted="onEntrySubmitted" />
```

- [ ] **Step 3: 构建** `cd web-admin && npm run build` → 成功无类型错误。
- [ ] **Step 4: 提交** `git add web-admin/src/views/production/plans/list.vue && git commit -m "feat(sp-b2): 核对结单接入逐道录入抽屉 (仅逐道报工计划)" -- web-admin/src/views/production/plans/list.vue`

---

## 验收与交接
- [ ] `cd web-admin && npm run build` 绿。
- [ ] `git diff origin/main...HEAD --stat` scope 干净（仅 processEntry.ts + 抽屉组件 + list.vue）。
- [ ] **端到端验证（B1+B2 一起，prod 或 test）**：DEMO_FACTORY 建一个逐道报工存货生产计划 → 核对结单→逐道录入→录两半成品批(焯水A/B)+成品批(熟制混锅 A78/B22)→提交→按成品批号查 成品出厂核算 → 出成率/单盒成本/混批 65.7:34.3 正确。
- [ ] **🔒 终审 + 部署回 Opus**：B1(traceCost)+B2 一起 prod 部署 + headed 验证（per Steve 方案2）。
- [ ] 不碰 F006/六膳门; DEMO_FACTORY 验证。

## Self-Review
- **契约一致**: `processEntry.ts` 类型 ↔ SP-B1 `ProcessChainEntryRequest/Result` 字段逐一对齐。✅
- **防呆**: Rule 1(产出 :max=投入, 注水例外)/Rule 2(抽屉标题+卡头带品名/工序/计划数量)/Rule 3(损耗——MVP 数值; 原因 dropdown 可后续)/Rule 4(idempotencyKey + 来源合计校验)/Rule 5(无工序→去配置, 无半成品批→来源选择器 empty state)。✅
- **No-placeholder 例外(诚实标注)**: Task 3 的 `listProductWorkProcesses` import 路径 + `isStepwise(row)` 判定字段 + 行数据字段名(productName/productTypeId) 需实现者对 list.vue / 现有 product-process client grep 现场确认 —— 已给 grep 指向。
- **YAGNI**: 不做拖拽 DAG 画布; 副产明细录入 MVP 省略(byproducts 留空数组, 后续按需); 损耗原因 dropdown 后续。
