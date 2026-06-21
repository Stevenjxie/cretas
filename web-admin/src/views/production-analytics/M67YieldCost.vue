<!--
  成品出厂核算 (M67 demo)
  复用现成后端 GET /production/orders/{orderId}/yield-summary (OrderYieldController)
  展示客户 3 个核心数: 出成率 / 单盒成本 / 单盒人工 + 逐道出成率 + 成本四拆 + 批次溯源桑基图
-->
<template>
  <div class="m67-page">
    <div class="m67-header">
      <div>
        <h2>成品出厂核算</h2>
        <p class="sub">全链路出成率 · 单盒成本 · 人工成本 (按订单批次)</p>
      </div>
      <div class="ctrls">
        <el-input v-model="orderId" placeholder="订单号" style="width: 220px" />
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <template v-if="data">
      <!-- 3 核心数 + 盒数 -->
      <div class="kpis">
        <KPICard title="整批出成率" :value="pct(data.overallYieldRate)" unit="%" format="number" :precision="1"
                 icon="TrendCharts" :target-value="60" subtitle="成品净重 ÷ 原料投入" />
        <KPICard title="单盒成本" :value="perBox(totalCostClosed)" unit="元/盒" format="currency" :precision="2"
                 icon="Coin" subtitle="含上游混批 traced 原料成本 ÷ 盒数" />
        <KPICard title="单盒人工" :value="perBox(cb?.laborCost ?? data.totalLaborCost)" unit="元/盒" format="currency" :precision="2"
                 icon="User" subtitle="总人工 ÷ 盒数" />
        <KPICard title="产出盒数" :value="boxCount" unit="盒" format="number" :precision="0"
                 icon="Box" :subtitle="`末道产出 ${num(data.totalLastOutput)} ${data.lastOutputUnit || 'kg'}`" />
      </div>

      <el-row :gutter="16" class="mb">
        <!-- 逐道出成率 -->
        <el-col :span="14">
          <el-card shadow="never">
            <template #header><b>逐道出成率</b><span class="hint">投入 → 产出 (注水增重 &gt;100% 正常)</span></template>
            <div v-for="s in steps" :key="s.processOrder" class="step">
              <div class="step-top">
                <span class="pname">{{ stepName(s) }}</span>
                <span class="qty">{{ num(s.totalInput) }} → {{ num(s.totalOutput) }} {{ s.outputUnit || 'kg' }}</span>
                <span class="yr" :class="yieldClass(s.yieldRate)">{{ s.yieldRate == null ? '—' : (s.yieldRate * 100).toFixed(1) + '%' }}</span>
              </div>
              <el-progress :percentage="barPct(s.yieldRate)" :status="yieldStatus(s.yieldRate)" :stroke-width="12" :show-text="false" />
            </div>
          </el-card>
        </el-col>

        <!-- 单盒成本拆解 -->
        <el-col :span="10">
          <el-card shadow="never">
            <template #header><b>单盒成本拆解</b><span class="hint">原料=上游混批 traced 成本之和 (闭环)</span></template>
            <div class="total-box">¥{{ perBox(totalCostClosed).toFixed(2) }}<span>/盒</span></div>
            <div v-for="c in costBreakdown" :key="c.name" class="cost-row">
              <span class="cdot" :style="{ background: c.color }"></span>
              <span class="cname">{{ c.name }}</span>
              <el-progress :percentage="c.share" :color="c.color" :stroke-width="14" style="flex:1" />
              <span class="cval">¥{{ c.perBox.toFixed(2) }}</span>
            </div>
          </el-card>
        </el-col>
      </el-row>

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
            <thead><tr><th>上游来源</th><th>投料量</th><th>单价</th><th>成本</th><th>重量占比</th><th>成本占比</th></tr></thead>
            <tbody>
              <tr v-for="row in mixCostSplit.rows" :key="row.name">
                <td>{{ row.name }}</td>
                <td>{{ row.qty }} kg</td>
                <td>¥{{ Number(row.unitPrice).toFixed(2) }}/kg</td>
                <td>¥{{ Number(row.cost).toFixed(2) }}</td>
                <td>{{ row.weightShare }}%</td>
                <td :class="{ hl: row.costShare !== row.weightShare }">{{ row.costShare }}%</td>
              </tr>
            </tbody>
          </table>
          <div class="mix-note">注意：本批两条上游链<b>单价不同</b>，所以<b>成本占比 ≠ 重量占比</b>——按重量糊一个平均会算错成本，必须按实测投料量×各自单价精确归集。这正是 Excel(XLOOKUP 只取一条链)做不到、客户"只能加权平均"的盲区。数据来源：后端单一权威成本服务 (/production/orders/.../cost-breakdown, 谱系遍历+上游成本回溯)。</div>
        </div>
        <div v-else class="mix-note">本批来自 {{ mixRels.length }} 个上游批次，按实测投料量逐批溯源。成本金额需价格查看权限。数据来源：后端成本拆分服务 (/cost-breakdown)。</div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading && !error" description="输入订单号后点刷新" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import KPICard from '@/components/smartbi/KPICard.vue';

interface Step {
  processOrder: number; processName?: string;
  totalInput?: number; totalOutput?: number; outputUnit?: string;
  yieldRate?: number; laborCost?: number; materialCost?: number;
}
interface MixRel { batchNumber?: string; batchId?: string; quantity?: number; unitPrice?: number; totalCost?: number; sourceType?: string }
interface CostSource { batchId?: string; batchName?: string; quantity?: number; unit?: string; unitPrice?: number; cost?: number; weightSharePct?: number; costSharePct?: number; depth?: number }
interface CostBreakdown {
  boxCount?: number; rawMaterialCost?: number; laborCost?: number; seasoningCost?: number;
  packagingCost?: number; totalCost?: number; perBoxCost?: number; priceMasked?: boolean; hasData?: boolean;
  sources?: CostSource[];
}
interface YieldSummary {
  orderId: string; overallYieldRate?: number;
  totalFirstInput?: number; totalLastOutput?: number; lastOutputUnit?: string;
  totalLaborCost?: number; totalMaterialCost?: number; totalCost?: number;
  batches?: Array<{ batchId?: number; steps?: Step[]; cumulativeYieldRate?: number }>;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const orderId = ref('SO-M67DEMO-001');
const gramsPerBox = ref(100);
const loading = ref(false);
const error = ref('');
const data = ref<YieldSummary | null>(null);
const sankeyEl = ref<HTMLElement | null>(null);
const mixEl = ref<HTMLElement | null>(null);
const mixRels = ref<MixRel[]>([]);
const cb = ref<CostBreakdown | null>(null);   // 后端单一权威成本拆分 (谱系遍历)
const hasMix = computed(() => mixRels.value.length > 1); // >1 上游批次 = 真混批
let chart: any = null;
let mixChart: any = null;

// 工序名 fallback (后端 processName 未解析时, 按 M67 卤味标准工序序显示)
const STAGE_NAMES: Record<number, string> = { 1: '修油', 2: '滚揉', 3: '焯水', 4: '熟制', 5: '气调', 6: '包装' };
const stepName = (s: Step) => s.processName || STAGE_NAMES[s.processOrder] || ('工序' + s.processOrder);

const steps = computed<Step[]>(() => {
  const b = data.value?.batches?.[0];
  return (b?.steps || []).slice().sort((a, c) => (a.processOrder || 0) - (c.processOrder || 0));
});
const boxCount = computed(() => {
  if (cb.value?.boxCount) return cb.value.boxCount;   // 权威: 后端 Σ批次盒数
  const out = data.value?.totalLastOutput;
  if (out == null || !gramsPerBox.value) return 0;
  return Math.round((out * 1000) / gramsPerBox.value);
});

const num = (v?: number | null) => (v == null ? '—' : Number(v).toFixed(1));
const pct = (v?: number | null) => (v == null ? 0 : v * 100);
const perBox = (v?: number | null) => (v == null || !boxCount.value ? 0 : v / boxCount.value);

// 成本全部以后端单一权威服务 cb 为准 (谱系遍历 + 上游成本回溯); 缺失时回退订单聚合
const upstreamCost = computed(() => Number(cb.value?.rawMaterialCost ?? mixRels.value.reduce((s, r) => s + Number(r.totalCost || 0), 0)));
const totalCostClosed = computed(() => Number(cb.value?.totalCost ?? (Number(data.value?.totalCost || 0) + upstreamCost.value)));

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
  if (!sankeyEl.value || !steps.value.length) return;
  if (!chart) chart = echarts.init(sankeyEl.value);
  const st = steps.value;
  const nodes: { name: string }[] = [{ name: '原料' }];
  st.forEach((s) => nodes.push({ name: stepName(s) }));
  const links: { source: string; target: string; value: number }[] = [];
  let prev = '原料';
  st.forEach((s) => {
    const cur = stepName(s);
    links.push({ source: prev, target: cur, value: Number(s.totalInput || 0) });
    prev = cur;
  });
  chart.setOption({
    tooltip: { trigger: 'item', formatter: (p: any) => p.dataType === 'edge' ? `${p.data.source} → ${p.data.target}: ${p.data.value} kg` : p.name },
    series: [{
      type: 'sankey', left: 20, right: 120, top: 20, bottom: 20,
      emphasis: { focus: 'adjacency' },
      lineStyle: { color: 'gradient', opacity: 0.5 },
      label: { fontSize: 12 },
      data: nodes, links,
    }],
  });
  chart.resize();
}

// 单一权威: 调后端 /production/orders/{orderId}/cost-breakdown (谱系遍历+上游成本回溯+人工归集)
// cb.sources 映射进 mixRels 供溯源桑基图/成本拆分表复用 (价格按 procurement:price:view 权限脱敏)
async function loadCostBreakdown() {
  mixRels.value = []; cb.value = null;
  if (!factoryId.value || !orderId.value) return;
  try {
    const resp = await get<CostBreakdown>(`/${factoryId.value}/production/orders/${orderId.value}/cost-breakdown`);
    if (resp.success && resp.data) {
      cb.value = resp.data;
      mixRels.value = (resp.data.sources || []).map(s => ({
        batchNumber: s.batchName, batchId: s.batchId, quantity: s.quantity,
        unitPrice: s.unitPrice, totalCost: s.cost,
      }));
    }
  } catch { /* 无成本拆分 → 混批卡不显示 */ }
}

// 成本拆分: 按实测投料量×单价 (异质成本下 成本占比 ≠ 重量占比, 正是 Excel 糊平均的盲区)
const mixCostSplit = computed(() => {
  const rows = mixRels.value.filter(r => r.totalCost != null);
  const totalCost = rows.reduce((s, r) => s + Number(r.totalCost || 0), 0);
  const totalQty = mixRels.value.reduce((s, r) => s + Number(r.quantity || 0), 0) || 1;
  return {
    hasCost: rows.length > 0,
    totalCost,
    rows: mixRels.value.map(r => ({
      name: r.batchNumber || r.batchId || '上游',
      qty: Number(r.quantity || 0),
      unitPrice: r.unitPrice,
      cost: r.totalCost,
      weightShare: Math.round((Number(r.quantity || 0) / totalQty) * 1000) / 10,
      costShare: totalCost ? Math.round((Number(r.totalCost || 0) / totalCost) * 1000) / 10 : null,
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
  if (!factoryId.value || !orderId.value) return;
  loading.value = true; error.value = '';
  try {
    const resp = await get<YieldSummary>(`/${factoryId.value}/production/orders/${orderId.value}/yield-summary`);
    if (resp.success && resp.data) {
      data.value = resp.data;
      await loadCostBreakdown();
      await nextTick();
      renderSankey();
      renderMix();
    } else {
      error.value = resp.message || '加载失败';
    }
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '加载失败，请检查订单号';
  } finally {
    loading.value = false;
  }
}

const onResize = () => { chart?.resize(); mixChart?.resize(); };
onMounted(() => { window.addEventListener('resize', onResize); load(); });
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
.step { margin-bottom: 14px; }
.step-top { display: flex; align-items: center; margin-bottom: 4px; }
.pname { font-weight: 600; width: 64px; }
.qty { color: #606266; font-size: 13px; flex: 1; }
.yr { font-weight: 700; }
.y-ok { color: #67c23a; } .y-low { color: #f56c6c; } .y-high { color: #e6a23c; } .y-na { color: #909399; }
.total-box { font-size: 30px; font-weight: 800; margin-bottom: 12px; }
.total-box span { font-size: 14px; color: #909399; font-weight: 400; }
.cost-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.cdot { width: 10px; height: 10px; border-radius: 50%; }
.cname { width: 40px; font-size: 13px; }
.cval { width: 64px; text-align: right; font-weight: 600; }
</style>
