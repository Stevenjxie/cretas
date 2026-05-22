<script setup lang="ts">
/**
 * Sprint 6 Track W4-B: 工资策略配置 (WagePolicy + HourlyRateRule).
 *
 * <p>路径: Canvas → 人事 → 工资策略.
 *
 * <p>防呆 (per fool-proof-design.md):
 * <ul>
 *   <li>Rule 1 max: hourly rate input :max="500" 实时 disable + 提示 "时薪上限 ¥500/h"</li>
 *   <li>Rule 2 context: dialog header 显 "员工 {name} — 工资策略配置"</li>
 *   <li>Rule 3 dropdown: mode 用 el-select enum + 联动显 hint</li>
 * </ul>
 *
 * @since 2026-05-19
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Delete, Setting } from '@element-plus/icons-vue';
import {
  listPolicies,
  savePolicy,
  deletePolicy,
  listHourlyRules,
  saveHourlyRule,
  deleteHourlyRule,
  calculateMonthly,
  calculateMonthlyBatch,
  listCalculations,
  WAGE_MODE_LABEL,
  type WagePolicy,
  type WageMode,
  type HourlyRateRule,
  type WageCalculation,
} from '@/api/wagePolicy';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

// ============ state ============
const activeTab = ref<'policies' | 'hourly' | 'calculations'>('policies');
const loading = ref(false);

// policies
const policies = ref<WagePolicy[]>([]);
const policyDialog = ref({
  visible: false,
  editing: null as WagePolicy | null,
  form: {
    employeeId: null as number | null,
    mode: 'PIECE_RATE' as WageMode,
    mixedFormulaHint: '',
    isActive: true,
    notes: '',
  },
});

// hourly rules
const hourlyRules = ref<HourlyRateRule[]>([]);
const hourlyDialog = ref({
  visible: false,
  editing: null as HourlyRateRule | null,
  form: {
    employeeId: null as number | null,
    baseHourlyRate: 30 as number,
    overtimeMultiplier: 1.5 as number,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: '' as string,
    isActive: true,
    notes: '',
  },
});

const MAX_HOURLY_RATE = 500;

// calculations
const calculations = ref<WageCalculation[]>([]);
const selectedMonth = ref<string>(new Date().toISOString().slice(0, 7));  // YYYY-MM

// ============ load ============
async function refreshAll() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    await Promise.all([refreshPolicies(), refreshHourlyRules(), refreshCalculations()]);
  } finally {
    loading.value = false;
  }
}

async function refreshPolicies() {
  const res = await listPolicies(factoryId.value);
  policies.value = res.data || [];
}

async function refreshHourlyRules() {
  const res = await listHourlyRules(factoryId.value);
  hourlyRules.value = res.data || [];
}

async function refreshCalculations() {
  if (!selectedMonth.value) return;
  const res = await listCalculations(factoryId.value, selectedMonth.value);
  calculations.value = res.data || [];
}

onMounted(() => {
  refreshAll();
});

// ============ policy CRUD ============
function openPolicyDialog(item: WagePolicy | null) {
  policyDialog.value.editing = item;
  if (item) {
    policyDialog.value.form = {
      employeeId: item.employeeId ?? null,
      mode: item.mode,
      mixedFormulaHint: item.mixedFormulaHint || '',
      isActive: item.isActive !== false,
      notes: item.notes || '',
    };
  } else {
    policyDialog.value.form = {
      employeeId: null,
      mode: 'PIECE_RATE',
      mixedFormulaHint: '',
      isActive: true,
      notes: '',
    };
  }
  policyDialog.value.visible = true;
}

async function savePolicyForm() {
  const f = policyDialog.value.form;
  const payload: WagePolicy = {
    id: policyDialog.value.editing?.id,
    employeeId: f.employeeId,
    mode: f.mode,
    mixedFormulaHint: f.mode === 'MIXED' ? f.mixedFormulaHint : '',
    isActive: f.isActive,
    notes: f.notes,
  };
  try {
    await savePolicy(factoryId.value, payload);
    ElMessage.success('保存成功');
    policyDialog.value.visible = false;
    await refreshPolicies();
  } catch (e) {
    // error toast 由 request.ts 自动 sticky
    console.error('savePolicy failed', e);
  }
}

async function removePolicy(item: WagePolicy) {
  if (!item.id) return;
  await ElMessageBox.confirm(
    `确定删除该 wage policy ${item.employeeId ? `(员工 ${item.employeeId})` : '(factory default)'}?`,
    '删除确认',
    { type: 'warning' },
  );
  try {
    await deletePolicy(factoryId.value, item.id);
    ElMessage.success('已删除');
    await refreshPolicies();
  } catch (e) {
    console.error('deletePolicy failed', e);
  }
}

// ============ hourly rule CRUD ============
function openHourlyDialog(item: HourlyRateRule | null) {
  hourlyDialog.value.editing = item;
  if (item) {
    hourlyDialog.value.form = {
      employeeId: item.employeeId,
      baseHourlyRate: item.baseHourlyRate,
      overtimeMultiplier: item.overtimeMultiplier ?? 1.5,
      effectiveFrom: item.effectiveFrom,
      effectiveTo: item.effectiveTo || '',
      isActive: item.isActive !== false,
      notes: item.notes || '',
    };
  } else {
    hourlyDialog.value.form = {
      employeeId: null,
      baseHourlyRate: 30,
      overtimeMultiplier: 1.5,
      effectiveFrom: new Date().toISOString().slice(0, 10),
      effectiveTo: '',
      isActive: true,
      notes: '',
    };
  }
  hourlyDialog.value.visible = true;
}

const hourlyFormValid = computed(() => {
  const f = hourlyDialog.value.form;
  if (!f.employeeId) return false;
  if (!f.baseHourlyRate || f.baseHourlyRate <= 0) return false;
  if (f.baseHourlyRate > MAX_HOURLY_RATE) return false;
  if (!f.effectiveFrom) return false;
  if (f.effectiveTo && f.effectiveTo < f.effectiveFrom) return false;
  return true;
});

const hourlyOverLimit = computed(() => {
  const f = hourlyDialog.value.form;
  return f.baseHourlyRate > MAX_HOURLY_RATE;
});

async function saveHourlyForm() {
  if (!hourlyFormValid.value) {
    ElMessage.warning('表单填写不完整或超出限制');
    return;
  }
  const f = hourlyDialog.value.form;
  const payload: HourlyRateRule = {
    id: hourlyDialog.value.editing?.id,
    employeeId: f.employeeId!,
    baseHourlyRate: f.baseHourlyRate,
    overtimeMultiplier: f.overtimeMultiplier,
    effectiveFrom: f.effectiveFrom,
    effectiveTo: f.effectiveTo || null,
    isActive: f.isActive,
    notes: f.notes,
  };
  try {
    await saveHourlyRule(factoryId.value, payload);
    ElMessage.success('保存成功');
    hourlyDialog.value.visible = false;
    await refreshHourlyRules();
  } catch (e) {
    console.error('saveHourlyRule failed', e);
  }
}

async function removeHourlyRule(item: HourlyRateRule) {
  if (!item.id) return;
  await ElMessageBox.confirm(`确定删除员工 ${item.employeeId} 的时薪规则?`, '删除确认', { type: 'warning' });
  try {
    await deleteHourlyRule(factoryId.value, item.id);
    ElMessage.success('已删除');
    await refreshHourlyRules();
  } catch (e) {
    console.error('deleteHourlyRule failed', e);
  }
}

// ============ calculate triggers ============
const triggering = ref(false);

async function triggerMonthlyBatch() {
  if (!selectedMonth.value) {
    ElMessage.warning('请选择月份');
    return;
  }
  await ElMessageBox.confirm(
    `确定 trigger 月份 ${selectedMonth.value} 全工厂员工的月度工资计算?`,
    '批量计算确认',
    { type: 'info' },
  );
  triggering.value = true;
  try {
    const res = await calculateMonthlyBatch(factoryId.value, selectedMonth.value);
    const data = res.data;
    if (data) {
      ElMessage.success(
        `批量计算完成: 共 ${data.workerCount} 员工, 成功 ${data.successCount}, 失败 ${data.failedCount}`,
      );
    }
    await refreshCalculations();
  } catch (e) {
    console.error('calculateMonthlyBatch failed', e);
  } finally {
    triggering.value = false;
  }
}

async function triggerSingleCalc(employeeId: number) {
  if (!selectedMonth.value) {
    ElMessage.warning('请选择月份');
    return;
  }
  try {
    await calculateMonthly(factoryId.value, employeeId, selectedMonth.value);
    ElMessage.success(`员工 ${employeeId} 月度计算完成`);
    await refreshCalculations();
  } catch (e) {
    console.error('calculateMonthly failed', e);
  }
}

// ============ ui helpers ============
function modeLabel(m: WageMode): string {
  return WAGE_MODE_LABEL[m];
}

function modeTagType(m: WageMode): '' | 'success' | 'warning' | 'info' {
  switch (m) {
    case 'PIECE_RATE': return '';
    case 'HOURLY': return 'success';
    case 'MIXED': return 'warning';
    default: return 'info';
  }
}
</script>

<template>
  <div class="wage-policy-page">
    <el-card>
      <template #header>
        <div class="header">
          <span>工资策略配置 (Sprint 6 W4-B)</span>
          <el-button :icon="Refresh" @click="refreshAll" :loading="loading">刷新</el-button>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <!-- ========== Tab 1: WagePolicy 列表 ========== -->
        <el-tab-pane label="工资策略 (mode)" name="policies">
          <div class="tab-toolbar">
            <el-button type="primary" :icon="Plus" @click="openPolicyDialog(null)">新增 Policy</el-button>
          </div>
          <el-table :data="policies" v-loading="loading" border>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="employeeId" label="员工 ID" width="120">
              <template #default="{ row }">
                <span v-if="row.employeeId">{{ row.employeeId }}</span>
                <el-tag v-else type="info" size="small">factory default</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="mode" label="模式" width="180">
              <template #default="{ row }">
                <el-tag :type="modeTagType(row.mode)">{{ modeLabel(row.mode) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="mixedFormulaHint" label="混合公式提示" min-width="180" />
            <el-table-column prop="isActive" label="启用" width="80">
              <template #default="{ row }">
                <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                  {{ row.isActive ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="notes" label="备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openPolicyDialog(row)">编辑</el-button>
                <el-button size="small" type="danger" :icon="Delete" @click="removePolicy(row)" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== Tab 2: HourlyRateRule 列表 ========== -->
        <el-tab-pane label="时薪规则" name="hourly">
          <div class="tab-toolbar">
            <el-button type="primary" :icon="Plus" @click="openHourlyDialog(null)">新增时薪规则</el-button>
            <span class="hint">时薪上限 ¥{{ MAX_HOURLY_RATE }}/h (防呆 Rule 1)</span>
          </div>
          <el-table :data="hourlyRules" v-loading="loading" border>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="employeeId" label="员工 ID" width="120" />
            <el-table-column prop="baseHourlyRate" label="基础时薪 (¥/h)" width="140">
              <template #default="{ row }">¥{{ row.baseHourlyRate }}</template>
            </el-table-column>
            <el-table-column prop="overtimeMultiplier" label="加班倍率" width="120" />
            <el-table-column prop="effectiveFrom" label="生效起" width="120" />
            <el-table-column prop="effectiveTo" label="生效止" width="120">
              <template #default="{ row }">
                <span v-if="row.effectiveTo">{{ row.effectiveTo }}</span>
                <el-tag v-else type="success" size="small">长期</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="isActive" label="启用" width="80">
              <template #default="{ row }">
                <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                  {{ row.isActive ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openHourlyDialog(row)">编辑</el-button>
                <el-button size="small" type="danger" :icon="Delete" @click="removeHourlyRule(row)" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== Tab 3: WageCalculation 月度计算 ========== -->
        <el-tab-pane label="月度计算" name="calculations">
          <div class="tab-toolbar">
            <span>选择月份:</span>
            <el-date-picker
              v-model="selectedMonth"
              type="month"
              format="YYYY-MM"
              value-format="YYYY-MM"
              placeholder="选择月份"
              @change="refreshCalculations"
              style="width: 150px"
            />
            <el-button :icon="Refresh" @click="refreshCalculations">刷新</el-button>
            <el-button
              type="warning"
              :icon="Setting"
              :loading="triggering"
              @click="triggerMonthlyBatch"
            >
              批量计算 (manual trigger)
            </el-button>
            <span class="hint">月底 1 号 01:00 自动 trigger (cron 0 0 1 1 * ?)</span>
          </div>
          <el-table :data="calculations" v-loading="loading" border>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="employeeId" label="员工 ID" width="100" />
            <el-table-column prop="employeeName" label="姓名" width="120" />
            <el-table-column prop="mode" label="模式" width="180">
              <template #default="{ row }">
                <el-tag :type="modeTagType(row.mode)">{{ modeLabel(row.mode) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="hourlyAmount" label="按时金额" width="120">
              <template #default="{ row }">¥{{ row.hourlyAmount }}</template>
            </el-table-column>
            <el-table-column prop="overtimeAmount" label="加班金额" width="120">
              <template #default="{ row }">¥{{ row.overtimeAmount }}</template>
            </el-table-column>
            <el-table-column prop="pieceRateAmount" label="计件金额" width="120">
              <template #default="{ row }">¥{{ row.pieceRateAmount }}</template>
            </el-table-column>
            <el-table-column prop="totalAmount" label="总金额" width="120">
              <template #default="{ row }">
                <strong>¥{{ row.totalAmount }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="totalHours" label="总工时" width="100" />
            <el-table-column prop="overtimeHours" label="加班工时" width="110" />
            <el-table-column prop="totalPieceCount" label="件数" width="100" />
            <el-table-column prop="notes" label="备注" min-width="120" show-overflow-tooltip />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="triggerSingleCalc(row.employeeId)">重算</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ========== Policy dialog (Rule 2 context, Rule 3 dropdown) ========== -->
    <el-dialog
      v-model="policyDialog.visible"
      :title="policyDialog.editing
        ? `编辑 wage policy — 员工 ${policyDialog.editing.employeeId ?? '(factory default)'}`
        : '新增 wage policy'"
      width="560px"
    >
      <el-form :model="policyDialog.form" label-width="120px">
        <el-form-item label="员工 ID">
          <el-input-number
            v-model="policyDialog.form.employeeId"
            :min="1"
            placeholder="留空 = 工厂默认 policy"
            style="width: 240px"
          />
          <span class="hint" style="margin-left: 8px">留空 = factory default (兜底所有未单独配置员工)</span>
        </el-form-item>
        <el-form-item label="工资模式" required>
          <el-select v-model="policyDialog.form.mode" style="width: 240px">
            <el-option label="PIECE_RATE — 按件 (PR #57 默认)" value="PIECE_RATE" />
            <el-option label="HOURLY — 按时 (工时 × 时薪)" value="HOURLY" />
            <el-option label="MIXED — 混合 (按时 + 计件)" value="MIXED" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="policyDialog.form.mode === 'MIXED'"
          label="混合公式提示"
        >
          <el-input
            v-model="policyDialog.form.mixedFormulaHint"
            placeholder="例: 基础工时 + 80% 计件提成"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="policyDialog.form.isActive" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="policyDialog.form.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="savePolicyForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ========== Hourly rule dialog (Rule 1 max 防呆) ========== -->
    <el-dialog
      v-model="hourlyDialog.visible"
      :title="hourlyDialog.editing
        ? `编辑时薪规则 — 员工 ${hourlyDialog.editing.employeeId}`
        : '新增时薪规则'"
      width="560px"
    >
      <el-form :model="hourlyDialog.form" label-width="120px">
        <el-form-item label="员工 ID" required>
          <el-input-number v-model="hourlyDialog.form.employeeId" :min="1" style="width: 240px" />
        </el-form-item>
        <el-form-item label="基础时薪 ¥/h" required>
          <el-input-number
            v-model="hourlyDialog.form.baseHourlyRate"
            :min="0.01"
            :max="MAX_HOURLY_RATE"
            :precision="2"
            :step="1"
            style="width: 240px"
          />
          <span :class="['hint', hourlyOverLimit ? 'hint-error' : '']" style="margin-left: 8px">
            时薪上限 ¥{{ MAX_HOURLY_RATE }}/h, 防误输 (防呆 Rule 1)
          </span>
        </el-form-item>
        <el-form-item label="加班倍率">
          <el-input-number
            v-model="hourlyDialog.form.overtimeMultiplier"
            :min="1"
            :max="3"
            :precision="2"
            :step="0.1"
            style="width: 240px"
          />
          <span class="hint" style="margin-left: 8px">默认 1.5 (常见 1.5 / 2.0)</span>
        </el-form-item>
        <el-form-item label="生效起" required>
          <el-date-picker
            v-model="hourlyDialog.form.effectiveFrom"
            type="date"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="生效止">
          <el-date-picker
            v-model="hourlyDialog.form.effectiveTo"
            type="date"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            placeholder="留空 = 长期有效"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="hourlyDialog.form.isActive" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="hourlyDialog.form.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="hourlyDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!hourlyFormValid" @click="saveHourlyForm">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wage-policy-page {
  padding: 16px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tab-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: #909399;
}

.hint-error {
  color: #f56c6c;
  font-weight: bold;
}
</style>
