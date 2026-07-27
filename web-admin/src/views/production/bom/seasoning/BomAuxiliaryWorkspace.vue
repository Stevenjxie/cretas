<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';
import {
  bomRecipeApi,
  bomSeasoningApi,
  type BomRecipeStatus,
  type BomItemSubstituteView,
  type SeasoningBindingView,
  type SeasoningProcessView,
  type SeasoningWorkspace,
} from '@/api/bom';
import { get } from '@/api/request';
import { bigCategoryOf } from '@/utils/materialCategory';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';
import ProcessSeasoningCard from './ProcessSeasoningCard.vue';
import SeasoningBindingDialog, { type SeasoningMaterialOption } from './SeasoningBindingDialog.vue';
import AuxiliaryAiImportDialog from './AuxiliaryAiImportDialog.vue';
import BomFamilyOutputCostingDialog from './BomFamilyOutputCostingDialog.vue';
import { buildMaterialSummaries, uniqueProcessesByNode } from './seasoningModel';

const props = withDefaults(defineProps<{
  factoryId: string;
  productTypeId: string;
  recipeId: string;
  recipeStatus: BomRecipeStatus;
  canWrite: boolean;
  canViewPrice: boolean;
  showReadonlyNotice?: boolean;
}>(), {
  showReadonlyNotice: true,
});

const emit = defineEmits<{
  'request-clone': [];
  'workflow-upgraded': [recipeId: string];
  changed: [];
}>();

const router = useRouter();
const workspace = ref<SeasoningWorkspace | null>(null);
const materials = ref<SeasoningMaterialOption[]>([]);
const loading = ref(false);
const loadError = ref('');
const activeView = ref<'process' | 'summary'>('process');
const expandedWorkProcessIds = ref<string[]>([]);
const dialogVisible = ref(false);
const dialogProcess = ref<SeasoningProcessView | null>(null);
const dialogBinding = ref<SeasoningBindingView | null>(null);
const substituteRelations = ref<BomItemSubstituteView[]>([]);
const substitutesLoaded = ref(false);
const substituteLoadError = ref('');
const upgradingWorkflow = ref(false);
const outputCostingVisible = ref(false);
const aiImportVisible = ref(false);

const processes = computed(() => uniqueProcessesByNode(workspace.value?.processes || []));
const summaries = computed(() => buildMaterialSummaries(processes.value));
const allProcessesExpanded = computed(() => (
  processes.value.length > 0
  && processes.value.every((process) => expandedWorkProcessIds.value.includes(process.workflowProcessNodeId))
));
const editable = computed(() => Boolean(
  props.canWrite
  && props.recipeStatus === 'DRAFT'
  && workspace.value?.status === 'DRAFT'
  && workspace.value?.editable
  && substitutesLoaded.value,
));
const workflowStatusLabel = computed(() => {
  const status = workspace.value?.workflowRevisionStatus;
  if (status === 'ENABLED') return '已启用';
  if (status === 'PUBLISHED') return '已发布';
  if (status === 'DRAFT') return '已保存草稿';
  return status || '已固定';
});
const outputRoleLabel = computed(() => ({
  MAIN: '主产出',
  CO_PRODUCT: '联产品',
  BY_PRODUCT: '副产品',
} as const)[workspace.value?.outputRole || 'MAIN']);

function formatSavedAt(value?: string | null): string {
  if (!value) return '保存时间未知';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

function openWorkflow() {
  void router.push({
    name: 'ProductProcesses',
    query: {
      productTypeId: workspace.value?.workflowOwnerProductTypeId || props.productTypeId,
    },
  });
}

async function upgradeWorkflow() {
  if (!workspace.value?.workflowUpgradeAvailable || upgradingWorkflow.value) return;
  try {
    await ElMessageBox.confirm(
      `系统会保留当前 BOM 和工艺历史，并创建可编辑草稿升级到 Workflow v${workspace.value.workflowUpgradeDefinitionVersion ?? '最新'}。是否继续？`,
      '升级到最新工艺',
      {
        confirmButtonText: '创建升级草稿',
        cancelButtonText: '暂不升级',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  upgradingWorkflow.value = true;
  try {
    const response = await bomRecipeApi.upgradeWorkflowRevision(props.factoryId, props.recipeId);
    if (!response.success || !response.data) {
      throw new Error(response.message || '工艺升级失败');
    }
    ElMessage.success('已创建升级草稿，原 BOM 与历史工艺保持不变');
    emit('workflow-upgraded', response.data.id);
  } catch (error: unknown) {
    ElMessage({
      message: (error as { message?: string }).message || '工艺升级失败，请按提示返回工艺页面检查未映射项',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    upgradingWorkflow.value = false;
  }
}

async function loadWorkspace() {
  if (!props.factoryId || !props.recipeId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await bomSeasoningApi.getWorkspace(props.factoryId, props.recipeId);
    if (!response.success || !response.data) throw new Error(response.message || '工序调料响应为空');
    workspace.value = response.data;
    const validIds = new Set(processes.value.map((process) => process.workflowProcessNodeId));
    const retained = expandedWorkProcessIds.value.filter((id) => validIds.has(id));
    expandedWorkProcessIds.value = retained.length
      ? retained
      : (processes.value[0] ? [processes.value[0].workflowProcessNodeId] : []);
  } catch (error: unknown) {
    loadError.value = (error as { message?: string }).message || '工序调料加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadSubstitutes() {
  if (!props.factoryId || !props.recipeId) return;
  substitutesLoaded.value = false;
  substituteLoadError.value = '';
  try {
    const response = await bomRecipeApi.listSubstitutes(props.factoryId, props.recipeId);
    if (!response.success || !Array.isArray(response.data)) {
      throw new Error(response.message || '替代物料关系响应为空');
    }
    substituteRelations.value = response.data;
    substitutesLoaded.value = true;
  } catch (error: unknown) {
    substituteLoadError.value = (error as { message?: string }).message || '替代物料关系加载失败';
  }
}

function substituteRelationsFor(binding: SeasoningBindingView | null): BomItemSubstituteView[] {
  if (!binding) return [];
  return substituteRelations.value
    .filter((relation) => relation.parentKind === 'SEASONING_ITEM' && relation.parentSeasoningItemId === binding.id);
}

async function loadMaterials() {
  if (!props.factoryId) return;
  try {
    const response = await get<Array<SeasoningMaterialOption & { category?: string | null; materialCategory?: string | null }>>(
      `/${props.factoryId}/raw-material-types/active`,
    );
    const rows = response.success && response.data ? response.data : [];
    materials.value = rows.filter((material) => {
      if (material.materialCategory === 'AUXILIARY') return true;
      const category = bigCategoryOf(material.category);
      return category === '辅料' || category === '调料';
    });
  } catch {
    ElMessage.error('辅料档案加载失败');
  }
}

function openAdd(process: SeasoningProcessView) {
  if (!substitutesLoaded.value) {
    ElMessage.error('替代物料关系尚未安全加载，暂不能新增辅料，请重试');
    return;
  }
  if (process.standardUsageSupported !== true) {
    ElMessage.warning('该工序的投入基准单位尚未形成可换算契约，暂不能配置标准辅料用量');
    return;
  }
  dialogProcess.value = process;
  dialogBinding.value = null;
  dialogVisible.value = true;
}

function openEdit(process: SeasoningProcessView, binding: SeasoningBindingView) {
  if (!substitutesLoaded.value) {
    ElMessage.error('替代物料关系尚未安全加载，暂不能编辑辅料，请重试');
    return;
  }
  if (process.standardUsageSupported !== true) {
    ElMessage.warning('该工序的投入基准单位尚未形成可换算契约，当前绑定仅可查看');
    return;
  }
  dialogProcess.value = process;
  dialogBinding.value = binding;
  dialogVisible.value = true;
}

function isConflict(error: unknown): boolean {
  const candidate = error as { response?: { status?: number }; status?: number; code?: string };
  return candidate.response?.status === 409 || candidate.status === 409 || candidate.code === 'SEASONING_REVISION_CONFLICT';
}

async function removeBinding(binding: SeasoningBindingView) {
  if (!workspace.value) return;
  if (!substitutesLoaded.value) {
    ElMessage.error('替代物料关系尚未安全加载，暂不能删除辅料绑定，请重试');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除「${binding.materialName || binding.name}」的本工序绑定？`, '删除工序调料', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await bomSeasoningApi.deleteBinding(
      props.factoryId,
      props.recipeId,
      binding.id,
      workspace.value.seasoningRevision,
    );
    await loadWorkspace();
    emit('changed');
  } catch (error: unknown) {
    if (isConflict(error)) {
      ElMessage.warning('配方已被其他人修改，已重新加载最新内容');
      await loadWorkspace();
    } else {
      ElMessage.error((error as { message?: string }).message || '删除调料失败');
    }
  }
}

async function afterSaved() {
  await Promise.all([loadWorkspace(), loadSubstitutes()]);
  emit('changed');
}

async function afterOutputCostingSaved() {
  await loadWorkspace();
  emit('changed');
}

async function handleConflict() {
  dialogVisible.value = false;
  await loadWorkspace();
}

function locateProcess(processNodeId: string) {
  activeView.value = 'process';
  if (!expandedWorkProcessIds.value.includes(processNodeId)) {
    expandedWorkProcessIds.value = [...expandedWorkProcessIds.value, processNodeId];
  }
}

function toggleProcess(processNodeId: string) {
  expandedWorkProcessIds.value = expandedWorkProcessIds.value.includes(processNodeId)
    ? expandedWorkProcessIds.value.filter((id) => id !== processNodeId)
    : [...expandedWorkProcessIds.value, processNodeId];
}

function toggleAllProcesses() {
  expandedWorkProcessIds.value = allProcessesExpanded.value
    ? []
    : processes.value.map((process) => process.workflowProcessNodeId);
}

watch(() => props.recipeId, async () => {
  workspace.value = null;
  substituteRelations.value = [];
  substitutesLoaded.value = false;
  substituteLoadError.value = '';
  activeView.value = 'process';
  await Promise.all([loadWorkspace(), loadSubstitutes()]);
});

onMounted(() => Promise.all([loadWorkspace(), loadMaterials(), loadSubstitutes()]));

function basisLabel(process: Pick<
  SeasoningProcessView,
  'standardBasisQuantity' | 'standardBasisUnit' | 'basisQuantity' | 'basisUnit'
>): string {
  const quantity = process.standardBasisQuantity ?? process.basisQuantity;
  const unit = process.standardBasisUnit ?? process.basisUnit;
  if (quantity == null || !unit) return '未解析';
  return `${Number(quantity).toFixed(4).replace(/\.?0+$/, '')}${businessUnitLabel(unit)}`;
}

function businessUnitLabel(unit: string): string {
  const code = canonicalUnitCode(unit);
  return ({ kg: '千克', g: '克', L: '升', mL: '毫升' } as Record<string, string>)[code]
    || displayUnit(code);
}

function usageLabel(usage: { dosagePerKgG: number; basisQuantity?: number | null; basisUnit?: string | null }): string {
  const denominator = basisLabel(usage);
  return `${Number(usage.dosagePerKgG).toFixed(4)} 克/${denominator}`;
}
</script>

<template>
  <section data-testid="bom-auxiliary-workspace" class="auxiliary-workspace">
    <div
      v-if="workspace?.workflowRevisionHash"
      class="workflow-source-card"
      data-testid="bom-workflow-source-card"
    >
      <div class="workflow-source-card__identity">
        <span class="workflow-source-card__eyebrow">工艺来源</span>
        <div class="workflow-source-card__title">
          <h3>{{ workspace.productName }} · 生产工艺</h3>
          <el-tag size="small" type="info">Workflow v{{ workspace.workflowDefinitionVersion }}</el-tag>
          <el-tag size="small" type="success">{{ workflowStatusLabel }}</el-tag>
        </div>
        <p>系统已根据当前 SKU 自动关联并固定该工艺版本，历史 BOM 不会随新版本自动变化。</p>
        <div class="workflow-source-card__facts" aria-label="工艺摘要">
          <span>{{ workspace.workflowRootCount ?? 0 }} 个投入入口</span>
          <span>{{ workspace.workflowProcessCount ?? processes.length }} 道工序</span>
          <span>{{ workspace.workflowTargetCount ?? 1 }} 个终端产出</span>
          <span>保存于 {{ formatSavedAt(workspace.workflowRevisionSavedAt) }}</span>
          <span v-if="(workspace.workflowTargetCount ?? 1) > 1">
            {{ outputRoleLabel }} · 成本分摊 {{ workspace.costAllocationRatio ?? 0 }}%
          </span>
        </div>
      </div>
      <div class="workflow-source-card__actions">
        <el-button data-testid="view-workflow" @click="openWorkflow">查看工艺</el-button>
        <el-button
          v-if="canViewPrice && (workspace.workflowTargetCount ?? 1) > 1"
          data-testid="open-family-output-costing"
          @click="outputCostingVisible = true"
        >
          产出成本配置
        </el-button>
        <el-button
          v-if="canWrite && workspace.workflowUpgradeAvailable"
          type="primary"
          :loading="upgradingWorkflow"
          data-testid="upgrade-workflow"
          @click="upgradeWorkflow"
        >
          升级到最新工艺
        </el-button>
      </div>
      <div
        v-if="workspace.workflowUpgradeAvailable"
        class="workflow-source-card__update"
        role="status"
        aria-live="polite"
        data-testid="workflow-update-notice"
      >
        发现更新的工艺版本 v{{ workspace.workflowUpgradeDefinitionVersion }}。升级会创建新 BOM 草稿，并按稳定工艺节点迁移现有配置。
      </div>
    </div>

    <BomFamilyOutputCostingDialog
      v-model="outputCostingVisible"
      :factory-id="factoryId"
      :recipe-id="recipeId"
      :can-write="canWrite"
      :can-view-price="canViewPrice"
      @saved="afterOutputCostingSaved"
    />

    <el-alert
      v-if="workspace && !loading && !workspace.workflowRevisionHash"
      type="error"
      show-icon
      :closable="false"
      class="state-alert"
      title="当前 BOM 尚未自动关联完整工艺"
      description="请返回工艺页面保存唯一且结构完整的 Workflow 草稿，再重新创建 BOM 草稿。系统不会猜测或静默绑定版本。"
      data-testid="workflow-source-error"
    />

    <el-alert
      v-if="showReadonlyNotice && recipeStatus !== 'DRAFT'"
      type="warning"
      show-icon
      :closable="false"
      title="当前 BOM 为生效或归档版本，只能查看工序调料"
      class="state-alert"
    >
      <template #default>
        <el-button v-if="canWrite" data-testid="request-clone" type="primary" size="small" @click="emit('request-clone')">克隆为新版本后修改</el-button>
      </template>
    </el-alert>

    <el-alert
      v-if="workspace?.anomalies?.length"
      type="error"
      show-icon
      :closable="false"
      :title="`检测到 ${workspace.anomalies.length} 项配置异常，请处理后再激活`"
      class="state-alert"
    />

    <el-alert
      v-if="substituteLoadError"
      data-testid="substitute-load-error"
      type="error"
      show-icon
      :closable="false"
      title="替代物料关系加载失败，辅料编辑已安全锁定"
      class="state-alert"
    >
      <template #default>
        <span>{{ substituteLoadError }}。系统不会用空集合覆盖已有替代关系。</span>
        <el-button link type="primary" data-testid="retry-substitutes" @click="loadSubstitutes">重试加载</el-button>
      </template>
    </el-alert>

    <div class="workspace-toolbar">
      <el-radio-group v-model="activeView" size="small">
        <el-radio-button value="process">工序编排</el-radio-button>
        <el-radio-button value="summary">辅料汇总</el-radio-button>
      </el-radio-group>
      <div class="workspace-toolbar__right">
        <el-button
          v-if="editable"
          type="primary"
          plain
          data-testid="open-auxiliary-ai-import"
          @click="aiImportVisible = true"
        >
          AI 识别批量添加
        </el-button>
        <el-button
          v-if="activeView === 'process' && processes.length"
          data-testid="toggle-all-processes"
          size="small"
          @click="toggleAllProcesses"
        >{{ allProcessesExpanded ? '全部收起' : '全部展开' }}</el-button>
        <span v-if="workspace" class="workspace-stats">{{ summaries.length }} 种辅料 · {{ processes.reduce((sum, process) => sum + process.bindings.length, 0) }} 条工序绑定</span>
      </div>
    </div>

    <div v-if="loading" v-loading="true" class="compact-loading" />
    <el-result v-else-if="loadError" icon="error" title="工序调料加载失败" :sub-title="loadError">
      <template #extra><el-button @click="loadWorkspace">重试</el-button></template>
    </el-result>
    <el-empty v-else-if="!processes.length" description="该 SKU 尚未配置 workflow 工序" :image-size="64" />

    <div
      v-else-if="activeView === 'process'"
      data-testid="process-seasoning-view"
    >
      <div data-testid="seasoning-two-column-layout" class="process-layout">
        <div data-testid="seasoning-editor-column" class="process-column">
          <ProcessSeasoningCard
            v-for="process in processes"
            :key="process.workflowProcessNodeId"
            :process="process"
            :processes="processes"
            :expanded="expandedWorkProcessIds.includes(process.workflowProcessNodeId)"
            :editable="editable && process.editable !== false && process.standardUsageSupported === true"
            @toggle="toggleProcess(process.workflowProcessNodeId)"
            @add="openAdd"
            @edit="openEdit"
            @delete="removeBinding"
          />
        </div>

        <aside data-testid="seasoning-compact-summary" class="compact-summary">
          <div class="compact-summary__header">
            <div>
              <strong>辅料汇总</strong>
              <p>按物料去重，快速核对跨工序使用</p>
            </div>
            <el-button link type="primary" @click="activeView = 'summary'">查看完整汇总</el-button>
          </div>

          <el-empty v-if="!summaries.length" description="暂未配置辅料" :image-size="48" />
          <div v-else class="compact-summary__list">
            <article v-for="item in summaries" :key="item.materialTypeId" class="compact-summary__item">
              <div class="compact-summary__material">
                <strong>{{ item.materialName }}</strong>
                <el-tag size="small" type="info">用于 {{ item.usages.length }} 个工序</el-tag>
              </div>
              <div class="compact-summary__usages">
                <button
                  v-for="usage in item.usages"
                  :key="usage.bindingId"
                  type="button"
                  class="compact-summary__usage"
                  @click="locateProcess(usage.workflowProcessNodeId || usage.workProcessId)"
                >
                  <span>{{ usage.processOrder }}. {{ usage.processName }}</span>
                  <strong>{{ usageLabel(usage) }}</strong>
                </button>
              </div>
            </article>
          </div>
        </aside>
      </div>
    </div>

    <div v-else data-testid="seasoning-summary-view">
      <el-table :data="summaries" size="small" border empty-text="暂无工序调料">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="summary-detail">
              <div v-for="usage in row.usages" :key="usage.bindingId" class="summary-usage">
                <span>{{ usage.processOrder }}. {{ usage.processName }}</span>
                <strong>{{ usageLabel(usage) }}</strong>
                <span>{{ usage.subsequentPotRatio == null ? '不按锅序' : `首锅 100% · 后续 ${Number(usage.subsequentPotRatio * 100).toFixed(2)}%` }}</span>
                <el-button link type="primary" @click="locateProcess(usage.workflowProcessNodeId || usage.workProcessId)">定位到工序</el-button>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="辅料" min-width="160">
          <template #default="{ row }">{{ row.materialName }}</template>
        </el-table-column>
        <el-table-column label="使用范围" width="130">
          <template #default="{ row }"><el-tag size="small">用于 {{ row.usages.length }} 个工序</el-tag></template>
        </el-table-column>
        <el-table-column label="工序绑定概览" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="usage in row.usages" :key="usage.bindingId" size="small" class="usage-tag">{{ usage.processOrder }}. {{ usage.processName }} {{ usageLabel(usage) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自动单价" width="120" align="right">
          <template #default="{ row }">{{ row.priceSnapshot == null ? '待计算' : `¥${Number(row.priceSnapshot).toFixed(4)}` }}</template>
        </el-table-column>
      </el-table>
    </div>

    <SeasoningBindingDialog
      v-model="dialogVisible"
      :factory-id="factoryId"
      :recipe-id="recipeId"
      :process="dialogProcess"
      :binding="dialogBinding"
      :substitute-relations="substituteRelationsFor(dialogBinding)"
      :materials="materials"
      :revision="workspace?.seasoningRevision || 0"
      @saved="afterSaved"
      @conflict="handleConflict"
    />
    <AuxiliaryAiImportDialog
      v-model="aiImportVisible"
      :factory-id="factoryId"
      :recipe-id="recipeId"
      :revision="workspace?.seasoningRevision || 0"
      :processes="processes"
      :materials="materials"
      @applied="afterSaved"
    />
  </section>
</template>

<style scoped>
.auxiliary-workspace { min-height: 180px; }
.state-alert { margin-bottom: 10px; }
.workflow-source-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px 20px; margin-bottom: 12px; padding: 16px 18px; border: 1px solid var(--el-color-primary-light-7); border-left: 4px solid var(--el-color-primary); border-radius: 8px; background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-bg-color) 72%); }
.workflow-source-card__identity { min-width: 0; }
.workflow-source-card__eyebrow { display: block; margin-bottom: 6px; color: var(--el-color-primary); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.workflow-source-card__title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.workflow-source-card__title h3 { min-width: 0; margin: 0; font-size: 15px; overflow-wrap: anywhere; text-wrap: balance; }
.workflow-source-card__identity p { margin: 7px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.55; }
.workflow-source-card__facts { display: flex; flex-wrap: wrap; gap: 6px 18px; margin-top: 10px; color: var(--el-text-color-regular); font-size: 12px; }
.workflow-source-card__facts span { position: relative; }
.workflow-source-card__facts span:not(:last-child)::after { position: absolute; right: -10px; color: var(--el-border-color); content: "·"; }
.workflow-source-card__actions { display: flex; align-items: flex-start; gap: 8px; }
.workflow-source-card__update { grid-column: 1 / -1; padding-top: 10px; border-top: 1px solid var(--el-color-primary-light-8); color: var(--el-color-warning-dark-2); font-size: 13px; line-height: 1.5; }
.workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.workspace-toolbar__right { display: flex; align-items: center; gap: 10px; }
.workspace-stats { color: var(--el-text-color-secondary); font-size: 12px; }
.compact-loading { min-height: 140px; }
.process-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; align-items: start; gap: 14px; }
.process-column { min-width: 0; }
.compact-summary { position: sticky; top: 12px; overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 6px; background: var(--el-bg-color); box-shadow: var(--el-box-shadow-lighter); }
.compact-summary__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 14px; border-bottom: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-extra-light); }
.compact-summary__header p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.compact-summary__list { max-height: calc(100vh - 270px); overflow-y: auto; padding: 0 12px; }
.compact-summary__item { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.compact-summary__item:last-child { border-bottom: 0; }
.compact-summary__material { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.compact-summary__material strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.compact-summary__usages { display: grid; gap: 5px; }
.compact-summary__usage { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 7px 8px; border: 0; border-radius: 4px; background: var(--el-fill-color-light); color: var(--el-text-color-regular); cursor: pointer; text-align: left; }
.compact-summary__usage:hover { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.compact-summary__usage:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.compact-summary__usage strong { flex: none; font-size: 12px; }
.summary-detail { padding: 8px 48px; }
.summary-usage { display: grid; grid-template-columns: minmax(120px, 1fr) 120px minmax(160px, 1fr) 80px; align-items: center; gap: 12px; padding: 7px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.usage-tag { margin: 2px 4px 2px 0; }

@media (max-width: 1180px) {
  .workflow-source-card { grid-template-columns: 1fr; }
  .workflow-source-card__actions { width: 100%; }
  .workflow-source-card__update { grid-column: 1; }
  .process-layout { grid-template-columns: 1fr; }
  .compact-summary { position: static; }
  .compact-summary__list { max-height: none; }
}
</style>
