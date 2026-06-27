<!--
  成品出厂核算 (通用版, 由 M67 demo 泛化而来)
  支持「按订单号」(OrderYieldSummaryDTO) 和「按批次号」(BatchYieldDTO → normalize) 双模式查询。
  批次号模式适用于存货生产 (无对应订单号) 场景。
  任意产品/批次均可查询; 无 M67 demo 硬编码。
-->
<template>
  <div class="m67-page">
    <div class="m67-header">
      <div>
        <h2>成品出厂核算</h2>
        <p class="sub">全链路出成率 · 单盒成本 · 人工成本 (按订单批次)</p>
      </div>
      <div class="ctrls-wrap">
        <el-select
          v-model="selectedBatchKey"
          filterable
          clearable
          placeholder="选择完工批次 (快速填入)"
          style="width: 320px"
          :loading="batchesLoading"
          @change="onBatchSelect"
        >
          <el-option
            v-for="b in finishedBatches"
            :key="b.batchNumber"
            :value="b.batchNumber"
            :label="`${b.productName ?? ''} · ${b.batchNumber}`"
          >
            <div class="batch-opt">
              <span class="batch-name">{{ b.productName ?? '未知品名' }} <span :class="b.settled ? 'badge-settled' : 'badge-unsettled'">{{ b.settled ? '已核算' : '未核算' }}</span></span>
              <span class="batch-no">批次: {{ b.batchNumber }}<template v-if="b.orderId"> · 订单: {{ b.orderId }}</template></span>
              <span class="batch-time">完工: {{ fmtTime(b.completedAt) }}</span>
            </div>
          </el-option>
        </el-select>
        <div class="ctrls">
          <el-radio-group v-model="queryMode" size="small">
            <el-radio-button value="order">订单号</el-radio-button>
            <el-radio-button value="batch">批次号</el-radio-button>
          </el-radio-group>
          <el-input v-if="queryMode === 'order'" v-model="orderId" placeholder="订单号" style="width: 220px" />
          <el-input v-else v-model="batchNumber" placeholder="批次号" style="width: 220px" />
          <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <template v-if="data">
      <!-- 3 核心数 + 盒数 -->
      <div class="kpis">
        <KPICard title="整批出成率" :value="overallYieldKpi.value" :unit="overallYieldKpi.unit" format="number" :precision="1"
                 icon="TrendCharts" :target-value="overallYieldKpi.targetValue" :subtitle="overallYieldKpi.subtitle"
                 :status="overallYieldKpi.status" />
        <KPICard title="单盒成本" :value="perBox(totalCostClosed)" unit="元/盒" format="currency" :precision="2"
                 icon="Coin" :subtitle="singleBoxCostSubtitle" />
        <KPICard title="单盒人工" :value="perBox(laborCostClosed)" unit="元/盒" format="currency" :precision="2"
                 icon="User" subtitle="总人工 ÷ 盒数" />
        <KPICard title="产出盒数" :value="boxCount" unit="盒" format="number" :precision="0"
                 icon="Box" :subtitle="`末道产出 ${num(data.totalLastOutput)} ${data.lastOutputUnit || 'kg'}`" />
      </div>

      <el-row :gutter="16" class="mb">
        <!-- 逐道出成率 -->
        <el-col :span="14">
          <el-card shadow="never">
            <template #header><b>逐道出成率 · 工序汇总</b><span class="hint">按工序聚合重复报工; 成本以右侧整批口径为准</span></template>
            <div class="step-summary-strip">
              <span>报工明细 {{ steps.length }} 条</span>
              <span>工序组 {{ groupedSteps.length }} 个</span>
              <span v-if="hasUnallocatedStepCost">人工 / 设备 / 其他未按本道分摊</span>
            </div>
            <div v-for="g in groupedSteps" :key="g.key" class="step">
              <div class="step-top">
                <span class="pname">{{ g.processName }}</span>
                <el-tag v-if="g.count > 1" size="small" effect="plain">{{ g.count }} 条</el-tag>
                <span class="qty">{{ num(g.totalInput) }} → {{ num(g.totalOutput) }} {{ g.outputUnit || 'kg' }}</span>
                <span class="yr" :class="yieldClass(g.avgYieldRate)">{{ g.avgYieldRate == null ? '—' : (g.avgYieldRate * 100).toFixed(1) + '%' }}</span>
              </div>
              <el-progress :percentage="barPct(g.avgYieldRate)" :status="yieldStatus(g.avgYieldRate)" :stroke-width="12" :show-text="false" />
              <!-- 逐工序完整成本: 原料摊首道 · 包装摊末道 -->
              <div class="step-cost">
                <template v-if="g.costValue !== null">
                  <span class="sc-full">
                    本组归集成本 ¥{{ g.costValue.toFixed(2) }}
                    <span v-for="tag in g.costTags" :key="tag" class="sc-tag">({{ tag }})</span>
                  </span>
                  <span v-if="stepPerBoxValue(g.costValue) !== null" class="sc-perbox">
                    每盒 ¥{{ stepPerBoxValue(g.costValue)!.toFixed(4) }}
                  </span>
                </template>
                <span v-else class="sc-masked">{{ g.costReason }}</span>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 单盒成本拆解 -->
        <el-col :span="10">
          <el-card shadow="never">
            <template #header><b>单盒成本拆解</b><span class="hint">{{ costBreakdownHint }}</span></template>
            <div class="total-box">¥{{ perBox(totalCostClosed).toFixed(2) }}<span>/盒</span></div>
            <div v-for="c in costBreakdown" :key="c.name" class="cost-row">
              <span class="cdot" :style="{ background: c.color }"></span>
              <span class="cname">{{ c.name }}</span>
              <el-progress :percentage="c.share" :color="c.color" :stroke-width="14" style="flex:1" />
              <span class="cval">{{ money2(c.perBox) }}</span>
            </div>

            <!-- 包装明细 4 拆 (膜/气体/标签/其他) — AUDIT-002 -->
            <div v-if="packagingDetail.length" class="pkg-detail">
              <div class="pkg-title">包装明细<span v-if="packagingTotal > 0"> (合计 ¥{{ packagingTotal.toFixed(2) }})</span></div>
              <div v-for="p in packagingDetail" :key="p.name" class="pkg-row">
                <span class="pkg-name">{{ p.name }}</span>
                <span class="pkg-cost">{{ p.cost != null ? money2(Number(p.cost)) : '需价格权限' }}</span>
                <span v-if="p.cost != null" class="pkg-perbox">¥{{ (Number(p.cost) / (boxCount || 1)).toFixed(3) }}/盒</span>
              </div>
            </div>

            <!-- 辅料按锅分摊 (一锅辅料被多批共用→按产出量分摊) — AUDIT-004 -->
            <div v-if="auxAllocations.length" class="aux-alloc">
              <div v-for="a in auxAllocations" :key="a.potNo" class="aux-row">
                <div class="aux-line1">辅料按锅分摊 · {{ a.method === 'FIXED_RATIO' ? '固定比例' : '按产出量' }}
                  <span class="aux-pot">锅 {{ a.potNo }}</span></div>
                <div class="aux-line2">
                  锅总{{ a.potTotalCost != null ? '¥' + Number(a.potTotalCost).toFixed(2) : '(需价格权限)' }}
                  · 本批 {{ num(a.batchOutput) }}/{{ num(a.potTotalOutput) }}kg
                  <span v-if="a.batchSharePct != null">({{ Number(a.batchSharePct).toFixed(1) }}%)</span>
                  <span v-if="a.batchShare != null" class="aux-share">→ 本批分摊 ¥{{ Number(a.batchShare).toFixed(2) }}</span>
                </div>
              </div>
            </div>

            <!-- 副产回收 (肥油/料头变现冲减成本) — AUDIT-001 -->
            <template v-if="hasByproduct">
              <el-divider style="margin: 10px 0" />
              <div v-for="b in byproducts" :key="b.name" class="cost-row byp">
                <span class="cdot" style="background:#909399"></span>
                <span class="cname" style="width:auto">副产·{{ b.name }}</span>
                <span class="byp-meta">{{ num(b.quantity) }} {{ b.unit }}<template v-if="b.unitPrice != null"> × ¥{{ Number(b.unitPrice).toFixed(2) }}</template></span>
                <span class="cval" :class="{ credit: b.value != null }">{{ b.value != null ? '−¥' + ((b.value || 0) / (boxCount || 1)).toFixed(2) : '需价格权限' }}</span>
              </div>
              <div v-if="netPerBox != null" class="net-box">
                单盒净成本 <b>¥{{ netPerBox.toFixed(2) }}</b><span>/盒 (毛成本扣副产回收 ¥{{ byproductCredit.toFixed(2) }})</span>
              </div>
            </template>

            <!-- 留样扣减 (产出不可售 → 可售单盒成本) — AUDIT-006 -->
            <template v-if="sampleRetainCount > 0">
              <el-divider style="margin: 10px 0" />
              <div class="cost-row byp">
                <span class="cdot" style="background:#909399"></span>
                <span class="cname" style="width:auto">留样 (不可售)</span>
                <span class="byp-meta">{{ sampleRetainCount }} 盒 · 可售 {{ sellableBoxCount }} 盒<template v-if="wasteQty"> · 料头损耗 {{ wasteQty }}kg</template></span>
                <span class="cval"></span>
              </div>
              <div v-if="sellablePerBox != null" class="net-box sell">
                可售单盒成本 <b>¥{{ sellablePerBox.toFixed(2) }}</b><span>/盒 (净成本 ÷ 可售 {{ sellableBoxCount }} 盒, 留样成本由售出盒承担)</span>
              </div>
            </template>
          </el-card>
        </el-col>
      </el-row>

      <!-- 段2(B) 辅料标准单价对账: 标准应投 vs 实际投料 → 多投/误差 -->
      <el-card v-if="recon" shadow="never" class="mb">
        <template #header>
          <b>辅料标准单价对账</b>
          <span class="hint">标准配方率反推「应投」 vs 实际报工 → 抓多投 / 误差 / 浪费 (预警阈值 {{ reconThresholdPct }}%)</span>
        </template>

        <!-- WARN 预警 (常驻, 含 next-action — 防呆四位一体) -->
        <el-alert v-for="iss in reconWarns" :key="iss.code" :title="iss.message" type="warning"
                  show-icon :closable="false" class="recon-warn" />

        <!-- 投料对账 (原料 kg) -->
        <div v-if="recon.standardFirstInput != null" class="recon-feed">
          <div class="rf-item"><span class="rf-label">标准应投</span><span class="rf-val">{{ num(recon.standardFirstInput) }} {{ recon.firstInputUnit }}</span></div>
          <div class="rf-sep">→</div>
          <div class="rf-item"><span class="rf-label">实际投料</span><span class="rf-val">{{ num(recon.actualFirstInput) }} {{ recon.firstInputUnit }}</span></div>
          <div class="rf-sep">=</div>
          <div class="rf-item">
            <span class="rf-label">多投 / 误差</span>
            <span class="rf-val" :class="recon.overFeedAlert ? 'over-bad' : 'over-ok'">
              {{ (recon.overFeed ?? 0) >= 0 ? '+' : '' }}{{ num(recon.overFeed) }} {{ recon.firstInputUnit }}
              <span v-if="recon.overFeedRate != null" class="rf-rate">({{ reconRate(recon.overFeedRate) }})</span>
            </span>
          </div>
        </div>

        <!-- 辅料成本 3 栏: 标准 / 实际 / 多投 -->
        <div v-if="recon.actualAuxCostTotal != null || recon.standardAuxCostTotal != null" class="recon-aux">
          <div class="ra-col">
            <div class="ra-t">标准辅料</div>
            <div class="ra-v">{{ reconMoney(recon.standardAuxCostTotal) }}</div>
            <div class="ra-pu">{{ reconMoney(recon.standardAuxCostPerUnit) }}/份</div>
          </div>
          <div class="ra-col">
            <div class="ra-t">实际辅料</div>
            <div class="ra-v">{{ reconMoney(recon.actualAuxCostTotal) }}</div>
            <div class="ra-pu">{{ reconMoney(recon.actualAuxCostPerUnit) }}/份</div>
          </div>
          <div class="ra-col" :class="recon.auxAlert ? 'ra-over' : ''">
            <div class="ra-t">多投差异</div>
            <div class="ra-v" :class="overCellClass(recon.auxOverCostTotal)">{{ reconMoney(recon.auxOverCostTotal) }}</div>
            <div class="ra-pu">{{ reconMoney(recon.auxOverCostPerUnit) }}/份<span v-if="recon.auxOverRate != null"> ({{ reconRate(recon.auxOverRate) }})</span></div>
          </div>
        </div>

        <!-- 逐工序对账明细 -->
        <table v-if="recon.steps && recon.steps.length" class="recon-tbl">
          <thead><tr><th>工序</th><th>标准率</th><th>实际率</th><th>标准kg</th><th>实际kg</th><th>标准辅料</th><th>实际辅料</th><th>多投</th></tr></thead>
          <tbody>
            <tr v-for="st in recon.steps" :key="st.processOrder">
              <td>{{ st.processName || ('工序' + st.processOrder) }}</td>
              <td>{{ reconRate(st.standardYieldRate) }}</td>
              <td>{{ reconRate(st.actualYieldRate) }}</td>
              <td>{{ num(st.standardKg) }}</td>
              <td>{{ num(st.actualKg) }}</td>
              <td>{{ reconMoney(st.standardAuxCost) }}</td>
              <td>{{ reconMoney(st.actualAuxCost) }}</td>
              <td :class="overCellClass(st.auxOverCost)">{{ reconMoney(st.auxOverCost) }}</td>
            </tr>
          </tbody>
        </table>

        <!-- INFO 提示 (诚实留空原因 + next-action) -->
        <div v-if="reconInfos.length" class="recon-info">
          <div v-for="iss in reconInfos" :key="iss.code" class="ri-line">· {{ iss.message }}</div>
        </div>
      </el-card>

      <!-- 批次溯源桑基图 -->
      <el-card shadow="never" class="mb">
        <template #header><b>批次溯源 (原料 → 各道工序 → 成品, 宽度=物料重量kg)</b></template>
        <div ref="sankeyEl" style="height: 320px"></div>
      </el-card>

      <!-- 多批混锅溯源 (多对多) — 数据驱动, 仅当该批有多个上游来源时显示 -->
      <el-card v-if="hasMix" shadow="never">
        <template #header>
          <b>多批混锅溯源</b>
          <span class="hint">实时从批次关联溯源 — 一锅熟制来自 {{ mixRels.length }} 个上游批次, 按实测投料量逐批归集 (Excel 做不到的"多对多")</span>
        </template>
        <div ref="mixEl" style="height: 300px"></div>

        <!-- 成本按混批比例真拆 (异质成本: 成本占比 ≠ 重量占比) -->
        <div v-if="mixCostSplit.hasCost" class="mix-cost">
          <div class="mix-cost-title">混批成本拆分 — 按实测投料量 × 各自单价精确归集 (合计 ¥{{ mixCostSplit.totalCost.toFixed(2) }})</div>
          <table class="mix-tbl">
            <thead><tr><th>上游来源</th><th>投料量</th><th>单价</th><th>成本</th><th>数量占比</th><th>成本占比</th></tr></thead>
            <tbody>
              <tr v-for="row in mixCostSplit.rows" :key="row.key">
                <td>{{ row.name }}</td>
                <td>{{ row.qty }} {{ row.unit || '' }}</td>
                <td>¥{{ Number(row.unitPrice).toFixed(2) }}/{{ row.unit || '单位' }}</td>
                <td>{{ money2(row.cost) }}</td>
                <td>{{ row.weightShare }}%</td>
                <td :class="{ hl: row.costShare !== row.weightShare }">{{ row.costShare }}%</td>
              </tr>
            </tbody>
          </table>
          <div class="mix-note">注意：各上游批次<b>单价可能不同</b>，因此<b>成本占比 ≠ 重量占比</b>——必须按实测投料量×各自单价精确归集，加权平均会引入误差。数据来源：后端单一权威成本服务 (/production/orders/.../cost-breakdown, 谱系遍历+上游成本回溯)。</div>
        </div>
        <div v-else class="mix-note">本批来自 {{ mixRels.length }} 个上游批次，按实测投料量逐批溯源。成本金额需价格查看权限。数据来源：后端成本拆分服务 (/cost-breakdown)。</div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading && !error" :description="queryMode === 'batch' ? '输入批次号后点刷新' : '输入订单号后点刷新'" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useRoute } from 'vue-router';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import KPICard from '@/components/smartbi/KPICard.vue';

interface Step {
  processOrder: number; processName?: string;
  totalInput?: number; totalOutput?: number; outputUnit?: string;
  yieldRate?: number; laborCost?: number; materialCost?: number;
}
interface StepGroup {
  key: string;
  processOrder: number;
  processName: string;
  count: number;
  totalInput: number;
  totalOutput: number;
  outputUnit?: string;
  avgYieldRate: number | null;
  costValue: number | null;
  costTags: string[];
  costReason: string;
}
interface MixRel {
  batchNumber?: string; batchId?: string; quantity?: number; unit?: string; unitPrice?: number; totalCost?: number;
  weightSharePct?: number; costSharePct?: number; sourceType?: string;
}
interface SankeyNode { name: string; displayName: string; itemStyle?: { color: string } }
interface CostSource { batchId?: string; batchName?: string; quantity?: number; unit?: string; unitPrice?: number; cost?: number; weightSharePct?: number; costSharePct?: number; depth?: number }
interface ByproductLine { name?: string; quantity?: number; unit?: string; unitPrice?: number; value?: number }
interface PackagingItem { name?: string; cost?: number }
interface AuxAllocation { potNo?: string; method?: string; potTotalCost?: number; potTotalOutput?: number; batchOutput?: number; batchShare?: number; batchSharePct?: number }
interface CostBreakdown {
  boxCount?: number; rawMaterialCost?: number; laborCost?: number; seasoningCost?: number;
  packagingCost?: number; totalCost?: number; perBoxCost?: number; priceMasked?: boolean; hasData?: boolean;
  byproductCredit?: number; netTotalCost?: number; netPerBoxCost?: number; byproducts?: ByproductLine[];
  sampleRetainCount?: number; wasteQuantity?: number; sellableBoxCount?: number; sellablePerBoxCost?: number;
  packagingDetail?: PackagingItem[]; auxiliaryAllocations?: AuxAllocation[];
  sources?: CostSource[];
}
interface EnhancedCostAnalysis {
  totalMaterialCost?: number; totalLaborCost?: number; totalEquipmentCost?: number; totalOtherCost?: number;
  totalCost?: number; unitCost?: number;
  costSummary?: { otherCost?: number; totalCost?: number };
  costBreakdown?: {
    rawMaterialCost?: number; laborCost?: number; equipmentCost?: number; otherCost?: number; totalCost?: number;
  };
}
/** 段2(B): 辅料标准单价双锚点对账 (CostReconcileResult) */
interface ReconStep {
  processOrder?: number; processName?: string;
  standardYieldRate?: number; actualYieldRate?: number;
  auxBasis?: string; auxUnitPrice?: number;
  standardKg?: number; actualKg?: number;
  standardAuxCost?: number; actualAuxCost?: number; auxOverCost?: number;
  configured?: boolean; hasAuxPrice?: boolean;
}
interface ReconIssue { code?: string; message?: string; severity?: string }
interface CostReconcile {
  standardFirstInput?: number; actualFirstInput?: number; firstInputUnit?: string;
  overFeed?: number; overFeedRate?: number; overFeedAlert?: boolean;
  portionCount?: number;
  standardAuxCostTotal?: number; actualAuxCostTotal?: number; auxOverCostTotal?: number;
  auxOverRate?: number; auxAlert?: boolean;
  standardAuxCostPerUnit?: number; actualAuxCostPerUnit?: number; auxOverCostPerUnit?: number;
  threshold?: number; standardComplete?: boolean; linear?: boolean;
  steps?: ReconStep[]; issues?: ReconIssue[];
}
interface YieldSummary {
  orderId: string; overallYieldRate?: number;
  totalFirstInput?: number; totalLastOutput?: number; lastOutputUnit?: string;
  totalLaborCost?: number; totalMaterialCost?: number; totalCost?: number;
  batches?: Array<{ batchId?: number; steps?: Step[]; cumulativeYieldRate?: number }>;
}
/** Raw shape returned by by-batch endpoint (BatchYieldDTO) */
interface BatchYieldDTO {
  batchId?: number; batchNumber?: string;
  firstStepInput?: number; lastStepOutput?: number;
  firstStepInputUnit?: string; lastStepOutputUnit?: string;
  cumulativeYieldRate?: number;
  steps?: Step[];
  totalLaborCost?: number; totalMaterialCost?: number; totalCost?: number;
  complete?: boolean; inProgress?: boolean;
}

/** Phase 1: 完工批次下拉数据源 */
interface FinishedBatch {
  batchNumber: string;
  orderId?: string;
  productName?: string;
  plannedQty?: number;
  actualQty?: number;
  unit?: string;
  completedAt?: string;
  settled?: boolean;
}

const route = useRoute();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const queryMode = ref<'order' | 'batch'>('order');
const orderId = ref('');
const batchNumber = ref('');
const gramsPerBox = ref(100);

/** Phase 1: 完工批次下拉 */
const finishedBatches = ref<FinishedBatch[]>([]);
const selectedBatchKey = ref<string | null>(null);
const batchesLoading = ref(false);

function fmtTime(iso?: string): string {
  if (!iso) return '—';
  // "2026-06-25T10:30:00" → "06-25 10:30"
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const min = String(d.getMinutes()).padStart(2, '0');
  return `${mm}-${dd} ${hh}:${min}`;
}

async function loadFinishedBatches(autoSelectLatest = false) {
  const fid = factoryId.value;
  if (!fid) return;
  batchesLoading.value = true;
  try {
    const resp = await get<FinishedBatch[]>(`/${fid}/production/batches/finished`);
    if (resp.success && Array.isArray(resp.data)) {
      finishedBatches.value = resp.data;
      if (autoSelectLatest && !selectedBatchKey.value && !batchNumber.value && !orderId.value) {
        const latest = resp.data.find((b) => !b.settled) || resp.data[0];
        if (latest?.batchNumber) {
          selectedBatchKey.value = latest.batchNumber;
          onBatchSelect(latest.batchNumber);
        }
      }
    }
  } catch {
    // 列表加载失败不影响手动输入
  } finally {
    batchesLoading.value = false;
  }
}

function onBatchSelect(bn: string | null) {
  if (!bn) return;
  const match = finishedBatches.value.find((b) => b.batchNumber === bn);
  if (match) {
    batchNumber.value = match.batchNumber;
    if (match.orderId) {
      queryMode.value = 'order';
      orderId.value = match.orderId;
    } else {
      queryMode.value = 'batch';
    }
    load();
  }
}

/** Map BatchYieldDTO (by-batch single-batch response) into YieldSummary (the shape the page renders). */
function normalizeBatchYield(dto: BatchYieldDTO): YieldSummary {
  return {
    orderId: dto.batchNumber || String(dto.batchId || ''),
    overallYieldRate: dto.cumulativeYieldRate,
    totalFirstInput: dto.firstStepInput,
    totalLastOutput: dto.lastStepOutput,
    lastOutputUnit: dto.lastStepOutputUnit,
    totalLaborCost: dto.totalLaborCost,
    totalMaterialCost: dto.totalMaterialCost,
    totalCost: dto.totalCost,
    batches: [{
      batchId: dto.batchId,
      cumulativeYieldRate: dto.cumulativeYieldRate,
      steps: dto.steps,
    }],
  };
}
const loading = ref(false);
const error = ref('');
const data = ref<YieldSummary | null>(null);
const sankeyEl = ref<HTMLElement | null>(null);
const mixEl = ref<HTMLElement | null>(null);
const mixRels = ref<MixRel[]>([]);
const cb = ref<CostBreakdown | null>(null);   // 后端单一权威成本拆分 (谱系遍历)
const enhancedCost = ref<EnhancedCostAnalysis | null>(null);
const recon = ref<CostReconcile | null>(null);   // 段2(B) 辅料标准单价对账
const hasMix = computed(() => mixRels.value.length > 1); // >1 上游批次 = 真混批
let chart: any = null;
let mixChart: any = null;

// 工序名来自后端: getYield 按产品工序配置 (ProductWorkProcess) 填充 step.processName
// (config-driven, 非硬编码 — 操作员报工经 WorkProcessTask, 文员录入经 processOrder→配置名)。
const stepName = (s: Step) => s.processName || ('工序' + s.processOrder);

const steps = computed<Step[]>(() => {
  const b = data.value?.batches?.[0];
  return (b?.steps || []).slice().sort((a, c) => (a.processOrder || 0) - (c.processOrder || 0));
});
const hasUnallocatedStepCost = computed(() => {
  if (!enhancedCost.value) return false;
  return laborCostClosed.value > 0 || equipmentCostClosed.value > 0 || totalCostClosed.value > rawMaterialCostClosed.value;
});
const groupedSteps = computed<StepGroup[]>(() => {
  const all = steps.value;
  const groups = new Map<string, { processOrder: number; processName: string; outputUnit?: string; items: Array<{ step: Step; index: number }> }>();

  all.forEach((step, index) => {
    const processName = stepName(step);
    const key = `${step.processOrder}-${processName}-${step.outputUnit || ''}`;
    const existing = groups.get(key);
    if (existing) {
      existing.items.push({ step, index });
    } else {
      groups.set(key, {
        processOrder: step.processOrder,
        processName,
        outputUnit: step.outputUnit,
        items: [{ step, index }],
      });
    }
  });

  return Array.from(groups.entries()).map(([key, group]) => {
    const yields = group.items.map(({ step }) => step.yieldRate).filter((v): v is number => v != null);
    const costValues = group.items
      .map(({ step, index }) => stepFullCostValue(step, index, all))
      .filter((v): v is number => v != null);
    const costTags = new Set<string>();
    group.items.forEach(({ index }) => {
      if (index === 0 && cb.value?.rawMaterialCost != null) costTags.add('含原料');
      if (index === all.length - 1 && cb.value?.packagingCost != null) costTags.add('含包装');
    });

    return {
      key,
      processOrder: group.processOrder,
      processName: group.processName,
      count: group.items.length,
      totalInput: group.items.reduce((sum, item) => sum + Number(item.step.totalInput || 0), 0),
      totalOutput: group.items.reduce((sum, item) => sum + Number(item.step.totalOutput || 0), 0),
      outputUnit: group.outputUnit,
      avgYieldRate: yields.length ? yields.reduce((sum, val) => sum + val, 0) / yields.length : null,
      costValue: costValues.length ? costValues.reduce((sum, val) => sum + val, 0) : null,
      costTags: Array.from(costTags),
      costReason: stepCostUnavailableReason(),
    };
  });
});
const isBoxUnit = (unit?: string | null) => ['box', '盒'].includes((unit || '').trim().toLowerCase());
const boxCount = computed(() => {
  const actualOut = data.value?.totalLastOutput;
  if (actualOut != null && isBoxUnit(data.value?.lastOutputUnit)) return Number(actualOut);
  if (cb.value?.boxCount) return cb.value.boxCount;   // 权威: 后端 Σ批次盒数
  const out = data.value?.totalLastOutput;
  if (out == null || !gramsPerBox.value) return 0;
  return Math.round((out * 1000) / gramsPerBox.value);
});

const num = (v?: number | null) => (v == null ? '—' : Number(v).toFixed(1));
const pct = (v?: number | null) => (v == null ? 0 : v * 100);
const perBox = (v?: number | null) => (v == null || !boxCount.value ? 0 : v / boxCount.value);
const money2 = (v?: number | null) => {
  if (v == null) return '—';
  const n = Number(v);
  if (n > 0 && n < 0.005) return '<¥0.01';
  return `¥${n.toFixed(2)}`;
};

const hasOverallYieldUnitIssue = computed(() => {
  const issueText = (recon.value?.issues || []).map(i => i.message || '').join(' ');
  if (issueText.includes('跨单位') || issueText.includes('克重') || issueText.includes('unit') || issueText.includes('gramsPerUnit')) {
    return true;
  }

  const hasInputAndOutput = Number(data.value?.totalFirstInput || 0) > 0 && Number(data.value?.totalLastOutput || 0) > 0;
  const lastUnit = (data.value?.lastOutputUnit || '').toLowerCase();
  return hasInputAndOutput && data.value?.overallYieldRate === 0 && !!lastUnit && lastUnit !== 'kg';
});

const overallYieldKpi = computed<{
  value: string | number;
  unit: string;
  subtitle: string;
  status: 'warning' | 'default';
  targetValue: number | undefined;
}>(() => {
  if (hasOverallYieldUnitIssue.value) {
    return {
      value: '不可折算',
      unit: '',
      subtitle: '首道投入与末道产出单位不同，先配置产品标准克重',
      status: 'warning' as const,
      targetValue: undefined,
    };
  }

  return {
    value: pct(data.value?.overallYieldRate),
    unit: '%',
    subtitle: '成品净重 ÷ 原料投入',
    status: 'default' as const,
    targetValue: 60,
  };
});

/**
 * 计算每道工序的完整成本:
 *   base = step.laborCost + step.materialCost (调料/辅料已由后端按道分配)
 *   首道 (index===0) 追加 cb.rawMaterialCost (原料成本)
 *   末道 (index===steps.length-1) 追加 cb.packagingCost (包装成本)
 *
 * 注意: Step 接口只有 laborCost / materialCost, 没有 stepCost 字段.
 * 当 priceMasked 或需追加的成本分量为 null 时返回 null → 触发"—"展示.
 */
function stepFullCostValue(s: Step, index: number, allSteps: Step[]): number | null {
  const cbVal = cb.value;
  if (cbVal?.priceMasked) return null;
  const hasDirectStepCost = s.laborCost != null || s.materialCost != null;
  const hasRawCost = index === 0 && cbVal?.rawMaterialCost != null;
  const hasPackagingCost = index === allSteps.length - 1 && cbVal?.packagingCost != null;
  if (!hasDirectStepCost && !hasRawCost && !hasPackagingCost) return null;

  const base = (s.laborCost ?? 0) + (s.materialCost ?? 0);
  let full = base;
  if (index === 0) {
    if (cbVal?.rawMaterialCost == null) return null;
    full += cbVal.rawMaterialCost;
  }
  if (index === allSteps.length - 1) {
    if (cbVal?.packagingCost == null) return null;
    full += cbVal.packagingCost;
  }
  return full;
}

function stepCostUnavailableReason(): string {
  if (cb.value?.priceMasked) return '成本需价格权限';
  if (hasUnallocatedStepCost.value) return '本批人工 / 设备 / 其他在右侧汇总，未按本道分摊';
  return '本道暂无成本归集';
}

function stepPerBoxValue(fullCost: number | null): number | null {
  if (fullCost == null || !boxCount.value) return null;
  return fullCost / boxCount.value;
}

// 成本全部以后端单一权威服务 cb 为准 (谱系遍历 + 上游成本回溯); 缺失时回退订单聚合
const upstreamCost = computed(() => Number(cb.value?.rawMaterialCost ?? mixRels.value.reduce((s, r) => s + Number(r.totalCost || 0), 0)));
const enhancedBreakdown = computed(() => enhancedCost.value?.costBreakdown);
const enhancedRawMaterialCost = computed(() => Number(enhancedBreakdown.value?.rawMaterialCost ?? enhancedCost.value?.totalMaterialCost ?? 0));
const rawMaterialCostClosed = computed(() => Number(cb.value?.rawMaterialCost ?? enhancedBreakdown.value?.rawMaterialCost ?? enhancedCost.value?.totalMaterialCost ?? upstreamCost.value));
const seasoningCostClosed = computed(() => Number(cb.value?.seasoningCost ?? 0));
const packagingCostClosed = computed(() => Number(cb.value?.packagingCost ?? 0));
const laborCostClosed = computed(() => Number(cb.value?.laborCost ?? enhancedBreakdown.value?.laborCost ?? enhancedCost.value?.totalLaborCost ?? data.value?.totalLaborCost ?? 0));
const equipmentCostClosed = computed(() => Number(enhancedBreakdown.value?.equipmentCost ?? enhancedCost.value?.totalEquipmentCost ?? 0));
const otherCostClosed = computed(() => {
  const explicitOther = enhancedBreakdown.value?.otherCost ?? enhancedCost.value?.totalOtherCost ?? enhancedCost.value?.costSummary?.otherCost;
  if (explicitOther != null) return Number(explicitOther);

  const enhancedTotal = enhancedBreakdown.value?.totalCost ?? enhancedCost.value?.totalCost ?? enhancedCost.value?.costSummary?.totalCost;
  if (enhancedTotal != null) {
    return Math.max(0, Number(enhancedTotal) - enhancedRawMaterialCost.value - laborCostClosed.value - equipmentCostClosed.value);
  }

  const fallbackTotal = cb.value?.totalCost ?? data.value?.totalCost;
  if (fallbackTotal != null) {
    return Math.max(0, Number(fallbackTotal) - rawMaterialCostClosed.value - seasoningCostClosed.value - packagingCostClosed.value - laborCostClosed.value);
  }

  return 0;
});
const totalCostClosed = computed(() => {
  if (cb.value?.totalCost != null) {
    return Number(cb.value.totalCost);
  }
  if (enhancedCost.value || cb.value) {
    return rawMaterialCostClosed.value
      + seasoningCostClosed.value
      + packagingCostClosed.value
      + laborCostClosed.value
      + equipmentCostClosed.value
      + otherCostClosed.value;
  }
  return Number(data.value?.totalCost || 0) + upstreamCost.value;
});
const singleBoxCostSubtitle = computed(() => enhancedCost.value
  ? '闭环总成本 ÷ 实际产出盒数'
  : '含上游混批 traced 原料成本 ÷ 盒数');
const costBreakdownHint = computed(() => enhancedCost.value
  ? '闭环总成本 = traced 原料 + 工序辅料 + 包材 + 人工 + 设备 + 其他'
  : '原料=上游混批 traced 成本之和 (闭环)');

// 副产回收 (肥油/料头等可变现副产物冲减成本); 价格脱敏时 value/credit/net 为 null
const byproducts = computed<ByproductLine[]>(() => cb.value?.byproducts || []);
const hasByproduct = computed(() => byproducts.value.length > 0);
const byproductCredit = computed(() => Number(cb.value?.byproductCredit ?? 0));
const netPerBox = computed(() => cb.value?.netPerBoxCost != null ? Number(cb.value.netPerBoxCost) : null);

// 留样扣减 (产出不可售 → 可售单盒成本); 料头损耗仅展示 (已体现在出成率, 不二次扣) — AUDIT-006
const sampleRetainCount = computed(() => Number(cb.value?.sampleRetainCount ?? 0));
const sellableBoxCount = computed(() => Number(cb.value?.sellableBoxCount ?? 0));
const sellablePerBox = computed(() => cb.value?.sellablePerBoxCost != null ? Number(cb.value.sellablePerBoxCost) : null);
const wasteQty = computed(() => cb.value?.wasteQuantity != null ? Number(cb.value.wasteQuantity) : null);

// 包装明细 4 拆 (膜/气体/标签/其他) — AUDIT-002
const packagingDetail = computed<PackagingItem[]>(() => cb.value?.packagingDetail || []);
const packagingTotal = computed(() => packagingDetail.value.reduce((s, p) => s + Number(p.cost || 0), 0));

// 辅料按锅分摊 (一锅辅料被多批共用→按产出量分摊) — AUDIT-004
const auxAllocations = computed<AuxAllocation[]>(() => cb.value?.auxiliaryAllocations || []);

const barPct = (y?: number | null) => (y == null ? 0 : Math.min(100, Math.round(y * 100)));
const yieldStatus = (y?: number | null) => {
  if (y == null) return 'info';
  const p = y * 100;
  if (p < 50) return 'exception';        // 损耗过大 → 红
  if (p > 130) return 'warning';          // 异常增重 → 黄
  return 'success';
};
const yieldClass = (y?: number | null) => {
  if (y == null) return 'y-na';
  const p = y * 100;
  if (p < 50) return 'y-low';
  if (p > 130) return 'y-high';
  return 'y-ok';
};

const costBreakdown = computed(() => {
  const d = data.value; if (!d && !cb.value) return [];
  if (enhancedCost.value || enhancedBreakdown.value) {
    const total = totalCostClosed.value || 1;
    const bc = boxCount.value || 1;
    const rawMat = rawMaterialCostClosed.value;
    const seasoning = seasoningCostClosed.value;
    const packaging = packagingCostClosed.value;
    const labor = laborCostClosed.value;
    const equipment = equipmentCostClosed.value;
    const other = otherCostClosed.value;
    const rows = [
      { name: '原料', amount: rawMat, color: '#5470c6' },
      { name: '辅料/调料', amount: seasoning, color: '#ee6666' },
      { name: '包材', amount: packaging, color: '#9a60b4' },
      { name: '人工', amount: labor, color: '#91cc75' },
      { name: '设备', amount: equipment, color: '#73c0de' },
      { name: '其他', amount: other, color: '#fac858' },
    ].filter((r) => r.amount > 0);
    return rows.map((r) => ({ ...r, share: Math.round((r.amount / total) * 100), perBox: r.amount / bc }));
  }
  const st = steps.value;
  // 全部以后端权威 cb 为准; cb 缺失时回退订单聚合/报告
  const labor = Number(cb.value?.laborCost ?? d?.totalLaborCost ?? 0);
  const rawMat = Number(cb.value?.rawMaterialCost ?? (upstreamCost.value > 0 ? upstreamCost.value : (st.length ? (st[0].materialCost || 0) : 0)));
  const pkgMat = Number(cb.value?.packagingCost ?? (st.length ? (st[st.length - 1].materialCost || 0) : 0));
  const seasoning = Number(cb.value?.seasoningCost ?? Math.max(0, (d?.totalMaterialCost || 0) - pkgMat));
  const total = totalCostClosed.value || 1;
  const bc = boxCount.value || 1;
  const rows = [
    { name: '原料', amount: rawMat, color: '#5470c6' },
    { name: '人工', amount: labor, color: '#91cc75' },
    { name: '调料', amount: seasoning, color: '#fac858' },
    { name: '包装', amount: pkgMat, color: '#ee6666' },
  ];
  return rows.map((r) => ({ ...r, share: Math.round((r.amount / total) * 100), perBox: r.amount / bc }));
});

function renderSankey() {
  if (!sankeyEl.value || !groupedSteps.value.length) return;
  if (!chart) chart = echarts.init(sankeyEl.value);
  const st = groupedSteps.value;
  const nodes: SankeyNode[] = [{ name: 'raw-input', displayName: '原料' }];
  const nodeLabel = new Map<string, string>([['raw-input', '原料']]);
  st.forEach((s, index) => {
    const name = `step-group-${index}-${s.processOrder}-${s.processName}`;
    const displayName = s.processName;
    nodes.push({ name, displayName });
    nodeLabel.set(name, displayName);
  });
  const links: { source: string; target: string; value: number }[] = [];
  let prev = 'raw-input';
  st.forEach((s, index) => {
    const cur = `step-group-${index}-${s.processOrder}-${s.processName}`;
    links.push({ source: prev, target: cur, value: Number(s.totalInput || 0) });
    prev = cur;
  });
  chart.setOption({
    tooltip: { trigger: 'item', formatter: (p: any) => p.dataType === 'edge' ? `${nodeLabel.get(p.data.source) || p.data.source} → ${nodeLabel.get(p.data.target) || p.data.target}: ${p.data.value} kg` : (p.data?.displayName || p.name) },
    series: [{
      type: 'sankey', left: 20, right: 120, top: 20, bottom: 20,
      emphasis: { focus: 'adjacency' },
      lineStyle: { color: 'gradient', opacity: 0.5 },
      label: { fontSize: 12, formatter: (p: any) => p.data?.displayName || p.name },
      data: nodes, links,
    }],
  });
  chart.resize();
}

// 单一权威: 调后端成本拆分端点 (谱系遍历+上游成本回溯+人工归集)
// cb.sources 映射进 mixRels 供溯源桑基图/成本拆分表复用 (价格按 procurement:price:view 权限脱敏)
// 订单号模式: /production/orders/{orderId}/cost-breakdown
// 批次号模式: /production/batches/{batchNumber}/cost-breakdown (返回相同 OrderCostBreakdownDTO 形态)
async function loadCostBreakdown() {
  mixRels.value = []; cb.value = null;
  const fid = factoryId.value;
  if (!fid) return;
  const costUrl = queryMode.value === 'batch'
    ? (batchNumber.value ? `/${fid}/production/batches/${batchNumber.value}/cost-breakdown` : null)
    : (orderId.value ? `/${fid}/production/orders/${orderId.value}/cost-breakdown` : null);
  if (!costUrl) return;
  try {
    const resp = await get<CostBreakdown>(costUrl);
    if (resp.success && resp.data) {
      cb.value = resp.data;
      mixRels.value = (resp.data.sources || []).map(s => ({
        batchNumber: s.batchName, batchId: s.batchId, quantity: s.quantity,
        unit: s.unit, unitPrice: s.unitPrice, totalCost: s.cost,
        weightSharePct: s.weightSharePct, costSharePct: s.costSharePct,
      }));
    }
  } catch { /* 无成本拆分 → 混批卡不显示 */ }
}

async function loadEnhancedBatchCost() {
  enhancedCost.value = null;
  const fid = factoryId.value;
  const batchId = data.value?.batches?.[0]?.batchId;
  if (!fid || !batchId) return;
  try {
    const resp = await get<EnhancedCostAnalysis>(`/${fid}/processing/batches/${batchId}/cost-analysis/enhanced`);
    if (resp.success && resp.data) {
      enhancedCost.value = resp.data;
    }
  } catch { /* 增强成本失败时回退 cost-breakdown 口径 */ }
}

// 段2(B): 辅料标准单价双锚点对账 (标准应投 vs 实际投料 → 多投/误差)。
// 端点按批次号 (BatchYieldDTO 同源逐道报工 + 标准配方率)。订单模式下若已选完工批次,
// onBatchSelect 已填 batchNumber → 仍可对账; 纯订单手输 (无批次号) → 跳过 (对账是批次级)。
async function loadReconcile() {
  recon.value = null;
  const fid = factoryId.value;
  if (!fid || !batchNumber.value) return;
  try {
    const resp = await get<CostReconcile>(`/${fid}/production/batches/${batchNumber.value}/aux-cost-reconcile`);
    if (resp.success && resp.data) {
      recon.value = resp.data;
    }
  } catch { /* 对账失败不阻塞主页面 */ }
}

// 对账展示辅助
const reconWarns = computed<ReconIssue[]>(() => (recon.value?.issues || []).filter((i) => i.severity === 'WARN'));
const reconInfos = computed<ReconIssue[]>(() => (recon.value?.issues || []).filter((i) => i.severity !== 'WARN'));
const reconThresholdPct = computed(() => (recon.value?.threshold != null ? (recon.value.threshold * 100).toFixed(0) : '5'));
const reconMoney = (v?: number | null) => (v == null ? '—' : '¥' + Number(v).toFixed(2));
const reconRate = (r?: number | null) => (r == null ? '—' : (r * 100).toFixed(1) + '%');
const overCellClass = (v?: number | null) => (v == null ? '' : (v > 0 ? 'over-bad' : (v < 0 ? 'over-credit' : '')));

// 成本拆分: 按实测投料量×单价 (异质成本下 成本占比 ≠ 重量占比, 正是 Excel 糊平均的盲区)
const mixCostSplit = computed(() => {
  const rows = mixRels.value.filter(r => r.totalCost != null);
  const totalCost = rows.reduce((s, r) => s + Number(r.totalCost || 0), 0);
  const totalQty = mixRels.value.reduce((s, r) => s + Number(r.quantity || 0), 0) || 1;
  return {
    hasCost: rows.length > 0,
    totalCost,
    rows: mixRels.value.map((r, index) => ({
      key: `${r.batchId || r.batchNumber || 'upstream'}-${index}`,
      name: r.batchNumber || r.batchId || '上游',
      qty: Number(r.quantity || 0),
      unit: r.unit,
      unitPrice: r.unitPrice,
      cost: r.totalCost,
      weightShare: r.weightSharePct != null ? Number(r.weightSharePct) : Math.round((Number(r.quantity || 0) / totalQty) * 1000) / 10,
      costShare: r.costSharePct != null ? Number(r.costSharePct) : (totalCost ? Math.round((Number(r.totalCost || 0) / totalCost) * 1000) / 10 : null),
    })),
  };
});

function renderMix() {
  if (!mixEl.value || !mixRels.value.length) return;
  if (!mixChart) mixChart = echarts.init(mixEl.value);
  const SZ = '熟制 (本批)';
  const FG = '成品';
  const palette = ['#5470c6', '#73c0de', '#fac858', '#ee6666', '#9a60b4'];
  const nodes: any[] = [];
  const seen = new Set<string>();
  const addNode = (name: string, color: string) => { if (!seen.has(name)) { seen.add(name); nodes.push({ name, itemStyle: { color } }); } };
  const links: { source: string; target: string; value: number }[] = [];
  let totalIn = 0;
  mixRels.value.forEach((r, i) => {
    const src = r.batchNumber || r.batchId || ('上游' + (i + 1));
    const v = Number(r.quantity || 0);
    totalIn += v;
    addNode(src, palette[i % palette.length]);
    links.push({ source: src, target: SZ, value: v });
  });
  addNode(SZ, '#fac858');
  addNode(FG, '#91cc75');
  const fgVal = Number(data.value?.totalLastOutput || totalIn);
  links.push({ source: SZ, target: FG, value: fgVal });
  mixChart.setOption({
    tooltip: { trigger: 'item', formatter: (p: any) => p.dataType === 'edge' ? `${p.data.source} → ${p.data.target}: ${p.data.value} kg` : p.name },
    series: [{
      type: 'sankey', left: 20, right: 140, top: 20, bottom: 20,
      emphasis: { focus: 'adjacency' },
      lineStyle: { color: 'gradient', opacity: 0.5 },
      label: { fontSize: 12 },
      data: nodes, links,
    }],
  });
  mixChart.resize();
}

async function load() {
  const fid = factoryId.value;
  const isBatch = queryMode.value === 'batch';
  const key = isBatch ? batchNumber.value : orderId.value;
  if (!fid || !key) return;
  loading.value = true; error.value = '';
  try {
    if (isBatch) {
      const resp = await get<BatchYieldDTO>(`/${fid}/production/batches/${key}/yield-summary`);
      if (resp.success && resp.data) {
        selectedBatchKey.value = resp.data.batchNumber || key;
        batchNumber.value = resp.data.batchNumber || key;
        data.value = normalizeBatchYield(resp.data);
      } else {
        error.value = resp.message || '加载失败';
        loading.value = false;
        return;
      }
    } else {
      const resp = await get<YieldSummary>(`/${fid}/production/orders/${key}/yield-summary`);
      if (resp.success && resp.data) {
        data.value = resp.data;
      } else {
        error.value = resp.message || '加载失败';
        loading.value = false;
        return;
      }
    }
    await loadCostBreakdown();
    await loadEnhancedBatchCost();
    await loadReconcile();
    await nextTick();
    renderSankey();
    renderMix();
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || `加载失败，请检查${isBatch ? '批次号' : '订单号'}`;
  } finally {
    loading.value = false;
  }
}

const onResize = () => { chart?.resize(); mixChart?.resize(); };
onMounted(() => {
  window.addEventListener('resize', onResize);
  const qOrderId = route.query.orderId as string | undefined;
  const qBatchNumber = route.query.batchNumber as string | undefined;
  if (qOrderId) {
    loadFinishedBatches();
    queryMode.value = 'order';
    orderId.value = qOrderId;
    load();
  } else if (qBatchNumber) {
    loadFinishedBatches();
    queryMode.value = 'batch';
    batchNumber.value = qBatchNumber;
    selectedBatchKey.value = qBatchNumber;
    load();
  } else {
    loadFinishedBatches(true);
  }
});
onBeforeUnmount(() => { window.removeEventListener('resize', onResize); chart?.dispose(); mixChart?.dispose(); });
</script>

<style scoped>
.m67-page { padding: 16px; }
.m67-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.m67-header h2 { margin: 0; }
.sub { color: #909399; margin: 4px 0 0; font-size: 13px; }
.ctrls { display: flex; align-items: center; gap: 8px; }
.gw { color: #909399; font-size: 13px; }
.mb { margin-bottom: 16px; }
.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.mix-note { color: #606266; font-size: 13px; background: #f4f6fa; border-radius: 6px; padding: 10px 12px; margin-top: 8px; }
.mix-cost { margin-top: 12px; }
.mix-cost-title { font-weight: 600; margin-bottom: 8px; }
.mix-tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.mix-tbl th, .mix-tbl td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: right; }
.mix-tbl th:first-child, .mix-tbl td:first-child { text-align: left; }
.mix-tbl thead th { background: #f5f7fa; color: #606266; font-weight: 600; }
.mix-tbl td.hl { color: #e6a23c; font-weight: 700; }
.step-summary-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  color: #606266;
  font-size: 12px;
}
.step-summary-strip span {
  padding: 3px 8px;
  background: #f4f6fa;
  border-radius: 4px;
}
.step { margin-bottom: 14px; }
.step-top { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.pname { font-weight: 600; min-width: 160px; max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qty { color: #606266; font-size: 13px; flex: 1; }
.step-labor { color: #e6a23c; font-size: 12px; margin-right: 10px; white-space: nowrap; }
.yr { font-weight: 700; }
.y-ok { color: #67c23a; } .y-low { color: #f56c6c; } .y-high { color: #e6a23c; } .y-na { color: #909399; }
.total-box { font-size: 30px; font-weight: 800; margin-bottom: 12px; }
.total-box span { font-size: 14px; color: #909399; font-weight: 400; }
.cost-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.cdot { width: 10px; height: 10px; border-radius: 50%; }
.cname { width: 40px; font-size: 13px; }
.cval { width: 64px; text-align: right; font-weight: 600; }
.cost-row.byp .byp-meta { flex: 1; color: #909399; font-size: 12px; }
.cost-row.byp .cval { width: auto; min-width: 64px; font-weight: 600; }
.cost-row.byp .cval.credit { color: #67c23a; }
.net-box { margin-top: 8px; padding: 8px 12px; background: #f0f9eb; border-radius: 6px; font-size: 14px; }
.net-box b { font-size: 20px; color: #529b2e; }
.net-box span { color: #909399; font-size: 12px; margin-left: 4px; }
.net-box.sell { background: #fdf6ec; }
.net-box.sell b { color: #b88230; }
.pkg-detail { margin: 6px 0 4px 18px; padding: 8px 12px; background: #f9fafc; border-radius: 6px; border-left: 3px solid #ee6666; }
.pkg-title { font-size: 12px; color: #909399; margin-bottom: 6px; }
.pkg-row { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 2px 0; }
.pkg-name { width: 48px; color: #606266; }
.pkg-cost { width: 80px; text-align: right; font-weight: 600; }
.pkg-perbox { color: #909399; font-size: 12px; }
.aux-alloc { margin: 6px 0 4px 18px; padding: 8px 12px; background: #f4f8fb; border-radius: 6px; border-left: 3px solid #5470c6; }
.aux-line1 { font-size: 13px; color: #606266; font-weight: 600; }
.aux-pot { color: #909399; font-weight: 400; margin-left: 6px; }
.aux-line2 { font-size: 12px; color: #909399; margin-top: 2px; }
.aux-share { color: #409eff; font-weight: 600; margin-left: 4px; }
.step-cost { display: flex; align-items: center; gap: 12px; margin-top: 4px; font-size: 12px; color: #606266; }
.sc-full { font-weight: 600; color: #303133; }
.sc-tag { color: #409eff; font-weight: 400; margin-left: 4px; }
.sc-perbox { color: #909399; }
.sc-masked { color: #c0c4cc; font-style: italic; }
.ctrls-wrap { display: flex; flex-direction: column; gap: 8px; align-items: flex-end; }
.batch-opt { display: flex; flex-direction: column; gap: 2px; }
.batch-name { font-weight: 600; font-size: 14px; }
.batch-no { font-size: 12px; color: #909399; }
.batch-time { font-size: 12px; color: #909399; }
.badge-settled { background: #f0f9eb; color: #529b2e; font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.badge-unsettled { background: #fef9f0; color: #b88230; font-size: 11px; padding: 1px 6px; border-radius: 3px; }

/* 段2(B) 辅料标准单价对账 */
.recon-warn { margin-bottom: 10px; }
.recon-feed { display: flex; align-items: center; gap: 14px; padding: 10px 14px; background: #f4f8fb; border-radius: 6px; margin-bottom: 12px; }
.rf-item { display: flex; flex-direction: column; gap: 2px; }
.rf-label { font-size: 12px; color: #909399; }
.rf-val { font-size: 18px; font-weight: 700; color: #303133; }
.rf-rate { font-size: 13px; font-weight: 600; margin-left: 4px; }
.rf-sep { font-size: 18px; color: #c0c4cc; }
.over-ok { color: #67c23a; }
.over-bad { color: #f56c6c; }
.over-credit { color: #67c23a; }
.recon-aux { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.ra-col { padding: 10px 14px; background: #f9fafc; border-radius: 6px; text-align: center; border: 1px solid #ebeef5; }
.ra-col.ra-over { background: #fef0f0; border-color: #fbc4c4; }
.ra-t { font-size: 12px; color: #909399; margin-bottom: 4px; }
.ra-v { font-size: 20px; font-weight: 700; color: #303133; }
.ra-pu { font-size: 12px; color: #909399; margin-top: 2px; }
.recon-tbl { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 8px; }
.recon-tbl th, .recon-tbl td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: right; }
.recon-tbl th:first-child, .recon-tbl td:first-child { text-align: left; }
.recon-tbl thead th { background: #f5f7fa; color: #606266; font-weight: 600; }
.recon-info { margin-top: 6px; padding: 8px 12px; background: #f4f6fa; border-radius: 6px; }
.ri-line { font-size: 12px; color: #606266; line-height: 1.7; }
</style>
