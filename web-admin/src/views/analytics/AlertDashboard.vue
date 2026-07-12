<script setup lang="ts">
/**
 * 生产异常预警看板
 * 提供告警汇总、筛选、确认/解决操作和 AI 根因分析详情
 *
 * 餐饮租户 (isRestaurant): 站立式/主动预警看板, 直接调用真实 14 指标阈值引擎
 * (DiagnosticsEngine, 经 GET /health-check-report), 不是聊天问答, 也不是硬编码
 * 3 条规则. 复用 HealthReportView.vue 的同款 API client + DiagnosisCard 渲染,
 * 首屏自动加载上月报告 (fool-proof-design Rule 1 — 主动触达, 不需用户先问).
 */
import { ref, onMounted, computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { get, post } from '@/api/request'
import request from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { isAxiosError } from 'axios'
import {
  fetchHealthCheckReport,
  type HealthCheckReport,
} from '@/api/smartbi/healthCheck'
import DiagnosisCard from '@/views/smart-bi/components/health/DiagnosisCard.vue'

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)
// Apr 24 Plan C Phase 7: restaurant tenants get Gold-derived alerts.
// Jul 11 2026: 硬编码 3 条阈值规则 → 换成真实 14 指标 DiagnosticsEngine (G4
// health-check-report), engine-backed 站立式预警 (见 module docstring).
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT')

const healthReport = ref<HealthCheckReport | null>(null)
const healthReportLoading = ref(false)
const healthReportError = ref('')

const healthDiagnoses = computed(() => healthReport.value?.diagnoses ?? [])
const healthSummary = computed(() => healthReport.value?.summary ?? null)
const healthMeta = computed(() => healthReport.value?.reportMeta ?? null)
const healthAllClear = computed(
  () => !healthReportLoading.value && healthReport.value != null && healthDiagnoses.value.length === 0,
)

async function loadRestaurantAlerts() {
  if (!factoryId.value) return
  healthReportLoading.value = true
  healthReportError.value = ''
  try {
    const res = await fetchHealthCheckReport(factoryId.value)
    if (!res.success || !res.data) {
      throw new Error(res.message || '预警看板加载失败')
    }
    healthReport.value = res.data
  } catch (e) {
    const msg = isAxiosError(e)
      ? (e.response?.data?.message ?? '请求失败')
      : (e instanceof Error ? e.message : String(e))
    healthReportError.value = msg
    healthReport.value = null
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    console.error('[alerts] restaurant health-check load failed:', e)
  } finally {
    healthReportLoading.value = false
  }
}

// --- State ---
const loading = ref(false)
const loadingAlerts = ref(false)
const detecting = ref(false)
const resolving = ref(false)

interface AlertSummary {
  criticalCount?: number
  warningCount?: number
  activeCount?: number
  resolvedToday?: number
}

interface AlertRecord {
  id: number | string
  level: string
  alertType: string
  metricName: string
  description: string
  currentValue: number | null
  baselineValue: number | null
  deviationPercent: number | null
  status: string
  productName?: string
  aiAnalysis?: string
  createdAt: string
  acknowledgedAt?: string
  resolvedAt?: string
  verifiedAt?: string
  resolutionNotes?: string
  autoVerified?: boolean
}

const summary = ref<AlertSummary>({})
const alerts = ref<AlertRecord[]>([])
const totalAlerts = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterStatus = ref('')
const filterLevel = ref('')
const resolveDialogVisible = ref(false)
const detailDrawerVisible = ref(false)
const selectedAlert = ref<AlertRecord | null>(null)
const resolutionNotes = ref('')

function statusTagType(status: string): string {
  const map: Record<string, string> = {
    ACTIVE: 'danger',
    ACKNOWLEDGED: 'warning',
    RESOLVED: 'success',
    VERIFIED: 'info'
  }
  return map[status] || 'info'
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    ACTIVE: '待处理',
    ACKNOWLEDGED: '已确认',
    RESOLVED: '已解决',
    VERIFIED: '已验证'
  }
  return map[status] || status
}

function levelLabel(level: string): string {
  const map: Record<string, string> = {
    CRITICAL: '严重',
    WARNING: '警告',
    INFO: '信息'
  }
  return map[level] || level
}

function alertTypeLabel(type: string): string {
  const map: Record<string, string> = {
    YIELD_DROP: '良率下降',
    COST_SPIKE: '成本飙升',
    QUALITY_ISSUE: '质量问题',
    QUALITY_FAIL: '质检不合格',
    EQUIPMENT_FAILURE: '设备故障',
    OEE_LOW: 'OEE 偏低',
    MATERIAL_SHORTAGE: '原料短缺',
    DELIVERY_DELAY: '交付延迟',
    THRESHOLD_BREACH: '阈值突破'
  }
  return map[type] || type
}

function metricLabel(metric: string): string {
  const map: Record<string, string> = {
    yield_rate: '良率',
    defect_rate: '不良率',
    material_cost: '材料成本',
    labor_cost: '人工成本',
    unit_cost: '单位成本',
    total_cost: '总成本',
    output_quantity: '产出量',
    equipment_utilization: '设备利用率',
    oee: 'OEE',
    quality_pass_rate: '质检合格率'
  }
  return map[metric] || metric
}

async function loadData() {
  if (!factoryId.value) {
    console.error('No factoryId available')
    ElMessage.error('无法获取工厂信息，请重新登录')
    return
  }
  loading.value = true
  try {
    const res = await get<AlertSummary>(`/${factoryId.value}/alerts/summary`)
    if (res?.success) {
      summary.value = res.data || {}
    } else {
      ElMessage.error(res?.message || '加载告警汇总失败')
    }
  } catch (e) {
    console.error('Load alert summary failed:', e)
    ElMessage.error('加载告警汇总失败，请稍后重试')
  } finally {
    loading.value = false
  }
  await loadAlerts()
}

async function loadAlerts() {
  loadingAlerts.value = true
  try {
    const params: Record<string, unknown> = {
      page: currentPage.value - 1,
      size: pageSize.value
    }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterLevel.value) params.level = filterLevel.value

    const res = await get<{ content: AlertRecord[]; totalElements: number }>(
      `/${factoryId.value}/alerts`, { params }
    )
    if (res?.success) {
      const data = res.data || { content: [], totalElements: 0 }
      alerts.value = data.content || []
      totalAlerts.value = data.totalElements || 0
    } else {
      ElMessage.error(res?.message || '加载告警列表失败')
    }
  } catch (e) {
    console.error('Load alerts failed:', e)
    ElMessage.error('加载告警列表失败，请稍后重试')
  } finally {
    loadingAlerts.value = false
  }
}

async function triggerDetection() {
  detecting.value = true
  try {
    const res = await post<{ newAlerts: number }>(`/${factoryId.value}/alerts/detect`, {})
    if (res?.success) {
      ElMessage.success(`检测完成，发现 ${res?.data?.newAlerts || 0} 条新告警`)
      await loadData()
    } else {
      ElMessage.error(res?.message || '检测失败')
    }
  } catch (e) {
    ElMessage.error('检测失败')
  } finally {
    detecting.value = false
  }
}

async function acknowledgeAlert(alert: AlertRecord) {
  try {
    const userId = authStore.user?.id || (authStore.user as { userId?: number | string } | null)?.userId
    if (!userId) {
      ElMessage.error('无法获取当前用户信息')
      return
    }
    // 后端 userId 是 @RequestParam，必须走 query string
    const res = await request.put(
      `/${factoryId.value}/alerts/${alert.id}/acknowledge`,
      null,
      { params: { userId } }
    ) as unknown as { success: boolean; message?: string }
    if (res?.success) {
      ElMessage.success('已确认')
      // Apr 18 2026 bug #49: 原来只刷 summary, 告警列表不更新 -> 用户看到行状态未变以为"没同步"。
      await Promise.all([loadData(), loadAlerts()])
    } else {
      ElMessage.error(res?.message || '操作失败')
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  }
}

function openResolveDialog(alert: AlertRecord) {
  selectedAlert.value = alert
  resolutionNotes.value = ''
  resolveDialogVisible.value = true
}

async function resolveAlert() {
  if (!selectedAlert.value) return
  resolving.value = true
  try {
    const userId = authStore.user?.id || (authStore.user as { userId?: number | string } | null)?.userId
    if (!userId) {
      ElMessage.error('无法获取当前用户信息')
      resolving.value = false
      return
    }
    // 后端 userId 是 @RequestParam（走 query）, resolutionNotes 是 body
    const res = await request.put(
      `/${factoryId.value}/alerts/${selectedAlert.value.id}/resolve`,
      { resolutionNotes: resolutionNotes.value },
      { params: { userId } }
    ) as unknown as { success: boolean; message?: string }
    if (res?.success) {
      ElMessage.success('已解决')
      resolveDialogVisible.value = false
      // Apr 18 2026 bug #49: 同 acknowledgeAlert, 解决后也必须同时刷新列表。
      await Promise.all([loadData(), loadAlerts()])
    } else {
      ElMessage.error(res?.message || '操作失败')
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  } finally {
    resolving.value = false
  }
}

function showDetail(alert: AlertRecord) {
  selectedAlert.value = alert
  detailDrawerVisible.value = true
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadAlerts()
}

onMounted(() => {
  if (isRestaurant.value) loadRestaurantAlerts()
  loadData()
})
</script>

<template>
  <div class="alert-dashboard">
    <div class="page-header">
      <h2>{{ isRestaurant ? '餐饮运营异常预警' : '生产异常预警' }}</h2>
      <div class="controls">
        <el-button v-if="!isRestaurant" type="primary" @click="triggerDetection" :loading="detecting">
          手动检测
        </el-button>
        <el-button @click="isRestaurant ? loadRestaurantAlerts() : loadData()" :icon="Refresh">刷新</el-button>
      </div>
    </div>

    <!-- Jul 11 2026: restaurant engine-backed standing alert dashboard.
         Replaces the 3 hardcoded client-side rules with the real 14-metric
         DiagnosticsEngine (via GET /health-check-report) — same data/engine
         as HealthReportView.vue's AI 经营体检, rendered here as alerts. -->
    <div v-if="isRestaurant" class="restaurant-alerts" style="margin-bottom:20px" data-test="restaurant-alert-panel">
      <!-- 加载中 -->
      <div v-if="healthReportLoading" v-loading="true" element-loading-text="正在检测经营异常..." style="height:160px" />

      <!-- 错误 (4-位一体: sticky toast 已在 loadRestaurantAlerts 内触发, 这里再显式展示) -->
      <el-alert
        v-else-if="healthReportError"
        :title="healthReportError"
        type="error"
        :closable="false"
        show-icon
        data-test="restaurant-alert-error"
      />

      <template v-else-if="healthReport">
        <!-- 摘要徽章 -->
        <div class="summary-row-restaurant" data-test="restaurant-summary-row">
          <div class="summary-card critical">
            <div class="summary-count">{{ healthSummary?.criticalCount ?? 0 }}</div>
            <div class="summary-label">严重</div>
          </div>
          <div class="summary-card warning">
            <div class="summary-count">{{ healthSummary?.warningCount ?? 0 }}</div>
            <div class="summary-label">预警</div>
          </div>
          <div class="summary-card resolved">
            <div class="summary-count">{{ healthSummary?.checkedCount ?? 0 }}</div>
            <div class="summary-label">已检查指标</div>
          </div>
          <el-tooltip
            v-if="healthSummary?.coverageNote"
            :content="healthSummary.coverageNote"
            placement="bottom"
            data-test="restaurant-coverage-tooltip"
          >
            <span class="coverage-hint">数据覆盖说明</span>
          </el-tooltip>
        </div>

        <!-- 全部健康 — 诚实空态, 不编造告警 -->
        <el-empty
          v-if="healthAllClear"
          description="本期无越界预警,经营指标均在正常范围"
          style="padding:30px 0"
          data-test="restaurant-all-clear"
        />

        <!-- 诊断卡片墙 (复用 AI 经营体检同款 DiagnosisCard) -->
        <div v-else class="card-wall">
          <DiagnosisCard
            v-for="(d, idx) in healthDiagnoses"
            :key="d.metricKey"
            :diagnosis="d"
            :default-expanded="d.severity === 'critical' && idx < 3"
          />
        </div>

        <div v-if="healthMeta" class="restaurant-report-footer">
          周期: {{ healthMeta.period }} · 业态: {{ healthMeta.subSector }} · 快照: {{ healthMeta.snapshotAt }}
          <span v-if="healthMeta.cacheHit"> · (缓存)</span>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-top:16px" show-icon>
        <template #title>关于本看板</template>
        <div style="font-size:12px;line-height:1.7">
          预警来自真实 14 指标经营诊断引擎 (食材成本率/人力成本率/折扣率/外卖依赖度/
          渠道收款率/评分趋势/食材损耗率/客单价达标率 等), 与"AI 经营体检"共用同一套
          行业基准阈值, 不是聊天问答也不是固定硬编码规则. 部分指标(如翻台率/充卡赠送
          依赖度/菜品毛利率)因暂无可靠数据来源而诚实跳过, 不编造数值 — 详见"数据覆盖
          说明".
        </div>
      </el-alert>
    </div>

    <!-- Factory tenant: manufacturing alerts only (original flow) -->
    <template v-if="!isRestaurant">

    <!-- Summary Cards -->
    <div class="summary-row" v-loading="loading" empty-text="暂无数据">
      <div class="summary-card critical">
        <div class="summary-count">{{ summary.criticalCount || 0 }}</div>
        <div class="summary-label">严重告警</div>
      </div>
      <div class="summary-card warning">
        <div class="summary-count">{{ summary.warningCount || 0 }}</div>
        <div class="summary-label">警告</div>
      </div>
      <div class="summary-card active">
        <div class="summary-count">{{ summary.activeCount || 0 }}</div>
        <div class="summary-label">待处理</div>
      </div>
      <div class="summary-card resolved">
        <div class="summary-count">{{ summary.resolvedToday || 0 }}</div>
        <div class="summary-label">今日已解决</div>
      </div>
    </div>

    <!-- Filters -->
    <div class="filter-bar">
      <el-select v-model="filterStatus" placeholder="状态筛选" clearable @change="loadAlerts" style="width: 140px">
        <el-option label="待处理" value="ACTIVE" />
        <el-option label="已确认" value="ACKNOWLEDGED" />
        <el-option label="已解决" value="RESOLVED" />
        <el-option label="已验证" value="VERIFIED" />
      </el-select>
      <el-select v-model="filterLevel" placeholder="级别筛选" clearable @change="loadAlerts" style="width: 140px; margin-left: 12px">
        <el-option label="严重" value="CRITICAL" />
        <el-option label="警告" value="WARNING" />
        <el-option label="信息" value="INFO" />
      </el-select>
    </div>

    <!-- Alert List -->
    <el-table :data="alerts" stripe border v-loading="loadingAlerts" empty-text="暂无数据" style="margin-top: 16px">
      <el-table-column prop="level" label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="row.level === 'CRITICAL' ? 'danger' : row.level === 'WARNING' ? 'warning' : 'info'" size="small">
            {{ levelLabel(row.level) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="alertType" label="类型" width="120">
        <template #default="{ row }">
          {{ alertTypeLabel(row.alertType) }}
        </template>
      </el-table-column>
      <el-table-column prop="metricName" label="指标" width="120">
        <template #default="{ row }">
          {{ metricLabel(row.metricName) }}
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="currentValue" label="当前值" width="100">
        <template #default="{ row }">
          {{ row.currentValue != null ? Number(row.currentValue).toFixed(1) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="baselineValue" label="基线值" width="100">
        <template #default="{ row }">
          {{ row.baselineValue != null ? Number(row.baselineValue).toFixed(1) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="160">
        <template #default="{ row }">
          {{ row.createdAt ? row.createdAt.replace('T', ' ').substring(0, 19) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" type="warning" size="small" @click="acknowledgeAlert(row)">确认</el-button>
          <el-button v-if="row.status === 'ACTIVE' || row.status === 'ACKNOWLEDGED'" type="success" size="small" @click="openResolveDialog(row)">解决</el-button>
          <el-button type="primary" size="small" link @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Pagination -->
    <el-pagination
      v-if="totalAlerts > pageSize"
      :current-page="currentPage"
      :page-size="pageSize"
      :total="totalAlerts"
      layout="total, prev, pager, next"
      @current-change="handlePageChange"
      style="margin-top: 16px; justify-content: flex-end"
    />

    <!-- Resolve Dialog -->
    <el-dialog v-model="resolveDialogVisible" title="解决告警" width="500px" destroy-on-close>
      <el-input v-model="resolutionNotes" type="textarea" :rows="4" placeholder="请输入解决说明..." />
      <template #footer>
        <el-button @click="resolveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="resolveAlert" :loading="resolving">确认解决</el-button>
      </template>
    </el-dialog>

    <!-- Detail Drawer -->
    <!-- Apr 18 2026 bug #52 (用户报 "title 与实际数据不符"):
         表格列用了 levelLabel/alertTypeLabel/metricLabel 本地化文本, 但详情
         抽屉直接显示原始枚举码 (如 THRESHOLD_EXCEEDED / CRITICAL), 导致
         用户看表格"严重 · 阈值超标"进详情变"CRITICAL · THRESHOLD_EXCEEDED"
         误以为 title 绑错。统一用 *Label() 函数。 -->
    <el-drawer v-model="detailDrawerVisible" title="告警详情" size="500px">
      <div v-if="selectedAlert" class="alert-detail">
        <div class="detail-row">
          <span class="detail-label">告警类型:</span>
          <span>{{ alertTypeLabel(selectedAlert.alertType) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">级别:</span>
          <el-tag :type="selectedAlert.level === 'CRITICAL' ? 'danger' : 'warning'" size="small">{{ levelLabel(selectedAlert.level) }}</el-tag>
        </div>
        <div class="detail-row">
          <span class="detail-label">指标:</span>
          <span>{{ metricLabel(selectedAlert.metricName) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">当前值:</span>
          <span class="value-bad">{{ selectedAlert.currentValue }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">基线值:</span>
          <span>{{ selectedAlert.baselineValue }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">偏差:</span>
          <span class="value-bad">{{ selectedAlert.deviationPercent != null ? selectedAlert.deviationPercent.toFixed(1) + '%' : '-' }}</span>
        </div>
        <div class="detail-row" v-if="selectedAlert.productName">
          <span class="detail-label">产品:</span>
          <span>{{ selectedAlert.productName }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">描述:</span>
          <span>{{ selectedAlert.description }}</span>
        </div>
        <div v-if="selectedAlert.aiAnalysis" class="ai-analysis-section">
          <h4>AI 根因分析</h4>
          <div v-html="DOMPurify.sanitize(marked(selectedAlert.aiAnalysis) as string)"></div>
        </div>
        <div class="status-timeline">
          <h4>状态时间线</h4>
          <el-timeline>
            <el-timeline-item :timestamp="selectedAlert.createdAt" type="danger">创建告警</el-timeline-item>
            <el-timeline-item v-if="selectedAlert.acknowledgedAt" :timestamp="selectedAlert.acknowledgedAt" type="warning">已确认</el-timeline-item>
            <el-timeline-item v-if="selectedAlert.resolvedAt" :timestamp="selectedAlert.resolvedAt" type="success">
              已解决{{ selectedAlert.resolutionNotes ? ': ' + selectedAlert.resolutionNotes : '' }}
            </el-timeline-item>
            <el-timeline-item v-if="selectedAlert.verifiedAt" :timestamp="selectedAlert.verifiedAt" type="primary">
              {{ selectedAlert.autoVerified ? '自动验证通过' : '人工验证通过' }}
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>
    </template><!-- /!isRestaurant -->
  </div>
</template>

<style lang="scss" scoped>
.restaurant-alerts {
  .summary-row-restaurant {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 16px;
    flex-wrap: wrap;

    .summary-card {
      min-width: 100px;
      padding: 14px 20px;
    }
  }
  .coverage-hint {
    font-size: 13px;
    color: #909399;
    cursor: help;
    text-decoration: underline dotted;
  }
  .card-wall {
    margin-top: 8px;
  }
  .restaurant-report-footer {
    margin-top: 16px;
    padding-top: 12px;
    border-top: 1px solid #ebeef5;
    font-size: 12px;
    color: #c0c4cc;
    text-align: center;
  }
}
.alert-dashboard {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  h2 {
    margin: 0;
    font-size: 20px;
    color: #303133;
  }
}

.controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  padding: 20px;
  border-radius: 12px;
  color: #fff;
  text-align: center;

  &.critical {
    background: linear-gradient(135deg, #ef4444, #dc2626);
  }
  &.warning {
    background: linear-gradient(135deg, #f59e0b, #d97706);
  }
  &.active {
    background: linear-gradient(135deg, #3b82f6, #2563eb);
  }
  &.resolved {
    background: linear-gradient(135deg, #10b981, #059669);
  }
}

.summary-count {
  font-size: 32px;
  font-weight: 700;
}

.summary-label {
  font-size: 14px;
  opacity: 0.9;
  margin-top: 4px;
}

.filter-bar {
  display: flex;
  align-items: center;
}

.alert-detail {
  .detail-row {
    display: flex;
    padding: 8px 0;
    border-bottom: 1px solid #f0f0f0;
  }
}

.detail-label {
  color: #666;
  width: 80px;
  flex-shrink: 0;
}

.value-bad {
  color: #ef4444;
  font-weight: 600;
}

.ai-analysis-section {
  margin-top: 16px;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;

  h4 {
    margin: 0 0 8px 0;
    color: #303133;
  }
}

.status-timeline {
  margin-top: 20px;

  h4 {
    margin: 0 0 12px 0;
    color: #303133;
  }
}
</style>
