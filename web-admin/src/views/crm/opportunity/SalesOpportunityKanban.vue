<!--
  Sprint 7 wave 2 T4 — 销售商机 看板视图 (2026-05-20).

  Drag-and-drop 8-column kanban (LEAD → ... → CLOSED_WON / CLOSED_LOST).
  Drop 调用 transitionStage REST with reason prompt.

  防呆 R5: empty kanban → "新增商机" button + 跳转 List.
-->
<template>
  <div class="sales-opportunity-kanban">
    <div class="page-header">
      <h2>商机看板</h2>
      <div class="header-actions">
        <el-switch
          v-model="onlyMine"
          active-text="我的商机"
          inactive-text="全部"
          @change="fetchAll"
        />
        <el-button :icon="Refresh" @click="fetchAll">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="goCreate">
          新增商机
        </el-button>
        <el-button :icon="DataLine" @click="goList">列表视图</el-button>
      </div>
    </div>

    <!-- Funnel summary bar -->
    <el-card v-if="!loading && totalCount > 0" class="summary-bar" shadow="never">
      <div class="summary-row">
        <div class="summary-item">
          <div class="summary-label">总商机</div>
          <div class="summary-value">{{ totalCount }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">总金额</div>
          <div class="summary-value">{{ formatCurrency(totalAmount) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">预期成交 (× 概率)</div>
          <div class="summary-value highlight">{{ formatCurrency(totalExpected) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">赢单数</div>
          <div class="summary-value">{{ wonCount }}</div>
        </div>
      </div>
    </el-card>

    <div v-loading="loading" class="kanban-board">
      <div
        v-for="meta in STAGE_META"
        :key="meta.value"
        class="kanban-column"
        :class="{ 'drag-over': dragOverStage === meta.value }"
        @dragover.prevent="onDragOver(meta.value)"
        @dragleave="onDragLeave"
        @drop.prevent="onDrop(meta.value)"
      >
        <div class="column-header" :style="{ borderTopColor: meta.color }">
          <div class="column-title">
            <el-tag :type="meta.tagType" effect="dark">
              {{ meta.label }}
            </el-tag>
            <span class="column-count">
              {{ columnsByStage[meta.value]?.length || 0 }}
            </span>
          </div>
          <div class="column-stats">
            <div>金额: {{ formatCurrency(stageTotalAmount(meta.value)) }}</div>
            <div class="expected">
              预期: {{ formatCurrency(stageExpected(meta.value)) }}
            </div>
          </div>
        </div>

        <div class="column-body">
          <div
            v-for="opp in columnsByStage[meta.value] || []"
            :key="opp.id"
            class="opp-card"
            draggable="true"
            @dragstart="onDragStart($event, opp)"
            @dragend="onDragEnd"
            @click="openDetail(opp)"
          >
            <div class="opp-title">{{ opp.title }}</div>
            <div class="opp-meta">
              <div>客户: {{ opp.customer?.name || opp.customerId }}</div>
              <div>金额: {{ formatCurrency(opp.valueAmount) }}</div>
              <div>概率: {{ opp.probability }}%</div>
              <div v-if="opp.expectedCloseDate">
                预期: {{ opp.expectedCloseDate }}
              </div>
            </div>
          </div>

          <!-- 防呆 R5: empty column hint -->
          <div
            v-if="(columnsByStage[meta.value] || []).length === 0"
            class="empty-column-hint"
          >
            <el-icon><DocumentAdd /></el-icon>
            <span>无商机</span>
            <el-button
              v-if="meta.value === 'LEAD'"
              type="primary"
              size="small"
              :icon="Plus"
              @click="goCreate"
            >
              新增
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 防呆 R5: empty kanban (across all columns) -->
    <div
      v-if="!loading && totalCount === 0"
      class="empty-kanban"
    >
      <el-empty description="暂无商机数据">
        <el-button type="primary" :icon="Plus" @click="goCreate">
          新增首个商机
        </el-button>
      </el-empty>
    </div>

    <!-- Drop confirmation dialog -->
    <el-dialog
      v-model="dropDialogVisible"
      title="变更商机阶段"
      width="540px"
      :close-on-click-modal="false"
    >
      <div v-if="pendingDrop" class="drop-confirm">
        <div class="context-bar">
          <strong>{{ pendingDrop.opp.title }}</strong>
          <div>
            从
            <el-tag :type="stageMeta(pendingDrop.opp.stage).tagType" effect="light">
              {{ stageLabel(pendingDrop.opp.stage) }}
            </el-tag>
            →
            <el-tag :type="stageMeta(pendingDrop.toStage).tagType" effect="dark">
              {{ stageLabel(pendingDrop.toStage) }}
            </el-tag>
          </div>
        </div>

        <el-form label-width="100px">
          <el-form-item
            v-if="dropReasonRequired"
            label="原因"
            required
          >
            <el-select
              v-model="dropReasonPreset"
              placeholder="选择标准原因"
              style="width: 100%; margin-bottom: 6px"
            >
              <el-option
                v-for="r in reasonPresets"
                :key="r"
                :label="r"
                :value="r"
              />
              <el-option label="其他 (自定义)" value="OTHER" />
            </el-select>
            <el-input
              v-if="dropReasonPreset === 'OTHER'"
              v-model="dropReasonCustom"
              type="textarea"
              :rows="2"
              placeholder="补充原因详情"
            />
          </el-form-item>
          <el-form-item v-if="dropSkipForward" label="跳级确认">
            <el-checkbox v-model="dropConfirmSkip">
              确认跨越多个阶段直接转换
            </el-checkbox>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="cancelDrop">取消</el-button>
        <el-button
          type="primary"
          :loading="dropping"
          @click="confirmDrop"
        >
          确认变更
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  Plus,
  Refresh,
  DataLine,
  DocumentAdd,
} from '@element-plus/icons-vue';
import {
  STAGE_META,
  type OpportunityStage,
  type SalesOpportunity,
  stageMeta,
  stageLabel,
  formatCurrency,
  expectedValue,
  listOpportunities,
  transitionStage,
} from '@/api/salesOpportunity';
import { useAuthStore } from '@/store/modules/auth';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');
const currentUserId = computed<number>(
  () => Number((authStore.user as { id?: number } | null)?.id || 0),
);

const onlyMine = ref(false);
const loading = ref(false);
const allOpportunities = ref<SalesOpportunity[]>([]);

// Group by stage for kanban rendering
const columnsByStage = computed<Record<string, SalesOpportunity[]>>(() => {
  const out: Record<string, SalesOpportunity[]> = {};
  for (const m of STAGE_META) out[m.value] = [];
  for (const opp of allOpportunities.value) {
    if (out[opp.stage]) out[opp.stage].push(opp);
  }
  return out;
});

// Summary bar metrics
const totalCount = computed(() => allOpportunities.value.length);
const totalAmount = computed(() =>
  allOpportunities.value.reduce(
    (sum, o) => sum + (typeof o.valueAmount === 'number' ? o.valueAmount : 0),
    0,
  ),
);
const totalExpected = computed(() =>
  allOpportunities.value.reduce((sum, o) => sum + expectedValue(o), 0),
);
const wonCount = computed(
  () => allOpportunities.value.filter((o) => o.stage === 'CLOSED_WON').length,
);

function stageTotalAmount(stage: OpportunityStage): number {
  return (columnsByStage.value[stage] || []).reduce(
    (sum, o) => sum + (typeof o.valueAmount === 'number' ? o.valueAmount : 0),
    0,
  );
}

function stageExpected(stage: OpportunityStage): number {
  return (columnsByStage.value[stage] || []).reduce(
    (sum, o) => sum + expectedValue(o),
    0,
  );
}

async function fetchAll() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // Fetch all 8 stages (large page to avoid pagination on kanban)
    const res = await listOpportunities(factoryId.value, {
      ownerId: onlyMine.value ? currentUserId.value : undefined,
      page: 0,
      size: 500,
    });
    allOpportunities.value = res.content || [];
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载商机失败';
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}

// ==================== Drag & Drop ====================
const dragOverStage = ref<string | null>(null);
const draggedOpp = ref<SalesOpportunity | null>(null);

interface PendingDrop {
  opp: SalesOpportunity;
  toStage: OpportunityStage;
}
const pendingDrop = ref<PendingDrop | null>(null);
const dropDialogVisible = ref(false);
const dropping = ref(false);
const dropReasonPreset = ref<string>('');
const dropReasonCustom = ref<string>('');
const dropConfirmSkip = ref(false);

const reasonPresets = [
  '客户预算未确认',
  '客户需要技术评估',
  '商务谈判中',
  '客户主动推进',
  '客户暂缓',
  '内部审批通过',
];

const dropSkipForward = computed(() => {
  if (!pendingDrop.value) return false;
  const fromMeta = stageMeta(pendingDrop.value.opp.stage);
  const toMeta = stageMeta(pendingDrop.value.toStage);
  return !fromMeta.terminal && !toMeta.terminal && toMeta.order - fromMeta.order > 1;
});

const dropBackward = computed(() => {
  if (!pendingDrop.value) return false;
  const fromMeta = stageMeta(pendingDrop.value.opp.stage);
  const toMeta = stageMeta(pendingDrop.value.toStage);
  return !fromMeta.terminal && !toMeta.terminal && toMeta.order < fromMeta.order;
});

const dropReactivate = computed(() => {
  if (!pendingDrop.value) return false;
  const fromMeta = stageMeta(pendingDrop.value.opp.stage);
  const toMeta = stageMeta(pendingDrop.value.toStage);
  return fromMeta.terminal && !toMeta.terminal;
});

const dropReasonRequired = computed(
  () => dropSkipForward.value || dropBackward.value || dropReactivate.value,
);

function onDragStart(ev: DragEvent, opp: SalesOpportunity) {
  draggedOpp.value = opp;
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move';
    ev.dataTransfer.setData('text/plain', opp.id);
  }
}

function onDragEnd() {
  dragOverStage.value = null;
}

function onDragOver(stage: OpportunityStage) {
  dragOverStage.value = stage;
}

function onDragLeave() {
  // small delay 友好
}

function onDrop(stage: OpportunityStage) {
  dragOverStage.value = null;
  if (!draggedOpp.value) return;
  if (draggedOpp.value.stage === stage) {
    // Same stage drop — no-op
    draggedOpp.value = null;
    return;
  }
  // Open confirmation dialog
  pendingDrop.value = {
    opp: draggedOpp.value,
    toStage: stage,
  };
  dropReasonPreset.value = '';
  dropReasonCustom.value = '';
  dropConfirmSkip.value = false;
  dropDialogVisible.value = true;
  draggedOpp.value = null;
}

function cancelDrop() {
  dropDialogVisible.value = false;
  pendingDrop.value = null;
}

async function confirmDrop() {
  if (!pendingDrop.value) return;
  if (dropReasonRequired.value && !dropReasonPreset.value) {
    ElMessage.warning('请选择变更原因');
    return;
  }
  if (dropReasonPreset.value === 'OTHER' && !dropReasonCustom.value.trim()) {
    ElMessage.warning('请填写原因详情');
    return;
  }

  const finalReason =
    dropReasonPreset.value === 'OTHER'
      ? dropReasonCustom.value.trim()
      : dropReasonPreset.value;

  dropping.value = true;
  try {
    await transitionStage(factoryId.value, pendingDrop.value.opp.id, {
      newStage: pendingDrop.value.toStage,
      reason: finalReason || undefined,
      confirmSkip: dropConfirmSkip.value,
      changedBy: currentUserId.value,
    });
    ElMessage.success(
      `已变更为 ${stageLabel(pendingDrop.value.toStage)}`,
    );
    dropDialogVisible.value = false;
    pendingDrop.value = null;
    await fetchAll();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '阶段变更失败';
    ElMessage.error(msg);
  } finally {
    dropping.value = false;
  }
}

// ==================== Nav ====================
function goCreate() {
  router.push({ name: 'CrmOpportunityList', query: { action: 'create' } });
}

function goList() {
  router.push({ name: 'CrmOpportunityList' });
}

function openDetail(opp: SalesOpportunity) {
  router.push({ name: 'CrmOpportunityList', query: { focus: opp.id } });
}

onMounted(() => {
  fetchAll();
});
</script>

<style scoped>
.sales-opportunity-kanban {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.summary-bar {
  margin-bottom: 16px;
}
.summary-row {
  display: flex;
  gap: 32px;
}
.summary-item {
  text-align: center;
}
.summary-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.summary-value {
  font-size: 20px;
  font-weight: 600;
  margin-top: 4px;
}
.summary-value.highlight {
  color: var(--el-color-primary);
}
.kanban-board {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  min-height: 480px;
  padding-bottom: 12px;
}
.kanban-column {
  flex: 0 0 240px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  border-top: 4px solid var(--el-color-info);
  transition: background 0.2s;
}
.kanban-column.drag-over {
  background: var(--el-color-primary-light-9);
}
.column-header {
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.column-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.column-count {
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.column-stats {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.column-stats .expected {
  color: var(--el-color-primary);
  font-weight: 600;
}
.column-body {
  padding: 8px;
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
}
.opp-card {
  background: white;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 8px;
  cursor: grab;
  transition: box-shadow 0.2s;
}
.opp-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.opp-card:active {
  cursor: grabbing;
}
.opp-title {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-color-primary);
}
.opp-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.empty-column-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 8px;
  color: var(--el-text-color-placeholder);
  gap: 8px;
  font-size: 12px;
}
.empty-kanban {
  margin-top: 60px;
}
.context-bar {
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  margin-bottom: 16px;
}
.drop-confirm {
  padding: 4px 0;
}
</style>
