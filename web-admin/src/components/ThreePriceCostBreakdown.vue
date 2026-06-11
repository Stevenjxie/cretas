<script setup lang="ts">
/**
 * SP3 P1 #39: 三价成本拆解 + 双口径人工对比视图 (ThreePriceCostBreakdown)
 *
 * 展示每个销售订单/成本组的三价 × 双口径对比：
 *   ① BOM 标准价 (quotedLaborCostPerKg, standardCostPerUnit)
 *   ② 实际移动均价 (actualCostPerUnit, actualLaborCostPerKg)
 *   ③ 销售价 (unitPrice)
 *
 * @PriceSensitive 规则:
 *   - 绝对成本字段（金额/单价）: 非财务角色后端返回 null → 显示 "—"
 *   - 偏差率 % / 是否超支 / alarmMessage: 不脱敏, 所有角色可见
 *   - 严禁前端重算成本（null 就是 null, 不做 fallback 推算）
 *
 * Props:
 *   costBreakdown  — FinanceCostBreakdown (当前订单 BOM/实际成本)
 *   orderAggregate — LaborEfficiencyOrderAggregateDTO (SP3 M3b 订单级人工聚合)
 *   loading        — 是否加载中
 *
 * 备注: processingFee 在当前后端恒为 null（WorkProcess.is_outsourced 待建），
 * 仅在非 null 时展示委外加工费行。
 */

import { computed } from 'vue';
import type { FinanceCostBreakdown, LineCostBreakdown } from '@/api/salesFinanceReview';
import type { LaborEfficiencyOrderAggregateDTO } from '@/api/laborEfficiency';

// ============================================================================
// Props
// ============================================================================

const props = defineProps<{
  /** SP3 FinanceCostBreakdown (含 SP3 扩展字段 variancePct/varianceAbsolute/alarmMessage) */
  costBreakdown?: FinanceCostBreakdown | null;
  /** SP3 M3b 订单级人工双口径聚合 */
  orderAggregate?: LaborEfficiencyOrderAggregateDTO | null;
  loading?: boolean;
}>();

// ============================================================================
// 工具函数
// ============================================================================

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function fmtPct(v: number | null | undefined, alwaysSign = true): string {
  if (v == null) return '—';
  const sign = alwaysSign && v > 0 ? '+' : '';
  return `${sign}${v.toFixed(2)}%`;
}

function fmtQty(v: number | null | undefined, unit = 'kg'): string {
  if (v == null) return '—';
  return `${v.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })} ${unit}`;
}

// ============================================================================
// 偏差状态映射
// ============================================================================

function varianceTagType(status: string | null | undefined): '' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'OK': return 'success';
    case 'WARNING': return 'warning';
    case 'CRITICAL': return 'danger';
    default: return '';
  }
}

function varianceTagLabel(status: string | null | undefined): string {
  switch (status) {
    case 'OK': return '正常';
    case 'WARNING': return '预警';
    case 'CRITICAL': return '严重超支';
    default: return status ?? '—';
  }
}

function variancePctClass(pct: number | null, belowThreshold: boolean | null): string {
  if (pct == null) return '';
  if (belowThreshold === false) return 'color-critical';
  if (pct > 0) return 'color-warning';
  return 'color-ok';
}

// ============================================================================
// 计算属性
// ============================================================================

/** 超支告警消息 (不脱敏; BOM 口径) */
const alarmMessage = computed(() => props.costBreakdown?.alarmMessage ?? null);

/** 行级成本明细 */
const lines = computed<LineCostBreakdown[]>(() => props.costBreakdown?.lines ?? []);

/** 是否有委外加工费 */
const hasProcessingFee = computed(() => props.costBreakdown?.processingFee != null);

/** 人工超支状态 (从 orderAggregate) */
const laborVarianceStatus = computed(() => props.orderAggregate?.varianceStatus ?? null);

/** 人工偏差率 (相对指标, 不脱敏) */
const laborVarianceRate = computed(() => props.orderAggregate?.varianceRate ?? null);

/** SP12: 实际成本拆分 (材料逐料 + 人工 + 制费). 来自生产真实领料/报工. */
const actualCostSplit = computed(() => props.costBreakdown?.actualCostSplit ?? null);

/** SP12: 逐料明细 (空数组兜底). */
const splitMaterials = computed(() => actualCostSplit.value?.materials ?? []);

/** SP12: 是否有可展示的拆分 (split 非 null 即展示, 内部各组分可诚实 null). */
const hasActualCostSplit = computed(() => actualCostSplit.value != null);
</script>

<template>
  <div class="three-price-breakdown" v-loading="loading">
    <!-- ======================================================
         0. 超支告警条 (SP3, 不脱敏)
         ====================================================== -->
    <el-alert
      v-if="alarmMessage"
      type="error"
      :title="alarmMessage"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <!-- ======================================================
         1. 订单级汇总: 三价口径 + 偏差
         ====================================================== -->
    <el-card shadow="never" class="summary-card">
      <template #header>
        <span class="card-title">成本汇总（三价口径）</span>
        <el-tag
          v-if="costBreakdown?.belowThreshold === false"
          type="danger"
          size="small"
          style="margin-left: 8px"
        >超支</el-tag>
        <el-tag
          v-else-if="costBreakdown?.belowThreshold === true"
          type="success"
          size="small"
          style="margin-left: 8px"
        >在控</el-tag>
      </template>

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="销售金额">
          {{ fmtMoney(costBreakdown?.totalAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="① BOM 标准成本">
          {{ fmtMoney(costBreakdown?.bomStandardCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="② 当前估算成本">
          {{ fmtMoney(costBreakdown?.currentEstimatedCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="③ 实际成本">
          {{ fmtMoney(costBreakdown?.actualCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="整单偏差率 (实际 vs 标准)">
          <span :class="variancePctClass(costBreakdown?.variancePct ?? null, costBreakdown?.belowThreshold ?? null)">
            {{ fmtPct(costBreakdown?.variancePct) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="整单偏差绝对值">
          {{ fmtMoney(costBreakdown?.varianceAbsolute) }}
        </el-descriptions-item>
        <el-descriptions-item label="估算毛利率">
          {{ fmtPct(costBreakdown?.profitMarginEstimated, false) }}
        </el-descriptions-item>
        <el-descriptions-item label="实际毛利率">
          {{ fmtPct(costBreakdown?.profitMarginActual, false) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="hasProcessingFee" label="委外加工费">
          {{ fmtMoney(costBreakdown?.processingFee) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ======================================================
         1b. SP12: 实际成本拆分 (材料逐料 + 人工 + 制费)
              来自生产真实领料 (MaterialConsumption) + 报工 (批次 laborCost/equipmentCost)
         ====================================================== -->
    <el-card
      v-if="hasActualCostSplit"
      shadow="never"
      class="actual-split-card"
      style="margin-top: 12px"
    >
      <template #header>
        <span class="card-title">实际成本拆分（材料 / 人工 / 制费）</span>
        <el-tag size="small" type="info" style="margin-left: 8px">
          关联批次 {{ actualCostSplit?.batchCount ?? 0 }} 个
        </el-tag>
      </template>

      <el-alert
        v-if="actualCostSplit?.dataSourceHint"
        type="warning"
        :title="actualCostSplit.dataSourceHint"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="材料成本">
          {{ fmtMoney(actualCostSplit?.materialCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="人工成本">
          {{ fmtMoney(actualCostSplit?.laborCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="制费 (设备+其他)">
          {{ fmtMoney(actualCostSplit?.overheadCost) }}
        </el-descriptions-item>
        <el-descriptions-item label="实际成本合计">
          <strong>{{ fmtMoney(actualCostSplit?.totalActualCost) }}</strong>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 逐原料/辅料/包材领料明细 -->
      <div v-if="splitMaterials.length > 0" style="margin-top: 12px">
        <div class="sub-title">材料逐项明细（实际领料）</div>
        <el-table :data="splitMaterials" size="small" border>
          <el-table-column prop="materialName" label="物料名称" min-width="140" />
          <el-table-column prop="category" label="分类" width="90">
            <template #default="{ row }">{{ row.category ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="实际用量" width="120" align="right">
            <template #default="{ row }">
              {{ fmtQty(row.actualQuantity, row.unit ?? '') }}
            </template>
          </el-table-column>
          <el-table-column label="移动均价" width="110" align="right">
            <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.amount) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- ======================================================
         2. SP3 M3b: 双口径人工对比 (研发预估 vs 实际)
         ====================================================== -->
    <el-card shadow="never" class="labor-card" style="margin-top: 12px">
      <template #header>
        <span class="card-title">双口径人工对比（成本组/公单级）</span>
        <el-tag
          v-if="laborVarianceStatus"
          :type="varianceTagType(laborVarianceStatus)"
          size="small"
          style="margin-left: 8px"
        >{{ varianceTagLabel(laborVarianceStatus) }}</el-tag>
      </template>

      <template v-if="orderAggregate">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="关联批次数">
            {{ orderAggregate.batchCount }}
          </el-descriptions-item>
          <el-descriptions-item label="合计好品量 (kg)">
            {{ fmtQty(orderAggregate.totalGoodQuantityKg) }}
          </el-descriptions-item>
          <el-descriptions-item label="折算产量 (盒)">
            {{ fmtQty(orderAggregate.totalGoodQuantityBoxes, '盒') }}
          </el-descriptions-item>
          <!-- 绝对值 @PriceSensitive — 无权限则后端返 null, 显示 "—" -->
          <el-descriptions-item label="① 研发预估人工成本">
            {{ fmtMoney(orderAggregate.totalQuotedLaborCost) }}
          </el-descriptions-item>
          <el-descriptions-item label="② 实际人工成本">
            {{ fmtMoney(orderAggregate.totalActualLaborCost) }}
          </el-descriptions-item>
          <el-descriptions-item label="偏差绝对值">
            {{ fmtMoney(orderAggregate.laborCostVarianceAbsolute) }}
          </el-descriptions-item>
          <!-- 偏差率不脱敏 -->
          <el-descriptions-item label="人工偏差率 %">
            <span
              :class="{
                'color-critical': (laborVarianceRate ?? 0) > 20,
                'color-warning': (laborVarianceRate ?? 0) > 10 && (laborVarianceRate ?? 0) <= 20,
                'color-ok': (laborVarianceRate ?? 0) <= 10,
              }"
            >
              {{ fmtPct(laborVarianceRate) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="偏差状态">
            <el-tag
              v-if="laborVarianceStatus"
              :type="varianceTagType(laborVarianceStatus)"
              size="small"
            >{{ varianceTagLabel(laborVarianceStatus) }}</el-tag>
            <span v-else class="text-muted">—</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 批次明细折叠 -->
        <el-collapse v-if="orderAggregate.batches?.length" style="margin-top: 12px">
          <el-collapse-item :title="`批次明细 (${orderAggregate.batches.length} 条)`" name="batches">
            <el-table
              :data="orderAggregate.batches"
              size="small"
              border
              stripe
            >
              <el-table-column prop="batchNumber" label="批次号" min-width="150" />
              <el-table-column prop="productName" label="产品" min-width="140" />
              <!-- @PriceSensitive 字段 -->
              <el-table-column label="报价人工/kg" align="right" min-width="120">
                <template #default="{ row }">{{ fmtMoney(row.quotedLaborCostPerKg) }}</template>
              </el-table-column>
              <el-table-column label="实际人工/kg" align="right" min-width="120">
                <template #default="{ row }">{{ fmtMoney(row.actualLaborCostPerKg) }}</template>
              </el-table-column>
              <!-- 偏差率不脱敏 -->
              <el-table-column label="偏差率" align="right" min-width="100">
                <template #default="{ row }">
                  <span :class="variancePctClass(row.varianceRate, row.belowThreshold ?? null)">
                    {{ fmtPct(row.varianceRate) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="状态" align="center" min-width="90">
                <template #default="{ row }">
                  <el-tag :type="varianceTagType(row.varianceStatus)" size="small">
                    {{ varianceTagLabel(row.varianceStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </template>

      <!-- 无聚合数据时的诚实提示 -->
      <el-empty
        v-else
        description="暂无人工对比数据（需完工批次）"
        :image-size="60"
        style="padding: 16px 0"
      />
    </el-card>

    <!-- ======================================================
         3. 行级成本明细：三价对比表
         ====================================================== -->
    <el-card shadow="never" class="line-card" style="margin-top: 12px">
      <template #header>
        <span class="card-title">行级三价成本明细</span>
      </template>

      <el-table
        v-if="lines.length > 0"
        :data="lines"
        border
        stripe
        size="small"
        empty-text="暂无行级成本数据"
      >
        <el-table-column prop="productName" label="产品" min-width="140" />
        <el-table-column label="数量" align="right" min-width="80">
          <template #default="{ row }">{{ row.quantity }}</template>
        </el-table-column>
        <!-- 销售价 (③) — 不脱敏 -->
        <el-table-column label="③ 销售单价" align="right" min-width="110">
          <template #default="{ row }">{{ fmtMoney(row.unitPrice) }}</template>
        </el-table-column>
        <!-- ① BOM 标准 @PriceSensitive -->
        <el-table-column label="① BOM 标准单价" align="right" min-width="130">
          <template #default="{ row }">{{ fmtMoney(row.standardCostPerUnit) }}</template>
        </el-table-column>
        <!-- ② 实际 @PriceSensitive -->
        <el-table-column label="② 实际单价" align="right" min-width="120">
          <template #default="{ row }">{{ fmtMoney(row.actualCostPerUnit) }}</template>
        </el-table-column>
        <!-- 偏差率 @PriceSensitive (per LineCostBreakdown spec) -->
        <el-table-column label="偏差率" align="right" min-width="110">
          <template #default="{ row }">
            <span :class="variancePctClass(row.variancePct, row.belowThreshold)">
              {{ fmtPct(row.variancePct) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="超支" align="center" min-width="80">
          <template #default="{ row }">
            <el-tag
              v-if="row.belowThreshold === false"
              type="danger"
              size="small"
            >超支</el-tag>
            <el-tag
              v-else-if="row.belowThreshold === true"
              type="success"
              size="small"
            >在控</el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="行销售额" align="right" min-width="120">
          <template #default="{ row }">{{ fmtMoney(row.lineAmount) }}</template>
        </el-table-column>
        <el-table-column label="行实际成本" align="right" min-width="120">
          <template #default="{ row }">{{ fmtMoney(row.actualLineCost) }}</template>
        </el-table-column>
      </el-table>

      <el-empty
        v-else
        description="暂无行级成本数据"
        :image-size="60"
        style="padding: 16px 0"
      />
    </el-card>

    <!-- 数据来源提示 -->
    <div
      v-if="costBreakdown?.dataSourceHint"
      class="data-hint"
    >
      数据来源: {{ costBreakdown.dataSourceHint }}
    </div>
  </div>
</template>

<style scoped>
.three-price-breakdown {
  /* 父组件负责 padding */
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.summary-card,
.labor-card,
.line-card {
  /* 紧凑间距 */
}

.color-critical {
  color: #f56c6c;
  font-weight: 600;
}

.color-warning {
  color: #e6a23c;
  font-weight: 600;
}

.color-ok {
  color: #67c23a;
}

.text-muted {
  color: #c0c4cc;
}

.data-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
  padding: 0 4px;
}
</style>
