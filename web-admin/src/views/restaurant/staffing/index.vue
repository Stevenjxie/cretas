<script setup lang="ts">
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { usePermissionStore } from '@/store/modules/permission';
import { getFactoryId, getUserData } from '@/api/smartbi/common';
import { askRestaurantQuestion } from '@/api/smartbi/restaurant-chat';
import {
  applyRestaurantStaffingAdjustment,
  getRestaurantStaffingDashboard,
} from '@/api/smartbi/restaurant-staffing';
import type {
  StaffingDailyRow,
  StaffingDashboard,
  StaffingHorizon,
  StaffingRolePlan,
  StaffingSummaryRow,
} from '@/types/restaurant-staffing';
import {
  isGroundedStaffingIntent,
  filterAndSortStaffingRows,
  gapLabel,
  gapTagType,
  paginateStaffingRows,
  resolveStaffingAiQuery,
  STAFFING_QUICK_QUESTIONS,
  staffingQuestionForHorizon,
  staffingPerspective,
} from './staffingViewModel';
import type { StaffingGapFilter, StaffingSortMode } from './staffingViewModel';

const permission = usePermissionStore();
const perspective = computed(() => staffingPerspective(permission.currentRole));
const horizon = ref<StaffingHorizon>('tomorrow');
const dashboard = ref<StaffingDashboard | null>(null);
const loading = ref(false);
const loadError = ref('');
const detailOpen = ref(false);
const selectedSummary = ref<StaffingSummaryRow | null>(null);
const question = ref('明天怎么排班');
type StaffingAiStatus = 'idle' | 'thinking' | 'done' | 'error';
const aiStatus = ref<StaffingAiStatus>('idle');
const aiAnswer = ref('');
const aiError = ref('');
const aiLastQuestion = ref('');
const aiRetryQuestion = ref('');
const aiSessionId = ref<string | null>(null);
const aiFollowups = ref<string[]>([]);
const aiIntentCode = ref<string | null>(null);
const selectedStoreId = ref<number | null>(null);
const selectedDaypart = ref('');
const gapFilter = ref<StaffingGapFilter>('all');
const sortMode = ref<StaffingSortMode>('gap-desc');
const currentPage = ref(1);
const pageSize = ref(10);

const adjustDialog = reactive({
  open: false,
  submitting: false,
  daily: null as StaffingDailyRow | null,
  role: null as StaffingRolePlan | null,
  adjustedStaff: 0,
  reason: '按预测 FactBook 建议调整',
});

const horizonOptions: Array<{ value: StaffingHorizon; label: string; note: string }> = [
  { value: 'tomorrow', label: '明天', note: '单日执行' },
  { value: 'week', label: '下周', note: '兼职与周工时' },
  { value: 'month', label: '下个月', note: '各店人效规划' },
];

const summary = computed(() => dashboard.value?.summary);
const sources = computed(() => dashboard.value?.sources ?? []);
const hasSimulation = computed(() => sources.value.some((item) => item.isSimulated));
const asking = computed(() => aiStatus.value === 'thinking');
const aiAnswerHtml = computed(() => (
  aiAnswer.value ? DOMPurify.sanitize(marked(aiAnswer.value) as string) : ''
));
const aiIsGroundedStaffing = computed(() => isGroundedStaffingIntent(aiIntentCode.value));
const aiWindowLabel = computed(() => {
  const current = dashboard.value;
  if (!current) return '预测范围加载中';
  return `${current.horizonLabel} · ${current.windowStart} 至 ${current.windowEnd}`;
});
const allSummaryRows = computed(() => dashboard.value?.summaryRows ?? []);
const storeOptions = computed(() => {
  const stores = new Map<number, string>();
  for (const row of allSummaryRows.value) stores.set(row.storeId, row.storeName);
  return [...stores.entries()]
    .map(([value, label]) => ({ value, label }))
    .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
});
const daypartOptions = computed(() => [...new Set(allSummaryRows.value.map((row) => row.daypart))]);
const filteredSummaryRows = computed(() => filterAndSortStaffingRows(allSummaryRows.value, {
  storeId: selectedStoreId.value,
  daypart: selectedDaypart.value,
  gap: gapFilter.value,
  sort: sortMode.value,
}));
const summaryPage = computed(() => paginateStaffingRows(
  filteredSummaryRows.value,
  currentPage.value,
  pageSize.value,
));
const pagedSummaryRows = computed(() => summaryPage.value.rows);
const hasActiveTableFilters = computed(() => (
  selectedStoreId.value !== null || selectedDaypart.value !== '' || gapFilter.value !== 'all'
));
const detailRows = computed(() => {
  const selected = selectedSummary.value;
  if (!selected || !dashboard.value) return [];
  return dashboard.value.dailyRows.filter(
    (row) => row.storeId === selected.storeId && row.daypart === selected.daypart,
  );
});

async function loadDashboard() {
  loading.value = true;
  loadError.value = '';
  try {
    dashboard.value = await getRestaurantStaffingDashboard(horizon.value);
    if (
      selectedStoreId.value !== null
      && !dashboard.value.summaryRows.some((row) => row.storeId === selectedStoreId.value)
    ) {
      selectedStoreId.value = null;
    }
    currentPage.value = 1;
  } catch (error) {
    console.error('[restaurant-staffing] dashboard failed', error);
    dashboard.value = null;
    loadError.value = error instanceof Error ? error.message : '预测排班读取失败';
  } finally {
    loading.value = false;
  }
}

watch(horizon, loadDashboard, { immediate: true });
watch(
  [selectedStoreId, selectedDaypart, gapFilter, sortMode, pageSize],
  () => { currentPage.value = 1; },
);

function resetTableFilters() {
  selectedStoreId.value = null;
  selectedDaypart.value = '';
  gapFilter.value = 'all';
}

function resetAiConversation() {
  aiStatus.value = 'idle';
  aiAnswer.value = '';
  aiError.value = '';
  aiLastQuestion.value = '';
  aiRetryQuestion.value = '';
  aiSessionId.value = null;
  aiFollowups.value = [];
  aiIntentCode.value = null;
}

function changeHorizon(next: StaffingHorizon) {
  if (next === horizon.value) return;
  const currentQuestion = question.value.trim();
  horizon.value = next;
  if (!currentQuestion || STAFFING_QUICK_QUESTIONS.some((item) => item === currentQuestion)) {
    question.value = staffingQuestionForHorizon(next);
  }
  resetAiConversation();
}

function openDetail(row: StaffingSummaryRow) {
  selectedSummary.value = row;
  detailOpen.value = true;
}

function openAdjustment(daily: StaffingDailyRow, role: StaffingRolePlan) {
  adjustDialog.daily = daily;
  adjustDialog.role = role;
  adjustDialog.adjustedStaff = role.recommendedStaff;
  adjustDialog.reason = '按预测 FactBook 建议调整';
  adjustDialog.open = true;
}

function idempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `staffing-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function confirmAdjustment() {
  const daily = adjustDialog.daily;
  const role = adjustDialog.role;
  if (!daily || !role) return;
  if (!adjustDialog.reason.trim()) {
    ElMessage.warning('请填写调整原因');
    return;
  }
  adjustDialog.submitting = true;
  try {
    const receipt = await applyRestaurantStaffingAdjustment({
      storeId: daily.storeId,
      targetDate: daily.date,
      daypart: daily.daypart as '午市' | '下午茶' | '晚市' | '夜宵',
      roleCode: role.roleCode,
      predictedGuests: daily.predictedGuests,
      policyVersion: role.policyVersion,
      priorStaff: role.currentStaff,
      recommendedStaff: role.recommendedStaff,
      adjustedStaff: adjustDialog.adjustedStaff,
      planFingerprint: role.planFingerprint,
      reason: adjustDialog.reason.trim(),
      idempotencyKey: idempotencyKey(),
    });
    ElMessage.success(
      receipt.businessWrite
        ? `调整已写入审计，记录 #${receipt.adjustmentId}`
        : `该调整已处理，记录 #${receipt.adjustmentId}`,
    );
    adjustDialog.open = false;
    await loadDashboard();
  } catch (error) {
    console.error('[restaurant-staffing] adjustment failed', error);
    ElMessage.error(error instanceof Error ? error.message : '排班调整失败');
  } finally {
    adjustDialog.submitting = false;
  }
}

async function askQuestion(prompt?: string) {
  const effective = (prompt ?? question.value).trim();
  if (!effective) return;
  let resolved = resolveStaffingAiQuery(effective, horizon.value, Boolean(aiSessionId.value));
  if (resolved.horizon !== horizon.value) {
    changeHorizon(resolved.horizon);
    resolved = resolveStaffingAiQuery(effective, horizon.value, false);
  }
  question.value = effective;
  aiStatus.value = 'thinking';
  aiError.value = '';
  aiRetryQuestion.value = effective;
  try {
    const user = getUserData() as Record<string, unknown>;
    const factoryUser = user.factoryUser && typeof user.factoryUser === 'object'
      ? user.factoryUser as Record<string, unknown>
      : {};
    const userId = user.userId ?? user.id ?? factoryUser.userId ?? factoryUser.id ?? factoryUser.username;
    if (typeof userId !== 'string' && typeof userId !== 'number') {
      throw new Error('当前登录用户标识缺失，请重新登录');
    }
    const response = await askRestaurantQuestion({
      factoryId: getFactoryId(),
      userId: String(userId),
      query: resolved.requestQuestion,
      sessionId: aiSessionId.value ?? undefined,
    });
    if (!response.success) throw new Error(response.error || response.message);
    aiAnswer.value = response.message ?? '已完成分析';
    aiLastQuestion.value = resolved.displayQuestion;
    aiIntentCode.value = response.intentCode;
    aiSessionId.value = response.javaSessionId ?? response.sessionId ?? null;
    aiFollowups.value = response.followUpChips ?? [];
    aiRetryQuestion.value = '';
    aiStatus.value = 'done';
  } catch (error) {
    console.error('[restaurant-staffing] AI question failed', error);
    aiError.value = error instanceof Error ? error.message : 'AI 问答暂不可用';
    aiStatus.value = 'error';
  }
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '等待 FactBook';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed);
}

function formatPct(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value.toFixed(1)}%`;
}
</script>

<template>
  <main class="staffing-page" aria-labelledby="staffing-title">
    <header class="staffing-hero">
      <div>
        <div class="eyebrow">餐饮运营 · {{ perspective.label }}</div>
        <h1 id="staffing-title">{{ perspective.title }}</h1>
        <p>{{ perspective.description }}</p>
      </div>
      <div class="hero-facts">
        <span>数字来源</span>
        <strong>预测 FactBook</strong>
        <small>大模型只解释，不生成数字</small>
      </div>
    </header>

    <section class="horizon-switch" aria-label="预测范围">
      <button
        v-for="item in horizonOptions"
        :key="item.value"
        type="button"
        :class="{ active: horizon === item.value }"
        @click="changeHorizon(item.value)"
      >
        <strong>{{ item.label }}</strong>
        <span>{{ item.note }}</span>
      </button>
    </section>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="page-alert"
    >
      <template #default><el-button link type="primary" @click="loadDashboard">重新读取</el-button></template>
    </el-alert>
    <el-alert
      v-else-if="hasSimulation"
      title="当前预订包含模拟来源"
      description="MOCK_REST 与 RES_3101_009 的模拟预订会保留来源标签；接入正式平台后同一接口会显示真实来源，系统不会把模拟数据伪装成真实预订。"
      type="warning"
      show-icon
      :closable="false"
      class="page-alert"
    />

    <section v-loading="loading" class="metric-grid" aria-label="排班关键指标">
      <article class="metric-card metric-card--primary">
        <span>预订覆盖</span>
        <strong>{{ formatPct(summary?.reservationCoveragePct) }}</strong>
        <small>当前有效预订 / 预测客流</small>
      </article>
      <article class="metric-card">
        <span>预测客流</span>
        <strong>{{ summary?.predictedGuests?.toLocaleString('zh-CN') ?? '—' }}</strong>
        <small>{{ dashboard?.windowStart }} 至 {{ dashboard?.windowEnd }}</small>
      </article>
      <article class="metric-card">
        <span>建议 / 现有</span>
        <strong>{{ summary ? `${summary.recommendedStaff} / ${summary.currentStaff}` : '—' }}</strong>
        <small>按各店时段峰值班次汇总</small>
      </article>
      <article class="metric-card" :class="{ 'metric-card--danger': (summary?.positiveGap ?? 0) > 0 }">
        <span>正向缺口</span>
        <strong>{{ summary?.positiveGap ?? '—' }}</strong>
        <small>只由预测需求与岗位约束产生</small>
      </article>
      <article class="metric-card">
        <span>兼职建议</span>
        <strong>{{ summary?.partTimePeople ?? '—' }}</strong>
        <small>按缺口工时折算</small>
      </article>
      <article class="metric-card">
        <span>平均置信度</span>
        <strong>{{ formatPct(summary?.confidencePct) }}</strong>
        <small>{{ perspective.focus }}</small>
      </article>
    </section>

    <section class="content-grid">
      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="section-heading">
            <div><h2>各门店 · 各时段</h2><p>历史人效仅作证据，不参与“缺人”方向判断。</p></div>
            <div class="section-meta">
              <span>{{ filteredSummaryRows.length }} / {{ allSummaryRows.length }} 条</span>
              <el-tag type="info" effect="plain">{{ dashboard?.horizonLabel ?? '预测范围' }}</el-tag>
            </div>
          </div>
        </template>
        <div class="table-toolbar" aria-label="门店明细筛选">
          <el-select
            v-model="selectedStoreId"
            clearable
            filterable
            placeholder="搜索或选择门店…"
            aria-label="搜索或选择门店"
            class="store-filter"
          >
            <el-option
              v-for="store in storeOptions"
              :key="store.value"
              :label="store.label"
              :value="store.value"
            />
          </el-select>
          <el-select v-model="selectedDaypart" aria-label="筛选营业时段" class="compact-filter">
            <el-option label="全部时段" value="" />
            <el-option v-for="item in daypartOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="gapFilter" aria-label="筛选人力状态" class="compact-filter">
            <el-option label="全部人力状态" value="all" />
            <el-option label="仅看缺人" value="shortage" />
            <el-option label="刚好匹配" value="balanced" />
            <el-option label="人力有余" value="surplus" />
          </el-select>
          <el-select v-model="sortMode" aria-label="门店明细排序" class="sort-filter">
            <el-option label="急缺优先" value="gap-desc" />
            <el-option label="客流从高到低" value="demand-desc" />
            <el-option label="低置信度优先" value="confidence-asc" />
            <el-option label="按门店名称" value="store" />
          </el-select>
          <el-button v-if="hasActiveTableFilters" link type="primary" @click="resetTableFilters">清除筛选</el-button>
        </div>
        <el-table
          :data="pagedSummaryRows"
          :row-key="(row: StaffingSummaryRow) => `${row.storeId}-${row.daypart}`"
          :max-height="560"
          stripe
          :empty-text="hasActiveTableFilters ? '没有符合当前筛选的门店时段' : '暂无可计算的预测事实'"
        >
          <el-table-column prop="storeName" label="门店" min-width="150" fixed />
          <el-table-column prop="daypart" label="时段" width="90" />
          <el-table-column label="预订覆盖" width="110" align="right">
            <template #default="{ row }">{{ formatPct(row.reservationCoveragePct) }}</template>
          </el-table-column>
          <el-table-column prop="predictedGuests" label="预测客流" width="105" align="right" />
          <el-table-column label="建议 / 现有" width="115" align="right">
            <template #default="{ row }">{{ row.recommendedStaff }} / {{ row.currentStaff }}</template>
          </el-table-column>
          <el-table-column label="缺口" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="gapTagType(row.gap)" effect="light">{{ gapLabel(row.gap) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="95" align="right">
            <template #default="{ row }">{{ formatPct(row.confidencePct) }}</template>
          </el-table-column>
          <el-table-column prop="partTimePeople" label="兼职" width="75" align="right" />
          <el-table-column label="操作" width="105" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">查看班次</el-button>
            </template>
          </el-table-column>
        </el-table>
        <footer v-if="allSummaryRows.length" class="table-footer">
          <span>当前显示 {{ summaryPage.from }}–{{ summaryPage.to }} 条，共 {{ summaryPage.total }} 条</span>
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50]"
            :total="summaryPage.total"
            :pager-count="5"
            background
            layout="sizes, prev, pager, next, jumper"
          />
        </footer>
      </el-card>

      <aside class="side-stack">
        <el-card shadow="never" class="source-card">
          <template #header><h2>预订来源</h2></template>
          <div v-if="sources.length" class="source-list">
            <div v-for="source in sources" :key="source.source">
              <span class="source-dot" :class="{ simulated: source.isSimulated }"></span>
              <div><strong>{{ source.source }}</strong><small>{{ source.isSimulated ? '模拟数据' : '平台数据' }}</small></div>
            </div>
          </div>
          <el-empty v-else :image-size="52" description="当前范围没有预订来源" />
        </el-card>

        <el-card shadow="never" class="ai-card">
          <template #header>
            <div class="ai-card__header">
              <div><h2>问餐饮 AI</h2><p>排班数字来自 FactBook，大模型负责解释和建议。</p></div>
              <el-button
                v-if="aiSessionId || aiAnswer"
                link
                type="primary"
                :disabled="asking"
                @click="resetAiConversation"
              >新对话</el-button>
            </div>
          </template>
          <div class="ai-context" role="note" aria-label="餐饮 AI 当前分析范围">
            <div>
              <span>当前排班范围</span>
              <strong>{{ aiWindowLabel }}</strong>
              <small>FactBook 生成于 {{ formatDateTime(dashboard?.generatedAt) }}</small>
            </div>
            <el-tag type="info" effect="plain">全部门店</el-tag>
            <p>未写时间时按当前范围补全；表格的门店、时段和缺口筛选只影响列表，不会缩小 AI 的全店 FactBook。</p>
          </div>
          <div class="question-chips">
            <button v-for="item in STAFFING_QUICK_QUESTIONS" :key="item" type="button" @click="askQuestion(item)">{{ item }}</button>
          </div>
          <el-input
            v-model="question"
            placeholder="例如：晚市怎么安排…"
            aria-label="向餐饮 AI 提问"
            :disabled="asking"
            @keyup.enter="askQuestion()"
          >
            <template #append><el-button :loading="asking" @click="askQuestion()">分析排班</el-button></template>
          </el-input>
          <div v-if="asking" class="ai-progress" role="status" aria-live="polite">
            <span class="ai-progress__dot" aria-hidden="true"></span>
            <div><strong>正在分析排班问题</strong><small>识别范围 → 生成预测 FactBook → 大模型解释</small></div>
          </div>
          <el-alert v-if="aiError" :title="aiError" type="error" show-icon :closable="false" class="ai-error">
            <template #default>
              <span v-if="aiAnswer">上一条有效回答仍保留。</span>
              <span v-else>本次没有产生新的排班结论。</span>
              <el-button link type="primary" :disabled="asking" @click="askQuestion(aiRetryQuestion)">重试这次问题</el-button>
            </template>
          </el-alert>
          <article v-if="aiAnswer" class="ai-answer" aria-live="polite">
            <header class="ai-answer__header">
              <div><span>你的问题</span><strong>{{ aiLastQuestion }}</strong></div>
              <el-tag v-if="aiIsGroundedStaffing" type="success" effect="plain">排班 FactBook 已绑定</el-tag>
            </header>
            <div v-if="aiIsGroundedStaffing" class="ai-trust-row" aria-label="回答依据">
              <span>数字：预测 FactBook</span>
              <span>解释：大模型</span>
              <span>历史人效：仅作证据</span>
            </div>
            <el-alert
              v-else
              title="本次回答未命中预测排班能力"
              description="该问题可能由其他餐饮分析能力回答，不能视为当前排班 FactBook 的解释。"
              type="warning"
              :closable="false"
              show-icon
              class="ai-boundary-alert"
            />
            <div class="ai-answer__content" v-html="aiAnswerHtml"></div>
          </article>
          <div v-if="aiFollowups.length" class="ai-followups" aria-label="继续追问">
            <span>继续追问</span>
            <button v-for="item in aiFollowups" :key="item" type="button" :disabled="asking" @click="askQuestion(item)">{{ item }}</button>
          </div>
        </el-card>
      </aside>
    </section>

    <el-drawer v-model="detailOpen" size="720px" :title="`${selectedSummary?.storeName ?? ''} · ${selectedSummary?.daypart ?? ''}`">
      <div class="drawer-intro">逐日展开岗位技能、工时、建议人数与已确认调整。调整会产生业务写入审计。</div>
      <section v-for="daily in detailRows" :key="`${daily.date}-${daily.daypart}`" class="shift-day">
        <div class="shift-day__header">
          <div><strong>{{ daily.date }}</strong><span>预测 {{ daily.predictedGuests }} 人 · 预订 {{ daily.reservedGuests }} 人</span></div>
          <el-tag :type="daily.positiveGap > 0 ? 'danger' : 'success'">{{ gapLabel(daily.gap) }}</el-tag>
        </div>
        <el-table :data="daily.roles" size="small">
          <el-table-column prop="roleName" label="岗位" min-width="90" />
          <el-table-column prop="requiredSkill" label="技能" min-width="120" />
          <el-table-column prop="shiftHours" label="工时" width="65" align="right" />
          <el-table-column label="建议 / 现有" width="105" align="right">
            <template #default="{ row }">{{ row.recommendedStaff }} / {{ row.currentStaff }}</template>
          </el-table-column>
          <el-table-column label="已排" width="75" align="right">
            <template #default="{ row }">{{ row.adjustedStaff ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="技能缺口" width="85" align="right">
            <template #default="{ row }">{{ row.skillGap }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button
                v-if="perspective.canAdjust"
                size="small"
                type="primary"
                plain
                @click="openAdjustment(daily, row)"
              >按建议调整</el-button>
              <span v-else>只读</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </el-drawer>

    <el-dialog v-model="adjustDialog.open" title="确认排班调整" width="480px" destroy-on-close>
      <el-alert
        title="这会写入排班调整审计"
        description="提交时后端会重新生成当前预测并核对计划指纹；预订、策略或建议人数已变化时会拒绝旧预览。"
        type="warning"
        :closable="false"
        show-icon
      />
      <dl class="confirm-facts">
        <div><dt>门店 / 时段</dt><dd>{{ adjustDialog.daily?.storeName }} · {{ adjustDialog.daily?.daypart }}</dd></div>
        <div><dt>日期 / 岗位</dt><dd>{{ adjustDialog.daily?.date }} · {{ adjustDialog.role?.roleName }}</dd></div>
        <div><dt>预测 / 建议</dt><dd>{{ adjustDialog.daily?.predictedGuests }} 人 · {{ adjustDialog.role?.recommendedStaff }} 人</dd></div>
      </dl>
      <el-form label-position="top">
        <el-form-item label="确认人数">
          <el-input-number v-model="adjustDialog.adjustedStaff" :min="0" :max="1000" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="adjustDialog.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="adjustDialog.submitting" @click="confirmAdjustment">确认并记录</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.staffing-page { min-height: 100%; padding: 24px; background: #f5f7fa; color: #1f2937; }
.staffing-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 28px 30px; border: 1px solid #dbe4ef; border-radius: 16px; background: linear-gradient(135deg, #fff 0%, #f2f7fc 100%); box-shadow: 0 8px 26px rgb(30 64 110 / 7%); }
.eyebrow { margin-bottom: 8px; color: #1b65a8; font-size: 13px; font-weight: 700; letter-spacing: .08em; }
.staffing-hero h1 { margin: 0; font-size: 30px; line-height: 1.2; letter-spacing: -.02em; }
.staffing-hero h1, .section-heading h2 { text-wrap: balance; }
.staffing-hero p { margin: 10px 0 0; color: #667085; }
.hero-facts { min-width: 210px; padding: 16px 18px; border-left: 3px solid #1b65a8; background: rgb(255 255 255 / 72%); }
.hero-facts span, .hero-facts small { display: block; color: #667085; font-size: 12px; }
.hero-facts strong { display: block; margin: 4px 0; font-size: 18px; }
.horizon-switch { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; max-width: 640px; margin: 18px 0; }
.horizon-switch button { display: flex; align-items: center; justify-content: space-between; min-height: 54px; padding: 10px 14px; border: 1px solid #d8dee8; border-radius: 10px; background: #fff; color: #344054; cursor: pointer; transition: border-color .18s, box-shadow .18s, transform .18s; }
.horizon-switch button:hover { border-color: #75a7d6; transform: translateY(-1px); }
.horizon-switch button:focus-visible, .question-chips button:focus-visible, .ai-followups button:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
.horizon-switch button.active { border-color: #1b65a8; color: #1b65a8; box-shadow: 0 0 0 2px rgb(27 101 168 / 10%); }
.horizon-switch span { font-size: 12px; color: #8491a3; }
.page-alert { margin-bottom: 16px; }
.metric-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.metric-card { min-width: 0; padding: 18px; border: 1px solid #e1e7ef; border-radius: 12px; background: #fff; }
.metric-card span, .metric-card small { display: block; color: #768398; font-size: 12px; }
.metric-card strong { display: block; margin: 8px 0 6px; font-size: 25px; font-variant-numeric: tabular-nums; }
.metric-card--primary { border-top: 3px solid #1b65a8; }
.metric-card--danger { border-top: 3px solid #d84c4c; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(360px, 420px); gap: 16px; align-items: start; }
.table-card, .source-card, .ai-card { border: 1px solid #e1e7ef; border-radius: 12px; }
.section-heading { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.section-heading h2, .source-card h2, .ai-card h2 { margin: 0; font-size: 17px; }
.section-heading p, .ai-card p { margin: 5px 0 0; color: #7b8798; font-size: 12px; }
.section-meta { display: flex; align-items: center; gap: 10px; color: #7b8798; font-size: 12px; white-space: nowrap; }
.table-toolbar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px; padding: 12px; border: 1px solid #edf2f7; border-radius: 10px; background: #f8fafc; }
.store-filter { width: min(240px, 100%); }
.compact-filter { width: 150px; }
.sort-filter { width: 165px; }
.table-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 40px; padding-top: 14px; color: #7b8798; font-size: 12px; }
.table-card :deep(.el-table__cell) { font-variant-numeric: tabular-nums; }
.side-stack { display: grid; gap: 16px; }
.source-list { display: grid; gap: 12px; }
.source-list > div { display: flex; gap: 10px; align-items: center; padding: 10px; border-radius: 8px; background: #f7f9fc; }
.source-list strong, .source-list small { display: block; overflow-wrap: anywhere; }
.source-list small { margin-top: 2px; color: #8491a3; font-size: 11px; }
.source-dot { width: 9px; height: 9px; flex: none; border-radius: 50%; background: #2c8c5a; }
.source-dot.simulated { background: #d69732; }
.ai-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.ai-context { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 5px 10px; margin-bottom: 14px; padding: 12px; border: 1px solid #dfe8f1; border-radius: 10px; background: #f7fafc; }
.ai-context span, .ai-context small { display: block; color: #778397; font-size: 11px; }
.ai-context strong { display: block; margin: 3px 0; color: #27364a; font-size: 13px; }
.ai-context p { grid-column: 1 / -1; margin: 5px 0 0; padding-top: 8px; border-top: 1px solid #e5ebf2; line-height: 1.55; }
.question-chips, .ai-followups { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 12px; }
.question-chips button, .ai-followups button { padding: 7px 9px; border: 1px solid #d8e3ef; border-radius: 999px; background: #f5f9fd; color: #1b65a8; font-size: 12px; cursor: pointer; }
.question-chips button:disabled, .ai-followups button:disabled { cursor: not-allowed; opacity: .55; }
.ai-progress { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding: 12px; border-radius: 9px; background: #f5f8fc; color: #42526a; }
.ai-progress strong, .ai-progress small { display: block; }
.ai-progress small { margin-top: 3px; color: #7d8999; font-size: 11px; }
.ai-progress__dot { width: 10px; height: 10px; flex: none; border-radius: 50%; background: #1b65a8; animation: ai-pulse 1.2s ease-in-out infinite; }
.ai-error { margin-top: 12px; }
.ai-answer { margin-top: 12px; overflow: hidden; border: 1px solid #dfe7f0; border-radius: 10px; background: #fff; color: #475467; }
.ai-answer__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 11px 12px; border-bottom: 1px solid #e8edf3; background: #f8fafc; }
.ai-answer__header span, .ai-answer__header strong { display: block; }
.ai-answer__header span { color: #7d8999; font-size: 11px; }
.ai-answer__header strong { margin-top: 3px; color: #27364a; font-size: 13px; }
.ai-trust-row { display: flex; flex-wrap: wrap; gap: 6px; padding: 10px 12px 0; }
.ai-trust-row span { padding: 4px 7px; border-radius: 6px; background: #edf7f1; color: #2b6f4b; font-size: 11px; }
.ai-boundary-alert { margin: 10px 12px 0; }
.ai-answer__content { padding: 12px; overflow-wrap: anywhere; font-size: 13px; line-height: 1.7; }
.ai-answer__content :deep(h1), .ai-answer__content :deep(h2), .ai-answer__content :deep(h3) { margin: 14px 0 7px; color: #263548; font-size: 15px; line-height: 1.45; }
.ai-answer__content :deep(h1:first-child), .ai-answer__content :deep(h2:first-child), .ai-answer__content :deep(h3:first-child), .ai-answer__content :deep(p:first-child) { margin-top: 0; }
.ai-answer__content :deep(p), .ai-answer__content :deep(ul), .ai-answer__content :deep(ol) { margin: 8px 0; }
.ai-answer__content :deep(ul), .ai-answer__content :deep(ol) { padding-left: 20px; }
.ai-answer__content :deep(strong) { color: #263548; }
.ai-followups { margin: 12px 0 0; padding-top: 10px; border-top: 1px solid #e8edf3; }
.ai-followups > span { width: 100%; color: #7d8999; font-size: 11px; }
@keyframes ai-pulse { 0%, 100% { opacity: .35; transform: scale(.82); } 50% { opacity: 1; transform: scale(1); } }
.drawer-intro { margin-bottom: 14px; padding: 10px 12px; border-radius: 8px; background: #f5f7fa; color: #667085; font-size: 13px; }
.shift-day { margin-bottom: 16px; overflow: hidden; border: 1px solid #e1e7ef; border-radius: 10px; }
.shift-day__header { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; background: #f7f9fc; }
.shift-day__header strong, .shift-day__header span { display: block; }
.shift-day__header span { margin-top: 3px; color: #7b8798; font-size: 12px; }
.confirm-facts { display: grid; gap: 8px; margin: 16px 0; }
.confirm-facts > div { display: flex; justify-content: space-between; gap: 20px; }
.confirm-facts dt { color: #7b8798; }
.confirm-facts dd { margin: 0; font-weight: 600; text-align: right; }
@media (max-width: 1280px) { .metric-grid { grid-template-columns: repeat(3, 1fr); } .content-grid { grid-template-columns: 1fr; } .side-stack { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 760px) {
  .staffing-page { padding: 14px; }
  .staffing-hero { align-items: flex-start; flex-direction: column; }
  .hero-facts { width: 100%; box-sizing: border-box; }
  .horizon-switch, .metric-grid, .side-stack { grid-template-columns: 1fr; }
  .section-heading, .table-footer { align-items: flex-start; flex-direction: column; }
  .ai-card__header, .ai-answer__header { align-items: flex-start; flex-direction: column; }
  .table-toolbar > :deep(.el-select) { width: 100%; }
  .table-footer { overflow-x: auto; }
}
@media (prefers-reduced-motion: reduce) {
  .horizon-switch button { transition: none; }
  .horizon-switch button:hover { transform: none; }
  .ai-progress__dot { animation: none; }
}
</style>
