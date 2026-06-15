<template>
  <el-tabs v-model="topTab" class="ai-explore-top-tabs">
    <el-tab-pane label="传 Excel 分析" name="analysis">
  <div ref="rootRef" class="smart-bi-analysis">
    <el-card class="upload-card">
      <template #header>
        <div class="card-header">
          <span class="title"><span class="section-badge section-badge--chart" aria-hidden="true"></span> 智能数据分析</span>
          <div class="header-actions">
            <!-- Upload batch switcher — extracted to analysis/UploadSwitcher.vue (Item 1 phase 3f).
                 Phase 6 dropdown switch async race guards live in selectBatch + enrichSheet/
                 idleEnrichNext callbacks (script side, untouched). The child only forwards
                 @change → selectBatch so race-guard semantics are preserved.
                 T6 Phase-2: model-value now accepts '__FUSED__' sentinel in addition to
                 numeric batch index; alwaysShow so fusion option is visible even with 0 uploads. -->
            <UploadSwitcher
              v-if="!goldMode"
              :batches="uploadBatches"
              :model-value="selectedBatchIndex"
              :format-batch-label="formatBatchLabel"
              :is-auto-sync-batch="isAutoSyncBatch"
              :safe-batch-name="safeBatchName"
              :always-show="true"
              @update:model-value="selectedBatchIndex = $event"
              @change="selectBatch"
            />
            <el-button v-if="uploadedSheets.length > 1" @click="openCrossSheetAnalysis" type="primary" size="small">
              <el-icon><DataAnalysis /></el-icon>
              综合分析
            </el-button>
            <el-button v-if="uploadedSheets.length > 0" @click="openYoYComparison" type="success" size="small">
              <el-icon><TrendCharts /></el-icon>
              同比分析
            </el-button>
            <el-button v-if="uploadedSheets.length > 0" @click="openStatisticalAnalysis" type="info" size="small">
              <el-icon><DataAnalysis /></el-icon>
              因果分析
            </el-button>
            <el-button v-if="uploadedSheets.length > 0" @click="openShareDialog" type="default" size="small" plain>
              <el-icon><Share /></el-icon>
              分享
            </el-button>
            <el-button v-if="canUpload && uploadedSheets.length > 0" @click="resetUpload" type="warning" size="small">
              <el-icon><Upload /></el-icon>
              上传新文件
            </el-button>
            <el-button v-if="uploadedSheets.length > 0" @click="startDemoTour" circle size="small" title="功能引导">
              <el-icon><QuestionFilled /></el-icon>
            </el-button>
          </div>
        </div>
      </template>

      <!-- WS6 (#1/#3): 餐饮 (gold) 租户默认显示 Gold 全量图表, 取代自动选中上传分片导致
           的 "0 个图表" 空白。文件上传分析仍可通过下方「分析上传文件」开关进入。 -->
      <template v-if="goldMode">
        <div class="gold-default-header">
          <div class="gold-default-info">
            <el-icon class="gold-default-icon"><DataAnalysis /></el-icon>
            <div>
              <div class="gold-default-title">本厂数据来自 Gold 全量层（无需选文件）</div>
              <div class="gold-default-desc">
                您的经营数据已汇总到全量数据层, 下方直接展示门店营收排行与渠道占比。
                如需查看完整经营图表, 可前往
                <router-link class="gold-default-link" to="/smart-bi/dashboard">经营驾驶舱</router-link>。
              </div>
            </div>
          </div>
          <el-button size="small" plain @click="enterUploadAnalysis">
            <el-icon style="margin-right: 4px"><Upload /></el-icon>分析上传文件
          </el-button>
        </div>

        <!-- 复用经营驾驶舱的 Gold 营收分析组件 (门店营收排行 + 渠道占比), 全量 agg_daily,
             不依赖上传文件。dateRange 由 probeGoldRange() 探测真实区间填充。 -->
        <RestaurantGoldGrid :factory-id="factoryId || ''" :date-range="goldDateRange" />
      </template>

      <template v-else>
      <!-- 餐饮租户从上传分析视图返回 Gold 视图的入口 -->
      <div v-if="isRestaurantTenant" class="gold-back-bar">
        <el-button size="small" text type="primary" @click="backToGoldView">
          <el-icon style="margin-right: 4px"><ArrowRight /></el-icon>返回 Gold 全量分析
        </el-button>
        <span class="gold-back-hint">当前为上传文件分析视图</span>
      </div>

      <!-- Python 服务降级警告 — extracted to analysis/PythonUnavailableAlert.vue (Item 1 phase 5) -->
      <PythonUnavailableAlert :visible="pythonUnavailable" />

      <!-- T6 Phase-2: 融合时序视图 (fused mode)
           当用户选择「全部(按时间融合)」时，替代单上传分析，展示跨批次时序数据。
           Fail-open: resolver 返空时显示空状态提示而非报错。 -->
      <div v-if="isFusedMode && !uploading" class="fused-timeseries-section" style="padding: 16px 0;">
        <el-card>
          <template #header>
            <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
              <el-tag type="primary">时间融合</el-tag>
              <span>全部批次时序合并视图</span>
              <!-- Domain selector: switch between production / sales / purchase / inventory -->
              <el-select
                v-model="fusedDomain"
                size="small"
                style="width: 100px;"
                title="选择时序域"
              >
                <el-option
                  v-for="opt in FUSED_DOMAIN_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </div>
          </template>
          <div v-if="fusedLoading" v-loading="true" style="min-height: 120px;" element-loading-text="正在加载融合时序数据..." />
          <div v-else-if="fusedError" style="padding: 16px;">
            <el-alert type="warning" :title="fusedError" :closable="false" show-icon />
          </div>
          <div v-else-if="fusedRows.length === 0" style="padding: 32px; text-align: center; color: #909399;">
            <el-empty description="暂无融合时序数据">
              <template #description>
                <p>该工厂的 {{ FUSED_DOMAIN_OPTIONS.find(o => o.value === fusedDomain)?.label ?? fusedDomain }} 域尚无跨批次融合时序数据。</p>
                <p style="font-size: 12px;">请先上传对应域的数据文件，系统将自动提取时序信息。</p>
              </template>
            </el-empty>
          </div>
          <div v-else>
            <el-table :data="fusedRows" size="small" max-height="400" style="width: 100%;">
              <el-table-column prop="period" label="时间周期" width="120" />
              <el-table-column prop="canonicalField" label="指标" width="180" />
              <el-table-column prop="valueNum" label="数值" width="120">
                <template #default="{ row }">
                  {{ row.valueNum != null ? Number(row.valueNum).toLocaleString() : '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="dims" label="维度" min-width="200">
                <template #default="{ row }">
                  <span v-if="row.dims && Object.keys(row.dims).length">
                    {{ Object.entries(row.dims).map(([k, v]) => `${k}: ${v}`).join(' / ') }}
                  </span>
                  <span v-else style="color: #c0c4cc;">—</span>
                </template>
              </el-table-column>
            </el-table>
            <div style="margin-top: 8px; color: #909399; font-size: 12px;">
              共 {{ fusedRows.length }} 条时序记录（{{ FUSED_DOMAIN_OPTIONS.find(o => o.value === fusedDomain)?.label ?? fusedDomain }} 域 · 自动按时间合并所有上传批次）
            </div>
          </div>
        </el-card>
      </div>

      <!-- 上传/空数据区域 (隐藏当 fused 模式激活时) -->
      <div v-if="uploadedSheets.length === 0 && !uploading && !isFusedMode" class="upload-section">
        <!-- 上传区域 — extracted to analysis/UploadArea.vue (Item 1 phase 6) -->
        <UploadArea
          :history-loading="historyLoading"
          :history-loading-long="historyLoadingLong"
          :can-upload="canUpload"
          :file-list="fileList"
          :uploading="uploading"
          :on-file-change="handleFileChange"
          @upload="uploadFile"
        />
      </div>

      <!-- 上传进度 (SSE 流式) — extracted to analysis/UploadProgressPanel.vue (Item 1 phase 5) -->
      <UploadProgressPanel
        :uploading="uploading"
        :progress="uploadProgress"
        :status="uploadStatus"
        :progress-text="progressText"
        :sheets="sheetProgressList"
        :completed-count="completedSheetCount"
        :total-count="totalSheetCount"
        :dictionary-hits="dictionaryHits"
        :llm-analyzed-fields="llmAnalyzedFields"
      />

      <!-- Demo 缓存提示条 — extracted to analysis/DemoCacheBanner.vue (Item 1 phase 5) -->
      <DemoCacheBanner
        :visible="usingDemoCache && uploadedSheets.length > 0 && !uploading"
        :file-name="demoCacheFileName"
        @refresh="refreshFromServer"
      />

      <!-- 结果展示 -->
      <div v-if="uploadedSheets.length > 0 && !uploading" v-loading="batchSwitching" element-loading-text="正在切换数据源..." class="result-section">
        <el-alert
          :title="`成功处理 ${uploadResult.totalSheets} 个 Sheet，共 ${uploadResult.totalSavedRows} 行数据`"
          type="success"
          :closable="false"
          show-icon
        />

        <!-- P1: 食品行业分析模板 — extracted to analysis/IndustryTemplateBar.vue (Item 1 phase 3b) -->
        <IndustryTemplateBar
          :visible="!!foodIndustryDetection?.is_food_industry"
          :templates="foodTemplates"
          :active-template="activeTemplate"
          @apply="applyTemplate"
        />

        <el-tabs v-model="activeTab" class="sheet-tabs">
          <el-tab-pane
            v-for="sheet in uploadedSheets"
            :key="sheet.sheetIndex"
            :name="String(sheet.sheetIndex)"
          >
            <!-- 自定义 Tab 标签 — extracted to analysis/SheetTabLabel.vue (Item 1 phase 9) -->
            <template #label>
              <SheetTabLabel
                :is-index="isIndexSheet(sheet)"
                :success="sheet.success"
                :display-name="getSheetDisplayName(sheet)"
                :saved-rows="sheet.savedRows"
              />
            </template>

            <!-- 索引页特殊展示 — extracted to analysis/IndexPageView.vue (Item 1 phase 6) -->
            <IndexPageView
              v-if="isIndexSheet(sheet)"
              :mappings="indexMetadata?.sheetMappings || []"
              :current-sheet-index="sheet.sheetIndex"
              @navigate="navigateToSheet"
            />

            <!-- 失败的 Sheet — extracted to analysis/FailedSheetView.vue (Item 1 phase 5) -->
            <FailedSheetView
              v-else-if="!sheet.success"
              :message="sheet.message"
              :can-retry="!!sheet.uploadId"
              :retrying="!!retryingSheets[sheet.sheetIndex]"
              @retry="handleRetrySheet(sheet)"
            />

            <!-- 普通 Sheet 展示 -->
            <template v-else>
              <!-- Sheet 信息 — extracted to analysis/SheetInfoStrip.vue (Item 1 phase 5) -->
              <SheetInfoStrip
                :detected-data-type="sheet.detectedDataType"
                :recommended-chart-type="sheet.flowResult?.recommendedChartType"
                :saved-rows="sheet.savedRows"
                :description="getSheetDescription(sheet)"
              />

              <!-- KPI 统计卡片 — extracted to analysis/KPIStripPanel.vue (Item 1 phase 3c) -->
              <KPIStripPanel
                :enriching="enrichingSheets.has(sheet.sheetIndex)"
                :kpis="sheet.flowResult?.kpiSummary ? filterMoneyKPIs(getSheetKPIs(sheet)) : []"
              />

              <!-- 图表展示（多图表仪表板） -->
              <div v-if="hasChartData(sheet) || enrichingSheets.has(sheet.sheetIndex)" class="chart-section">
                <!-- Chart section header — extracted to analysis/ChartSectionHeader.vue (Item 1 phase 9) -->
                <ChartSectionHeader
                  :has-data="hasChartData(sheet)"
                  :refreshing="enrichingSheets.has(sheet.sheetIndex)"
                  v-model:layout-edit-mode="layoutEditMode"
                  :auto-refresh-interval="autoRefreshInterval"
                  @refresh="handleRefreshAnalysis(sheet)"
                  @set-auto-refresh="setAutoRefresh"
                />

                <!-- Global Filter Bar (Power BI / Tableau style) — extracted to analysis/FilterChipsBar.vue (Item 1 phase 3a) -->
                <FilterChipsBar
                  v-if="hasChartData(sheet)"
                  :dimension="globalFilterDimension"
                  :values="globalFilterValues"
                  :available-dimensions="getFilterableDimensions(sheet)"
                  :dimension-values="getDimensionValues(sheet, globalFilterDimension)"
                  :filtered-row-count="filteredRawData ? filteredRowCount : 0"
                  :total-row-count="totalRowCount"
                  @update:dimension="globalFilterDimension = $event"
                  @update:values="globalFilterValues = $event"
                  @dimension-change="handleGlobalFilterChange(sheet)"
                  @apply="handleGlobalFilterApply(sheet)"
                  @clear="clearGlobalFilter(sheet)"
                />

                <!-- Explore Panel Toggle — extracted to analysis/ExplorePanelToggle.vue (Item 1 phase 9) -->
                <ExplorePanelToggle
                  :visible="hasChartData(sheet)"
                  :expanded="explorePanelVisible"
                  :selected-count="exploreDimensions.length"
                  @toggle="explorePanelVisible = !explorePanelVisible"
                />
                <!-- Explore panel — extracted to analysis/ExplorePanel.vue (Item 1 phase 7) -->
                <ExplorePanel
                  :visible="explorePanelVisible && hasChartData(sheet)"
                  :available-dimensions="availableExploreDimensions(sheet)"
                  :selected-dimensions="exploreDimensions"
                  :filters="exploreDimensionFilters"
                  :get-dimension-values-preview="(dim) => getDimensionValues(sheet, dim).slice(0, 20)"
                  :get-dimension-value-count="(dim) => getDimensionValues(sheet, dim).length"
                  @add="addExploreDimension"
                  @remove="removeExploreDimension"
                  @move="moveExploreDimension"
                  @apply="applyExploreFilter(sheet)"
                  @clear="clearExploreFilter(sheet)"
                  @filter-change="(dim, values) => { exploreDimensionFilters[dim] = values }"
                />

                <!-- Chart skeleton wrapper — extracted to analysis/ChartSkeletonWrapper.vue -->
                <ChartSkeletonWrapper
                  :visible="enrichingSheets.has(sheet.sheetIndex) && !hasChartData(sheet)"
                  :charts="enrichPhases.get(sheet.sheetIndex)?.charts || 0"
                  :charts-total="enrichPhases.get(sheet.sheetIndex)?.chartsTotal || 0"
                />

                <!-- P6: 编排模式 — extracted to analysis/DashboardBuilderWrapper.vue (Item 1 phase 11) -->
                <DashboardBuilderWrapper
                  :visible="layoutEditMode && hasChartData(sheet)"
                  :layout="getCachedLayout(sheet)"
                  :available-charts="availableChartDefinitions"
                  @layout-change="handleLayoutChange"
                  @save="(layout) => handleLayoutSave(layout, sheet.uploadId, sheet.sheetIndex)"
                />

                <!-- 标准模式 (v-show preserves ECharts DOM) -->
                <div v-show="!layoutEditMode || !hasChartData(sheet)" class="chart-dashboard" :class="`layout-${chartLayoutMode}`">
                  <!-- Chart action bar — extracted to analysis/ChartActionBar.vue (Item 1 phase 8) -->
                  <ChartActionBar
                    :refresh-all-loading="refreshAllChartsLoading"
                    :chart-count="getSheetCharts(sheet).filter(c => !isChartDataEmpty(c.config)).length"
                    v-model:layout-mode="chartLayoutMode"
                    @refresh-all="handleRefreshAllCharts(sheet)"
                    @export-excel="handleExportExcel(sheet)"
                    @export-pdf="handleExportPDF(sheet)"
                  />

                  <!-- Cross-chart filter bar — extracted to analysis/ChartFilterBar.vue -->
                  <ChartFilterBar :filter="activeFilter" @clear="clearChartFilter" />
                  <!-- P2: Grouped charts with section headers (when enough charts to group) -->
                  <template v-if="getGroupedCharts(sheet).length > 1">
                    <template v-for="(group, gIdx) in getGroupedCharts(sheet)" :key="`group-${gIdx}`">
                      <ChartGroupHeader :icon="group.icon" :label="group.label" :count="group.charts.length" />
                      <ChartGridItem
                        v-for="{ chart, originalIndex } in group.charts"
                        :key="`chart-${sheet.sheetIndex}-${chart.title || originalIndex}`"
                        :chart="chart"
                        :container-id="`chart-${sheet.sheetIndex}-${originalIndex}`"
                        :is-empty="isChartDataEmpty(chart.config)"
                        :size-class="getChartSizeClass(chart)"
                        :title-label="cleanDisplayLabel(chart.title || '数据分析')"
                        :columns="getSheetColumns(sheet)"
                        :y-fields="extractYFieldsFromConfig(chart.config)"
                        :row-count="sheet.flowResult?.kpiSummary?.rowCount || 0"
                        :switching="switchingChart?.sheetIndex === sheet.sheetIndex && switchingChart?.chartIndex === originalIndex"
                        :mini-insight="getChartInsightText(chart)"
                        :displayed-count="getDisplayedCount(chart)"
                        @switch-type="(type) => handleSwitchChartType(sheet, originalIndex, type)"
                        @apply-config="(config) => handleApplyChartConfig(sheet, originalIndex, config)"
                        @refresh="handleRefreshChart(sheet, originalIndex)"
                        @export="(cmd) => handleChartExport(cmd, sheet.sheetIndex, originalIndex, chart.title)"
                        @view-more="handleViewMoreData(sheet, originalIndex, chart)"
                      />
                    </template>
                  </template>
                  <!-- Flat layout (few charts or no grouping match) -->
                  <template v-else>
                  <ChartGridItem
                    v-for="(chart, idx) in getSheetCharts(sheet)"
                    :key="`chart-${sheet.sheetIndex}-${chart.title || idx}`"
                    :chart="chart"
                    :container-id="`chart-${sheet.sheetIndex}-${idx}`"
                    :is-empty="isChartDataEmpty(chart.config)"
                    :size-class="getChartSizeClass(chart)"
                    :title-label="cleanDisplayLabel(chart.title || '数据分析')"
                    :columns="getSheetColumns(sheet)"
                    :y-fields="extractYFieldsFromConfig(chart.config)"
                    :row-count="sheet.flowResult?.kpiSummary?.rowCount || 0"
                    :switching="switchingChart?.sheetIndex === sheet.sheetIndex && switchingChart?.chartIndex === idx"
                    :mini-insight="getChartInsightText(chart)"
                    :displayed-count="getDisplayedCount(chart)"
                    @switch-type="(type) => handleSwitchChartType(sheet, idx, type)"
                    @apply-config="(config) => handleApplyChartConfig(sheet, idx, config)"
                    @refresh="handleRefreshChart(sheet, idx)"
                    @export="(cmd) => handleChartExport(cmd, sheet.sheetIndex, idx, chart.title)"
                    @view-more="handleViewMoreData(sheet, idx, chart)"
                  />
                  </template>
                </div>
              </div>

              <!-- 高管摘要横幅 — extracted to analysis/ExecutiveSummaryBanner.vue (Item 1 phase 3e) -->
              <ExecutiveSummaryBanner
                :summary="getExecutiveSummary(sheet)"
                :kpis="filterMoneyKPIs(getSheetKPIs(sheet))"
                :structured-insight="getStructuredInsight(sheet)"
                :sensitivity-count="getSensitivityAnalysis(sheet)?.length || 0"
              />

              <!-- A6: 食品行业标准参考面板 — extracted to analysis/FoodIndustryPanel.vue (Item 1 phase 4a) -->
              <FoodIndustryPanel
                v-if="foodIndustryDetection"
                :visible="!!foodIndustryDetection.is_food_industry && enrichedSheets.has(sheet.sheetIndex)"
                :detection="foodIndustryDetection"
              />

              <!-- AI 分析 — extracted to analysis/AIInsightsStream.vue (Item 1 phase 3d) -->
              <AIInsightsStream
                :visible="!!(sheet.flowResult?.aiAnalysis || sheet.flowResult?.chartConfig?.aiAnalysis || enrichingSheets.has(sheet.sheetIndex))"
                :enriching="enrichingSheets.has(sheet.sheetIndex)"
                :structured-insight="getStructuredInsight(sheet)"
                :raw-analysis="getAIAnalysis(sheet)"
                :cache-hint="getCacheHint(sheet)"
                :format-analysis="formatAnalysis"
              />

              <!-- 敏感性分析 — extracted to analysis/SensitivityAnalysisTable.vue (Item 1 phase 4b) -->
              <SensitivityAnalysisTable :rows="getSensitivityAnalysis(sheet) || []" />

              <!-- 无数据 / 数据预览 — extracted to analysis/EmptySheetPlaceholder.vue (Item 1 phase 4c) -->
              <EmptySheetPlaceholder
                :empty="!hasChartData(sheet) && !sheet.flowResult?.aiAnalysis && !enrichingSheets.has(sheet.sheetIndex)"
                @view-data="loadSheetData(sheet)"
              />
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>
      </template>
    </el-card>
  </div>
    </el-tab-pane>
    <el-tab-pane label="问数据 (AI)" name="query">
      <AIQuery />
    </el-tab-pane>
  </el-tabs>

  <!-- 下钻分析抽屉 -->
  <!-- 下钻分析抽屉 — extracted to analysis/DrillDownDrawer.vue (Item 1 phase 2). The
       composable's callback writes ECharts into id 'drill-down-chart' which the child
       renders identically — chart wiring preserved. -->
  <DrillDownDrawer
    v-model:visible="drillDownVisible"
    :loading="drillDownLoading"
    :result="drillDownResult"
    :context="drillDownContext"
    :stack="drillStack"
    :format-analysis="formatAnalysis"
    :get-column-label="getColumnLabel"
    @drill-by-dimension="drillByDimension"
    @back-to-root="drillBackToRoot"
    @back-to="drillBackTo"
    @close="drillStack.splice(0)"
  />

  <!-- 分享链接对话框 — extracted to analysis/ShareDialog.vue (Item 1 phase 2) -->
  <ShareDialog ref="shareDialogRef" :factory-id="factoryId" :active-tab="activeTab" />

  <!-- 综合分析对话框 — extracted to analysis/CrossSheetDialog.vue (Item 1 phase 2). The
       composable's renderCrossSheetCharts callback writes ECharts into elements with ids
       'cross-chart-${idx}' which the child renders identically — chart wiring preserved. -->
  <CrossSheetDialog
    v-model:visible="crossSheetVisible"
    :loading="crossSheetLoading"
    :result="crossSheetResult"
    :kpi-keys="crossSheetKpiKeys"
    :format-analysis="formatAnalysis"
    :get-column-label="getColumnLabel"
    :clean-display-label="cleanDisplayLabel"
  />

  <!-- 同比分析对话框 — extracted to analysis/YoYDialog.vue (Item 1 phase 2) -->
  <YoYDialog ref="yoyDialogRef" :available-sheets="dataSheets" :get-sheet-display-name="getSheetDisplayName" />

  <!-- P5: 因果分析对话框 -->
  <!-- 因果分析对话框 — extracted to analysis/StatisticalDialog.vue (Item 1 phase 2). The
       composable's correlation chart mounts into id 'stat-heatmap-chart' which the child
       renders identically — chart + dispose-on-close path preserved. -->
  <StatisticalDialog
    v-model:visible="statisticalVisible"
    :loading="statisticalLoading"
    :result="statisticalResult"
    :distribution-table-data="distributionTableData"
    :distribution-type-label="distributionTypeLabel"
    :available-sheets="dataSheets"
    :get-sheet-display-name="getSheetDisplayName"
    @run-for-sheet="runStatisticalAnalysis"
    @reset="statisticalResult = null"
    @close="disposeStatHeatmap"
  />

  <!-- 数据预览 Dialog — extracted to analysis/DataPreviewDialog.vue (Item 1 phase 2) -->
  <DataPreviewDialog ref="dataPreviewRef" :get-column-label="getColumnLabel" />

  <!-- Demo 演示引导 -->
  <DemoTour
    ref="demoTourRef"
    :data-ready="tourDataReady"
  />

  <!-- Q5: Keyboard shortcuts help overlay -->
  <ShortcutsHelpOverlay :visible="showShortcutsHelp" :shortcuts="shortcutsList" @close="showShortcutsHelp = false" />
</template>

<script setup lang="ts">
defineOptions({ name: 'SmartBIAnalysis' });
import { ref, reactive, computed, onMounted, onBeforeUnmount, onDeactivated, onActivated, nextTick, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { useAppStore } from '@/store/modules/app';
import { usePermissionStore } from '@/store/modules/permission';
import { post } from '@/api/request';
import { getErrorMessage } from '@/utils/errorToast';
import { getUploadTableData, getUploadHistory, deduplicateUploads, enrichSheetAnalysis, getSmartKPIs, chartDrillDown, crossSheetAnalysis, yoyComparison, renameMeaninglessColumns, statisticalAnalysis, invalidateAnalysisCache, retrySheetUpload, smartRecommendChart, buildChart, checkPythonHealth, humanizeColumnName, FOOD_TEMPLATES, mapColumnsToTemplate, detectFoodIndustryLocal } from '@/api/smartbi';
import type { FoodTemplate } from '@/api/smartbi';
import type { UploadHistoryItem, EnrichResult, EnrichProgress, ColumnSummary, StructuredAIData, SmartKPI, DrillDownResult as DrillDownResultType, CrossSheetResult as CrossSheetResultType, FinancialMetrics, YoYResult, YoYComparisonItem, StatisticalResult, PythonHealthStatus } from '@/api/smartbi';
import { ElMessage } from 'element-plus';
import { UploadFilled, Upload, Refresh, CircleCheckFilled, CircleCloseFilled, Loading, List, Document, Tickets, InfoFilled, ArrowRight, Pointer, DataAnalysis, TrendCharts, Download, Filter, Warning, WarningFilled, QuestionFilled, Share, CopyDocument, Link, Timer, Operation, Plus, Rank, Top, Bottom, Close } from '@element-plus/icons-vue';
import type { UploadFile, UploadUserFile } from 'element-plus';
import echarts from '@/utils/echarts';
import type { SmartBIChartOption, SmartBIChartItem } from '@/types/echarts';
import DOMPurify from 'dompurify';
import { defineAsyncComponent } from 'vue';
import KPICard from '@/components/smartbi/KPICard.vue';
import AIQuery from './AIQuery.vue';
import { resolveTopTab } from './smartBIAnalysisTab';
import ShareDialog from './analysis/ShareDialog.vue';
import DataPreviewDialog from './analysis/DataPreviewDialog.vue';
import YoYDialog from './analysis/YoYDialog.vue';
import CrossSheetDialog from './analysis/CrossSheetDialog.vue';
import DrillDownDrawer from './analysis/DrillDownDrawer.vue';
import StatisticalDialog from './analysis/StatisticalDialog.vue';
import FilterChipsBar from './analysis/FilterChipsBar.vue';
import IndustryTemplateBar from './analysis/IndustryTemplateBar.vue';
import KPIStripPanel from './analysis/KPIStripPanel.vue';
import AIInsightsStream from './analysis/AIInsightsStream.vue';
import ExecutiveSummaryBanner from './analysis/ExecutiveSummaryBanner.vue';
import UploadSwitcher from './analysis/UploadSwitcher.vue';
import FoodIndustryPanel from './analysis/FoodIndustryPanel.vue';
import SensitivityAnalysisTable from './analysis/SensitivityAnalysisTable.vue';
import EmptySheetPlaceholder from './analysis/EmptySheetPlaceholder.vue';
import PythonUnavailableAlert from './analysis/PythonUnavailableAlert.vue';
import UploadProgressPanel from './analysis/UploadProgressPanel.vue';
import DemoCacheBanner from './analysis/DemoCacheBanner.vue';
import FailedSheetView from './analysis/FailedSheetView.vue';
import SheetInfoStrip from './analysis/SheetInfoStrip.vue';
import IndexPageView from './analysis/IndexPageView.vue';
import UploadArea from './analysis/UploadArea.vue';
import ChartSkeletonWrapper from './analysis/ChartSkeletonWrapper.vue';
import ExplorePanel from './analysis/ExplorePanel.vue';
import ChartActionBar from './analysis/ChartActionBar.vue';
import ChartFilterBar from './analysis/ChartFilterBar.vue';
import SheetTabLabel from './analysis/SheetTabLabel.vue';
import ChartSectionHeader from './analysis/ChartSectionHeader.vue';
import ExplorePanelToggle from './analysis/ExplorePanelToggle.vue';
import ChartGridItem from './analysis/ChartGridItem.vue';
import ChartGroupHeader from './analysis/ChartGroupHeader.vue';
import type { ChartMeta } from './components/chartInsight';
import DashboardBuilderWrapper from './analysis/DashboardBuilderWrapper.vue';
import AIInsightPanel from '@/components/smartbi/AIInsightPanel.vue';
import ChartSkeleton from '@/components/smartbi/ChartSkeleton.vue';
// T3.1: Lazy-load rarely-used components — only loaded when user triggers them
const YoYMoMComparisonChart = defineAsyncComponent(() => import('@/components/smartbi/YoYMoMComparisonChart.vue'));
// ChartTypeSelector + ChartConfigPanel moved into analysis/ChartGridItem.vue (Item 1 phase 10)
// DashboardBuilder moved into analysis/DashboardBuilderWrapper.vue (Item 1 phase 11)
const DemoTour = defineAsyncComponent(() => import('@/components/smartbi/DemoTour.vue'));
// SmartBIEmptyState moved to analysis/UploadArea.vue (Item 1 phase 6)
const ShortcutsHelpOverlay = defineAsyncComponent(() => import('@/components/smartbi/ShortcutsHelpOverlay.vue'));
// DashboardLayout/DashboardCard/ChartDefinition types moved into analysis/DashboardBuilderWrapper.vue
import type { ComparisonData } from '@/components/smartbi/YoYMoMComparisonChart.vue';
import type { AIInsight } from '@/components/smartbi/AIInsightPanel.vue';
import { saveDemoCache, loadDemoCache } from '@/utils/demo-cache';
import type { DemoCacheData } from '@/utils/demo-cache';
import { useSmartBIShortcuts } from '@/composables/useSmartBIShortcuts';
import { compactAxisFormatter, compactTooltipFormatter, compactLabelFormatter } from '@/composables/useChartEnhancer';
import { useSmartBIDrillDown, type SheetRef } from './composables/useSmartBIDrillDown';
import { useSmartBIStatistical } from './composables/useSmartBIStatistical';
import { useSmartBICrossSheet } from './composables/useSmartBICrossSheet';
import { useSmartBIDashboardLayout } from './composables/useSmartBIDashboardLayout';
import RestaurantGoldGrid from './components/RestaurantGoldGrid.vue';
import { getGoldDataRange } from '@/api/smartbi/dataRange';
import { getFactoryProductionFused } from '@/api/smartbi/factoryGold';
import type { FusedTimeseriesRow, FusedDomain } from '@/api/smartbi/factoryGold';
import { resolveAllHistoryRange } from './analysisDefaults';
import { shouldDefaultToGold, pickDefaultBatchIndex } from './analysisGoldMode';
// U2 — Chart Auto-Insight Tier 1 (replaces getChartMiniInsight)
import { buildChartInsight } from './components/chartInsight';
import type { ChartWithMeta, UserPermissions } from './components/chartInsight';
import ChartInsight from './components/ChartInsight.vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const appStore = useAppStore();

// WS6 (#1/#3): 餐饮 (gold) 租户的「传 Excel 分析」tab 默认显示 Gold 全量图表,
// 不再自动选中某个上传分片 (qhj 的 *_part4.csv 这类只是 1/4 分片 → 0 图表空白)。
// 真正全量数据在 Gold 层 (经营驾驶舱 / 经营分析 已渲染同一组件)。文件上传分析仍
// 保留在「分析上传文件」开关后, 供用户真正上传新文件时使用。制造业租户不受影响。
const isRestaurantTenant = computed(() => shouldDefaultToGold(authStore.factoryType));
// 餐饮租户默认 gold 视图; 用户点「分析上传文件」可切到上传分析视图。
const showUploadAnalysis = ref(false);
// Gold 视图是否生效 = 餐饮租户 且 未切到上传分析。
const goldMode = computed(() => isRestaurantTenant.value && !showUploadAnalysis.value);
// RestaurantGoldGrid 需要非 null 的 dateRange 才会加载 (其 load() 在 dateRange 为 null
// 时早返回)。探测 Gold 真实 [min,max] 区间; 失败回落到足够宽的窗口 (绝不回到近30天)。
const goldDateRange = ref<[string, string] | null>(null);
const goldRangeProbed = ref(false);
async function probeGoldRange() {
  if (goldRangeProbed.value || !factoryId.value) return;  // factoryId 未就绪 → 不置 probed, 等 watch 重试
  goldRangeProbed.value = true;
  try {
    const dr = await getGoldDataRange(factoryId.value);
    goldDateRange.value = resolveAllHistoryRange(dr);
  } catch {
    // 探测失败 → 用宽回退窗 (RestaurantGoldGrid 自身再处理空数据/报错, 不伪造)。
    goldDateRange.value = resolveAllHistoryRange(null);
  }
}
// 餐饮租户从 Gold 视图切到「分析上传文件」视图。首次切换时确保历史已加载, 以便用户
// 浏览/选择已上传文件 (gold 模式下我们跳过了自动 selectBatch)。
const enterUploadAnalysis = () => {
  showUploadAnalysis.value = true;
  if (uploadBatches.value.length > 0 && uploadedSheets.value.length === 0) {
    selectBatch(pickDefaultBatchIndex(uploadBatches.value.map(b => b.fileName)));
  }
};
const backToGoldView = () => {
  showUploadAnalysis.value = false;
};
// 若 factoryId 在 mount 后才异步就绪 (auth 水合延迟), 重试探测 Gold 区间。
watch(factoryId, (id) => {
  if (id && isRestaurantTenant.value && !goldRangeProbed.value) {
    probeGoldRange().catch(() => {});
  }
});

// P3: AI 探索顶层 tab (传 Excel 分析 / 问数据 AI)。与 sheet 级 activeTab 互不相干。
const route = useRoute();
const topTab = ref(resolveTopTab(route.query));
watch(() => route.query.tab, () => { topTab.value = resolveTopTab(route.query); });
const echartsThemeName = computed(() => appStore.theme === 'dark' ? 'cretas-dark' : 'cretas');
const permissionStore = usePermissionStore();
const canUpload = computed(() => permissionStore.canWrite('analytics'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// U2 — Tier 1 insight permissions (gates absolute ¥ in chart insights per §2.6)
// finance:read_write ↔ canWrite('finance') in permissionStore
const userPermissions = computed((): UserPermissions => ({
  canViewFinance: permissionStore.canWrite('finance'),
}));

/**
 * Tier 1 insight text for ChartGridItem's mini-insight prop.
 * Replaces the old getChartMiniInsight() — uses buildChartInsight() rules-first engine.
 * Returns '' when data insufficient (honest-null → ChartGridItem hides the insight bar).
 */
const getChartInsightText = (chart: ChartWithMeta): string => {
  const result = buildChartInsight(chart, userPermissions.value);
  return result?.finding ?? '';
};

// Defense-in-depth: drop money-shape KPIs from list when role lacks canViewPrice.
// Backend @PriceSensitive already strips values, but ghost cards (empty value
// with "万元" unit) look broken. Detection mirrors TemplateCard.vue CURRENCY_KEY_RE
// + checks unit text for currency markers.
const MONEY_TITLE_RE = /(金额|单价|价格|总价|销售额|营业额|营收|收入|成本|消费|储值|实收|应收|应付|毛利|净利|利润|payable|receivable|revenue|amount|price|cost|profit|deposit)/i;
const MONEY_UNIT_RE = /(元|¥|￥|万元|亿元|RMB|CNY)/;
function filterMoneyKPIs(list: SmartKPI[]): SmartKPI[] {
  if (canViewPrice.value) return list;
  return list.filter(k => !MONEY_TITLE_RE.test(k.title) && !MONEY_UNIT_RE.test(k.unit || ''));
}

// 历史批次
interface UploadBatch {
  fileName: string;
  uploadTime: string;
  sheetCount: number;
  totalRows: number;
  uploads: UploadHistoryItem[];
  uploadId?: number;
  id?: number;
}
const rootRef = ref<HTMLDivElement>();
let resizeObserver: ResizeObserver | null = null; // container resize for sidebar toggle etc.
const uploadBatches = ref<UploadBatch[]>([]);
/** T6 Phase-2: '__FUSED__' sentinel or numeric batch index */
const selectedBatchIndex = ref<number | '__FUSED__'>('__FUSED__');
const historyLoading = ref(false);
const batchSwitching = ref(false);  // U6: loading feedback when switching data source

// ============================================================
// T6 Phase-2: 融合时序 (fused timeseries) state
// ============================================================

/** True when '__FUSED__' is selected in UploadSwitcher */
const isFusedMode = computed(() => selectedBatchIndex.value === '__FUSED__');

/** Rows returned by timeseries resolver for fused view */
const fusedRows = ref<FusedTimeseriesRow[]>([]);
const fusedLoading = ref(false);
const fusedError = ref<string | null>(null);

/** Currently selected domain for the fused timeseries view. Defaults to production. */
const fusedDomain = ref<FusedDomain>('production');

/** Domain options shown in the fused view domain selector. */
const FUSED_DOMAIN_OPTIONS: { label: string; value: FusedDomain }[] = [
  { label: '生产', value: 'production' },
  { label: '销售', value: 'sales' },
  { label: '采购', value: 'purchase' },
  { label: '库存', value: 'inventory' },
];

/**
 * Load fused timeseries rows for the selected domain via resolver.
 * Uses getFactoryProductionFused → GET /api/smartbi/factory/production-fused?domain=...
 * Fail-open: sets fusedRows=[] + fusedError message on any error.
 */
const loadFusedTimeseries = async () => {
  const fid = factoryId.value;
  if (!fid) return;
  fusedLoading.value = true;
  fusedError.value = null;
  try {
    const end = new Date();
    const start = new Date();
    start.setFullYear(start.getFullYear() - 1);
    const fmt = (d: Date) => d.toISOString().slice(0, 10);
    const res = await getFactoryProductionFused({
      factoryId: fid,
      start: fmt(start),
      end: fmt(end),
      domain: fusedDomain.value,
    });
    if (res.success === false) throw new Error(res.message ?? '后端返回失败');
    fusedRows.value = Array.isArray(res.data) ? res.data : [];
  } catch (err: unknown) {
    fusedRows.value = [];
    const msg = err instanceof Error ? err.message : String(err);
    fusedError.value = `融合时序加载失败: ${msg}`;
    console.warn('[T6-fused] loadFusedTimeseries error:', err);
  } finally {
    fusedLoading.value = false;
  }
};

// T6: auto-load fused data whenever fused mode becomes active (e.g., user switches dropdown)
watch(isFusedMode, (active) => {
  if (active && factoryId.value) loadFusedTimeseries();
});

// Reload when domain selection changes while fused mode is active
watch(fusedDomain, () => {
  if (isFusedMode.value && factoryId.value) loadFusedTimeseries();
});

// Python 服务健康状态
const pythonHealthStatus = ref<PythonHealthStatus | null>(null);
const pythonUnavailable = computed(() => {
  if (!pythonHealthStatus.value) return false;
  return !pythonHealthStatus.value.available;
});

/** Check Python health with exponential backoff retry (1s, 2s, 4s) */
async function checkHealthWithRetry(maxRetries = 3): Promise<boolean> {
  for (let i = 0; i < maxRetries; i++) {
    try {
      const res = await checkPythonHealth();
      if (res?.data) {
        pythonHealthStatus.value = res.data;
        if (res.data.available) return true;
      }
    } catch { /* ignore */ }
    if (i < maxRetries - 1) await new Promise(r => setTimeout(r, 1000 * Math.pow(2, i)));
  }
  pythonHealthStatus.value = { enabled: false, available: false, llmConfigured: false, consecutiveFailures: maxRetries, lastCheckMs: Date.now(), url: '' };
  return false;
}

/** Null-safe batch file name — fallback chain handles null/undefined from API or cache */
const safeBatchName = (batch: UploadBatch): string => {
  const candidates = [
    batch.fileName,
    (batch as unknown as Record<string, unknown>).batchName as string | undefined,
    (batch as unknown as Record<string, unknown>).originalFileName as string | undefined,
  ];
  for (const name of candidates) {
    if (name && name !== 'null' && name !== 'undefined' && name.trim() !== '') return name;
  }
  // Last resort: generate from upload time or batch id
  if (batch.uploadTime) return `Excel_${batch.uploadTime.replace(/[- :]/g, '')}`;
  const batchId = (batch as unknown as Record<string, unknown>).uploadId ?? (batch as unknown as Record<string, unknown>).id;
  return batchId ? `Upload #${batchId}` : 'Excel数据';
};
/** 判断批次是否来自自动同步 (detectedTableType === 'AUTO_PRODUCTION') */
const isAutoSyncBatch = (batch: UploadBatch): boolean => {
  return batch.uploads.some(u => u.tableType === 'AUTO_PRODUCTION');
};

/** Formatted label for dropdown: "[自动同步] 文件名 (N 表)" or "文件名 (N 表)" */
const formatBatchLabel = (batch: UploadBatch): string => {
  const prefix = isAutoSyncBatch(batch) ? '[自动同步] ' : '';
  return `${prefix}${safeBatchName(batch)} (${batch.sheetCount} 表)`;
};

// 上传相关 (uploadRef moved into analysis/UploadArea.vue — Item 1 phase 6)
const fileList = ref<UploadUserFile[]>([]);
const uploading = ref(false);
const uploadProgress = ref(0);
const uploadStatus = ref<'success' | 'exception' | 'warning' | undefined>();
const progressText = ref('');

// Sheet 数据
interface SheetResult {
  sheetIndex: number;
  sheetName: string;
  success: boolean;
  message: string;
  detectedDataType?: string;
  savedRows?: number;
  uploadId?: number;
  tableType?: 'index' | 'data' | 'summary' | 'metadata' | 'unknown';
  flowResult?: {
    recommendedChartType?: string;
    chartConfig?: Record<string, unknown>;
    aiAnalysis?: string;
    recommendedTemplates?: Record<string, unknown>[];
    charts?: Array<{ chartType: string; title: string; config: Record<string, unknown>; xField?: string; totalItems?: number; meta?: ChartMeta | null }>;
    kpiSummary?: { rowCount: number; columnCount: number; columns: ColumnSummary[] };
    structuredAI?: StructuredAIData;
    displayNameMap?: Record<string, string>;
    financialMetrics?: FinancialMetrics;
    /** Internal streaming buffer used during SSE AI text accumulation. */
    _streamingAIText?: string;
  };
}

// 索引页映射
interface IndexSheetMapping {
  index: number;
  reportName: string;
  sheetName: string;
  description?: string;
}

// 索引元数据
interface IndexMetadata {
  hasIndex: boolean;
  indexSheetIndex?: number;
  sheetMappings: IndexSheetMapping[];
}

interface BatchUploadResult {
  totalSheets: number;
  successCount: number;
  failedCount: number;
  requiresConfirmationCount: number;
  totalSavedRows: number;
  message: string;
  results: SheetResult[];
  indexMetadata?: IndexMetadata;
}

const uploadedSheets = ref<SheetResult[]>([]);
const uploadResult = ref<BatchUploadResult | null>(null);
const activeTab = ref('');
const indexMetadata = ref<IndexMetadata | null>(null);

// 数据预览 — Item 1 phase 2: moved to analysis/DataPreviewDialog.vue
const dataPreviewRef = ref<InstanceType<typeof DataPreviewDialog> | null>(null);

// Sheet retry 状态
const retryingSheets = reactive<Record<number, boolean>>({});

// Enrichment 状态 (前端驱动的图表/AI补充)
const enrichingSheets = ref<Set<number>>(new Set());
const enrichedSheets = ref<Set<number>>(new Set());
// P0: Progressive rendering phase tracking
interface EnrichPhaseState {
  kpi: boolean;
  charts: number;       // count of charts loaded so far
  chartsTotal: number;  // expected total charts
  ai: boolean;
}
const enrichPhases = ref<Map<number, EnrichPhaseState>>(new Map());
// R-21: 缓存 enrichment 获取的原始数据，避免 drill-down 重复请求
const sheetRawDataCache = new Map<number, Record<string, unknown>[]>();
// 缓存时间戳：uploadId → cachedAt ISO string
const cachedAtMap = ref<Map<number, string>>(new Map());

// A6: 食品行业检测结果
const foodIndustryDetection = ref<{
  is_food_industry: boolean;
  confidence: number;
  detected_categories: string[];
  matched_keywords: string[];
  suggested_benchmarks: string[];
  suggested_standards: string[];
} | null>(null);

// P1: 食品行业模板
const foodTemplates = FOOD_TEMPLATES;
const activeTemplate = ref<string>('');

// 下钻分析 (composable — lazy deps resolved at call time)
const {
  drillDownVisible, drillDownLoading, drillDownResult, drillDownContext,
  drillStack, currentDrillSheet,
  handleChartDrillDown, drillByDimension, drillBackToRoot, drillBackTo, inferMeasures,
} = useSmartBIDrillDown({
  sheetRawDataCache,
  processEChartsOptions: (opts: Record<string, unknown>) => processEChartsOptions(opts),
  waitForElement: (id: string) => waitForElement(id),
  getSheetCharts: (s: unknown) => getSheetCharts(s as SheetResult),
});

// Global filter state
const globalFilterDimension = ref('');
const globalFilterValues = ref<string[]>([]);

// Q1: Data filtering state
const filteredRawData = ref<Record<string, any>[] | null>(null);
const totalRowCount = ref(0);
const filteredRowCount = ref(0);

// Explore Panel state (Superset-style multi-dimension)
const explorePanelVisible = ref(false);
const exploreDimensions = ref<string[]>([]);
const exploreDimensionFilters = reactive<Record<string, string[]>>({});

// Q2: Auto-refresh state
const autoRefreshInterval = ref<number>(0);
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null;

// 综合分析状态
// ========== Share Dialog ==========
// Item 1 phase 2: dialog state + methods extracted to analysis/ShareDialog.vue
const shareDialogRef = ref<InstanceType<typeof ShareDialog> | null>(null);

const openShareDialog = () => {
  // T6: guard against '__FUSED__' sentinel (no single batch in fused mode)
  const idx = selectedBatchIndex.value;
  const batch = idx !== '__FUSED__' ? uploadBatches.value[idx] : undefined;
  shareDialogRef.value?.open(batch);
};

// 综合分析 (composable)
const {
  crossSheetVisible, crossSheetLoading, crossSheetResult, crossSheetKpiKeys,
  openCrossSheetAnalysis: _openCrossSheet, renderCrossSheetCharts,
} = useSmartBICrossSheet({
  processEChartsOptions: (opts: Record<string, unknown>) => processEChartsOptions(opts),
  resolveEChartsOptions: (config: Record<string, unknown>) => resolveEChartsOptions(config),
  enhanceChartOption: (opts: Record<string, unknown>) => enhanceChartOption(opts),
  waitForElement: (id: string) => waitForElement(id),
  isIndexSheet: (s: unknown) => isIndexSheet(s as SheetResult),
  getSheetDisplayName: (s: unknown) => getSheetDisplayName(s as SheetResult),
});
const openCrossSheetAnalysis = () => _openCrossSheet(uploadedSheets.value);

// 同比分析状态
// Item 1 phase 2: yoy state moved to analysis/YoYDialog.vue
const yoyDialogRef = ref<InstanceType<typeof YoYDialog> | null>(null);
const dataSheets = computed(() => uploadedSheets.value.filter(s => !isIndexSheet(s) && s.uploadId && s.success));

// 因果分析 (composable)
const {
  statisticalVisible, statisticalLoading, statisticalResult,
  distributionTableData, distributionTypeLabel,
  openStatisticalAnalysis, runStatisticalAnalysis, disposeStatHeatmap,
} = useSmartBIStatistical({ sheetRawDataCache });

// 仪表板布局 (composable)
const {
  layoutEditMode, dashboardLayouts, availableChartDefinitions,
  chartsToLayout, handleLayoutChange, handleLayoutSave,
  saveLayout, loadSavedLayout,
  getCachedLayout: _getCachedLayout,
} = useSmartBIDashboardLayout();
const getCachedLayout = (sheet: SheetResult) => _getCachedLayout(sheet, getSheetCharts);

// ========== Demo 缓存 & Tour 引导 ==========
const usingDemoCache = ref(false);
const demoCacheFileName = ref('');
const demoTourRef = ref<InstanceType<typeof DemoTour> | null>(null);
const tourDataReady = ref(false);

// (DemoTour 通过 CSS 选择器自动定位目标元素，无需手动传 ref)

/** 构建 DemoCacheData 用于保存 */
const buildDemoCacheData = (): DemoCacheData | null => {
  if (uploadedSheets.value.length === 0 || !uploadResult.value) return null;
  // T6: guard against '__FUSED__' sentinel
  const idx = selectedBatchIndex.value;
  const batch = idx !== '__FUSED__' ? uploadBatches.value[idx] : undefined;
  return {
    uploadBatch: {
      fileName: batch ? safeBatchName(batch) : 'unknown',
      uploadTime: batch?.uploadTime || new Date().toISOString(),
      sheetCount: uploadedSheets.value.length,
      totalRows: uploadResult.value.totalSavedRows,
    },
    sheets: uploadedSheets.value.map(s => ({
      sheetIndex: s.sheetIndex,
      sheetName: s.sheetName,
      success: s.success,
      message: s.message,
      detectedDataType: s.detectedDataType,
      savedRows: s.savedRows,
      uploadId: s.uploadId,
      tableType: s.tableType,
      flowResult: s.flowResult ? {
        recommendedChartType: s.flowResult.recommendedChartType,
        chartConfig: s.flowResult.chartConfig,
        aiAnalysis: s.flowResult.aiAnalysis,
        charts: s.flowResult.charts,
        kpiSummary: s.flowResult.kpiSummary,
        structuredAI: s.flowResult.structuredAI,
        financialMetrics: s.flowResult.financialMetrics,
      } : undefined,
    })) as DemoCacheData['sheets'],
    uploadResult: {
      totalSheets: uploadResult.value.totalSheets,
      successCount: uploadResult.value.successCount,
      failedCount: uploadResult.value.failedCount,
      requiresConfirmationCount: uploadResult.value.requiresConfirmationCount,
      totalSavedRows: uploadResult.value.totalSavedRows,
      message: uploadResult.value.message,
    },
    indexMetadata: indexMetadata.value || undefined,
  };
};

/** 检查当前数据是否 "enrichment 完成" (至少一半 sheet 有图表) 并自动缓存 */
const tryAutoSaveDemoCache = () => {
  const dataSheetList = uploadedSheets.value.filter(s => !isIndexSheet(s) && s.success);
  if (dataSheetList.length === 0) return;
  const enrichedCount = dataSheetList.filter(s => hasChartData(s) && s.flowResult?.aiAnalysis).length;
  // 至少一半的 sheet 完成了 enrichment 才缓存
  if (enrichedCount < Math.ceil(dataSheetList.length / 2)) return;

  const firstUploadId = uploadedSheets.value.find(s => s.uploadId)?.uploadId;
  if (!firstUploadId) return;

  const cacheData = buildDemoCacheData();
  if (cacheData) {
    saveDemoCache(firstUploadId, cacheData);
  }
};

/** 从缓存恢复数据 */
const restoreFromDemoCache = (): boolean => {
  const cached = loadDemoCache();
  if (!cached) return false;

  // 恢复 sheets
  uploadedSheets.value = cached.sheets.map(s => ({
    ...s,
    tableType: s.tableType as SheetResult['tableType'],
  }));

  // 恢复 uploadResult
  uploadResult.value = {
    ...cached.uploadResult,
    results: uploadedSheets.value,
  };

  // 恢复 indexMetadata
  if (cached.indexMetadata) {
    indexMetadata.value = cached.indexMetadata;
  }

  // 恢复批次信息
  uploadBatches.value = [{
    fileName: cached.uploadBatch.fileName,
    uploadTime: cached.uploadBatch.uploadTime,
    sheetCount: cached.uploadBatch.sheetCount,
    totalRows: cached.uploadBatch.totalRows,
    uploadId: cached.uploadId,
    id: cached.uploadId,
    uploads: [] as UploadHistoryItem[],
  }];
  selectedBatchIndex.value = 0;

  // 设置 active tab
  const firstSuccess = uploadedSheets.value.find(s => s.success && !isIndexSheet(s as SheetResult));
  activeTab.value = String((firstSuccess || uploadedSheets.value[0]).sheetIndex);

  usingDemoCache.value = true;
  demoCacheFileName.value = cached.uploadBatch.fileName;

  return true;
};

/** 重新触发 Tour 引导 */
const startDemoTour = () => {
  demoTourRef.value?.startTour();
};

/** 刷新数据 (清除缓存，重新从服务器加载) */
const refreshFromServer = () => {
  usingDemoCache.value = false;
  demoCacheFileName.value = '';
  uploadedSheets.value = [];
  uploadResult.value = null;
  enrichedSheets.value = new Set();
  enrichingSheets.value = new Set();
  enrichPhases.value = new Map();
  activeTab.value = '';
  tourDataReady.value = false;
  loadHistory();
};

// ========== Cross-chart filter state (Phase 3.4) ==========
const activeFilter = ref<{ dimension: string; value: string } | null>(null);

// ========== Debounce timer for tab switch (Phase 2.3) ==========
let renderDebounceTimer: ReturnType<typeof setTimeout> | null = null;

// ========== Hover throttle timers per chart (avoid closure leak on tab switch) ==========
const hoverThrottleTimers = new Map<string, ReturnType<typeof setTimeout>>();

function clearHoverThrottleTimers() {
  hoverThrottleTimers.forEach(timer => clearTimeout(timer));
  hoverThrottleTimers.clear();
}

// 获取 Sheet 的所有图表（多图表优先，单图表兼容）
const getSheetCharts = (sheet: SheetResult): Array<{ chartType: string; title: string; config: Record<string, unknown>; xField?: string; totalItems?: number }> => {
  if (sheet.flowResult?.charts?.length) return sheet.flowResult.charts;
  if (sheet.flowResult?.chartConfig) return [{ chartType: 'bar', title: '数据分析', config: sheet.flowResult.chartConfig }];
  return [];
};

/**
 * Smart chart sizing — returns CSS class based on chart type & data characteristics.
 * - 'chart-size-wide': spans full row (complex bar/line with many categories, or horizontal bar)
 * - 'chart-size-square': square-ish aspect ratio (pie, radar)
 * - '': default half-width
 */
const getChartSizeClass = (chart: { chartType: string; config: Record<string, unknown> }): string => {
  const type = chart.chartType || 'bar';
  const config = (chart.config || {}) as SmartBIChartOption;

  // Pie and radar work best in square aspect ratio
  if (type === 'pie' || type === 'radar') return 'chart-size-square';

  // Horizontal bar (category on yAxis) — needs width for labels + bars
  const yAxisRaw = config.yAxis;
  const yAxis = (Array.isArray(yAxisRaw) ? yAxisRaw[0] : yAxisRaw) as Record<string, unknown> | undefined;
  if (yAxis && yAxis.type === 'category') return 'chart-size-wide';

  // Many x-axis categories — span full width so labels aren't crushed
  const xAxis = config.xAxis as Record<string, unknown> | undefined;
  if (xAxis && Array.isArray(xAxis.data) && xAxis.data.length > 12) return 'chart-size-wide';

  // Multi-series bar/line charts — need more room for legend + data
  const series = config.series;
  if (Array.isArray(series) && series.length > 3) return 'chart-size-wide';

  return '';
};

// 判断 sheet 是否有图表数据
const hasChartData = (sheet: SheetResult): boolean => {
  const charts = getSheetCharts(sheet);
  return charts.length > 0 && charts.some(c => c.config && !isChartDataEmpty(c.config));
};

// === P2: Layout mode (compact / comfortable / presentation) ===
type LayoutMode = 'compact' | 'comfortable' | 'presentation';
const chartLayoutMode = ref<LayoutMode>('comfortable');

// Resize all ECharts instances when layout mode changes (CSS grid reflow doesn't trigger ResizeObserver on root)
watch(chartLayoutMode, () => {
  // Wait for CSS transition (0.25s) to complete before resizing
  setTimeout(() => {
    (rootRef.value || document).querySelectorAll('[id^="chart-"]').forEach(dom => {
      const instance = echarts.getInstanceByDom(dom as HTMLElement);
      if (instance) instance.resize();
    });
  }, 300);
});

// === P2: Chart grouping by semantic category ===
interface ChartGroup {
  label: string;
  icon: string;
  charts: Array<{ chart: { chartType: string; title: string; config: Record<string, unknown>; xField?: string; totalItems?: number }; originalIndex: number }>;
}

const CHART_GROUP_RULES: Array<{ label: string; icon: string; patterns: RegExp[] }> = [
 { label: '收入与销售', icon: '', patterns: [/收入|营收|销售|revenue|sales|金额|成交|GMV/i] },
 { label: '成本与费用', icon: '', patterns: [/成本|费用|支出|cost|expense|开支/i] },
 { label: '利润与效率', icon: '', patterns: [/利润|毛利|净利|profit|margin|效率|ROI|回报/i] },
 { label: '趋势与时间', icon: '', patterns: [/趋势|月|季|年|时间|日期|trend|monthly|daily|weekly/i] },
 { label: '分布与占比', icon: '', patterns: [/占比|分布|比例|构成|distribution|proportion|结构/i] },
 { label: '排名与对比', icon: '', patterns: [/排名|排行|TOP|对比|比较|rank|comparison/i] },
];

const getGroupedCharts = (sheet: SheetResult): ChartGroup[] => {
  const charts = getSheetCharts(sheet);
  if (charts.length <= 3) return []; // Too few to group

  const groups: Map<string, ChartGroup> = new Map();
 const ungrouped: ChartGroup = { label: '其他分析', icon: '', charts: [] };

  for (let i = 0; i < charts.length; i++) {
    const chart = charts[i];
    const title = chart.title || '';
    const chartType = chart.chartType || '';
    let matched = false;

    // Try title-based matching
    for (const rule of CHART_GROUP_RULES) {
      if (rule.patterns.some(p => p.test(title))) {
        if (!groups.has(rule.label)) {
          groups.set(rule.label, { label: rule.label, icon: rule.icon, charts: [] });
        }
        groups.get(rule.label)!.charts.push({ chart, originalIndex: i });
        matched = true;
        break;
      }
    }

    // Fallback: group pie charts into "分布与占比"
    if (!matched && chartType === 'pie') {
      const distLabel = '分布与占比';
      if (!groups.has(distLabel)) {
 groups.set(distLabel, { label: distLabel, icon: '', charts: [] });
      }
      groups.get(distLabel)!.charts.push({ chart, originalIndex: i });
      matched = true;
    }

    if (!matched) {
      ungrouped.charts.push({ chart, originalIndex: i });
    }
  }

  // Build result: only include groups with 1+ charts, merge single-chart groups into ungrouped
  const result: ChartGroup[] = [];
  for (const group of groups.values()) {
    if (group.charts.length >= 1) {
      result.push(group);
    } else {
      ungrouped.charts.push(...group.charts);
    }
  }
  if (ungrouped.charts.length > 0) result.push(ungrouped);

  // If only one group and it's "其他分析" (no semantic match), skip grouping
  if (result.length <= 1 && result[0]?.label === '其他分析') return [];

  return result;
};

// 智能 KPI 选择（使用 smartbi.ts 的 getSmartKPIs），带缓存避免重复计算 (R-18)
const kpiCache = new Map<string, SmartKPI[]>();
const computeSmartKPIs = (
  kpiSummary: { rowCount: number; columnCount: number; columns: ColumnSummary[] },
  financialMetrics?: FinancialMetrics | null,
  uploadId?: number,
  displayNameMap?: Record<string, string>
): SmartKPI[] => {
  const cacheKey = `${uploadId ?? 'x'}-${kpiSummary.rowCount}-${kpiSummary.columnCount}-${kpiSummary.columns?.length}-${financialMetrics ? 'fm' : ''}-${displayNameMap ? 'dnm' : ''}`;
  const cached = kpiCache.get(cacheKey);
  if (cached) return cached;
  const result = getSmartKPIs(kpiSummary, financialMetrics, displayNameMap);
  kpiCache.set(cacheKey, result);
  return result;
};

// 获取 Sheet 的 KPI 列表（用于模板中）
const getSheetKPIs = (sheet: SheetResult): SmartKPI[] => {
  if (!sheet.flowResult?.kpiSummary) return [];
  return computeSmartKPIs(sheet.flowResult.kpiSummary, sheet.flowResult?.financialMetrics, sheet.uploadId, sheet.flowResult?.displayNameMap);
};

// 获取高管摘要
const getExecutiveSummary = (sheet: SheetResult): string => {
  return sheet.flowResult?.structuredAI?.executiveSummary || '';
};

// 构建 AIInsightPanel 所需的结构化数据
const getStructuredInsight = (sheet: SheetResult): AIInsight | null => {
  const structured = sheet.flowResult?.structuredAI;
  const chartConfigAi = sheet.flowResult?.chartConfig?.aiAnalysis;
  const aiText: string = sheet.flowResult?.aiAnalysis
    || (typeof chartConfigAi === 'string' ? chartConfigAi : '')
    || '';

  // 必须有结构化数据或 AI 文本
  if (!structured && !aiText) return null;

  const positive: string[] = [];
  const negative: string[] = [];
  const suggestions: string[] = [];

  // 从结构化数据分类
  if (structured) {
    if (structured.riskAlerts?.length) {
      for (const r of structured.riskAlerts) {
        negative.push(`${r.title}: ${r.description}${r.mitigation ? ` (建议: ${r.mitigation})` : ''}`);
      }
    }
    if (structured.opportunities?.length) {
      for (const o of structured.opportunities) {
        suggestions.push(`${o.title}: ${o.description}${o.actionRequired ? ` → ${o.actionRequired}` : ''}`);
      }
    }
  }

  // 从 AI 文本中提取（按 sentiment 分类）
  if (aiText) {
    const lines = aiText.split('\n\n').filter(Boolean);
    for (const line of lines) {
      // Post-process: humanize raw column name patterns like "8的2025-01-01_预算数"
      let cleanLine = line.replace(/\*\*/g, '').trim();
      cleanLine = cleanLine.replace(/\d+的(\d{4}-\d{2}-\d{2})[_]?([^\s,，。;；]*)/g, (_m, date, suffix) => {
        return humanizeColumnName(date) + (suffix ? humanizeColumnName(suffix) : '');
      });
      if (!cleanLine) continue;

      // 启发式分类（R-20: 先检查负面关键词，避免"增长下降"误判为正面）
      if (/negative|下降|风险|异常|低于|不足|减少|亏损|下滑|萎缩/i.test(cleanLine)) {
        negative.push(cleanLine);
      } else if (/recommendation|建议|改进|优化|应该|需要|可以/i.test(cleanLine)) {
        suggestions.push(cleanLine);
      } else if (/positive|增长|提升|良好|突出|达到|超过|上升|盈利/i.test(cleanLine)) {
        suggestions.push(cleanLine);
      } else {
        // 默认放到 positive
        positive.push(cleanLine);
      }
    }
  }

  // 至少有一个分组有内容
  if (positive.length === 0 && negative.length === 0 && suggestions.length === 0) {
    return null;
  }

  return {
    positive: { title: '积极发现', items: positive },
    negative: { title: '风险关注', items: negative },
    suggestions: { title: '改进建议', items: suggestions },
    generatedAt: new Date().toISOString()
  };
};

// 获取敏感性分析数据
const getSensitivityAnalysis = (sheet: SheetResult): Array<{ factor: string; current_value: string; impact_description: string }> | undefined => {
  return sheet.flowResult?.structuredAI?.sensitivityAnalysis;
};

// 获取 Sheet 显示名称（优先使用索引页的报表名）
const getSheetDisplayName = (sheet: SheetResult): string => {
  if (indexMetadata.value?.hasIndex) {
    const mapping = indexMetadata.value.sheetMappings.find(
      m => m.index === sheet.sheetIndex
    );
    if (mapping?.reportName) {
      return mapping.reportName;
    }
  }
  return sheet.sheetName;
};

// 获取 Sheet 的编制说明
const getSheetDescription = (sheet: SheetResult): string | undefined => {
  if (indexMetadata.value?.hasIndex) {
    const mapping = indexMetadata.value.sheetMappings.find(
      m => m.index === sheet.sheetIndex
    );
    return mapping?.description;
  }
  return undefined;
};

// 判断是否为索引页
const isIndexSheet = (sheet: SheetResult): boolean => {
  return sheet.tableType === 'index' ||
    sheet.sheetIndex === indexMetadata.value?.indexSheetIndex;
};

// SSE 进度相关
interface SheetProgress {
  sheetIndex: number;
  sheetName: string;
  stage: string;
  message: string;
  status: 'pending' | 'processing' | 'complete' | 'failed';
}

const sheetProgressList = ref<SheetProgress[]>([]);
const totalSheetCount = ref(0);
const completedSheetCount = ref(0);
const dictionaryHits = ref(0);
const llmAnalyzedFields = ref(0);

// Sheet 预览信息
interface SheetInfo {
  index: number;
  name: string;
  rowCount: number;
  columnCount: number;
}

const availableSheets = ref<SheetInfo[]>([]);
const selectedSheets = ref<number[]>([]);

// 文件选择
const handleFileChange = (file: UploadFile) => {
  if (file.size! > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 50MB');
    fileList.value = [];
    return;
  }
  fileList.value = [file];
};

// 预览 Sheet 列表
const previewSheets = async (file: File) => {
  const formData = new FormData();
  formData.append('file', file);

  try {
    const response = await post<SheetInfo[]>(
      `/${factoryId.value}/smart-bi/sheets`,
      formData,
      { timeout: 120000 } // 2分钟超时，LLM分析需要较长时间
    );

    if (response.success && response.data) {
      availableSheets.value = response.data;
      // 默认选择所有非空 Sheet
      selectedSheets.value = response.data
        .filter(s => s.rowCount > 0)
        .map(s => s.index);
      return true;
    }
    return false;
  } catch (error: unknown) {
    ElMessage.error(`预览失败: ${getErrorMessage(error)}`);
    return false;
  }
};

// 上传文件 (使用 SSE 流式进度)
const uploadFile = async () => {
  if (fileList.value.length === 0) {
    ElMessage.warning('请先选择文件');
    return;
  }

  const file = fileList.value[0].raw;
  if (!file) return;

  // 重置状态
  uploading.value = true;
  uploadProgress.value = 5;
  progressText.value = '正在预览 Sheet 列表...';
  sheetProgressList.value = [];
  totalSheetCount.value = 0;
  completedSheetCount.value = 0;
  dictionaryHits.value = 0;
  llmAnalyzedFields.value = 0;
  uploadStatus.value = undefined;

  // 1. 预览 Sheets
  const previewSuccess = await previewSheets(file);
  if (!previewSuccess) {
    uploading.value = false;
    return;
  }

  uploadProgress.value = 10;
  progressText.value = '准备上传...';

  // 2. 构建 Sheet 配置
  // headerRow: -1 表示使用 Python auto-parse 的自动检测功能
  // Python StructureDetector 会自动识别标题行、合并单元格、数据起始行
  const sheetConfigs = availableSheets.value
    .filter(s => s.rowCount > 0)
    .map(s => ({
      sheetIndex: s.index,
      headerRow: -1,  // 让 Python /auto-parse 自动检测，不再硬编码
      autoConfirm: true
    }));

  // Abort any in-flight SSE upload before starting a new one
  if (uploadAbortController) uploadAbortController.abort();
  uploadAbortController = new AbortController();

  // 初始化 Sheet 进度列表
  sheetProgressList.value = sheetConfigs.map(config => {
    const sheetInfo = availableSheets.value.find(s => s.index === config.sheetIndex);
    return {
      sheetIndex: config.sheetIndex,
      sheetName: sheetInfo?.name || `Sheet ${config.sheetIndex}`,
      stage: '等待中',
      message: '',
      status: 'pending' as const
    };
  });

  // 3. 使用 SSE 流式上传
  const formData = new FormData();
  formData.append('file', file);
  formData.append('sheetConfigs', JSON.stringify(sheetConfigs));

  try {
    progressText.value = '开始处理...';

    // 使用 fetch + ReadableStream 处理 SSE
    // VITE_API_BASE_URL 已包含 /api/mobile，不需要重复
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api/mobile';
    const url = `${baseUrl}/${factoryId.value}/smart-bi/upload-batch-stream`;

    const response = await fetch(url, {
      method: 'POST',
      body: formData,
      credentials: 'include', // Send HttpOnly auth cookies
      headers: {
        'X-Client-Type': 'web',
      },
      signal: uploadAbortController?.signal,
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();

    if (!reader) {
      throw new Error('无法获取响应流');
    }

    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 解析 SSE 事件
      const lines = buffer.split('\n');
      buffer = lines.pop() || ''; // 保留不完整的行

      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const eventData = JSON.parse(line.substring(5));
            handleSSEEvent(eventData);
          } catch (e) {
            console.warn('Failed to parse SSE data:', line);
          }
        }
      }
    }

    // 处理最后一个事件
    if (buffer.startsWith('data:')) {
      try {
        const eventData = JSON.parse(buffer.substring(5));
        handleSSEEvent(eventData);
      } catch (e) {
        // ignore
      }
    }

  } catch (error: unknown) {
    if (error instanceof Error && error.name === 'AbortError') return; // Component unmounted or new upload started
    uploadStatus.value = 'exception';
    progressText.value = '上传失败';
    ElMessage.error(`上传失败: ${getErrorMessage(error)}`);
    uploading.value = false; // 错误时立即停止上传状态
  }
};

// SSE 事件类型 (Tier 3 vue-tsc cleanup 2026-05-10)
interface SSEUploadEvent {
  type?: string;
  progress?: number;
  sheetIndex?: number;
  sheetName?: string;
  stage?: string;
  message?: string;
  completedSheets?: number;
  totalSheets?: number;
  dictionaryHits?: number;
  llmAnalyzedFields?: number;
  result?: BatchUploadResult;
  error?: string;
}

// 处理 SSE 事件
const handleSSEEvent = (event: SSEUploadEvent) => {
  const { type, progress, sheetIndex, sheetName, stage, message, completedSheets, totalSheets, dictionaryHits: dictHits, llmAnalyzedFields: llmFields, result } = event;

  // 更新总体进度
  if (progress) {
    uploadProgress.value = progress;
  }
  if (totalSheets) {
    totalSheetCount.value = totalSheets;
  }
  if (completedSheets !== undefined) {
    completedSheetCount.value = completedSheets;
  }
  if (dictHits !== undefined && dictHits !== null) {
    dictionaryHits.value += dictHits;
  }
  if (llmFields !== undefined && llmFields !== null) {
    llmAnalyzedFields.value += llmFields;
  }

  // 更新进度文本
  if (message) {
    progressText.value = message;
  }

  // 更新 Sheet 进度
  if (sheetIndex !== undefined && sheetIndex !== null) {
    const sheetProgress = sheetProgressList.value.find(s => s.sheetIndex === sheetIndex);
    if (sheetProgress) {
      if (stage) sheetProgress.stage = stage;
      if (message) sheetProgress.message = message;

      // 根据事件类型设置状态
      switch (type) {
        case 'SHEET_START':
        case 'PARSING':
        case 'FIELD_MAPPING':
        case 'LLM_ANALYZING':
        case 'PERSISTING':
        case 'CHART_GENERATING':
          sheetProgress.status = 'processing';
          break;
        case 'SHEET_COMPLETE':
          sheetProgress.status = 'complete';
          break;
        case 'SHEET_FAILED':
          sheetProgress.status = 'failed';
          break;
      }
    }
  }

  // 处理完成事件
  if (type === 'COMPLETE' && result) {
    uploadStatus.value = 'success';
    progressText.value = '分析完成！';
    uploadResult.value = result;
    // Include both successful AND failed sheets (failed ones get retry button)
    uploadedSheets.value = result.results || [];

    // 捕获索引元数据
    if (result.indexMetadata) {
      indexMetadata.value = result.indexMetadata;
    }

    if (uploadedSheets.value.length > 0) {
      // Prefer first successful sheet as active tab
      const firstSuccess = uploadedSheets.value.find(s => s.success);
      activeTab.value = String((firstSuccess || uploadedSheets.value[0]).sheetIndex);

      // 重要：先设置 uploading = false，让 DOM 渲染出来，然后再渲染图表
      uploading.value = false;

      // 等待 DOM 更新后再渲染图表
      nextTick(() => {
        setTimeout(() => {
          renderActiveChart();
        }, 100); // 额外延迟确保 DOM 完全渲染
      });

      // P5: Enrich all sheets with concurrency limit (max 2 parallel, no fixed stagger)
      const dataSheets = uploadedSheets.value.filter(s => !isIndexSheet(s) && s.uploadId);
      const sheetsToEnrich = dataSheets.filter(sheet => {
        const sheetHasCharts = hasChartData(sheet);
        const hasAI = !!sheet.flowResult?.aiAnalysis;
        return (!sheetHasCharts || !hasAI) && sheet.uploadId;
      });
      if (sheetsToEnrich.length > 0) {
        // Semaphore-style concurrency limiter (max 2 to respect DashScope QPS)
        let running = 0;
        let idx = 0;
        const startNext = () => {
          while (running < 2 && idx < sheetsToEnrich.length) {
            running++;
            const sheet = sheetsToEnrich[idx++];
            enrichSheet(sheet).catch(() => {}).finally(() => { running--; startNext(); });
          }
        };
        startNext();
      }
    }

    ElMessage.success(result.message || '上传成功');

    // Re-check Python health after upload (enrichment needs it)
    checkHealthWithRetry(2).catch(() => {});
  }

  // 处理错误事件
  if (type === 'ERROR') {
    uploadStatus.value = 'exception';
    progressText.value = event.error || '处理失败';
    ElMessage.error(event.error || '处理失败');
  }
};

/** Animation registry — stagger delays by named key */
const ANIM_REGISTRY: Record<string, (idx: number) => number> = {
  stagger_80: (idx) => idx * 80,
  stagger_60: (idx) => idx * 60,
  stagger_5:  (idx) => idx * 5,
};

/** Formatter registry — tooltip/label callbacks by named key */
const FMT_REGISTRY: Record<string, (...args: unknown[]) => string> = {
  thousands_sep: (v: unknown) => {
    const num = typeof v === 'number' ? v : Number(v);
    if (typeof num !== 'number' || isNaN(num)) return v == null ? '-' : String(v);
    const abs = Math.abs(num);
    if (abs >= 1e8) return (num / 1e8).toFixed(1) + '亿';
    if (abs >= 1e4) return (num / 1e4).toFixed(1) + '万';
    return num.toLocaleString('zh-CN');
  },
  boxplot_tooltip: (p: unknown) => {
    const param = p as Record<string, unknown>;
    const d = param.data as number[];
    return `${param.name}<br/>最小: ${d[0]}<br/>Q1: ${d[1]}<br/>中位数: ${d[2]}<br/>Q3: ${d[3]}<br/>最大: ${d[4]}`;
  },
  correlation_tooltip: (p: unknown) => ((p as Record<string, unknown>).data as number[])[2].toFixed(2),
  correlation_label: (p: unknown) => ((p as Record<string, unknown>).data as number[])[2].toFixed(1),
  quadrant_scatter_tooltip: (p: unknown) => {
    const d = (p as Record<string, unknown>).data as (string | number)[];
    return `${d[2]}<br/>收入: ${Number(d[0]).toLocaleString()}<br/>利润率: ${d[1]}%`;
  },
  quadrant_scatter_label: (p: unknown) => String(((p as Record<string, unknown>).data as unknown[])[2]),
};

/**
 * Process ECharts options: resolve __ANIM__/__FMT__ named references from Python.
 * No eval/new Function — all callbacks are pre-registered above.
 */
const processEChartsOptions = (opts: Record<string, unknown>): Record<string, unknown> => {
  const processValue = (val: unknown): unknown => {
    if (typeof val === 'string') {
      if (val.startsWith('__ANIM__')) return ANIM_REGISTRY[val.slice(8)] ?? val;
      if (val.startsWith('__FMT__'))  return FMT_REGISTRY[val.slice(7)] ?? val;
    }
    if (Array.isArray(val)) return val.map(processValue);
    if (val && typeof val === 'object') {
      const result: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
        result[k] = processValue(v);
      }
      return result;
    }
    return val;
  };
  return processValue(opts) as Record<string, unknown>;
};

/**
 * Resolve ECharts options from various config formats
 */
const resolveEChartsOptions = (config: Record<string, unknown>): Record<string, unknown> | null => {
  if (((config as SmartBIChartOption)).series || ((config as SmartBIChartOption)).xAxis || ((config as SmartBIChartOption)).yAxis) {
    return config;
  } else if (typeof ((config as SmartBIChartOption)).chartOptions === 'string') {
    try { return JSON.parse((config as SmartBIChartOption).chartOptions as string) as Record<string, unknown>; } catch { return null; }
  } else if (((config as SmartBIChartOption)).options) {
    return (config as SmartBIChartOption).options as Record<string, unknown>;
  }
  return null;
};

/**
 * Add anomaly markPoints + mean markLine to chart (Phase 3.1)
 * anomalies comes from Python chart_builder IQR detection
 */
const applyAnomalyOverlay = (opts: Record<string, unknown>, anomalies: Record<string, any>) => {
  if (!anomalies || !opts) return;
  const series = ((opts as SmartBIChartOption)).series;
  if (!Array.isArray(series)) return;

  for (const s of series) {
    const colName = s.name;
    const anomalyData = anomalies[colName];
    if (!anomalyData) continue;

    // Mean reference line (Grafana style)
    if (anomalyData.mean != null) {
      s.markLine = {
        silent: true,
        symbol: 'none',
        lineStyle: { type: 'dashed', color: '#9ca3af', width: 1 },
        label: { formatter: `均值: ${anomalyData.mean}`, position: 'insideEndTop', fontSize: 11, color: '#9ca3af' },
        data: [{ yAxis: anomalyData.mean }]
      };
    }

    // Outlier red dots (ThoughtSpot SpotIQ)
    if (anomalyData.outliers?.length) {
      s.markPoint = {
        symbol: 'circle',
        symbolSize: 8,
        itemStyle: { color: '#dc2626', borderColor: '#fff', borderWidth: 1 },
        label: { show: false },
        data: anomalyData.outliers.map((o: Record<string, unknown>) => ({
          xAxis: o.index,
          yAxis: o.value,
          value: `${Number(o.deviation) > 0 ? '+' : ''}${o.deviation}σ`
        }))
      };
    }
  }
};

// 综合图表增强：DataZoom + 标签自适应 + 近零值 + 零值标签隐藏 + 图例人性化 + 离群值 + 万/亿格式化
/**
 * Clean meaningless auto-generated suffixes from display labels.
 * Converts "实际收入_2" → "实际收入(2)", "入库_3" → "入库(3)" etc.
 * Keeps labels distinguishable while making them human-readable.
 */
const cleanDisplayLabel = (label: string): string => {
  // Convert _N suffixes to (N) format for readability; leave (N) as-is
  return label.replace(/_(\d{1,2})(?=$|\s|、)/g, (_m, n) => `(${n})`);
};

/** Template helper: resolve a raw column name to its best display label */
const getColumnLabel = (col: string): string => {
  const idx = parseInt(activeTab.value);
  const sheet = uploadedSheets.value.find(s => s.sheetIndex === idx);
  const map = sheet?.flowResult?.displayNameMap;
  return map?.[col] || humanizeColumnName(col);
};

const enhanceChartOption = (opts: Record<string, unknown>, displayNameMap?: Record<string, string>): void => {
  const nameOf = (col: string) => displayNameMap?.[col] || humanizeColumnName(col);
  // === Clean meaningless suffixes in title, series names, legend, radar indicators ===
  {
    const _title = ((opts as SmartBIChartOption)).title;
    if (_title && typeof _title.text === 'string') _title.text = cleanDisplayLabel(_title.text);
  }
  {
    const _series = ((opts as SmartBIChartOption)).series;
    if (Array.isArray(_series)) {
      for (const s of _series) {
        if (typeof s.name === 'string') s.name = cleanDisplayLabel(s.name);
      }
    }
    const _legend = ((opts as SmartBIChartOption)).legend;
    if (_legend && Array.isArray(_legend.data)) {
      _legend.data = _legend.data.map((d: unknown) =>
        typeof d === 'string' ? cleanDisplayLabel(d) : d
      );
    }
  }
  // Clean radar indicator names (before radar max rounding)
  {
    const _radar = ((opts as SmartBIChartOption)).radar;
    if (_radar) {
      const radarItems = Array.isArray(_radar) ? _radar : [_radar];
      for (const r of radarItems) {
        if (Array.isArray(r.indicator)) {
          for (const ind of r.indicator) {
            if (typeof ind.name === 'string') ind.name = cleanDisplayLabel(ind.name);
          }
        }
      }
    }
  }

  // Helper: extract all numeric values from series data
  const getSeriesStats = (o: Record<string, unknown>): { max: number; min: number; count: number; nonZeroMin: number; zeroCount: number; median: number } => {
    const series = ((o as SmartBIChartOption)).series;
    if (!Array.isArray(series)) return { max: 0, min: 0, count: 0, nonZeroMin: Infinity, zeroCount: 0, median: 0 };
    let maxVal = 0, minVal = Infinity, count = 0, nonZeroMin = Infinity, zeroCount = 0;
    const allValues: number[] = [];
    for (const s of series) {
      const data = s?.data;
      if (!Array.isArray(data)) continue;
      for (const d of data) {
        const v = typeof d === 'number' ? d : (Array.isArray(d) ? Number(d[1]) || 0 : Number((d as Record<string, unknown>)?.value) || 0);
        const abs = Math.abs(v);
        allValues.push(abs);
        if (abs > maxVal) maxVal = abs;
        if (abs < minVal) minVal = abs;
        if (abs > 0 && abs < nonZeroMin) nonZeroMin = abs;
        if (v === 0) zeroCount++;
        count++;
      }
    }
    // Compute median for outlier detection
    allValues.sort((a, b) => a - b);
    const median = allValues.length > 0 ? allValues[Math.floor(allValues.length / 2)] : 0;
    return { max: maxVal, min: minVal, count, nonZeroMin, zeroCount, median };
  };

  const stats = getSeriesStats(opts);
  const xAxis = ((opts as SmartBIChartOption)).xAxis;
  const yAxis = ((opts as SmartBIChartOption)).yAxis;
  const series = ((opts as SmartBIChartOption)).series;
  const chartType = Array.isArray(series) ? series[0]?.type : '';

  // === D2: 图例名称人性化 (uses displayNameMap when available) ===
  if (Array.isArray(series)) {
    for (const s of series) {
      if (s.name && typeof s.name === 'string') {
        s.name = nameOf(s.name);
      }
    }
  }
  // Legend data humanization
  const legend = ((opts as SmartBIChartOption)).legend;
  if (legend && Array.isArray(legend.data)) {
    legend.data = legend.data.map((rawItem: unknown) => {
      if (typeof rawItem === 'string') return nameOf(rawItem);
      const item = rawItem as Record<string, unknown>;
      if (item && typeof item.name === 'string') {
        item.name = nameOf(item.name);
        return item;
      }
      return item;
    });
  }

  // === 轴名称人性化 ===
  const yAxes = Array.isArray(yAxis) ? yAxis : (yAxis ? [yAxis] : []);
  for (const ax of yAxes) {
    if (ax && typeof ax.name === 'string') {
      ax.name = nameOf(ax.name);
    }
  }
  if (xAxis && typeof xAxis.name === 'string') {
    xAxis.name = nameOf(xAxis.name);
  }

  // === P1.1: 食品行业语义配色 ===
  if (Array.isArray(series) && chartType !== 'pie') {
    const semanticColorMap: Array<{ pattern: RegExp; colors: string[] }> = [
      { pattern: /收入|营收|销售额|revenue/i, colors: ['#52c41a', '#73d13d'] },
      { pattern: /成本|费用|支出|cost|expense/i, colors: ['#ff4d4f', '#ff7875'] },
      { pattern: /利润|净利|毛利|profit/i, colors: ['#1890ff', '#40a9ff'] },
      { pattern: /率|比例|占比|ratio|margin|rate/i, colors: ['#722ed1', '#9254de'] },
    ];
    for (const s of series) {
      if (!s.name || typeof s.name !== 'string') continue;
      if (s.itemStyle?.color) continue; // Don't override explicit colors
      for (const rule of semanticColorMap) {
        if (rule.pattern.test(s.name)) {
          s.itemStyle = s.itemStyle || {};
          s.itemStyle.color = rule.colors[0];
          if (s.lineStyle) s.lineStyle.color = rule.colors[0];
          if (s.areaStyle) {
            s.areaStyle.color = {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: rule.colors[0] + '40' },
                { offset: 1, color: rule.colors[0] + '08' },
              ]
            };
          }
          break;
        }
      }
    }
  }

  // === P2: Gradient area fill for line series (subtle depth effect) ===
  if (Array.isArray(series)) {
    for (const s of series) {
      if (s.type !== 'line' || s.areaStyle) continue; // skip if already has areaStyle
      const baseColor = s.itemStyle?.color || s.lineStyle?.color;
      if (!baseColor || typeof baseColor !== 'string') continue;
      s.areaStyle = {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: baseColor + '30' },
            { offset: 1, color: baseColor + '05' },
          ],
        },
      };
    }
  }

  // === DataZoom — 数据量>30时自动启用 slider+inside ===
  if (xAxis && xAxis.type === 'category' && Array.isArray(xAxis.data) && xAxis.data.length > 30) {
    const dataLen = xAxis.data.length;
    const endPercent = Math.min(100, Math.round((25 / dataLen) * 100));
    if (!((opts as SmartBIChartOption)).dataZoom) {
      ((opts as SmartBIChartOption)).dataZoom = [
        { type: 'slider', show: true, xAxisIndex: 0, start: 0, end: endPercent, height: 20, bottom: 8 },
        { type: 'inside', xAxisIndex: 0, start: 0, end: endPercent }
      ];
      const grid = ((opts as SmartBIChartOption)).grid || {};
      const curBottom = typeof grid.bottom === 'number' ? grid.bottom : 50;
      grid.bottom = Math.max(curBottom, 60);
      ((opts as SmartBIChartOption)).grid = grid;
    }
  }

  // === X轴标签自适应 — interval + rotate + formatter截断 ===
  if (xAxis && xAxis.type === 'category' && Array.isArray(xAxis.data)) {
    const dataLen = xAxis.data.length;
    const maxLabelLen = Math.max(...xAxis.data.map((d: unknown) => String(d).length));
    xAxis.axisLabel = xAxis.axisLabel || {};
    // Calculate optimal rotation (always override — Python's 30° is often not enough)
    let optimalRotate = 0;
    if (dataLen > 50) optimalRotate = 60;
    else if (dataLen > 30) optimalRotate = 50;
    else if (dataLen > 15) optimalRotate = 45;
    else if (maxLabelLen > 4 && dataLen > 4) optimalRotate = 40;
    else if (maxLabelLen > 6 && dataLen > 2) optimalRotate = 35;
    // Apply: always use the MORE aggressive rotation
    if (optimalRotate > (xAxis.axisLabel.rotate || 0)) {
      xAxis.axisLabel.rotate = optimalRotate;
    }
    if (dataLen > 20 && xAxis.axisLabel.interval === undefined) {
      xAxis.axisLabel.interval = Math.max(0, Math.ceil(dataLen / 8) - 1);
    }
    // Adjust grid bottom when labels are rotated to prevent clipping
    const curRotate = xAxis.axisLabel.rotate || 0;
    if (curRotate >= 30) {
      const grid = ((opts as SmartBIChartOption)).grid || {};
      const curBottom = typeof grid.bottom === 'number' ? grid.bottom :
                        (typeof grid.bottom === 'string' && grid.bottom.endsWith('%') ? parseInt(grid.bottom) : 50);
      const neededBottom = curRotate >= 45 ? 85 : 70;
      if (curBottom < neededBottom) {
        grid.bottom = neededBottom;
        ((opts as SmartBIChartOption)).grid = grid;
      }
    }
    // D5: 标签截断 — rotated labels can be shorter since they have more vertical space
    if (!xAxis.axisLabel.formatter) {
      const isWaterfall = Array.isArray(series) && series.some((s: Record<string, unknown>) => s.type === 'bar' && s.stack);
      const maxLen = isWaterfall ? 18 : (curRotate >= 35 ? 7 : 10);
      xAxis.axisLabel.formatter = (val: string) => {
        const str = String(val);
        if (/^\d{4}-\d{2}-\d{2}/.test(str)) return str.slice(5, 10);
        if (/^\d{4}-\d{2}$/.test(str)) return str.slice(5) + '月';
        // Subtable prefix: abbreviate "净利-管理费用" → "净·管理费用"
        const dashIdx = str.indexOf('-');
        if (dashIdx > 0 && dashIdx <= 4 && str.length > maxLen) {
          const prefix = str.slice(0, 1);
          const suffix = str.slice(dashIdx + 1);
          const abbrev = prefix + '·' + suffix;
          return abbrev.length > maxLen ? abbrev.slice(0, maxLen - 1) + '…' : abbrev;
        }
        return str.length > maxLen ? str.slice(0, maxLen - 1) + '…' : str;
      };
    }
    xAxis.axisLabel.hideOverlap = true;
    xAxis.axisLabel.fontSize = xAxis.axisLabel.fontSize || (dataLen > 10 ? 10 : 11);
  }

  // === Y轴 category 标签（横向柱状图如排行）截断 ===
  // Narrow: yAxis can be EChartsAxis | EChartsAxis[]; use single-axis branch only
  const yAxisSingle = !Array.isArray(yAxis) ? yAxis : undefined;
  if (yAxisSingle && yAxisSingle.type === 'category' && Array.isArray(yAxisSingle.data)) {
    yAxisSingle.axisLabel = yAxisSingle.axisLabel || {};
    if (!yAxisSingle.axisLabel.formatter) {
      yAxisSingle.axisLabel.formatter = (val: string) => {
        const str = String(val);
        return str.length > 10 ? str.slice(0, 9) + '…' : str;
      };
    }
    // Ensure enough left margin for horizontal bar labels
    const grid = ((opts as SmartBIChartOption)).grid || {};
    if (!grid.left || (typeof grid.left === 'number' && grid.left < 100)) {
      grid.left = '18%';
    }
    // Ensure enough right margin for data labels on horizontal bars
    if (!grid.right || (typeof grid.right === 'number' && grid.right < 60)) {
      grid.right = '12%';
    }
    ((opts as SmartBIChartOption)).grid = grid;
  }

  // === D1+D7: 零值标签隐藏 + 标签防重叠 ===
  if (Array.isArray(series)) {
    for (const s of series) {
      // Hide zero-value bar/line labels to reduce visual noise
      if ((s.type === 'bar' || s.type === 'line') && s.label && s.label.show) {
        const origFormatter = s.label.formatter;
        s.label.formatter = (params: unknown) => {
          const p = params as { value?: unknown; seriesName?: string; name?: string; percent?: unknown };
          const val = typeof p.value === 'number' ? p.value :
                      (Array.isArray(p.value) ? Number(p.value[1]) : Number(p.value));
          // Hide zero or near-zero labels
          if (val === 0 || (Math.abs(val) < 0.01 && Math.abs(val) > 0)) return '';
          // If there was an original formatter, apply it
          if (typeof origFormatter === 'function') return origFormatter(params);
          // ECharts template strings like "{c}万" — substitute placeholders
          if (typeof origFormatter === 'string') {
            return origFormatter
              .replace(/\{a\}/g, p.seriesName || '')
              .replace(/\{b\}/g, p.name || '')
              .replace(/\{c\}/g, String(val))
              .replace(/\{d\}/g, String(p.percent ?? ''));
          }
          // Default: smart number formatting
          const abs = Math.abs(val);
          if (abs >= 1e4) return `${(val / 1e4).toFixed(abs >= 1e5 ? 0 : 2)}万`;
          if (abs >= 1000) return `${(val / 1000).toFixed(1)}K`;
          return Number.isInteger(val) ? String(val) : val.toFixed(2);
        };
      }
      if (s.label && s.label.show && !s.labelLayout) {
        s.labelLayout = { hideOverlap: true };
      }
    }
    // sampling for large datasets
    if (stats.count > 100) {
      for (const s of series) {
        if ((s.type === 'line' || s.type === 'bar') && !s.sampling) {
          s.sampling = 'lttb';
        }
      }
    }
  }

  // === Radar indicator max rounding — fix "ticks may be not readable" warnings ===
  const radar = ((opts as SmartBIChartOption)).radar;
  if (radar) {
    const radarItems = Array.isArray(radar) ? radar : [radar];
    for (const r of radarItems) {
      // Set explicit splitNumber so tick calculation is deterministic
      if (!r.splitNumber) r.splitNumber = 5;
      // Round indicator max values to "nice" numbers divisible by splitNumber
      const sn = r.splitNumber;
      for (const ind of (r.indicator || [])) {
        if (typeof ind.max === 'number' && ind.max > 0) {
          const magnitude = Math.pow(10, Math.floor(Math.log10(ind.max)));
          const normalized = ind.max / magnitude;
          const nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
          let rounded = nice * magnitude;
          // Ensure max is divisible by splitNumber for clean tick intervals
          const remainder = rounded % sn;
          if (remainder !== 0) rounded = rounded + (sn - remainder);
          ind.max = rounded;
        }
      }
    }
  }

  // === P3: Legend scroll mode when many series ===
  if (legend && Array.isArray(series) && series.length > 5) {
    legend.type = 'scroll';
    legend.pageIconSize = 12;
    legend.pageTextStyle = { fontSize: 11 };
  }

  // === D6: 饼图图例优化 — 右侧纵排 + 截断 + 缩小饼图留空间 ===
  if (chartType === 'pie' && legend) {
    legend.formatter = (name: string) => {
      return name.length > 12 ? name.slice(0, 10) + '…' : name;
    };
    legend.tooltip = { show: true }; // hover to see full name
    // Move legend to right side (vertical layout) for better space usage
    const pieDataLen = Array.isArray(series) && series[0]?.data ? (series[0].data as unknown[]).length : 0;
    if (pieDataLen > 4) {
      legend.orient = 'vertical';
      legend.right = '2%';
      legend.top = 'middle';
      legend.left = undefined;
      legend.bottom = undefined;
      legend.type = 'scroll';
      legend.pageIconSize = 12;
      legend.pageTextStyle = { fontSize: 11 };
      legend.textStyle = { fontSize: 12 };
      // Shrink pie radius and shift left to make room for legend
      if (Array.isArray(series)) {
        for (const s of series) {
          if (s.type === 'pie') {
            if (!s.center) s.center = ['38%', '50%'];
            if (!s.radius) s.radius = pieDataLen > 8 ? '60%' : '65%';
          }
        }
      }
    }
  }

  // === Tooltip: show full category name (not truncated) ===
  if (chartType !== 'pie' && chartType !== 'radar') {
    const tooltip = ((opts as SmartBIChartOption)).tooltip || {};
    if (!tooltip.trigger) tooltip.trigger = 'axis';
    // confine tooltip to chart container to prevent overflow
    tooltip.confine = true;
    ((opts as SmartBIChartOption)).tooltip = tooltip;
  }

  // === Grid padding — ensure labels/legends have enough room ===
  if (chartType !== 'pie' && chartType !== 'radar') {
    const grid = ((opts as SmartBIChartOption)).grid || {};
    // Ensure minimum padding so axis labels aren't clipped
    if (!grid.top || (typeof grid.top === 'number' && grid.top < 40)) grid.top = 40;
    if (!grid.right || (typeof grid.right === 'number' && grid.right < 30)) grid.right = 30;
    ((opts as SmartBIChartOption)).grid = grid;
  }

  // === D3: 极端离群值检测 ===
  // 当最大值 > 10x 中位数时，在 tooltip 中提示
  if (chartType === 'bar' && stats.median > 0 && stats.max > stats.median * 10) {
    ((opts as SmartBIChartOption)).tooltip = ((opts as SmartBIChartOption)).tooltip || {};
    const origTipFormatter = ((opts as SmartBIChartOption)).tooltip.formatter;
    if (!origTipFormatter) {
      ((opts as SmartBIChartOption)).tooltip.formatter = (params: unknown) => {
        const p = Array.isArray(params) ? params[0] : params;
        const val = typeof p.value === 'number' ? p.value : (Array.isArray(p.value) ? p.value[1] : p.value);
        const numVal = Number(val);
        const base = `${p.marker || ''}${p.seriesName}: <b>${numVal.toLocaleString()}</b>`;
        if (Math.abs(numVal) > stats.median * 10) {
 return `${p.name}<br/>${base}<br/><span style="color:#ff6b35;font-size:11px"> 离群值 (${(numVal / stats.median).toFixed(0)}x 中位数)</span>`;
        }
        return `${p.name}<br/>${base}`;
      };
    }
  }

  // === 近零值智能处理 ===
  if (yAxisSingle && chartType !== 'pie' && stats.max > 0 && stats.nonZeroMin < Infinity) {
    const ratio = stats.max / stats.nonZeroMin;
    // Case 1: Extreme range → enable scale for better resolution
    if (ratio > 100 && stats.nonZeroMin < stats.max * 0.01) {
      yAxisSingle.scale = true;
      if (!yAxisSingle.splitNumber) yAxisSingle.splitNumber = 8;
    }
    // Case 2: Value concentration — 80% of values in 10% of range
    if (stats.count > 5 && Array.isArray(series)) {
      const allValues: number[] = [];
      for (const s of series) {
        if (!Array.isArray(s?.data)) continue;
        for (const d of s.data) {
          const v = typeof d === 'number' ? d : (Array.isArray(d) ? Number(d[1]) || 0 : Number((d as Record<string, unknown>)?.value) || 0);
          if (v !== 0) allValues.push(Math.abs(v));
        }
      }
      if (allValues.length > 5) {
        allValues.sort((a, b) => a - b);
        const rangeTotal = allValues[allValues.length - 1] - allValues[0];
        if (rangeTotal > 0) {
          // Check if 80% of values fall within 10% of range
          const p10 = allValues[Math.floor(allValues.length * 0.1)];
          const p90 = allValues[Math.floor(allValues.length * 0.9)];
          const innerRange = p90 - p10;
          if (innerRange < rangeTotal * 0.1) {
            yAxisSingle.scale = true;
            if (!yAxisSingle.splitNumber) yAxisSingle.splitNumber = 6;
          }
        }
      }
    }
  }

  // === 万/亿 axis formatter ===
  if (yAxisSingle && typeof yAxisSingle.name === 'string') {
    const match = yAxisSingle.name.match(/\(([万亿])\)/);
    if (match) {
      const suffix = match[1];
      const divisor = suffix === '亿' ? 1e8 : 1e4;
      const minThreshold = suffix === '亿' ? 1e8 : 1e4;

      if (stats.max < minThreshold) {
        yAxisSingle.name = yAxisSingle.name.replace(/\s*\([万亿]\)/, '');
      } else {
        yAxisSingle.axisLabel = yAxisSingle.axisLabel || {};
        if (!yAxisSingle.axisLabel.formatter) {
          yAxisSingle.axisLabel.formatter = (value: number) => {
            if (value === 0) return '0';
            const scaled = value / divisor;
            return Number.isInteger(scaled) ? `${scaled}${suffix}` : `${scaled.toFixed(1)}${suffix}`;
          };
        }
      }
    }
  }

  // === G1: Auto 万/亿 axis formatter (handles dual-axis arrays + horizontal bars) ===
  if (chartType !== 'pie' && stats.max >= 10000) {
    // Y-axis: iterate array for dual-axis charts
    const yAxesList = Array.isArray(yAxis) ? yAxis : (yAxis ? [yAxis] : []);
    for (const ax of yAxesList) {
      if (ax && ax.type !== 'category' && !ax.axisLabel?.formatter) {
        ax.axisLabel = ax.axisLabel || {};
        ax.axisLabel.formatter = compactAxisFormatter;
      }
    }
    // X-axis type=value: horizontal bars + scatter (auto, no name match needed)
    if (xAxis && xAxis.type === 'value' && !xAxis.axisLabel?.formatter) {
      xAxis.axisLabel = xAxis.axisLabel || {};
      xAxis.axisLabel.formatter = compactAxisFormatter;
    }
  }

  // Scatter/explicit xAxis with (万)/(亿) in name
  if (xAxis && xAxis.type === 'value' && typeof xAxis.name === 'string') {
    const xMatch = xAxis.name.match(/\(([万亿])\)/);
    if (xMatch) {
      const xSuffix = xMatch[1];
      const xDivisor = xSuffix === '亿' ? 1e8 : 1e4;
      const xMinThreshold = xSuffix === '亿' ? 1e8 : 1e4;
      if (stats.max < xMinThreshold) {
        xAxis.name = xAxis.name.replace(/\s*\([万亿]\)/, '');
      } else {
        xAxis.axisLabel = xAxis.axisLabel || {};
        if (!xAxis.axisLabel.formatter) {
          xAxis.axisLabel.formatter = (value: number) => {
            if (value === 0) return '0';
            const scaled = value / xDivisor;
            return Number.isInteger(scaled) ? `${scaled}${xSuffix}` : `${scaled.toFixed(1)}${xSuffix}`;
          };
        }
      }
    }
  }

  // === G2: Compact data labels for series with label.show ===
  if (Array.isArray(series) && stats.max >= 10000) {
    for (const s of series) {
      if (s.label?.show && !s.label.formatter) {
        s.label.formatter = compactLabelFormatter;
      }
    }
  }

  // === Tooltip style unification (white card, box-shadow, confine) ===
  {
    const tip = ((opts as SmartBIChartOption)).tooltip || {};
    tip.confine = true;
    tip.backgroundColor = 'rgba(255, 255, 255, 0.95)';
    tip.borderColor = '#ebeef5';
    tip.borderWidth = 1;
    tip.textStyle = { ...((tip.textStyle as Record<string, unknown>) || {}), color: '#303133' };
    tip.extraCssText = 'box-shadow: 0 2px 12px rgba(0,0,0,0.1); backdrop-filter: blur(4px);';
    // P1: Compact number formatting in tooltip values (万/亿)
    if (!tip.valueFormatter) {
      tip.valueFormatter = compactTooltipFormatter;
    }
    ((opts as SmartBIChartOption)).tooltip = tip;
  }

  // === Mobile adaptive: shrink labels when container is narrow ===
  // Charts at ≤ 50% viewport width (2-col grid) get smaller text
  if (typeof window !== 'undefined' && window.innerWidth < 768) {
    if (xAxis && xAxis.axisLabel) {
      xAxis.axisLabel.fontSize = Math.min(xAxis.axisLabel.fontSize || 11, 9);
    }
    const yAxes2 = Array.isArray(yAxis) ? yAxis : (yAxis ? [yAxis] : []);
    for (const ax of yAxes2) {
      if (ax?.axisLabel) ax.axisLabel.fontSize = Math.min(ax.axisLabel.fontSize || 11, 9);
    }
    if (legend) {
      legend.textStyle = { ...((legend.textStyle as Record<string, unknown>) || {}), fontSize: 10 };
      legend.itemWidth = 12;
      legend.itemHeight = 8;
    }
  }
};

// 渲染当前激活 Tab 的所有图表（多图表仪表板 — 8-benchmark upgrade）
// T5.2: Intersection Observer — only render charts when they enter the viewport
let chartObserver: IntersectionObserver | null = null;
const pendingChartConfigs = new Map<string, { chart: Record<string, unknown>; idx: number; sheet: Record<string, unknown> }>();

function getOrCreateChartObserver(): IntersectionObserver {
  if (chartObserver) return chartObserver;
  chartObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const dom = entry.target as HTMLElement;
        const chartId = dom.id;
        const pending = pendingChartConfigs.get(chartId);
        if (!pending) continue;
        // Render now that it's visible
        renderSingleChart(dom, pending.chart, pending.idx, pending.sheet);
        pendingChartConfigs.delete(chartId);
        chartObserver?.unobserve(dom); // stop observing once rendered
      }
    },
    { rootMargin: '200px', threshold: 0.01 } // trigger 200px before entering viewport
  );
  return chartObserver;
}

const renderActiveCharts = () => {
  const activeSheetIndex = parseInt(activeTab.value);
  const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === activeSheetIndex);
  if (!activeSheet) return;

  const charts = getSheetCharts(activeSheet);
  const observer = getOrCreateChartObserver();

  // Render all charts eagerly — typical sheet has 5-8 charts, IntersectionObserver
  // was causing blank charts due to timing issues with contain:paint and fast scrolling.
  // For sheets with many charts (>12), defer later ones via observer.
  charts.forEach((chart, idx) => {
    const chartId = `chart-${activeSheet.sheetIndex}-${idx}`;
    const dom = document.getElementById(chartId);
    if (!dom) return;

    const config = chart.config;
    if (!config || isChartDataEmpty(config)) return;

    if (idx < 12) {
      renderSingleChart(dom, chart, idx, activeSheet);
    } else {
      pendingChartConfigs.set(chartId, { chart, idx, sheet: activeSheet });
      observer.observe(dom);
    }
  });
};

/** Render a single chart into its DOM container */
function renderSingleChart(dom: HTMLElement, chart: Record<string, unknown>, idx: number, activeSheet: Record<string, unknown>) {
    const config = chart.config as Record<string, unknown> | undefined;
    if (!config || isChartDataEmpty(config)) return;

    // Get all charts for cross-chart hover interactions
    const charts = getSheetCharts(activeSheet as unknown as SheetResult);

    // Deep-copy config before resolving so enhanceChartOption does NOT mutate
    // the original chart.config (whose series names must stay as raw column names
    // for extractYFieldsFromConfig → buildChart round-trips).
    let echartsOptions = resolveEChartsOptions(JSON.parse(JSON.stringify(config)));
    if (!echartsOptions) return;

    // Process __ANIM__/__FMT__ named references from Python
    echartsOptions = processEChartsOptions(echartsOptions);
    enhanceChartOption(echartsOptions, (activeSheet?.flowResult as { displayNameMap?: Record<string, string> } | undefined)?.displayNameMap);
    // H3: ARIA accessibility for screen readers (parity with DynamicChartRenderer)
    (echartsOptions as SmartBIChartOption).aria = { enabled: true, decal: { show: true } };
    // Strip ECharts-internal title — Vue card header already displays it
    delete echartsOptions.title;

    // D4: 全零图表检测 — 当95%+数据为零时添加提示水印
    const eSeries = ((echartsOptions as SmartBIChartOption)).series;
    if (Array.isArray(eSeries) && eSeries[0]?.type !== 'pie') {
      let totalVals = 0, zeroVals = 0;
      for (const s of eSeries) {
        if (!Array.isArray(s?.data)) continue;
        for (const d of s.data) {
          totalVals++;
          const dRec = d as Record<string, unknown> | unknown[];
          const v = typeof d === 'number' ? d : Number((dRec as Record<string, unknown>)?.value ?? (Array.isArray(d) ? d[1] : undefined) ?? 0);
          if (v === 0) zeroVals++;
        }
      }
      if (totalVals > 5 && zeroVals / totalVals > 0.9) {
        // Add watermark-style hint
        ((echartsOptions as SmartBIChartOption)).graphic = [
          {
            type: 'text',
            left: 'center',
            top: '38%',
            style: {
              text: '本项数据集中在少数项目',
              fontSize: 13,
              fill: 'rgba(150,150,150,0.6)',
              fontWeight: 'normal',
            },
            silent: true,
          },
          {
            type: 'text',
            left: 'center',
            top: '46%',
            style: {
              text: '可拖动下方滑块或切换维度查看',
              fontSize: 11,
              fill: 'rgba(180,180,180,0.5)',
            },
            silent: true,
          }
        ];
      }
    }

    // Apply anomaly overlay if available
    const anomalies = ((config as unknown as SmartBIChartOption)).anomalies || ((chart as unknown as SmartBIChartItem)).anomalies;
    if (anomalies) {
      applyAnomalyOverlay(echartsOptions, anomalies);
    }

    try {
      // ECharts instance reuse (Phase 2.2) — avoid dispose+init cycle
      let instance = echarts.getInstanceByDom(dom);
      if (!instance) {
        instance = echarts.init(dom, echartsThemeName.value);
      }
      // G-9: Connect chart instances in same sheet for cross-chart tooltip sync
      const groupName = `sheet-${activeSheet.sheetIndex}`;
      instance.group = groupName;
      echarts.connect(groupName);
      // Suppress ECharts false-positive "alignTicks" warning on radar indicator axes
      // (radar internal axes set alignTicks:true by default; our nice-rounded max values ARE readable)
      const chartType = ((echartsOptions as SmartBIChartOption)).series?.[0]?.type
        ?? (Array.isArray(((echartsOptions as SmartBIChartOption)).series) ? ((echartsOptions as SmartBIChartOption)).series[0]?.type : undefined);
      if (chartType === 'radar') {
        const _origWarn = console.warn;
        // eslint-disable-next-line no-console
        console.warn = (...args: unknown[]) => {
          if (typeof args[0] === 'string' && args[0].includes('alignTicks')) return;
          _origWarn.apply(console, args);
        };
        try {
          instance.setOption(echartsOptions, { notMerge: true });
        } finally {
          console.warn = _origWarn;
        }
      } else {
        instance.setOption(echartsOptions, { notMerge: true });
      }

      // R-14: Apply visual emphasis if a cross-chart filter is active
      if (activeFilter.value) {
        const filterVal = activeFilter.value.value;
        // Find matching data index from xAxis
        const xData = (echartsOptions as SmartBIChartOption)?.xAxis?.data;
        if (Array.isArray(xData)) {
          const matchIdx = xData.indexOf(filterVal);
          if (matchIdx >= 0) {
            // Downplay all, then highlight the matched data point
            instance.dispatchAction({ type: 'downplay' });
            instance.dispatchAction({ type: 'highlight', dataIndex: matchIdx });
          }
        }
        // For pie charts, match by name
        const seriesArr = (echartsOptions as SmartBIChartOption)?.series;
        if (Array.isArray(seriesArr)) {
          for (const s of seriesArr) {
            if (s.type === 'pie' && Array.isArray(s.data)) {
              const pieIdx = (s.data as Record<string, unknown>[]).findIndex((d) => d.name === filterVal);
              if (pieIdx >= 0) {
                instance.dispatchAction({ type: 'downplay' });
                instance.dispatchAction({ type: 'highlight', dataIndex: pieIdx });
              }
            }
          }
        }
      }

      // Click events: Ctrl+Click = filter, normal Click = drill-down
      instance.off('click');
      instance.on('click', (rawParams: unknown) => {
        const params = rawParams as { event?: { event?: { ctrlKey?: boolean; metaKey?: boolean } } } & Record<string, unknown>;
        if (params.event?.event?.ctrlKey || params.event?.event?.metaKey) {
          applyChartFilter(activeSheet as unknown as SheetResult, params as Record<string, unknown>);
        } else {
          handleChartDrillDown(activeSheet as unknown as SheetRef, idx, params as Record<string, unknown>);
        }
      });

      // P0-B: Throttled hover cross-filtering with dispatchAction (100ms throttle)
      const chartKey = `chart-${activeSheet.sheetIndex}-${idx}`;
      instance.off('mouseover');
      instance.on('mouseover', (rawParams: unknown) => {
        const params = rawParams as { name?: string; seriesName?: string };
        const hoverValue = params.name || params.seriesName;
        if (!hoverValue) return;
        if (hoverThrottleTimers.has(chartKey)) return; // throttle: skip if pending
        hoverThrottleTimers.set(chartKey, setTimeout(() => { hoverThrottleTimers.delete(chartKey); }, 100));
        charts.forEach((_c, sibIdx) => {
          if (sibIdx === idx) return;
          const sibId = `chart-${activeSheet.sheetIndex}-${sibIdx}`;
          const sibDom = document.getElementById(sibId);
          if (!sibDom) return;
          const sibInstance = echarts.getInstanceByDom(sibDom);
          if (!sibInstance) return;
          const sibOpt = sibInstance.getOption() as Record<string, unknown>;
          // Bar/line: match xAxis by name (not index — safe with DataZoom)
          const xData = (sibOpt?.xAxis as Array<{ data?: unknown[] }> | undefined)?.[0]?.data;
          if (Array.isArray(xData)) {
            const matchIdx = xData.indexOf(hoverValue);
            if (matchIdx >= 0) {
              sibInstance.dispatchAction({ type: 'highlight', dataIndex: matchIdx });
            }
          }
          // Pie: match by name
          const sibSeries = sibOpt?.series;
          if (Array.isArray(sibSeries)) {
            sibSeries.forEach((s: Record<string, unknown>) => {
              if (s.type === 'pie' && Array.isArray(s.data)) {
                const pieIdx = (s.data as Record<string, unknown>[]).findIndex((d) => d.name === hoverValue);
                if (pieIdx >= 0) sibInstance.dispatchAction({ type: 'highlight', dataIndex: pieIdx });
              }
            });
          }
        });
      });
      instance.off('mouseout');
      instance.on('mouseout', () => {
        charts.forEach((_c, sibIdx) => {
          if (sibIdx === idx) return;
          const sibId = `chart-${activeSheet.sheetIndex}-${sibIdx}`;
          const sibDom = document.getElementById(sibId);
          if (!sibDom) return;
          const sibInstance = echarts.getInstanceByDom(sibDom);
          if (sibInstance) sibInstance.dispatchAction({ type: 'downplay' });
        });
      });
    } catch (error) {
      console.error(`Failed to render chart chart-${activeSheet.sheetIndex}-${idx}:`, error);
    }
}

// 向后兼容：旧版渲染入口
const renderActiveChart = () => renderActiveCharts();

// 监听 Tab 切换，带 150ms debounce 防止快速切换重复渲染 (Phase 2.3)
// P6: 切换编排模式时渲染 builder 内的图表
let layoutRenderPending = false;
watch(layoutEditMode, (isBuilder) => {
  if (isBuilder && !layoutRenderPending) {
    layoutRenderPending = true;
    nextTick(() => {
      const tryRender = () => {
        const activeSheetIndex = parseInt(activeTab.value);
        const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === activeSheetIndex);
        if (!activeSheet) { layoutRenderPending = false; return; }
        const layout = getCachedLayout(activeSheet);
        const charts = getSheetCharts(activeSheet);
        const firstCardDom = layout.cards.length ? document.getElementById(`builder-chart-${layout.cards[0].id}`) : null;
        if (!firstCardDom) {
          // DOM not ready yet, retry on next frame
          requestAnimationFrame(tryRender);
          return;
        }
        layout.cards.forEach((card, i) => {
          if (i >= charts.length) return;
          const dom = document.getElementById(`builder-chart-${card.id}`);
          if (!dom) return;
          try {
            let instance = echarts.getInstanceByDom(dom);
            if (!instance) instance = echarts.init(dom, echartsThemeName.value);
            const config = charts[i].config;
            if (config) {
              const processed = processEChartsOptions(JSON.parse(JSON.stringify(config)) as Record<string, unknown>);
              enhanceChartOption(processed, activeSheet?.flowResult?.displayNameMap);
              delete processed.title;
              instance.setOption(processed, { notMerge: true });
            }
          } catch (e) {
            console.error(`Failed to render builder chart ${card.id}:`, e);
          }
        });
        layoutRenderPending = false;
      };
      requestAnimationFrame(tryRender);
    });
  } else if (!isBuilder) {
    // Switching back to standard mode — re-render charts with DOM-ready check
    nextTick(() => {
      const tryRenderStandard = () => {
        const activeSheetIndex = parseInt(activeTab.value);
        const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === activeSheetIndex);
        if (!activeSheet) return;
        const charts = getSheetCharts(activeSheet);
        if (charts.length === 0) return;
        const firstDom = document.getElementById(`chart-${activeSheetIndex}-0`);
        if (!firstDom) {
          requestAnimationFrame(tryRenderStandard);
          return;
        }
        renderActiveCharts();
      };
      setTimeout(() => requestAnimationFrame(tryRenderStandard), 300);
    });
  }
});

watch(activeTab, (newTab, oldTab) => {
  // Clear active filter on tab switch
  activeFilter.value = null;
  globalFilterDimension.value = '';
  globalFilterValues.value = [];
  layoutEditMode.value = false; // P6: reset to standard mode on tab switch

  // Clear hover throttle timers from previous tab to avoid leaked closures
  clearHoverThrottleTimers();

  // T4.1: Clear ECharts instances for previous tab but DON'T dispose —
  // instances are reused on tab switch back (avoid dispose+init cycle ~500ms overhead).
  // Instances are disposed only when component unmounts or after 60s idle.
  if (oldTab) {
    (rootRef.value || document).querySelectorAll(`[id^="chart-${oldTab}-"]`).forEach(dom => {
      const inst = echarts.getInstanceByDom(dom as HTMLElement);
      if (inst) {
        inst.off('click');
        inst.off('mouseover');
        inst.off('mouseout');
        inst.clear(); // clear options but keep instance alive for reuse
      }
    });
  }

  if (renderDebounceTimer) clearTimeout(renderDebounceTimer);
  renderDebounceTimer = setTimeout(() => {
    nextTick(() => {
      renderActiveCharts();

      // 检查当前 tab 的 sheet 是否需要 enrichment
      const activeSheetIndex = parseInt(activeTab.value);
      const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === activeSheetIndex);
      if (activeSheet && !isIndexSheet(activeSheet) && activeSheet.uploadId) {
        const hasCharts = hasChartData(activeSheet);
        const hasAI = !!activeSheet.flowResult?.aiAnalysis;
        if ((!hasCharts || !hasAI) && !enrichingSheets.value.has(activeSheetIndex) && !enrichedSheets.value.has(activeSheetIndex)) {
          enrichSheet(activeSheet);
        }
      }
    });
  }, 150);
});

// H1: Re-render all charts when theme toggles (ECharts doesn't support runtime theme swap)
watch(echartsThemeName, () => {
  const root = rootRef.value || document;
  root.querySelectorAll('[id^="chart-"]').forEach(dom => {
    const inst = echarts.getInstanceByDom(dom as HTMLElement);
    if (inst) inst.dispose();
  });
  nextTick(() => renderActiveCharts());
});

// 渲染单个图表
const renderChart = (sheet: SheetResult) => {
  const chartId = `chart-${sheet.sheetIndex}`;
  const chartDom = document.getElementById(chartId);

  if (!chartDom) {
    console.warn(`Chart container not found: ${chartId}`);
    return;
  }

  const chartConfig = sheet.flowResult?.chartConfig;
  if (!chartConfig) {
    console.warn('No chartConfig found');
    return;
  }

  // 检测空数据，跳过渲染
  if (isChartDataEmpty(chartConfig)) {
    console.warn('Chart data is empty, skipping render');
    return;
  }

  // 确定 ECharts options
  let echartsOptions: Record<string, unknown> | null = null;

  // Case 1: chartConfig 本身就是完整 ECharts 配置（来自 Python enrichment）
  if (chartConfig.series || chartConfig.xAxis || chartConfig.yAxis) {
    echartsOptions = chartConfig;
  }
  // Case 2: Java 返回的 { chartOptions: "JSON string" } 格式
  else if (chartConfig.chartOptions && typeof chartConfig.chartOptions === 'string') {
    try {
      echartsOptions = JSON.parse(chartConfig.chartOptions);
    } catch (e) {
      console.warn('Failed to parse chartOptions JSON string:', e);
    }
  }
  // Case 3: Java 返回的 { options: {...} } 格式
  else if (chartConfig.options) {
    echartsOptions = chartConfig.options as Record<string, unknown>;
  }
  // Case 4: 有 data 但没有 options，尝试构建基础图表
  else if (chartConfig.data) {
    echartsOptions = buildBasicOptions(String(chartConfig.chartType || 'line'), chartConfig.data as Record<string, unknown>);
  }

  if (!echartsOptions) {
    console.warn('No chart options could be built');
    return;
  }

  try {
    // 销毁旧实例避免重复初始化
    const existingInstance = echarts.getInstanceByDom(chartDom);
    if (existingInstance) {
      existingInstance.dispose();
    }
    const myChart = echarts.init(chartDom, echartsThemeName.value);
    myChart.setOption(echartsOptions);
  } catch (error) {
    console.error('Failed to render chart:', error);
  }
};

// 根据数据构建基础 ECharts 配置
const buildBasicOptions = (chartType: string, data: Record<string, unknown>): Record<string, unknown> | null => {

  // 从数据中提取可能的字段
  if (!data || typeof data !== 'object') return null;

  // 尝试识别 x 轴和 y 轴数据
  const keys = Object.keys(data);
  if (keys.length === 0) return null;

  // 简单策略：第一个数组作为系列数据
  for (const key of keys) {
    if (Array.isArray(data[key])) {
      return {
        title: { text: chartType + ' Chart' },
        tooltip: {},
        xAxis: { type: 'category', data: (data[key] as unknown[]).map((_: unknown, i: number) => i + 1) },
        yAxis: { type: 'value' },
        series: [{ type: chartType.toLowerCase() || 'line', data: data[key] }]
      };
    }
  }

  return null;
};

// === Per-chart mini insight — replaced by Tier 1 getChartInsightText (U2, 2026-06-10) ===
// getChartMiniInsight() removed; ChartInsight is registered above near imports.

// 获取 AI 分析
const getAIAnalysis = (sheet: SheetResult): string => {
  const chartConfigAi = sheet.flowResult?.chartConfig?.aiAnalysis;
  return sheet.flowResult?.aiAnalysis ||
         (typeof chartConfigAi === 'string' ? chartConfigAi : '') ||
         '暂无 AI 分析';
};

// 格式化分析结果 — 增强版：按句号分段 + 关键数字加粗
const formatAnalysis = (analysis: string): string => {
  return formatAIText(analysis);
};

// 将 AI 文本按中文句号分段，并对关键数字加粗显示
const formatAIText = (text: string): string => {
  if (!text) return '';

  // Step 1: 先处理 markdown 标记
  let formatted = text
    .replace(/\*\*trend\*\*/gi, '<strong>趋势</strong>')
    .replace(/\*\*anomaly\*\*/gi, '<strong>异常</strong>')
    .replace(/\*\*recommendation\*\*/gi, '<strong>建议</strong>')
    .replace(/\*\*comparison\*\*/gi, '<strong>对比</strong>')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/【(.*?)】/g, '<span class="highlight">【$1】</span>');

  // Step 2: 按换行符切分段落，再对每段按句号细分
  const paragraphs = formatted.split(/\n+/);
  const htmlParts: string[] = [];
  for (const para of paragraphs) {
    const trimmed = para.trim();
    if (!trimmed) continue;
    // 按中文句号分段（保留句号在每句末尾）
    const sentences = trimmed.split(/(?<=。)/);
    const validSentences = sentences.filter(s => s.trim());
    if (validSentences.length > 1) {
      // 多句：每句一个 <p>
      for (const s of validSentences) {
        const sTrimmed = s.trim();
        if (sTrimmed) htmlParts.push(`<p>${sTrimmed}</p>`);
      }
    } else {
      htmlParts.push(`<p>${trimmed}</p>`);
    }
  }

  formatted = htmlParts.join('') || `<p>${formatted}</p>`;

  // Step 3: 关键数字加粗（百分比、万元金额、普通金额）
  formatted = formatted.replace(/(\d+\.?\d*%)/g, '<strong>$1</strong>');
  formatted = formatted.replace(/(\d+\.?\d*万)/g, '<strong>$1</strong>');
  formatted = formatted.replace(/(\d[\d,]*\.?\d*元)/g, '<strong>$1</strong>');
  // 列表序号加粗
  formatted = formatted.replace(/(<p>)(\d+\.\s)/g, '$1<strong>$2</strong>');

  // Step 4: 清理空段落
  formatted = formatted.replace(/<p>\s*<\/p>/g, '');

  return DOMPurify.sanitize(formatted);
};

// 刷新分析：清除缓存后强制重新 enrichment
const handleRefreshAnalysis = async (sheet: SheetResult) => {
  if (!sheet.uploadId || enrichingSheets.value.has(sheet.sheetIndex)) return;
  // 清除前端 enriched 状态
  enrichedSheets.value.delete(sheet.sheetIndex);
  cachedAtMap.value.delete(sheet.uploadId);
  // 清除后端缓存
  await invalidateAnalysisCache(sheet.uploadId);
  // 强制重新执行 enrichment
  enrichSheet(sheet, true);
};

// 缓存状态提示文本
const getCacheHint = (sheet: SheetResult): string => {
  if (!sheet.uploadId) return '';
  const cachedAt = cachedAtMap.value.get(sheet.uploadId);
  if (!cachedAt) return '';
  try {
    const d = new Date(cachedAt);
    const pad = (n: number) => String(n).padStart(2, '0');
    const timeStr = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    return `分析结果来自缓存（${timeStr}），点击"刷新分析"获取最新结果`;
  } catch {
    return '分析结果来自缓存，点击"刷新分析"获取最新结果';
  }
};

// 检测 ECharts chartConfig 中数据是否为空
const isChartDataEmpty = (chartConfig: Record<string, unknown>): boolean => {
  if (!chartConfig || Object.keys(chartConfig).length === 0) return true;

  // 辅助：检查 series 数组是否全为空数据
  const isSeriesEmpty = (series: unknown) => {
    const arr = Array.isArray(series) ? series : [series];
    return arr.every((s: Record<string, unknown>) => !s.data || (s.data as unknown[]).length === 0);
  };

  // Case 1: 直接 ECharts options 格式（有 series）
  if (chartConfig.series) {
    return isSeriesEmpty(chartConfig.series);
  }

  // Case 2: Java 返回的 { chartOptions: "JSON string" } 格式
  if (chartConfig.chartOptions && typeof chartConfig.chartOptions === 'string') {
    try {
      const parsed = JSON.parse(chartConfig.chartOptions as string);
      if (parsed.series) return isSeriesEmpty(parsed.series);
    } catch { /* ignore parse error */ }
  }

  // Case 3: Java 返回的 { options: {...} } 格式
  if ((chartConfig.options as Record<string, unknown> | undefined)?.series) {
    return isSeriesEmpty((chartConfig.options as Record<string, unknown>).series);
  }

  // Case 4: 有 data 但没有有效数据
  if (chartConfig.data && typeof chartConfig.data === 'object') {
    const values = Object.values(chartConfig.data as Record<string, unknown>);
    return values.every((v: unknown) => !Array.isArray(v) || v.length === 0);
  }

  return false;
};

// P2.1: 使用 idle callback 预缓存下一个 sheet
const idleEnrichNext = (currentSheetIndex: number) => {
  // Polyfill for requestIdleCallback (not available in all browsers)
  const idleCb = (window as unknown as { requestIdleCallback?: (fn: () => void) => number }).requestIdleCallback || ((fn: Function) => setTimeout(fn, 2000));

  idleCb(() => {
    // 仅在网络空闲时预加载（没有其他正在进行的 enrichment）
    if (enrichingSheets.value.size > 0) {
      return;
    }

    // 查找下一个未 enriched 的 sheet
    const nextSheet = uploadedSheets.value.find(
      s => s.sheetIndex > currentSheetIndex
        && !enrichedSheets.value.has(s.sheetIndex)
        && !enrichingSheets.value.has(s.sheetIndex)
        && !isIndexSheet(s)
        && s.uploadId
    );

    if (nextSheet?.uploadId) {
      // 静默预加载（无加载 UI）
      const preCacheUploadId = nextSheet.uploadId;
      enrichSheetAnalysis(preCacheUploadId).then(result => {
        if (result && result.success) {
          // STALENESS GUARD: pre-cache fires async; user may have switched
          // uploads in the meantime. Verify uploadId still matches.
          const sheet = uploadedSheets.value.find(s => s.sheetIndex === nextSheet.sheetIndex);
          if (sheet && sheet.uploadId === preCacheUploadId) {
            if (!sheet.flowResult) {
              sheet.flowResult = {};
            }
            // 更新多图表数据
            if (result.charts?.length) {
              sheet.flowResult.charts = result.charts;
              sheet.flowResult.chartConfig = result.charts[0].config; // 向后兼容
            }
            // 更新 KPI 摘要
            if (result.kpiSummary) {
              sheet.flowResult.kpiSummary = result.kpiSummary;
            }
            // 更新 AI 分析
            if (result.aiAnalysis) {
              sheet.flowResult.aiAnalysis = result.aiAnalysis;
            }
            // 更新结构化 AI
            if (result.structuredAI) {
              sheet.flowResult.structuredAI = result.structuredAI;
            }
            // 更新 displayNameMap
            if (result.displayNameMap) {
              sheet.flowResult.displayNameMap = result.displayNameMap;
            }
            // 缓存原始数据
            if (result.rawData?.length && nextSheet.uploadId) {
              sheetRawDataCache.set(nextSheet.uploadId, result.rawData);
            }

            // 标记为已 enriched
            enrichedSheets.value.add(nextSheet.sheetIndex);
          }
        }
      }).catch(err => {
        // 静默忽略预缓存错误（不影响用户体验）
        console.warn(`[P2.1] Pre-cache failed for sheet ${nextSheet.sheetIndex}:`, err);
      });
    }
  });
};

// 通过前端驱动 Python 服务补充 Sheet 的图表和 AI 分析 (P0: 渐进式渲染)
const enrichSheet = async (sheet: SheetResult, forceRefresh = false) => {
  const sheetIndex = sheet.sheetIndex;
  const uploadId = sheet.uploadId;
  if (!uploadId || enrichingSheets.value.has(sheetIndex)) return;

  enrichingSheets.value.add(sheetIndex);
  // Initialize progressive phase tracking
  enrichPhases.value.set(sheetIndex, { kpi: false, charts: 0, chartsTotal: 0, ai: false });

  try {
    const result: EnrichResult = await enrichSheetAnalysis(uploadId, forceRefresh, (progress: EnrichProgress) => {
      // STALENESS GUARD: After upload-dropdown switch, the sheet at this
      // sheetIndex may belong to a NEW upload. Verify uploadId still matches
      // before writing — otherwise old upload's data bleeds into new upload's
      // KPI cards (the "review-carryover" false positive caught Apr 24 2026).
      const currentSheet = uploadedSheets.value.find(s => s.sheetIndex === sheetIndex);
      if (!currentSheet || currentSheet.uploadId !== uploadId) return;
      if (!currentSheet.flowResult) currentSheet.flowResult = {};

      const phase = enrichPhases.value.get(sheetIndex);

      if (progress.phase === 'kpi' && progress.partial.kpiSummary) {
        currentSheet.flowResult.kpiSummary = progress.partial.kpiSummary;
        if (progress.partial.financialMetrics !== undefined) {
          currentSheet.flowResult.financialMetrics = progress.partial.financialMetrics;
        }
        if (phase) {
          phase.kpi = true;
          phase.chartsTotal = progress.partial.chartsTotal || 0;
        }
      }

      if (progress.phase === 'chart-single' && progress.partial.charts?.length) {
        currentSheet.flowResult.charts = progress.partial.charts;
        currentSheet.flowResult.chartConfig = progress.partial.charts[0].config;
        if (phase) phase.charts = progress.partial.charts.length;
        // Render charts immediately if this is the active tab
        if (parseInt(activeTab.value) === sheetIndex) {
          nextTick(() => renderActiveCharts());
        }
      }

      // T1.1: Handle streaming AI text chunks — show progressively before final parse
      if (progress.phase === 'ai-streaming' && progress.partial.aiStreamChunk) {
        if (!currentSheet.flowResult._streamingAIText) {
          currentSheet.flowResult._streamingAIText = '';
        }
        currentSheet.flowResult._streamingAIText += progress.partial.aiStreamChunk;
        // Show raw streaming text as preview (will be replaced by structured result)
        currentSheet.flowResult.aiAnalysis = currentSheet.flowResult._streamingAIText;
      }

      if (progress.phase === 'ai') {
        if (progress.partial.aiAnalysis) {
          currentSheet.flowResult.aiAnalysis = progress.partial.aiAnalysis;
        }
        if (progress.partial.structuredAI) {
          currentSheet.flowResult.structuredAI = progress.partial.structuredAI;
        }
        // Clear streaming preview
        delete currentSheet.flowResult._streamingAIText;
        if (phase) phase.ai = true;
      }
    });

    if (result.success) {
      // STALENESS GUARD (final sync): Same risk as the progress callback above —
      // user may have switched uploads while the request was in flight.
      const currentSheet = uploadedSheets.value.find(s => s.sheetIndex === sheetIndex);
      if (currentSheet && currentSheet.uploadId === uploadId) {
        if (!currentSheet.flowResult) currentSheet.flowResult = {};
        // Final sync — ensure all data is set (handles cache-hit path where onProgress fires 'complete' only)
        if (result.charts?.length) {
          currentSheet.flowResult.charts = result.charts;
          currentSheet.flowResult.chartConfig = result.charts[0].config;
        } else if (result.chartConfig) {
          currentSheet.flowResult.chartConfig = result.chartConfig;
        }
        // Auto-retry empty charts (works for both cached and fresh results)
        if (currentSheet.flowResult.charts?.length) {
          const emptyIdx = currentSheet.flowResult.charts
            .map((c: { config: Record<string, unknown> }, i: number) => {
              if (!c.config || Object.keys(c.config).length === 0) return i;
              const series = c.config.series;
              if (!series) return i;
              const arr = Array.isArray(series) ? series : [series];
              return arr.every((s: Record<string, unknown>) => !s.data || (s.data as unknown[]).length === 0) ? i : -1;
            })
            .filter((i: number) => i >= 0);
          if (emptyIdx.length > 0) {
            // Fire-and-forget: auto-retry empty charts in background
            (async () => {
              try {
                for (const idx of emptyIdx) {
                  await handleRefreshChart(currentSheet, idx);
                }
                if (parseInt(activeTab.value) === sheetIndex) {
                  await nextTick();
                  renderActiveCharts();
                }
              } catch { /* non-critical */ }
            })();
          }
        }
        if (result.kpiSummary) currentSheet.flowResult.kpiSummary = result.kpiSummary;
        if (result.aiAnalysis) currentSheet.flowResult.aiAnalysis = result.aiAnalysis;
        if (result.structuredAI) currentSheet.flowResult.structuredAI = result.structuredAI;
        if (result.displayNameMap) currentSheet.flowResult.displayNameMap = result.displayNameMap;
        // Persist rawData for cross-filtering & Excel export
        if (result.rawData?.length && uploadId) {
          sheetRawDataCache.set(uploadId, result.rawData);
        } else if (uploadId && !sheetRawDataCache.has(uploadId)) {
          getUploadTableData(uploadId, 0, 2000).then(res => {
            if (res.success && res.data?.data?.length) {
              sheetRawDataCache.set(uploadId, res.data.data as Record<string, unknown>[]);
            }
          }).catch(() => {});
        }
      }
      enrichedSheets.value.add(sheetIndex);
      // Mark all phases complete
      const phase = enrichPhases.value.get(sheetIndex);
      if (phase) { phase.kpi = true; phase.ai = true; phase.charts = phase.chartsTotal; }

      // A6: Run food industry detection on first enriched sheet
      if (!foodIndustryDetection.value) {
        // Try rawData from enrichment result, sheetRawDataCache, or fetch
        let detectData = result.rawData;
        if (!detectData?.length && uploadId && sheetRawDataCache.has(uploadId)) {
          detectData = sheetRawDataCache.get(uploadId);
        }
        if (!detectData?.length && uploadId) {
          // Cache hit path — rawData not available, fetch minimal sample for detection
          try {
            const tableRes = await getUploadTableData(uploadId, 0, 20);
            if (tableRes.success && tableRes.data?.data?.length) {
              detectData = tableRes.data.data as Record<string, unknown>[];
            }
          } catch { /* non-critical */ }
        }
        if (detectData?.length) {
          const colNames = Object.keys(detectData[0]);
          const sampleRows = detectData.slice(0, 15);
          foodIndustryDetection.value = detectFoodIndustryLocal(colNames, sampleRows);
        }
      }

      // Track cache status for UI hint
      if (result.cached && result.cachedAt) {
        cachedAtMap.value.set(uploadId, result.cachedAt);
      } else {
        cachedAtMap.value.delete(uploadId);
      }

      // Render charts (final pass)
      if (parseInt(activeTab.value) === sheetIndex) {
        await nextTick();
        renderActiveCharts();
      }

      if (!tourDataReady.value && hasChartData(sheet) && result.aiAnalysis) {
        tourDataReady.value = true;
      }

      tryAutoSaveDemoCache();
      idleEnrichNext(sheetIndex);
    } else {
      console.warn(`[Enrich] Sheet ${sheetIndex} enrichment failed:`, result.error);
      ElMessage.warning(`Sheet "${sheet.sheetName}" 图表增强失败: ${result.error || '未知错误'}`);
    }
  } catch (error) {
    console.error(`[Enrich] Sheet ${sheetIndex} error:`, error);
    ElMessage.warning(`Sheet "${sheet.sheetName}" 图表增强异常，请检查 Python 服务是否运行`);
  } finally {
    enrichingSheets.value.delete(sheetIndex);
  }
};

// ========== P1: Template Application ==========

/** Apply a food industry analysis template to the active sheet */
const applyTemplate = async (template: FoodTemplate) => {
  const sheetIndex = parseInt(activeTab.value);
  const sheet = uploadedSheets.value.find(s => s.sheetIndex === sheetIndex);
  if (!sheet?.uploadId) {
    ElMessage.warning('请先选择一个数据表');
    return;
  }

  activeTemplate.value = template.id;
  enrichingSheets.value.add(sheetIndex);

  try {
    // Get raw data (from cache or fetch)
    let rawData = sheetRawDataCache.get(sheet.uploadId);
    if (!rawData) {
      const tableRes = await getUploadTableData(sheet.uploadId, 0, 2000);
      if (!tableRes.success || !tableRes.data?.data?.length) {
        ElMessage.warning('无法获取表格数据');
        return;
      }
      rawData = renameMeaninglessColumns(tableRes.data.data as Record<string, unknown>[]);
      sheetRawDataCache.set(sheet.uploadId, rawData);
    }

    // Detect label field for column mapping
    const allKeys = Object.keys(rawData[0]);
    const catCols = allKeys.filter(k => {
      const vals = rawData!.slice(0, 10).map(r => r[k]);
      return vals.every(v => typeof v === 'string' || v == null);
    });
    const labelField = catCols[0] || allKeys[0];

    // Map template columns to actual data columns
    const plans = mapColumnsToTemplate(rawData, template, labelField);
    if (!plans || plans.length === 0) {
      ElMessage.warning(`模板 "${template.name}" 无法匹配当前数据列，请检查数据格式`);
      return;
    }

    // Build charts from template plan (skip recommendChart)
    const charts: Array<{ chartType: string; title: string; config: Record<string, unknown>; xField?: string }> = [];
    for (const plan of plans) {
      const res = await buildChart({
        chartType: plan.chartType,
        data: plan.data,
        xField: plan.xField,
        yFields: plan.yFields,
        title: plan.title,
      });
      if (res.success && res.option) {
        charts.push({ chartType: plan.chartType, title: plan.title, config: res.option, xField: plan.xField });
      }
    }

    if (charts.length === 0) {
      ElMessage.warning('模板图表构建失败');
      return;
    }

    // Apply to sheet
    if (!sheet.flowResult) sheet.flowResult = {};
    sheet.flowResult.charts = charts;
    sheet.flowResult.chartConfig = charts[0].config;
    enrichedSheets.value.add(sheetIndex);

    await nextTick();
    renderActiveCharts();
    ElMessage.success(`已应用模板 "${template.name}"，生成 ${charts.length} 个图表`);
  } catch (error) {
    console.error('Template apply error:', error);
    ElMessage.error('模板应用失败');
  } finally {
    enrichingSheets.value.delete(sheetIndex);
  }
};

// ========== Chart Switching & Refresh (Phase 3) ==========

/** Track which chart is currently being switched/refreshed */
const switchingChart = ref<{ sheetIndex: number; chartIndex: number } | null>(null);

/** Extract yFields from an ECharts config object by reading series names */
const extractYFieldsFromConfig = (config: Record<string, unknown>): string[] => {
  const series = config?.series as Array<{ name?: string }> | undefined;
  if (Array.isArray(series)) {
    return series.map(s => s.name).filter(Boolean) as string[];
  }
  return [];
};

/** Switch a single chart's type */
const handleSwitchChartType = async (sheet: SheetResult, chartIndex: number, newType: string) => {
  const charts = getSheetCharts(sheet);
  const chart = charts[chartIndex];
  if (!chart || !sheet.uploadId) return;

  switchingChart.value = { sheetIndex: sheet.sheetIndex, chartIndex };
  try {
    // Get raw data for chart rebuilding, use cache if available
    let rawData = sheetRawDataCache.get(sheet.uploadId);
    if (!rawData) {
      const tableRes = await getUploadTableData(sheet.uploadId, 0, 2000);
      if (!tableRes.success || !tableRes.data?.data?.length) return;
      rawData = renameMeaninglessColumns(tableRes.data.data as Record<string, unknown>[]);
      sheetRawDataCache.set(sheet.uploadId, rawData);
    }

    // Extract yFields from current chart config series names
    const yFields = extractYFieldsFromConfig(chart.config);

    const result = await buildChart({
      chartType: newType,
      data: rawData.slice(0, 200),
      xField: chart.xField,
      yFields: yFields.length > 0 ? yFields : undefined,
      title: chart.title
    });

    if (result.success && result.option) {
      const currentSheet = uploadedSheets.value.find(s => s.sheetIndex === sheet.sheetIndex);
      if (currentSheet?.flowResult?.charts?.[chartIndex]) {
        currentSheet.flowResult.charts[chartIndex] = {
          ...currentSheet.flowResult.charts[chartIndex],
          chartType: newType,
          config: result.option
        };
        await nextTick();
        renderActiveCharts();
      }
    } else {
      ElMessage.warning('切换图表类型失败: ' + (result.error || '未知错误'));
    }
  } catch (e) {
    console.error('Chart type switch failed:', e);
    ElMessage.warning('图表切换失败');
  } finally {
    switchingChart.value = null;
  }
};

/** Refresh a single chart with a new random recommendation */
const handleRefreshChart = async (sheet: SheetResult, chartIndex: number) => {
  const charts = getSheetCharts(sheet);
  const chart = charts[chartIndex];
  if (!chart || !sheet.uploadId) return;

  switchingChart.value = { sheetIndex: sheet.sheetIndex, chartIndex };
  try {
    let rawData = sheetRawDataCache.get(sheet.uploadId);
    if (!rawData) {
      const tableRes = await getUploadTableData(sheet.uploadId, 0, 2000);
      if (!tableRes.success || !tableRes.data?.data?.length) return;
      rawData = renameMeaninglessColumns(tableRes.data.data as Record<string, unknown>[]);
      sheetRawDataCache.set(sheet.uploadId, rawData);
    }

    // Get current chart types to exclude (filter nulls to avoid 422)
    const currentTypes = charts.map(c => c.chartType).filter(Boolean) as string[];

    const recRes = await smartRecommendChart({
      data: rawData.slice(0, 100),
      excludeTypes: currentTypes,
      maxRecommendations: 3
    });

    if (recRes.success && recRes.recommendations?.length) {
      // Auto-fill yFields from numeric columns if LLM returned empty
      const inferYFields = (rec: typeof recRes.recommendations[0]) => {
        if (rec.yFields && rec.yFields.length > 0) return rec.yFields;
        // Infer from data: pick numeric columns excluding xField
        const sample = rawData[0] as Record<string, unknown> | undefined;
        if (!sample) return [];
        return Object.entries(sample)
          .filter(([k, v]) => typeof v === 'number' && k !== rec.xField)
          .slice(0, 5)
          .map(([k]) => k);
      };
      // Try each recommendation until one produces non-empty series
      let replaced = false;
      for (const rec of recRes.recommendations) {
        const yFields = inferYFields(rec);
        if (yFields.length === 0) continue;
        const buildRes = await buildChart({
          chartType: rec.chartType,
          data: rawData.slice(0, 200),
          xField: rec.xField,
          yFields,
          title: rec.title || chart.title
        });

        if (buildRes.success && buildRes.option && !isChartDataEmpty(buildRes.option)) {
          const currentSheet = uploadedSheets.value.find(s => s.sheetIndex === sheet.sheetIndex);
          if (currentSheet?.flowResult?.charts?.[chartIndex]) {
            currentSheet.flowResult.charts[chartIndex] = {
              chartType: rec.chartType,
              title: rec.title || chart.title,
              config: buildRes.option,
              xField: rec.xField
            };
            await nextTick();
            renderActiveCharts();
            replaced = true;
            break;
          }
        }
      }
      if (!replaced) {
        ElMessage.info('暂无更多推荐图表类型');
      }
    } else {
      ElMessage.info('暂无更多推荐图表类型');
    }
  } catch (e) {
    console.error('Chart refresh failed:', e);
    ElMessage.error('刷新图表失败，请重试');
  } finally {
    switchingChart.value = null;
  }
};

/** Refresh ALL charts for a sheet ("换一批") */
const refreshAllChartsLoading = ref(false);
const handleRefreshAllCharts = async (sheet: SheetResult) => {
  if (!sheet.uploadId || refreshAllChartsLoading.value) return;
  refreshAllChartsLoading.value = true;
  try {
    // Force refresh the entire enrichment with cache invalidation
    await invalidateAnalysisCache(sheet.uploadId);
    enrichedSheets.value.delete(sheet.sheetIndex);
    await enrichSheet(sheet, true);
    ElMessage.success('图表已刷新');
  } catch (e) {
    console.error('Refresh all charts failed:', e);
    ElMessage.warning('图表刷新失败');
  } finally {
    refreshAllChartsLoading.value = false;
  }
};

/** Rebuild chart with custom axis config (Phase 4) */
const handleApplyChartConfig = async (
  sheet: SheetResult,
  chartIndex: number,
  config: { xField: string; yFields: string[]; seriesField?: string; aggregation?: string }
) => {
  const charts = getSheetCharts(sheet);
  const chart = charts[chartIndex];
  if (!chart || !sheet.uploadId) return;

  switchingChart.value = { sheetIndex: sheet.sheetIndex, chartIndex };
  try {
    let rawData = sheetRawDataCache.get(sheet.uploadId);
    if (!rawData) {
      const tableRes = await getUploadTableData(sheet.uploadId, 0, 2000);
      if (!tableRes.success || !tableRes.data?.data?.length) return;
      rawData = renameMeaninglessColumns(tableRes.data.data as Record<string, unknown>[]);
      sheetRawDataCache.set(sheet.uploadId, rawData);
    }

    const result = await buildChart({
      chartType: chart.chartType,
      data: rawData.slice(0, 200),
      xField: config.xField,
      yFields: config.yFields,
      title: chart.title
    });

    if (result.success && result.option) {
      const currentSheet = uploadedSheets.value.find(s => s.sheetIndex === sheet.sheetIndex);
      if (currentSheet?.flowResult?.charts?.[chartIndex]) {
        currentSheet.flowResult.charts[chartIndex] = {
          ...currentSheet.flowResult.charts[chartIndex],
          config: result.option,
          xField: config.xField
        };
        // Save config to localStorage for persistence
        const key = `chart-config-${sheet.uploadId}-${chartIndex}`;
        localStorage.setItem(key, JSON.stringify(config));
        await nextTick();
        renderActiveCharts();
      }
    } else {
      ElMessage.warning('配置应用失败: ' + (result.error || ''));
    }
  } catch (e) {
    console.error('Apply chart config failed:', e);
    ElMessage.error('应用图表配置失败');
  } finally {
    switchingChart.value = null;
  }
};

/** Get column info for chart config panel */
const getSheetColumns = (sheet: SheetResult): Array<{ name: string; type: 'numeric' | 'categorical' | 'date' }> => {
  const kpi = sheet.flowResult?.kpiSummary;
  if (!kpi?.columns) return [];
  return kpi.columns.map(col => ({
    name: col.name,
    type: ['int64', 'float64', 'number', 'int32', 'float32'].includes(col.type) ? 'numeric' as const
      : col.type === 'datetime64' ? 'date' as const
      : 'categorical' as const
  }));
};

// Drill-down analysis — provided by useSmartBIDrillDown composable

// ========== P1.3: View More (truncated chart data) ==========
const getDisplayedCount = (chart: { chartType: string; config: Record<string, unknown> }): number => {
  const opt = chart.config as SmartBIChartOption;
  if (chart.chartType === 'pie') {
    return opt?.series?.[0]?.data?.length ?? 0;
  }
  return opt?.xAxis?.data?.length ?? opt?.dataset?.source?.length ?? 0;
};

const handleViewMoreData = (sheet: SheetResult, chartIdx: number, chart: { chartType: string; title: string; totalItems?: number }) => {
  // Show the raw data tab for this sheet, which contains all rows
  loadSheetData(sheet);
  ElMessage.info(`图表"${chart.title}"显示了前 ${getDisplayedCount(chart as SmartBIChartItem)} 项，完整 ${chart.totalItems} 项数据可在下方原始数据中查看`);
};

// ========== Chart Export (Phase 3.3 — industry standard, 8/8 benchmarks) ==========
const handleChartExport = (command: string, sheetIndex: number, chartIdx: number, chartTitle?: string) => {
  const chartId = `chart-${sheetIndex}-${chartIdx}`;
  const dom = document.getElementById(chartId);
  if (!dom) return;

  const instance = echarts.getInstanceByDom(dom);
  if (!instance) return;

  const fileName = `${chartTitle || '图表'}-${new Date().toISOString().slice(0, 10)}`;

  try {
    if (command === 'png') {
      const url = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' });
      const link = document.createElement('a');
      link.download = `${fileName}.png`;
      link.href = url;
      link.click();
      ElMessage.success('图表已导出为 PNG');
    } else if (command === 'svg') {
      const url = instance.getDataURL({ type: 'svg' });
      const link = document.createElement('a');
      link.download = `${fileName}.svg`;
      link.href = url;
      link.click();
      ElMessage.success('图表已导出为 SVG');
    }
  } catch (error) {
    console.error('Chart export failed:', error);
    ElMessage.error('图表导出失败，请重试');
  }
};

// ========== Excel Export (SheetJS) ==========
const handleExportExcel = async (sheet: SheetResult) => {
  try {
    const XLSX = await import('xlsx');
    const wb = XLSX.utils.book_new();

    // 1. Data sheet — use cached rawData or fetch
    let rawData = sheetRawDataCache.get(sheet.uploadId!);
    if (!rawData?.length) {
      const tableRes = await getUploadTableData(sheet.uploadId!, 0, 5000);
      if (tableRes.success && tableRes.data?.data?.length) {
        rawData = tableRes.data.data as Record<string, unknown>[];
        sheetRawDataCache.set(sheet.uploadId!, rawData);
      }
    }
    if (rawData?.length) {
      const ws = XLSX.utils.json_to_sheet(rawData);
      XLSX.utils.book_append_sheet(wb, ws, sheet.sheetName || '数据');
    }

    // 2. KPI summary sheet
    const kpi = sheet.flowResult?.kpiSummary;
    if (kpi?.columns?.length) {
      const kpiRows = kpi.columns
        .filter(c => ['int64', 'float64', 'number', 'int32', 'float32'].includes(c.type))
        .map(c => ({
          指标: c.name,
          类型: c.type,
          最小值: c.min ?? '',
          最大值: c.max ?? '',
          平均值: c.mean != null ? Math.round(c.mean * 100) / 100 : '',
          合计: c.sum ?? '',
        }));
      if (kpiRows.length) {
        const kpiWs = XLSX.utils.json_to_sheet(kpiRows);
        XLSX.utils.book_append_sheet(wb, kpiWs, 'KPI汇总');
      }
    }

    // 3. AI analysis sheet
    const ai = sheet.flowResult?.aiAnalysis;
    if (ai) {
      const aiWs = XLSX.utils.aoa_to_sheet([['AI 智能分析'], [''], ...ai.split('\n').map(line => [line])]);
      XLSX.utils.book_append_sheet(wb, aiWs, 'AI分析');
    }

    const fileName = `${sheet.sheetName || '分析报告'}-${new Date().toISOString().slice(0, 10)}.xlsx`;
    XLSX.writeFile(wb, fileName);
    ElMessage.success(`已导出 Excel: ${fileName}`);
  } catch (error) {
    console.error('Excel export failed:', error);
    ElMessage.error('Excel 导出失败');
  }
};

// ========== PDF Export (ECharts getDataURL + jsPDF + Chinese Font) ==========
let cachedChineseFont: string | null = null;

const loadChineseFont = async (): Promise<string | null> => {
  if (cachedChineseFont) return cachedChineseFont;
  try {
    const resp = await fetch('/fonts/simhei-subset.ttf');
    if (!resp.ok) return null;
    const buf = await resp.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let binary = '';
    for (let i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    cachedChineseFont = btoa(binary);
    return cachedChineseFont;
  } catch {
    return null;
  }
};

const handleExportPDF = async (sheet: SheetResult) => {
  try {
    const { default: jsPDF } = await import('jspdf');
    const doc = new jsPDF('p', 'mm', 'a4');
    const pageWidth = doc.internal.pageSize.getWidth();
    let yOffset = 15;

    // Load and register Chinese font
    const fontBase64 = await loadChineseFont();
    const hasChinese = !!fontBase64;
    if (fontBase64) {
      doc.addFileToVFS('SimHei-subset.ttf', fontBase64);
      doc.addFont('SimHei-subset.ttf', 'SimHei', 'normal');
      doc.setFont('SimHei');
    }

    // Title
    doc.setFontSize(18);
    doc.text(sheet.sheetName || 'SmartBI 分析报告', pageWidth / 2, yOffset, { align: 'center' });
    yOffset += 10;

    doc.setFontSize(10);
    doc.text(new Date().toISOString().slice(0, 10), pageWidth / 2, yOffset, { align: 'center' });
    yOffset += 10;

    // KPI section
    const kpi = sheet.flowResult?.kpiSummary;
    if (kpi?.columns?.length) {
      doc.setFontSize(14);
      doc.text('关键指标摘要', 15, yOffset);
      yOffset += 8;
      doc.setFontSize(9);
      const numericCols = kpi.columns.filter(c => ['int64', 'float64', 'number', 'int32', 'float32'].includes(c.type));
      for (const col of numericCols.slice(0, 6)) {
        const sumVal = col.sum != null ? Number(col.sum).toLocaleString('zh-CN') : 'N/A';
        doc.text(`${col.name}: ${sumVal}`, 15, yOffset);
        yOffset += 5;
      }
      yOffset += 5;
    }

    // Chart images
    const charts = getSheetCharts(sheet);
    for (let i = 0; i < charts.length; i++) {
      const chartId = `chart-${sheet.sheetIndex}-${i}`;
      const dom = document.getElementById(chartId);
      if (!dom) continue;
      const instance = echarts.getInstanceByDom(dom);
      if (!instance) continue;

      const imgData = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' });
      const imgWidth = pageWidth - 30;
      const imgHeight = imgWidth * 0.6;

      if (yOffset + imgHeight > 280) {
        doc.addPage();
        yOffset = 15;
        if (hasChinese) doc.setFont('SimHei');
      }

      doc.setFontSize(11);
      doc.text(charts[i].title || `图表 ${i + 1}`, 15, yOffset);
      yOffset += 6;
      doc.addImage(imgData, 'PNG', 15, yOffset, imgWidth, imgHeight);
      yOffset += imgHeight + 10;
    }

    // AI analysis text
    const ai = sheet.flowResult?.aiAnalysis;
    if (ai) {
      if (yOffset > 200) {
        doc.addPage();
        yOffset = 15;
        if (hasChinese) doc.setFont('SimHei');
      }
      doc.setFontSize(14);
      doc.text('AI 智能分析', 15, yOffset);
      yOffset += 8;
      doc.setFontSize(9);
      const plainText = ai.replace(/\*\*/g, '').replace(/#{1,3}\s*/g, '');
      const lines = doc.splitTextToSize(plainText, pageWidth - 30);
      for (const line of lines) {
        if (yOffset > 280) {
          doc.addPage();
          yOffset = 15;
          if (hasChinese) doc.setFont('SimHei');
        }
        doc.text(line, 15, yOffset);
        yOffset += 4.5;
      }
    }

    // Footer
    const totalPages = doc.getNumberOfPages();
    for (let p = 1; p <= totalPages; p++) {
      doc.setPage(p);
      if (hasChinese) doc.setFont('SimHei');
      doc.setFontSize(8);
      doc.setTextColor(150);
      doc.text(`Cretas SmartBI · 第 ${p}/${totalPages} 页`, pageWidth / 2, 290, { align: 'center' });
      doc.setTextColor(0);
    }

    const fileName = `${sheet.sheetName || 'SmartBI'}-${new Date().toISOString().slice(0, 10)}.pdf`;
    doc.save(fileName);
    ElMessage.success(`已导出 PDF: ${fileName}`);
  } catch (error) {
    console.error('PDF export failed:', error);
    ElMessage.error('PDF 导出失败');
  }
};

// ========== Global Filter Bar ==========
const filterDimensionsLoading = ref(false);
// T3.2: Memoize dimension computation — avoid recalculating on every re-render
const _dimensionCache = new Map<number, string[]>();

const getFilterableDimensions = (sheet: SheetResult): string[] => {
  const uploadId = sheet.uploadId!;
  // Return cached result if available
  if (_dimensionCache.has(uploadId)) return _dimensionCache.get(uploadId)!;

  const rawData = sheetRawDataCache.get(uploadId);
  if (!rawData?.length) {
    // Lazy-load rawData on first filter interaction
    if (!filterDimensionsLoading.value && uploadId) {
      filterDimensionsLoading.value = true;
      getUploadTableData(uploadId, 0, 2000).then(res => {
        if (res.success && res.data?.data?.length) {
          sheetRawDataCache.set(uploadId, res.data.data as Record<string, unknown>[]);
          _dimensionCache.delete(uploadId); // invalidate cache so next call recomputes
        }
      }).finally(() => { filterDimensionsLoading.value = false; });
    }
    return [];
  }
  const allKeys = Object.keys(rawData[0]);
  const dims: string[] = [];
  for (const key of allKeys) {
    const uniqueVals = new Set(rawData.map(r => String(r[key] ?? '')));
    if (uniqueVals.size < 2) continue;
    // Check if column is mostly numeric
    const numericCount = rawData.filter(r => !isNaN(Number(r[key]))).length;
    const isNumeric = numericCount >= rawData.length * 0.8;
    // Include non-numeric columns with reasonable cardinality (up to 300 for large tables)
    if (!isNumeric && uniqueVals.size <= 300) {
      dims.push(key);
    }
  }
  // Fallback: if no categorical columns found, offer the first non-numeric column regardless
  if (dims.length === 0) {
    for (const key of allKeys) {
      const numericCount = rawData.filter(r => !isNaN(Number(r[key]))).length;
      if (numericCount < rawData.length * 0.5) {
        dims.push(key);
        break;
      }
    }
  }
  _dimensionCache.set(uploadId, dims);
  return dims;
};

const getDimensionValues = (sheet: SheetResult, dimension: string): string[] => {
  const rawData = sheetRawDataCache.get(sheet.uploadId!);
  if (!rawData?.length || !dimension) return [];
  const vals = [...new Set(rawData.map(r => String(r[dimension] ?? '')))].filter(Boolean);
  return vals.sort().slice(0, 100); // cap at 100 to keep dropdown usable
};

const handleGlobalFilterChange = (_sheet: SheetResult) => {
  globalFilterValues.value = [];
  filteredRawData.value = null;
  totalRowCount.value = 0;
  filteredRowCount.value = 0;
};

// SSE upload abort controller — aborted on unmount or new upload
let uploadAbortController: AbortController | null = null;

// Q1: Data filtering with debounce
let filterDebounceTimer: ReturnType<typeof setTimeout> | null = null;

const applyDataFilter = async (sheet: SheetResult) => {
  const rawData = sheetRawDataCache.get(sheet.uploadId!);
  if (!rawData || !globalFilterDimension.value || globalFilterValues.value.length === 0) {
    filteredRawData.value = null;
    totalRowCount.value = 0;
    filteredRowCount.value = 0;
    return;
  }

  const filtered = rawData.filter(row => {
    const val = String(row[globalFilterDimension.value] ?? '');
    return globalFilterValues.value.includes(val);
  });

  totalRowCount.value = rawData.length;
  filteredRowCount.value = filtered.length;
  filteredRawData.value = filtered;

  // Re-render charts with filtered data by calling Python chart builder
  await rebuildChartsWithData(sheet, filtered);
};

const rebuildChartsWithData = async (sheet: SheetResult, data: Record<string, unknown>[]) => {
  if (!sheet.uploadId || data.length === 0) return;

  enrichingSheets.value.add(sheet.sheetIndex);

  try {
    // Call buildChart API with filtered data to generate new charts
    const chartPromises = [];
    const columns = Object.keys(data[0] || {});
    const numericCols = columns.filter(col => {
      const vals = data.map(r => r[col]).filter(v => v != null);
      return vals.length > 0 && vals.every(v => !isNaN(Number(v)));
    });
    const categoricalCols = columns.filter(col => !numericCols.includes(col));

    // Build 2-3 charts with filtered data
    if (categoricalCols.length > 0 && numericCols.length > 0) {
      // Bar chart
      chartPromises.push(buildChart({
        data,
        chartType: 'bar',
        xField: categoricalCols[0],
        yFields: [numericCols[0]],
      }));

      // Pie chart if we have categorical data
      if (numericCols.length > 0) {
        chartPromises.push(buildChart({
          data,
          chartType: 'pie',
          xField: categoricalCols[0],
          yFields: [numericCols[0]],
        }));
      }
    }

    const results = await Promise.allSettled(chartPromises);
    type BuildChartResult = { success: boolean; option?: Record<string, unknown>; error?: string };
    const newCharts = results
      .filter((r): r is PromiseFulfilledResult<BuildChartResult> => r.status === 'fulfilled' && r.value?.success === true)
      .map(r => {
        const opt = r.value.option as Record<string, unknown> | undefined;
        const series = opt?.series as Array<{ type?: string }> | undefined;
        const title = opt?.title as { text?: string } | undefined;
        return {
          chartType: series?.[0]?.type || 'bar',
          title: title?.text || '筛选分析',
          config: opt || {},
        };
      });

    if (newCharts.length > 0) {
      // Update flowResult with new charts
      sheet.flowResult = {
        ...sheet.flowResult,
        charts: newCharts,
      };

      // Re-render charts
      await nextTick();
      renderActiveCharts();
    } else {
      ElMessage.warning('无法为筛选后的数据生成图表');
    }
  } catch (error) {
    console.error('Failed to rebuild charts with filtered data:', error);
    ElMessage.warning('图表重建失败，请稍后重试');
  } finally {
    enrichingSheets.value.delete(sheet.sheetIndex);
  }
};

const handleGlobalFilterApply = (sheet: SheetResult) => {
  if (!globalFilterDimension.value || !globalFilterValues.value.length) {
    filteredRawData.value = null;
    totalRowCount.value = 0;
    filteredRowCount.value = 0;
    nextTick(() => renderActiveCharts());
    return;
  }

  // Debounce to avoid rapid re-filtering
  if (filterDebounceTimer) clearTimeout(filterDebounceTimer);
  filterDebounceTimer = setTimeout(() => {
    applyDataFilter(sheet);
  }, 300);
};

const clearGlobalFilter = (sheet: SheetResult) => {
  globalFilterDimension.value = '';
  globalFilterValues.value = [];
  filteredRawData.value = null;
  totalRowCount.value = 0;
  filteredRowCount.value = 0;

  // Reset to original data - trigger re-enrichment
  if (sheet.uploadId) {
    enrichSheet(sheet);
  }
};

// ========== Explore Panel (Superset-style multi-dimension) ==========
const availableExploreDimensions = (sheet: SheetResult): string[] => {
  return getFilterableDimensions(sheet).filter(d => !exploreDimensions.value.includes(d));
};

const addExploreDimension = (dim: string) => {
  if (!exploreDimensions.value.includes(dim)) {
    exploreDimensions.value.push(dim);
    exploreDimensionFilters[dim] = []; // empty = all values (no filter)
  }
};

const removeExploreDimension = (idx: number) => {
  const dim = exploreDimensions.value[idx];
  exploreDimensions.value.splice(idx, 1);
  delete exploreDimensionFilters[dim];
};

const moveExploreDimension = (idx: number, direction: number) => {
  const newIdx = idx + direction;
  if (newIdx < 0 || newIdx >= exploreDimensions.value.length) return;
  const arr = exploreDimensions.value;
  const temp = arr[idx];
  arr[idx] = arr[newIdx];
  arr[newIdx] = temp;
  // Force reactivity
  exploreDimensions.value = [...arr];
};

const applyExploreFilter = (sheet: SheetResult) => {
  const rawData = sheetRawDataCache.get(sheet.uploadId!);
  if (!rawData) return;

  let filtered = [...rawData];
  for (const dim of exploreDimensions.value) {
    const selectedVals = exploreDimensionFilters[dim];
    if (selectedVals && selectedVals.length > 0) {
      filtered = filtered.filter(row => selectedVals.includes(String(row[dim] ?? '')));
    }
    // If selectedVals is empty array, no filter is applied for this dimension (all values pass)
  }

  if (filtered.length === rawData.length) {
    // No actual filtering happened — clear filter state
    filteredRawData.value = null;
    totalRowCount.value = 0;
    filteredRowCount.value = 0;
  } else {
    totalRowCount.value = rawData.length;
    filteredRowCount.value = filtered.length;
    filteredRawData.value = filtered;
  }

  // Re-render charts with filtered data
  rebuildChartsWithData(sheet, filtered.length < rawData.length ? filtered : rawData);
};

const clearExploreFilter = (sheet: SheetResult) => {
  exploreDimensions.value = [];
  Object.keys(exploreDimensionFilters).forEach(k => delete exploreDimensionFilters[k]);
  filteredRawData.value = null;
  totalRowCount.value = 0;
  filteredRowCount.value = 0;

  // Reset to original data
  if (sheet.uploadId) {
    enrichSheet(sheet);
  }
};

// ========== Cross-chart linked filter (Phase 3.4 — Power BI + Superset + Tableau) ==========
const applyChartFilter = (sheet: SheetResult, params: Record<string, unknown>) => {
  const filterValueRaw = params.name ?? params.seriesName ?? '';
  const filterValue = String(filterValueRaw);
  if (!filterValue) return;

  // Determine dimension from xAxis
  const charts = getSheetCharts(sheet);
  let dimension = '';
  for (const c of charts) {
    const xField = ((c as SmartBIChartItem)).xField;
    if (xField) { dimension = xField; break; }
    const xName = ((c.config as SmartBIChartOption))?.xAxis?.name;
    if (xName) { dimension = xName; break; }
  }

  // Q1: Ctrl+click triggers global filter data filtering
  const ev = params.event as { event?: { ctrlKey?: boolean; metaKey?: boolean } } | undefined;
  if (ev?.event?.ctrlKey || ev?.event?.metaKey) {
    globalFilterDimension.value = dimension || '项目';
    globalFilterValues.value = [filterValue];
    handleGlobalFilterApply(sheet);
    return;
  }

  // Toggle filter if same value
  if (activeFilter.value?.value === filterValue && activeFilter.value?.dimension === dimension) {
    activeFilter.value = null;
  } else {
    activeFilter.value = { dimension: dimension || '项目', value: filterValue };
  }

  // Re-render all charts with filter applied
  nextTick(() => renderActiveCharts());
};

const clearChartFilter = () => {
  activeFilter.value = null;
  nextTick(() => renderActiveCharts());
};

// ========== DOM-aware rendering helper (Phase 2.4) ==========
const waitForElement = (id: string, timeout = 2000): Promise<HTMLElement | null> => {
  return new Promise((resolve) => {
    const existing = document.getElementById(id);
    if (existing) { resolve(existing); return; }

    const observer = new MutationObserver(() => {
      const el = document.getElementById(id);
      if (el) { observer.disconnect(); resolve(el); }
    });
    observer.observe(document.body, { childList: true, subtree: true });

    setTimeout(() => { observer.disconnect(); resolve(null); }, timeout);
  });
};

// ========== Cleanup on unmount (Phase 2.2 — prevent memory leaks) ==========
// ========== keep-alive lifecycle: pause/resume side effects ==========
onDeactivated(() => {
  // Pause resize listener & auto-refresh when cached (navigated away)
  window.removeEventListener('resize', handleResize);
  resizeObserver?.disconnect(); resizeObserver = null;
  if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
  clearHoverThrottleTimers();
  // Disconnect IntersectionObserver to prevent stale callbacks and memory leaks
  if (chartObserver) { chartObserver.disconnect(); chartObserver = null; }
  pendingChartConfigs.clear();
});

onActivated(() => {
  // Resume resize listener when re-entering
  window.addEventListener('resize', handleResize);
  // Resume ResizeObserver for sidebar toggle / container resize
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(rootRef.value);
  }
  // Resize ECharts to fit (container size may have changed) + reconnect groups
  nextTick(() => {
    const root = rootRef.value;
    (root || document).querySelectorAll('[id^="chart-"]').forEach(dom => {
      const instance = echarts.getInstanceByDom(dom as HTMLElement);
      if (instance) {
        instance.resize();
        // Re-establish connect group after reactivation
        if (instance.group) echarts.connect(instance.group);
      }
    });
  });
});

onBeforeUnmount(() => {
  if (uploadAbortController) { uploadAbortController.abort(); uploadAbortController = null; }
  if (filterDebounceTimer) clearTimeout(filterDebounceTimer);
  if (renderDebounceTimer) clearTimeout(renderDebounceTimer);
  clearHoverThrottleTimers();
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
  window.removeEventListener('resize', handleResize);
  resizeObserver?.disconnect(); resizeObserver = null;
  // T5.2: Disconnect intersection observer
  if (chartObserver) { chartObserver.disconnect(); chartObserver = null; }
  pendingChartConfigs.clear();
  sheetRawDataCache.clear();
  kpiCache.clear();
  // Dispose all ECharts instances within this component's scope
  const root = rootRef.value;
  (root || document).querySelectorAll('[id^="chart-"]').forEach(dom => {
    const instance = echarts.getInstanceByDom(dom as HTMLElement);
    if (instance) instance.dispose();
  });
  // Dispose stat heatmap if open
  disposeStatHeatmap();
});

// Cross-sheet analysis — provided by useSmartBICrossSheet composable

// 打开同比分析对话框 — delegates to extracted YoYDialog component
const openYoYComparison = () => {
  yoyDialogRef.value?.open();
};

// P5: Statistical analysis — provided by useSmartBIStatistical composable

// 加载 Sheet 数据 — delegates to extracted DataPreviewDialog component
const loadSheetData = async (sheet: SheetResult) => {
  await dataPreviewRef.value?.open({
    uploadId: sheet.uploadId,
    sheetName: sheet.sheetName,
  });
};

// 导航到指定 Sheet
const navigateToSheet = (sheetIndex: number) => {
  // 找到目标 Sheet 在 uploadedSheets 中的位置
  const targetSheet = uploadedSheets.value.find(s => s.sheetIndex === sheetIndex);
  if (targetSheet) {
    activeTab.value = String(sheetIndex);
  } else {
    ElMessage.warning('该报表数据未加载或处理失败');
  }
};

// 重置上传
const resetUpload = () => {
  fileList.value = [];
  uploadedSheets.value = [];
  uploadResult.value = null;
  activeTab.value = '';
  uploading.value = false;
  uploadProgress.value = 0;
  indexMetadata.value = null;
  enrichedSheets.value = new Set();
  enrichingSheets.value = new Set();
  enrichPhases.value = new Map();
};

// 重试失败的 Sheet
const handleRetrySheet = async (sheet: SheetResult) => {
  if (!sheet.uploadId) {
    ElMessage.error('该 Sheet 无上传记录，请重新上传文件');
    return;
  }

  retryingSheets[sheet.sheetIndex] = true;
  try {
    const res = await retrySheetUpload(sheet.uploadId);
    if (res.success) {
      ElMessage.success(res.data?.message || '重试成功');
      // Update sheet to successful state
      sheet.success = true;
      sheet.savedRows = res.data?.rowCount || 0;
      sheet.message = '重试成功';

      // Trigger enrichment for the retried sheet
      if (sheet.uploadId) {
        enrichedSheets.value.delete(sheet.uploadId);
        enrichingSheets.value.delete(sheet.uploadId);
        nextTick(() => {
          enrichSheetAnalysis(sheet.uploadId!).then(enrichResult => {
            if (enrichResult) {
              sheet.flowResult = {
                ...sheet.flowResult,
                charts: enrichResult.charts,
                kpiSummary: enrichResult.kpiSummary,
                structuredAI: enrichResult.structuredAI,
                displayNameMap: enrichResult.displayNameMap,
              };
              enrichedSheets.value.add(sheet.uploadId!);
            }
          }).catch(err => {
            console.warn('Enrichment after retry failed:', err);
          });
        });
      }
    } else {
      ElMessage.error(res.message || '重试失败');
    }
  } catch (error) {
    console.error('Retry failed:', error);
    ElMessage.error('重试请求失败');
  } finally {
    retryingSheets[sheet.sheetIndex] = false;
  }
};

// 将上传记录列表组装为一个批次
const makeBatch = (uploads: UploadHistoryItem[]): UploadBatch => {
  const first = uploads[0];
  const d = new Date(first.createdAt);
  const pad = (n: number) => String(n).padStart(2, '0');
  const uploadTime = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return {
    fileName: (first.fileName && first.fileName !== 'null' && first.fileName.trim() !== '') ? first.fileName : `Excel_${uploadTime.replace(/[- :]/g, '')}`,
    uploadTime,
    sheetCount: uploads.length,
    totalRows: uploads.reduce((sum, u) => sum + (u.rowCount || 0), 0),
    uploadId: first.id,
    id: first.id,
    uploads,
  };
};

// 选择某个批次，填充 uploadedSheets
// T6 Phase-2: accepts '__FUSED__' sentinel → switches to fused timeseries mode
const selectBatch = (index: number | '__FUSED__') => {
  selectedBatchIndex.value = index;

  // Fused mode: clear per-upload sheets, load timeseries resolver data
  if (index === '__FUSED__') {
    uploadedSheets.value = [];
    uploadResult.value = null;
    usingDemoCache.value = false;
    demoCacheFileName.value = '';
    enrichedSheets.value = new Set();
    enrichingSheets.value = new Set();
    enrichPhases.value = new Map();
    kpiCache.clear();
    loadFusedTimeseries();
    return;
  }

  // Normal batch mode: clear fused state
  fusedRows.value = [];
  fusedError.value = null;

  const batch = uploadBatches.value[index];
  if (!batch) return;

  // U6: Show loading feedback when switching data source
  batchSwitching.value = true;

  const sorted = [...batch.uploads].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  );

  uploadedSheets.value = sorted.map((u, idx) => ({
    sheetIndex: idx,
    sheetName: u.sheetName,
    success: true,
    message: '从历史记录加载',
    tableType: u.tableType as SheetResult['tableType'],
    detectedDataType: u.tableType,
    savedRows: u.rowCount,
    uploadId: u.id,
    flowResult: {},
  }));

  // 重建 indexMetadata：服务端可能未区分 index 类型（统一存为 general），
  // 因此同时按 tableType 和 sheet 名称模式匹配检测索引页
  const indexNamePattern = /^(索引|目录|index|目次|sheet\s*index)$/i;
  const indexSheet = uploadedSheets.value.find(
    s => s.tableType === 'index' || indexNamePattern.test((s.sheetName || '').trim())
  );
  if (indexSheet) {
    indexSheet.tableType = 'index';  // 补齐 tableType
    indexMetadata.value = {
      hasIndex: true,
      indexSheetIndex: indexSheet.sheetIndex,
      sheetMappings: uploadedSheets.value
        .filter(s => s !== indexSheet)
        .map(s => ({ index: s.sheetIndex, reportName: s.sheetName, sheetName: s.sheetName })),
    };
  } else {
    indexMetadata.value = null;
  }

  uploadResult.value = {
    totalSheets: sorted.length,
    successCount: sorted.length,
    failedCount: 0,
    requiresConfirmationCount: 0,
    totalSavedRows: batch.totalRows,
    message: `${safeBatchName(batch)} (${batch.sheetCount} 表, ${batch.totalRows} 行)`,
    results: uploadedSheets.value,
  };

  // Clear stale cache banner when switching batches
  usingDemoCache.value = false;
  demoCacheFileName.value = '';

  enrichedSheets.value = new Set();
  enrichingSheets.value = new Set();
  enrichPhases.value = new Map();
  kpiCache.clear();
  // Staleness fix (Apr 24 2026): clear cross-upload UI/detection state too,
  // otherwise prior upload's filter chips and food-industry templates leak
  // into the new upload's render.
  globalFilterDimension.value = '';
  globalFilterValues.value = [];
  foodIndustryDetection.value = null;
  activeTemplate.value = '';
  activeTab.value = '0';
  nextTick(() => {
    // U6: Clear loading after DOM updates with new batch data
    batchSwitching.value = false;
    renderActiveChart();
    // R-11: 自动触发当前 tab sheet 的 enrichment（history 加载后 flowResult 为空）
    const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === 0);
    if (activeSheet && !isIndexSheet(activeSheet) && activeSheet.uploadId) {
      enrichSheet(activeSheet);
    }
  });
};

// P2-6 fix: show "冷启动" hint if history load takes >5s
const historyLoadingLong = ref(false);
let _historyLongTimer: ReturnType<typeof setTimeout> | null = null;

// 加载历史上传记录（按文件名 + 时间窗口分组为批次）
const loadHistory = async () => {
  historyLoading.value = true;
  historyLoadingLong.value = false;
  if (_historyLongTimer) clearTimeout(_historyLongTimer);
  _historyLongTimer = setTimeout(() => { historyLoadingLong.value = true; }, 5000);
  try {
    const response = await getUploadHistory();
    if (!response.success || !response.data?.length) return;

    // Dedup: keep only the latest upload per fileName+sheetName combo
    const uploads = deduplicateUploads(response.data as UploadHistoryItem[])
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

    const batches: UploadBatch[] = [];
    let currentBatch: UploadHistoryItem[] = [];
    let currentFileName = '';
    let currentTime = 0;

    for (const u of uploads) {
      const t = new Date(u.createdAt).getTime();
      if (u.fileName !== currentFileName || (currentTime - t) > 5 * 60 * 1000) {
        if (currentBatch.length > 0) {
          batches.push(makeBatch(currentBatch));
        }
        currentBatch = [u];
        currentFileName = u.fileName;
        currentTime = t;
      } else {
        currentBatch.push(u);
      }
    }
    if (currentBatch.length > 0) batches.push(makeBatch(currentBatch));

    // 如果已从 demo 缓存恢复了数据，保留缓存中的 uploadId，只追加服务器批次
    if (usingDemoCache.value) {
      // 保留当前缓存批次（第一个），追加服务器批次到后面
      const cached = uploadBatches.value[0];
      if (cached) {
        uploadBatches.value = [cached, ...batches];
      } else {
        uploadBatches.value = batches;
      }
    } else {
      uploadBatches.value = batches;
      // WS6 (#1/#3): gold 模式 (餐饮租户默认) 下不自动选中上传分片 —— 否则会落到某个
      // *_partN 分片产出 "0 个图表" 空白。批次仍建好, 供用户点「分析上传文件」时浏览。
      // 非 gold 模式 (制造业, 或餐饮已切到上传分析) 下默认选中: 优先完整文件 (跳过分片)。
      if (batches.length > 0 && !goldMode.value) {
        selectBatch(pickDefaultBatchIndex(batches.map(b => b.fileName)));
      }
    }
  } catch (error) {
    console.error('加载历史记录失败:', error);
    ElMessage.error('加载历史记录失败');
  } finally {
    historyLoading.value = false;
    historyLoadingLong.value = false;
    if (_historyLongTimer) { clearTimeout(_historyLongTimer); _historyLongTimer = null; }
  }
};

// ========== Window resize handler for ECharts (R-9) ==========
let resizeRaf = 0;
const handleResize = () => {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    (rootRef.value || document).querySelectorAll('[id^="chart-"]').forEach(dom => {
      const instance = echarts.getInstanceByDom(dom as HTMLElement);
      if (instance) instance.resize();
    });
    resizeRaf = 0;
  });
};

// Q2: Auto-refresh timer methods
const setAutoRefresh = (interval: number) => {
  autoRefreshInterval.value = interval;
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
  if (interval > 0) {
    autoRefreshTimer = setInterval(() => {
      const sheet = currentSheet.value;
      if (sheet) handleRefreshAnalysis(sheet);
    }, interval);
  }
};

// Q5: Keyboard shortcuts integration
const currentSheet = computed(() => {
  const idx = parseInt(activeTab.value);
  return uploadedSheets.value.find(s => s.sheetIndex === idx) || null;
});

const switchToPrevSheet = () => {
  const idx = parseInt(activeTab.value);
  if (isNaN(idx) || uploadedSheets.value.length === 0) return;
  const currentIndex = uploadedSheets.value.findIndex(s => s.sheetIndex === idx);
  if (currentIndex > 0) {
    activeTab.value = String(uploadedSheets.value[currentIndex - 1].sheetIndex);
  }
};

const switchToNextSheet = () => {
  const idx = parseInt(activeTab.value);
  if (isNaN(idx) || uploadedSheets.value.length === 0) return;
  const currentIndex = uploadedSheets.value.findIndex(s => s.sheetIndex === idx);
  if (currentIndex >= 0 && currentIndex < uploadedSheets.value.length - 1) {
    activeTab.value = String(uploadedSheets.value[currentIndex + 1].sheetIndex);
  }
};

const { showHelp: showShortcutsHelp, shortcuts: shortcutsList } = useSmartBIShortcuts({
  onPrevSheet: switchToPrevSheet,
  onNextSheet: switchToNextSheet,
  onRefresh: () => { const s = currentSheet.value; if (s) handleRefreshAnalysis(s); },
  onExport: () => { const s = currentSheet.value; if (s) handleExportExcel(s); },
  onShare: openShareDialog,
  onToggleLayout: () => { layoutEditMode.value = !layoutEditMode.value; },
  onHelp: () => { showShortcutsHelp.value = !showShortcutsHelp.value; },
});

onMounted(() => {
  // 后台检查 Python 服务健康状态 (with retry)
  checkHealthWithRetry().catch(() => {});

  // WS6 (#1/#3): 餐饮 (gold) 租户默认 Gold 视图 — 探测真实数据区间填充 RestaurantGoldGrid。
  if (isRestaurantTenant.value) {
    probeGoldRange().catch(() => {});
  }

  // Demo 缓存快速恢复: 如果有缓存，跳过网络请求直接渲染
  const restoredFromCache = restoreFromDemoCache();
  if (restoredFromCache) {
    nextTick(() => {
      renderActiveChart();
      // 如果缓存中的 sheet 已有图表数据，标记 tour ready
      const activeSheet = uploadedSheets.value.find(s => s.sheetIndex === parseInt(activeTab.value));
      if (activeSheet && hasChartData(activeSheet)) {
        tourDataReady.value = true;
      }
      // 后台静默刷新: 异步加载服务端历史，不影响当前渲染
      loadHistory();
    });
  } else {
    loadHistory();
  }
  window.addEventListener('resize', handleResize);
  // ResizeObserver for container resize (sidebar toggle, panel collapse)
  if (rootRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(rootRef.value);
  }
});
</script>


<style scoped lang="scss" src="./analysis/SmartBIAnalysis.scss"></style>
