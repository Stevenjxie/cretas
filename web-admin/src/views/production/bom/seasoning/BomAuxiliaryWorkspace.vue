<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { bomSeasoningApi, type BomRecipeStatus, type SeasoningBindingView, type SeasoningProcessView, type SeasoningWorkspace } from '@/api/bom';
import { get } from '@/api/request';
import { bigCategoryOf } from '@/utils/materialCategory';
import ProcessSeasoningCard from './ProcessSeasoningCard.vue';
import SeasoningBindingDialog, { type SeasoningMaterialOption } from './SeasoningBindingDialog.vue';
import { buildMaterialSummaries } from './seasoningModel';

const props = defineProps<{
  factoryId: string;
  productTypeId: string;
  recipeId: string;
  recipeStatus: BomRecipeStatus;
  canWrite: boolean;
}>();

const emit = defineEmits<{
  'request-clone': [];
  changed: [];
}>();

const workspace = ref<SeasoningWorkspace | null>(null);
const materials = ref<SeasoningMaterialOption[]>([]);
const loading = ref(false);
const loadError = ref('');
const activeView = ref<'process' | 'summary'>('process');
const expandedWorkProcessId = ref('');
const dialogVisible = ref(false);
const dialogProcess = ref<SeasoningProcessView | null>(null);
const dialogBinding = ref<SeasoningBindingView | null>(null);

const processes = computed(() => [...(workspace.value?.processes || [])].sort((a, b) => a.processOrder - b.processOrder));
const summaries = computed(() => buildMaterialSummaries(processes.value));
const editable = computed(() => Boolean(
  props.canWrite
  && props.recipeStatus === 'DRAFT'
  && workspace.value?.status === 'DRAFT'
  && workspace.value?.editable,
));

async function loadWorkspace() {
  if (!props.factoryId || !props.recipeId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await bomSeasoningApi.getWorkspace(props.factoryId, props.recipeId);
    if (!response.success || !response.data) throw new Error(response.message || '工序调料响应为空');
    workspace.value = response.data;
    if (!processes.value.some((process) => process.workProcessId === expandedWorkProcessId.value)) {
      expandedWorkProcessId.value = processes.value[0]?.workProcessId || '';
    }
  } catch (error: unknown) {
    loadError.value = (error as { message?: string }).message || '工序调料加载失败';
  } finally {
    loading.value = false;
  }
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
  dialogProcess.value = process;
  dialogBinding.value = null;
  dialogVisible.value = true;
}

function openEdit(process: SeasoningProcessView, binding: SeasoningBindingView) {
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
  await loadWorkspace();
  emit('changed');
}

async function handleConflict() {
  dialogVisible.value = false;
  await loadWorkspace();
}

function locateProcess(workProcessId: string) {
  activeView.value = 'process';
  expandedWorkProcessId.value = workProcessId;
}

watch(() => props.recipeId, async () => {
  workspace.value = null;
  activeView.value = 'process';
  await loadWorkspace();
});

onMounted(() => Promise.all([loadWorkspace(), loadMaterials()]));
</script>

<template>
  <section data-testid="bom-auxiliary-workspace" class="auxiliary-workspace">
    <el-alert
      v-if="recipeStatus !== 'DRAFT'"
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

    <div class="workspace-toolbar">
      <el-radio-group v-model="activeView" size="small">
        <el-radio-button value="process">工序编排</el-radio-button>
        <el-radio-button value="summary">辅料汇总</el-radio-button>
      </el-radio-group>
      <span v-if="workspace" class="workspace-stats">{{ summaries.length }} 种辅料 · {{ processes.reduce((sum, process) => sum + process.bindings.length, 0) }} 条工序绑定</span>
    </div>

    <div v-if="loading" v-loading="true" class="compact-loading" />
    <el-result v-else-if="loadError" icon="error" title="工序调料加载失败" :sub-title="loadError">
      <template #extra><el-button @click="loadWorkspace">重试</el-button></template>
    </el-result>
    <el-empty v-else-if="!processes.length" description="该 SKU 尚未配置 workflow 工序" :image-size="64" />

    <div v-else-if="activeView === 'process'" data-testid="process-seasoning-view">
      <ProcessSeasoningCard
        v-for="process in processes"
        :key="process.workProcessId"
        :process="process"
        :processes="processes"
        :expanded="expandedWorkProcessId === process.workProcessId"
        :editable="editable"
        @toggle="expandedWorkProcessId = expandedWorkProcessId === process.workProcessId ? '' : process.workProcessId"
        @add="openAdd"
        @edit="openEdit"
        @delete="removeBinding"
      />
    </div>

    <div v-else data-testid="seasoning-summary-view">
      <el-table :data="summaries" size="small" border empty-text="暂无工序调料">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="summary-detail">
              <div v-for="usage in row.usages" :key="usage.bindingId" class="summary-usage">
                <span>{{ usage.processOrder }}. {{ usage.processName }}</span>
                <strong>{{ Number(usage.dosagePerKgG).toFixed(4) }} g/kg</strong>
                <span>{{ usage.subsequentPotRatio == null ? '不按锅序' : `首锅 100% · 后续 ${Number(usage.subsequentPotRatio * 100).toFixed(2)}%` }}</span>
                <el-button link type="primary" @click="locateProcess(usage.workProcessId)">定位到工序</el-button>
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
            <el-tag v-for="usage in row.usages" :key="usage.bindingId" size="small" class="usage-tag">{{ usage.processOrder }}. {{ usage.processName }} {{ Number(usage.dosagePerKgG).toFixed(4) }} g/kg</el-tag>
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
      :materials="materials"
      :revision="workspace?.seasoningRevision || 0"
      @saved="afterSaved"
      @conflict="handleConflict"
    />
  </section>
</template>

<style scoped>
.auxiliary-workspace { min-height: 180px; }
.state-alert { margin-bottom: 10px; }
.workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.workspace-stats { color: var(--el-text-color-secondary); font-size: 12px; }
.compact-loading { min-height: 140px; }
.summary-detail { padding: 8px 48px; }
.summary-usage { display: grid; grid-template-columns: minmax(120px, 1fr) 120px minmax(160px, 1fr) 80px; align-items: center; gap: 12px; padding: 7px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.usage-tag { margin: 2px 4px 2px 0; }
</style>
