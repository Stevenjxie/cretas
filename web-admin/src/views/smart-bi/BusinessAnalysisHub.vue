<script setup lang="ts">
/**
 * BusinessAnalysisHub — 经营分析 (WS4)
 *
 * 把散落的 财务看板 / 销售分析 / 趋势分析 / KPI看板 / 指标中心 合并成单一 tab 化模块。
 * 一级 el-tabs: 财务 / 销售 / 趋势 / KPI·指标 (内层再分 KPI看板 / 指标中心)。
 *
 * - ?tab= query 同步 (保书签 + 旧路径 redirect 落点, 参考 financeDashboardSection 的
 *   ?section= 同步模式)。
 * - 各子视图已在 WS1/WS4 Task 1 gold 化 + 默认全部历史 — 此 hub 仅做容器编排, 不改子视图逻辑。
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import FinancialDashboardPBI from './FinancialDashboardPBI.vue';
import SalesAnalysis from './SalesAnalysis.vue';
import TrendsView from '@/views/analytics/trends/index.vue';
import KpiView from '@/views/analytics/kpi/index.vue';
import IndicatorCenterDashboard from '@/views/indicator-center/IndicatorCenterDashboard.vue';
import { resolveBusinessAnalysisTab, type BusinessAnalysisTab } from './businessAnalysisTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<BusinessAnalysisTab>(resolveBusinessAnalysisTab(route.query));
// KPI·指标 内层子 tab (kpi 看板 / 指标中心)。?sub= 同步, 默认 kpi。
const kpiSubTab = ref<string>(route.query.sub === 'indicator' ? 'indicator' : 'kpi');

// 外部 query 变化 (e.g. 旧路径 redirect 带入 ?tab=) → 同步激活 tab。
watch(
  () => route.query.tab,
  () => { activeTab.value = resolveBusinessAnalysisTab(route.query); },
);

function syncQuery(name: string) {
  // replace 而非 push — tab 切换不污染浏览器返回栈 (与 FinancialDashboardPBI section 同模式)。
  router.replace({ query: { ...route.query, tab: name } });
}

function syncKpiSubQuery(name: string) {
  router.replace({ query: { ...route.query, tab: 'kpi', sub: name } });
}
</script>

<template>
  <div class="business-analysis-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="财务" name="finance" lazy>
        <FinancialDashboardPBI />
      </el-tab-pane>
      <el-tab-pane label="销售" name="sales" lazy>
        <SalesAnalysis />
      </el-tab-pane>
      <el-tab-pane label="趋势" name="trend" lazy>
        <TrendsView />
      </el-tab-pane>
      <el-tab-pane label="KPI·指标" name="kpi" lazy>
        <el-tabs v-model="kpiSubTab" type="card" class="kpi-sub-tabs" @tab-change="syncKpiSubQuery">
          <el-tab-pane label="KPI 看板" name="kpi" lazy>
            <KpiView />
          </el-tab-pane>
          <el-tab-pane label="指标中心" name="indicator" lazy>
            <IndicatorCenterDashboard />
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style lang="scss" scoped>
.business-analysis-hub {
  padding: 16px;

  .hub-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 8px;
    }
  }

  .kpi-sub-tabs {
    margin-top: 8px;
  }
}
</style>
