<!--
  Sprint 7 wave 2 T4 — 销售商机 列表视图 (2026-05-20).

  Path: Canvas → CRM → 商机
  Backend: SalesOpportunityController @ /api/mobile/{factoryId}/sales-opportunity

  防呆 R3: stage el-select dropdown (+ 转化率 hint per option)
  防呆 R5: empty list → "新增商机" button (router-style flow)
-->
<template>
  <div class="sales-opportunity-list">
    <div class="page-header">
      <h2>销售商机管理</h2>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">
          新增商机
        </el-button>
        <el-button :icon="DataAnalysis" @click="openKanban">
          看板视图
        </el-button>
        <el-button :icon="DataLine" @click="openFunnel">
          漏斗报表
        </el-button>
      </div>
    </div>

    <el-card class="filter-bar" shadow="never">
      <el-form :inline="true" :model="filter">
        <el-form-item label="阶段">
          <el-select
            v-model="filter.stage"
            placeholder="全部"
            clearable
            style="width: 220px"
          >
            <el-option
              v-for="m in STAGE_META"
              :key="m.value"
              :label="`${m.label} (${m.probability}%)`"
              :value="m.value"
            >
              <span>{{ m.label }}</span>
              <span style="float: right; color: var(--el-text-color-secondary)">
                {{ m.probability }}%
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="我的商机">
          <el-switch v-model="filter.onlyMine" @change="onFilterChange" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onFilterChange">查询</el-button>
          <el-button @click="onResetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table
        :data="opportunities"
        v-loading="loading"
        empty-text="暂无商机数据"
        style="width: 100%"
      >
        <el-table-column prop="title" label="商机标题" min-width="220">
          <template #default="{ row }">
            <el-link type="primary" underline="hover" @click="openEdit(row)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="客户" width="180">
          <template #default="{ row }">
            {{ row.customer?.name || row.customerId }}
          </template>
        </el-table-column>
        <el-table-column label="阶段" width="140">
          <template #default="{ row }">
            <el-tag :type="stageMeta(row.stage).tagType" effect="light">
              {{ stageLabel(row.stage) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="商机金额" width="160" align="right">
          <template #default="{ row }">
            {{ formatCurrency(row.valueAmount) }}
          </template>
        </el-table-column>
        <el-table-column label="转化率" width="100" align="center">
          <template #default="{ row }">
            {{ row.probability }}%
          </template>
        </el-table-column>
        <el-table-column label="预期值" width="160" align="right">
          <template #default="{ row }">
            {{ formatCurrency(expectedValue(row)) }}
          </template>
        </el-table-column>
        <el-table-column label="预期成交" width="120">
          <template #default="{ row }">
            {{ row.expectedCloseDate || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="业务员" width="120">
          <template #default="{ row }">
            {{ row.owner?.realName || row.owner?.username || row.ownerId }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="primary" link @click="openTransition(row)">
              变更阶段
            </el-button>
            <el-popconfirm
              title="确定删除此商机吗?"
              @confirm="onDelete(row)"
            >
              <template #reference>
                <el-button type="danger" link>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="totalElements > 0"
        v-model:current-page="page"
        v-model:page-size="size"
        :total="totalElements"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="fetchList"
        @size-change="fetchList"
        style="margin-top: 20px; justify-content: flex-end"
      />
    </el-card>

    <!-- Create / Edit dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="640px"
      @close="closeDialog"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="120px"
      >
        <el-form-item label="客户" prop="customerId">
          <el-select
            v-model="form.customerId"
            placeholder="选择客户"
            filterable
            :disabled="isEdit"
            style="width: 100%"
          >
            <el-option
              v-for="c in customers"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商机标题" prop="title">
          <el-input v-model="form.title" placeholder="例如: 长期合作年度采购协议" />
        </el-form-item>
        <el-form-item label="阶段" prop="stage" v-if="!isEdit">
          <el-select v-model="form.stage" style="width: 100%">
            <el-option
              v-for="m in STAGE_META"
              :key="m.value"
              :label="`${m.label} (默认 ${m.probability}%)`"
              :value="m.value"
            />
          </el-select>
          <div class="hint-text">
            阶段决定默认转化率, 创建后可通过"变更阶段"流转 (per state machine).
          </div>
        </el-form-item>
        <el-form-item label="商机金额" prop="valueAmount">
          <el-input-number
            v-model="form.valueAmount"
            :precision="2"
            :step="1000"
            :min="0"
            style="width: 100%"
            placeholder="人民币 ¥"
          />
        </el-form-item>
        <el-form-item label="转化率" prop="probability">
          <el-input-number
            v-model="form.probability"
            :min="0"
            :max="100"
            :step="5"
            style="width: 200px"
          />
          <span class="hint-text" style="margin-left: 12px">
            % (空表示使用阶段默认)
          </span>
        </el-form-item>
        <el-form-item label="预期成交日期" prop="expectedCloseDate">
          <el-date-picker
            v-model="form.expectedCloseDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="预期成交日"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="业务员" prop="ownerId">
          <el-select
            v-model="form.ownerId"
            placeholder="选择业务员"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="u in salesUsers"
              :key="u.id"
              :label="u.realName || u.username"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="商机背景 / 沟通要点 / 后续计划等"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Transition stage dialog -->
    <el-dialog
      v-model="transitionVisible"
      title="变更商机阶段"
      width="560px"
    >
      <div v-if="transitionTarget" class="transition-form">
        <div class="context-bar">
          <strong>{{ transitionTarget.title }}</strong>
          <div>
            当前阶段:
            <el-tag :type="stageMeta(transitionTarget.stage).tagType" effect="light">
              {{ stageLabel(transitionTarget.stage) }}
            </el-tag>
            <span class="hint-text" style="margin-left: 8px">
              当前转化率 {{ transitionTarget.probability }}%
            </span>
          </div>
        </div>
        <el-form :model="transitionForm" label-width="100px">
          <el-form-item label="目标阶段" required>
            <el-select v-model="transitionForm.newStage" style="width: 100%">
              <el-option
                v-for="m in STAGE_META"
                :key="m.value"
                :label="`${m.label} (默认 ${m.probability}%)`"
                :value="m.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="reasonRequired" label="原因" required>
            <el-select
              v-model="transitionForm.reasonPreset"
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
              v-if="transitionForm.reasonPreset === 'OTHER'"
              v-model="transitionForm.reason"
              type="textarea"
              :rows="2"
              placeholder="补充原因详情"
            />
          </el-form-item>
          <el-form-item v-if="isSkipForward" label="跳级确认">
            <el-checkbox v-model="transitionForm.confirmSkip">
              确认跨越多个阶段直接转换
            </el-checkbox>
            <div class="hint-text">
              你正从 {{ stageLabel(transitionTarget.stage) }} 直接跳到
              {{ stageLabel(transitionForm.newStage) }}, 请确认理解后续 funnel 数据将丢失中间状态.
            </div>
          </el-form-item>
          <el-form-item label="转化率覆盖">
            <el-input-number
              v-model="transitionForm.probabilityOverride"
              :min="0"
              :max="100"
              :step="5"
              style="width: 200px"
            />
            <span class="hint-text" style="margin-left: 12px">
              空 = 使用目标阶段默认
            </span>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="transitionVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="transitioning"
          @click="onSubmitTransition"
        >
          确认变更
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { Plus, DataAnalysis, DataLine } from '@element-plus/icons-vue';
import {
  STAGE_META,
  type OpportunityStage,
  type SalesOpportunity,
  stageMeta,
  stageLabel,
  formatCurrency,
  expectedValue,
  listOpportunities,
  createOpportunity,
  updateOpportunity,
  transitionStage,
  deleteOpportunity,
} from '@/api/salesOpportunity';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');
const currentUserId = computed<number>(() => Number((authStore.user as { id?: number } | null)?.id || 0));

// ==================== State ====================
const opportunities = ref<SalesOpportunity[]>([]);
const totalElements = ref(0);
const loading = ref(false);
const page = ref(1);
const size = ref(20);
const filter = reactive<{ stage?: OpportunityStage; onlyMine: boolean }>({
  stage: undefined,
  onlyMine: false,
});

const customers = ref<Array<{ id: string; name: string }>>([]);
const salesUsers = ref<Array<{ id: number; username?: string; realName?: string }>>([]);

// ==================== Create/Edit Dialog ====================
const dialogVisible = ref(false);
const isEdit = ref(false);
const editingId = ref<string | null>(null);
const submitting = ref(false);
const formRef = ref<FormInstance>();
const form = reactive<{
  customerId: string;
  title: string;
  stage: OpportunityStage;
  valueAmount?: number | null;
  probability?: number | null;
  expectedCloseDate?: string | null;
  ownerId: number;
  description?: string;
}>({
  customerId: '',
  title: '',
  stage: 'LEAD',
  valueAmount: null,
  probability: null,
  expectedCloseDate: null,
  ownerId: 0,
  description: '',
});

const formRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  title: [{ required: true, message: '请输入商机标题', trigger: 'blur' }],
  ownerId: [{ required: true, message: '请选择业务员', trigger: 'change' }],
};

const dialogTitle = computed(() => (isEdit.value ? '编辑商机' : '新增商机'));

// ==================== Transition dialog ====================
const transitionVisible = ref(false);
const transitionTarget = ref<SalesOpportunity | null>(null);
const transitioning = ref(false);
const transitionForm = reactive<{
  newStage: OpportunityStage;
  reasonPreset: string;
  reason: string;
  confirmSkip: boolean;
  probabilityOverride?: number | null;
}>({
  newStage: 'QUALIFIED',
  reasonPreset: '',
  reason: '',
  confirmSkip: false,
  probabilityOverride: null,
});

// 防呆 R3: 标准 reason 选项
const reasonPresets = [
  '客户预算未确认',
  '客户需要技术评估',
  '商务谈判中',
  '客户主动推进',
  '客户暂缓',
  '内部审批通过',
];

const isSkipForward = computed(() => {
  if (!transitionTarget.value) return false;
  const fromMeta = stageMeta(transitionTarget.value.stage);
  const toMeta = stageMeta(transitionForm.newStage);
  return !fromMeta.terminal && !toMeta.terminal && toMeta.order - fromMeta.order > 1;
});

const isBackward = computed(() => {
  if (!transitionTarget.value) return false;
  const fromMeta = stageMeta(transitionTarget.value.stage);
  const toMeta = stageMeta(transitionForm.newStage);
  return !fromMeta.terminal && !toMeta.terminal && toMeta.order < fromMeta.order;
});

const isReactivate = computed(() => {
  if (!transitionTarget.value) return false;
  const fromMeta = stageMeta(transitionTarget.value.stage);
  const toMeta = stageMeta(transitionForm.newStage);
  return fromMeta.terminal && !toMeta.terminal;
});

const reasonRequired = computed(
  () => isSkipForward.value || isBackward.value || isReactivate.value,
);

// ==================== API ====================
async function fetchList() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listOpportunities(factoryId.value, {
      stage: filter.stage,
      ownerId: filter.onlyMine ? currentUserId.value : undefined,
      page: page.value - 1,
      size: size.value,
    });
    opportunities.value = res.content || [];
    totalElements.value = res.totalElements || 0;
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载商机列表失败';
    ElMessage.error(msg);
  } finally {
    loading.value = false;
  }
}

async function fetchDependencies() {
  if (!factoryId.value) return;
  try {
    const [custRes, userRes] = await Promise.all([
      get<{ content: Array<{ id: string; name: string }> }>(
        `/${factoryId.value}/customers`,
        { params: { page: 1, size: 200 } },
      ),
      get<{ content: Array<{ id: number; username?: string; realName?: string; fullName?: string }> }>(
        `/${factoryId.value}/users`,
        { params: { page: 0, size: 200 } },
      ),
    ]);
    if (custRes.success && custRes.data) {
      customers.value = custRes.data.content || [];
    }
    if (userRes.success && userRes.data) {
      salesUsers.value = (userRes.data.content || []).map((u) => ({
        id: u.id,
        username: u.username,
        realName: u.fullName || u.realName,
      }));
    }
  } catch (e) {
    // 防呆 R5 — UI 不阻塞列表查看, 仅创建时报错
    console.warn('加载客户/用户列表失败, 创建商机将不可选 dropdown', e);
  }
}

// ==================== Handlers ====================
function onFilterChange() {
  page.value = 1;
  fetchList();
}

function onResetFilter() {
  filter.stage = undefined;
  filter.onlyMine = false;
  page.value = 1;
  fetchList();
}

function openCreate() {
  isEdit.value = false;
  editingId.value = null;
  Object.assign(form, {
    customerId: '',
    title: '',
    stage: 'LEAD' as OpportunityStage,
    valueAmount: null,
    probability: null,
    expectedCloseDate: null,
    ownerId: currentUserId.value || 0,
    description: '',
  });
  dialogVisible.value = true;
}

function openEdit(opp: SalesOpportunity) {
  isEdit.value = true;
  editingId.value = opp.id;
  Object.assign(form, {
    customerId: opp.customerId,
    title: opp.title,
    stage: opp.stage,
    valueAmount: opp.valueAmount ?? null,
    probability: opp.probability ?? null,
    expectedCloseDate: opp.expectedCloseDate ?? null,
    ownerId: opp.ownerId,
    description: opp.description ?? '',
  });
  dialogVisible.value = true;
}

function closeDialog() {
  dialogVisible.value = false;
  formRef.value?.resetFields();
}

async function onSubmit() {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    submitting.value = true;
    try {
      if (isEdit.value && editingId.value) {
        await updateOpportunity(factoryId.value, editingId.value, {
          title: form.title,
          valueAmount: form.valueAmount ?? null,
          probability: form.probability ?? null,
          expectedCloseDate: form.expectedCloseDate ?? null,
          ownerId: form.ownerId,
          description: form.description,
        });
        ElMessage.success('商机已更新');
      } else {
        await createOpportunity(factoryId.value, {
          customerId: form.customerId,
          title: form.title,
          stage: form.stage,
          valueAmount: form.valueAmount ?? null,
          probability: form.probability ?? null,
          expectedCloseDate: form.expectedCloseDate ?? null,
          ownerId: form.ownerId,
          description: form.description,
          createdBy: currentUserId.value,
        });
        ElMessage.success('商机已创建');
      }
      closeDialog();
      fetchList();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '操作失败';
      ElMessage.error(msg);
    } finally {
      submitting.value = false;
    }
  });
}

function openTransition(opp: SalesOpportunity) {
  transitionTarget.value = opp;
  // Default: next stage if non-terminal, else CLOSED_LOST
  const fromMeta = stageMeta(opp.stage);
  let suggest: OpportunityStage = opp.stage;
  if (!fromMeta.terminal) {
    const next = STAGE_META.find((m) => m.order === fromMeta.order + 1);
    if (next) suggest = next.value;
  }
  Object.assign(transitionForm, {
    newStage: suggest,
    reasonPreset: '',
    reason: '',
    confirmSkip: false,
    probabilityOverride: null,
  });
  transitionVisible.value = true;
}

async function onSubmitTransition() {
  if (!transitionTarget.value) return;
  if (reasonRequired.value && !transitionForm.reasonPreset) {
    ElMessage.warning('请选择变更原因');
    return;
  }
  if (transitionForm.reasonPreset === 'OTHER' && !transitionForm.reason.trim()) {
    ElMessage.warning('请填写原因详情');
    return;
  }

  const finalReason =
    transitionForm.reasonPreset === 'OTHER'
      ? transitionForm.reason.trim()
      : transitionForm.reasonPreset;

  transitioning.value = true;
  try {
    await transitionStage(factoryId.value, transitionTarget.value.id, {
      newStage: transitionForm.newStage,
      reason: finalReason || undefined,
      confirmSkip: transitionForm.confirmSkip,
      probabilityOverride: transitionForm.probabilityOverride ?? undefined,
      changedBy: currentUserId.value,
    });
    ElMessage.success('商机阶段已变更');
    transitionVisible.value = false;
    fetchList();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '阶段变更失败';
    ElMessage.error(msg);
  } finally {
    transitioning.value = false;
  }
}

async function onDelete(opp: SalesOpportunity) {
  try {
    await deleteOpportunity(factoryId.value, opp.id);
    ElMessage.success('商机已删除');
    fetchList();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '删除失败';
    ElMessage.error(msg);
  }
}

function openKanban() {
  router.push({ name: 'CrmOpportunityKanban' }).catch(() => {
    ElMessageBox.alert(
      '看板视图路由暂不可达, 请检查路由配置.',
      '看板视图',
      { type: 'info' },
    );
  });
}

function openFunnel() {
  router.push({ name: 'CrmOpportunityFunnel' }).catch(() => {
    ElMessageBox.alert(
      '漏斗报表路由暂不可达, 请检查路由配置.',
      '漏斗报表',
      { type: 'info' },
    );
  });
}

// ==================== Lifecycle ====================
onMounted(async () => {
  await fetchDependencies();
  fetchList();
});

watch(() => filter.onlyMine, () => {
  onFilterChange();
});
</script>

<style scoped>
.sales-opportunity-list {
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
  font-size: 20px;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.filter-bar {
  margin-bottom: 16px;
}
.hint-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.context-bar {
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  margin-bottom: 16px;
}
.transition-form {
  padding: 8px 0;
}
</style>
