<script setup lang="ts">
import { computed } from 'vue';
import type { SectionPayload } from '@/types/restaurant-chat';
import BenchmarkBarsCard from './cards/BenchmarkBarsCard.vue';
import HeatmapCard from './cards/HeatmapCard.vue';
import RfmGridCard from './cards/RfmGridCard.vue';
import RxPrescriptionCard from './cards/RxPrescriptionCard.vue';
import MenuQuadrantCard from './cards/MenuQuadrantCard.vue';
import CrossChainCard from './cards/CrossChainCard.vue';
import ForecastCard from './cards/ForecastCard.vue';
import RawJsonCard from './cards/RawJsonCard.vue';
import BomVarianceCard from './cards/BomVarianceCard.vue';
import SalesPlanCard from './cards/SalesPlanCard.vue';
import LaborProductivityCard from './cards/LaborProductivityCard.vue';
import SeatOccupancyCard from './cards/SeatOccupancyCard.vue';
import ComboSplitCard from './cards/ComboSplitCard.vue';
import ReturnAnomalyCard from './cards/ReturnAnomalyCard.vue';
import ReviewCompetitiveCard from './cards/ReviewCompetitiveCard.vue';
import SmartReorderCard from './cards/SmartReorderCard.vue';
import DailyReconciliationCard from './cards/DailyReconciliationCard.vue';
import ProcurementForecastCard from './cards/ProcurementForecastCard.vue';
import ShiftAnalysisCard from './cards/ShiftAnalysisCard.vue';
import PieceworkCalcCard from './cards/PieceworkCalcCard.vue';
import PerformanceEvalCard from './cards/PerformanceEvalCard.vue';
import StoreKpiDashboardCard from './cards/StoreKpiDashboardCard.vue';
import AdvancedTrafficPersonaCard from './cards/AdvancedTrafficPersonaCard.vue';

const props = defineProps<{
  section: SectionPayload;
}>();

// Map from Python sectionName → Vue component
// Falls back to RawJsonCard for unknown section types.
const CARD_MAP: Record<string, unknown> = {
  benchmark_alerts: BenchmarkBarsCard,
  dining_heatmap: HeatmapCard,
  member_rfm: RfmGridCard,
  diagnostics: RxPrescriptionCard,
  menu_engineering: MenuQuadrantCard,
  cross_chain_benchmark: CrossChainCard,
  restaurant_forecast: ForecastCard,
  bom_variance: BomVarianceCard,
  sales_plan_tracking: SalesPlanCard,
  labor_productivity: LaborProductivityCard,
  seat_occupancy: SeatOccupancyCard,
  combo_split: ComboSplitCard,
  return_anomaly: ReturnAnomalyCard,
  review_competitive: ReviewCompetitiveCard,
  smart_reorder: SmartReorderCard,
  daily_reconciliation: DailyReconciliationCard,
  procurement_forecast: ProcurementForecastCard,
  shift_analysis: ShiftAnalysisCard,
  piecework_calc: PieceworkCalcCard,
  performance_eval: PerformanceEvalCard,
  store_kpi_dashboard: StoreKpiDashboardCard,
  advanced_traffic_persona: AdvancedTrafficPersonaCard,
};

const resolvedCard = computed(() => {
  return CARD_MAP[props.section.sectionName] ?? RawJsonCard;
});
</script>

<template>
  <div v-if="section.status === 'ok'" class="section-card-wrapper">
    <component :is="resolvedCard" :section="section" :data="section.data" />
  </div>
  <div v-else-if="section.status === 'skipped'" class="section-skipped">
    <span class="skipped-icon">○</span>
    <span class="skipped-label">{{ section.sectionName }}:</span>
    <span class="skipped-text">
      {{ section.warnings[0] ?? '数据不足, 已跳过' }}
    </span>
  </div>
  <div v-else class="section-failed">
 <span class="failed-icon"></span>
    <span>{{ section.sectionName }} 分析失败</span>
  </div>
</template>

<style scoped>
.section-card-wrapper {
  margin-top: 12px;
}
.section-skipped {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
  padding: 8px 12px;
  background: #f2ece0;
  border: 1px dashed #a8a29e;
  border-radius: 4px;
  font-family: 'Noto Serif SC', serif;
  font-size: 11px;
  color: #a8a29e;
}
.skipped-icon {
  color: #c9a66b;
  font-size: 14px;
}
.skipped-label {
  font-family: monospace;
  color: #6b6b6b;
}
.section-failed {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
  padding: 10px 14px;
  background: #fef2f2;
  border: 1px solid #b91c1c;
  border-radius: 4px;
  color: #8b1a1a;
  font-size: 11px;
}
</style>
