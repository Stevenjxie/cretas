<script setup lang="ts">
/**
 * Sprint 7 T2 F-PERIOD — 期间结账管理 admin UI.
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 2 (context): 每行显 {year}-{month} + N 笔凭证 + 状态 tag
 * - Rule 3 (constrained input): 反结账原因 dropdown + 其他 textarea
 * - Rule 5 (dead-end nav): CLOSED 行的 "尝试修改 voucher" 走 ElMessageBox.confirm
 *   "期间已结账, 是否反结账?" → 跳转此页 + auto-open dialog
 *
 * Route: /finance/accounting-period
 * Permission: finance:read_write (Controller 层 enforce)
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { Refresh, Lock, Unlock, Calendar, Finished } from '@element-plus/icons-vue';
import {
  computeAdjustWindow,
  reconciliationTagType,
} from './monthCloseHelpers';

// ============================================================
// Types — mirror backend AccountingPeriod entity
// ============================================================
type PeriodStatus = 'OPEN' | 'PENDING_CLOSE' | 'CLOSED';

interface AccountingPeriod {
  id: string;
  factoryId: string;
  year: number;
  month: number;
  status: PeriodStatus;
  openedAt?: string;
  openedBy?: number;
  closedAt?: string;
  closedBy?: number;
  reopenedAt?: string;
  reopenedBy?: number;
  reopenReason?: string;
  approvalWorkflowInstanceId?: string;
  createdAt?: string;
  updatedAt?: string;
  // Wave2 月结自动闭环字段
  adjustDeadline?: string;
  reconciliationStatus?: string;
  reconciliationSummary?: string;
  totalRevenueSnapshot?: number;
  netProfitSnapshot?: number;
  reportReadyAt?: string;
}

// Wave2 月结对账预览 DTO
interface CheckItem {
  name: string;
  passed: boolean;
  severity: 'BLOCKING' | 'WARNING' | 'INFO';
  detail: string;
  value?: unknown;
}
interface MonthCloseReconciliation {
  factoryId: string;
  year: number;
  month: number;
  canClose: boolean;
  reconciliationStatus: string;
  checks: CheckItem[];
  summary: string;
}

// ============================================================
// Store / route
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

const loading = ref(false);
const periods = ref<AccountingPeriod[]>([]);

// ============================================================
// Wave2 月结 (Month-Close) state
// ============================================================
const monthClosePreviewVisible = ref(false);
const monthCloseLoading = ref(false);
const monthClosePreview = ref<MonthCloseReconciliation | null>(null);
const monthCloseTarget = ref<{ year: number; month: number } | null>(null);

// ============================================================
// Open / close dialog state
// ============================================================
const openDialogVisible = ref(false);
const openForm = ref({ year: new Date().getFullYear(), month: new Date().getMonth() + 1 });

const closeDialogVisible = ref(false);
const closeForm = ref<{ period: AccountingPeriod | null; voucherCount: number }>({
  period: null, voucherCount: 0,
});

// 反结账 dialog (Rule 3 — dropdown + 其他 textarea)
const reopenDialogVisible = ref(false);
const reopenForm = ref<{
  period: AccountingPeriod | null;
  reasonType: string;
  reasonExtra: string;
}>({
  period: null,
  reasonType: '',
  reasonExtra: '',
});

const REOPEN_REASON_PRESETS = [
  { value: '财务发现少入一笔凭证', label: '少入凭证 — 财务核对发现遗漏' },
  { value: '审计退回, 需补录调整', label: '审计退回 — 需补录调整凭证' },
  { value: '系统对账发现差异, 需要调整', label: '对账差异 — 需要调整凭证' },
  { value: '上级单位要求重新报表', label: '上级要求 — 重新生成报表' },
  { value: '其他', label: '其他 (请填写具体原因)' },
];

// ============================================================
// Status label / type
// ============================================================
const STATUS_LABEL: Record<PeriodStatus, string> = {
  OPEN: '开账中',
  PENDING_CLOSE: '审批中',
  CLOSED: '已结账',
};

const STATUS_TAG_TYPE: Record<PeriodStatus, 'success' | 'warning' | 'danger'> = {
  OPEN: 'success',
  PENDING_CLOSE: 'warning',
  CLOSED: 'danger',
};

// ============================================================
// API calls
// ============================================================
async function loadPeriods() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<AccountingPeriod[]>(
      `/${factoryId.value}/finance/accounting-periods`
    );
    if (res.success && res.data) {
      periods.value = res.data;
    }
  } catch {
    // interceptor toasts
  } finally {
    loading.value = false;
  }
}

async function fetchVoucherCount(year: number, month: number): Promise<number> {
  // 防呆 Rule 2 (context): close dialog 显 "{year}-{month} 期间 — N 笔凭证"
  // 调 voucher list endpoint 取 totalElements (按月过滤需 fromDate/toDate)
  try {
    const fromDate = `${year}-${String(month).padStart(2, '0')}-01`;
    const lastDay = new Date(year, month, 0).getDate();
    const toDate = `${year}-${String(month).padStart(2, '0')}-${lastDay}`;
    const res = await get<{ totalElements: number }>(
      `/${factoryId.value}/finance/vouchers`,
      { params: { fromDate, toDate, page: 1, size: 1 } }
    );
    return res.data?.totalElements ?? 0;
  } catch {
    return 0;
  }
}

// ============================================================
// Actions: open / requestClose / confirmClose / reopen
// ============================================================
async function handleOpenPeriod() {
  if (!factoryId.value) return;
  try {
    const res = await post<AccountingPeriod>(
      `/${factoryId.value}/finance/accounting-periods/open`,
      { year: openForm.value.year, month: openForm.value.month }
    );
    if (res.success) {
      ElMessage.success('期间已打开');
      openDialogVisible.value = false;
      await loadPeriods();
    }
  } catch {
    // interceptor toasts
  }
}

async function handleRequestClose(period: AccountingPeriod) {
  // 防呆 Rule 2 context: 显 "{year}-{month} 期间 — N 笔凭证" 在 confirm dialog
  const voucherCount = await fetchVoucherCount(period.year, period.month);
  closeForm.value = { period, voucherCount };

  try {
    await ElMessageBox.confirm(
      `确认对 ${period.year}-${String(period.month).padStart(2, '0')} 期间发起结账?\n\n` +
      `该期间共有 ${voucherCount} 笔凭证, 提交后将走 finance director 审批流程.\n` +
      `审批通过前 voucher 仍可修改; 审批通过后 voucher 将被锁定.`,
      '发起结账',
      {
        confirmButtonText: '发起结账',
        cancelButtonText: '取消',
        type: 'warning',
      }
    );
  } catch {
    return;
  }

  try {
    const res = await post<AccountingPeriod>(
      `/${factoryId.value}/finance/accounting-periods/request-close`,
      { year: period.year, month: period.month }
    );
    if (res.success) {
      ElMessage.success('结账请求已提交, 等待 finance director 审批');
      await loadPeriods();
    }
  } catch {
    // interceptor toasts
  }
}

async function handleConfirmClose(period: AccountingPeriod) {
  const voucherCount = await fetchVoucherCount(period.year, period.month);

  try {
    await ElMessageBox.confirm(
      `确认结账 ${period.year}-${String(period.month).padStart(2, '0')} 期间?\n\n` +
      `该期间共 ${voucherCount} 笔凭证, 结账后所有 voucher 不可修改 / 过账 / 作废.\n` +
      `如需修改, 必须先反结账 (audit log 会留痕).`,
      '确认结账',
      {
        confirmButtonText: '确认结账',
        cancelButtonText: '取消',
        type: 'error',
      }
    );
  } catch {
    return;
  }

  try {
    const res = await post<AccountingPeriod>(
      `/${factoryId.value}/finance/accounting-periods/confirm-close`,
      { year: period.year, month: period.month }
    );
    if (res.success) {
      ElMessage.success('期间已结账, 凭证写入已锁定');
      await loadPeriods();
    }
  } catch {
    // interceptor toasts
  }
}

function openReopenDialog(period: AccountingPeriod) {
  reopenForm.value = {
    period,
    reasonType: '',
    reasonExtra: '',
  };
  reopenDialogVisible.value = true;
}

async function handleReopen() {
  const form = reopenForm.value;
  if (!form.period) return;

  // 防呆 Rule 3: dropdown 选项 + 其他 textarea
  let reason = form.reasonType;
  if (form.reasonType === '其他') {
    if (!form.reasonExtra.trim()) {
      ElMessage.error('选择"其他"时, 必须填写具体原因');
      return;
    }
    reason = form.reasonExtra.trim();
  } else if (!form.reasonType) {
    ElMessage.error('请选择反结账原因');
    return;
  }

  try {
    const res = await post<AccountingPeriod>(
      `/${factoryId.value}/finance/accounting-periods/reopen`,
      {
        year: form.period.year,
        month: form.period.month,
        reason,
      }
    );
    if (res.success) {
      ElMessage.success('期间已反结账, voucher 可继续修改');
      reopenDialogVisible.value = false;
      await loadPeriods();
    }
  } catch {
    // interceptor toasts
  }
}

// ============================================================
// Wave2 月结: 一键月结流程
//   1. GET /preview → 弹对账预显 dialog (防呆 R1)
//   2. canClose=false 时 disable 确认 button
//   3. 确认 → POST /execute → 成功后 dead-end 跳报表 (防呆 R5)
// ============================================================
async function handleMonthClose(period: AccountingPeriod) {
  if (!factoryId.value) return;
  // 幂等防呆 R4: 已 CLOSED 不应到这 (按钮已隐藏), 防御性再挡一次
  if (period.status === 'CLOSED') {
    ElMessage.warning('该期间已结账, 不能重复结账');
    return;
  }
  monthCloseTarget.value = { year: period.year, month: period.month };
  monthClosePreview.value = null;
  monthCloseLoading.value = true;
  monthClosePreviewVisible.value = true;
  try {
    const res = await get<MonthCloseReconciliation>(
      `/${factoryId.value}/finance/month-close/preview`,
      { params: { year: period.year, month: period.month } }
    );
    if (res.success && res.data) {
      monthClosePreview.value = res.data;
    }
  } catch {
    // interceptor toasts; 关 dialog
    monthClosePreviewVisible.value = false;
  } finally {
    monthCloseLoading.value = false;
  }
}

async function confirmMonthClose() {
  const target = monthCloseTarget.value;
  if (!target || !factoryId.value) return;
  if (!monthClosePreview.value?.canClose) {
    ElMessage.error('对账校验未通过, 无法结账');
    return;
  }
  monthCloseLoading.value = true;
  try {
    const res = await post<{
      adjustDeadline?: string;
      reportReadyAt?: string;
      message?: string;
      incomeStatement?: { netProfit?: number };
    }>(
      `/${factoryId.value}/finance/month-close/execute`,
      { year: target.year, month: target.month }
    );
    if (res.success) {
      monthClosePreviewVisible.value = false;
      await loadPeriods();
      // 防呆 R5 dead-end nav: 结账后跳报表
      try {
        await ElMessageBox.confirm(
          `${target.year}-${String(target.month).padStart(2, '0')} 月结完成, 利润表已生成.\n` +
          `是否立即查看财务报表?`,
          '月结完成',
          {
            confirmButtonText: '查看报表',
            cancelButtonText: '稍后',
            type: 'success',
          }
        );
        router.push('/finance/three-statements');
      } catch {
        // 用户选"稍后", 不跳转
      }
    }
  } catch {
    // interceptor toasts
  } finally {
    monthCloseLoading.value = false;
  }
}

// ============================================================
// Mounted — check route query for ?year=&month= deep-link
// (Rule 5 dead-end nav: PeriodClosedException actionHint 跳此页 + auto-open dialog)
// ============================================================
onMounted(async () => {
  await loadPeriods();

  const queryYear = Number(route.query.year);
  const queryMonth = Number(route.query.month);
  if (queryYear && queryMonth) {
    // Deep-link from PeriodClosedException actionHint — find this period
    // and offer reopen dialog
    const target = periods.value.find(
      p => p.year === queryYear && p.month === queryMonth && p.status === 'CLOSED'
    );
    if (target) {
      openReopenDialog(target);
    }
  }
});
</script>

<template>
  <div class="accounting-period-page">
    <el-card>
      <template #header>
        <div class="header">
          <h2>
            <el-icon><Calendar /></el-icon>
            期间结账管理
          </h2>
          <div class="actions">
            <el-button @click="loadPeriods" :icon="Refresh">刷新</el-button>
            <el-button
              type="primary"
              :icon="Unlock"
              :disabled="!canWrite"
              @click="openDialogVisible = true">
              打开期间
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        title="期间结账机制说明"
        type="info"
        :closable="false"
        show-icon>
        <template #default>
          <ul style="padding-left: 20px; margin: 5px 0;">
            <li><b>开账中 (OPEN)</b>: 凭证可写入 / 过账 / 作废</li>
            <li><b>审批中 (PENDING_CLOSE)</b>: 财务发起结账, 等待 finance director 审批, voucher 仍可修改</li>
            <li><b>已结账 (CLOSED)</b>: 利润表已生成并冻结. 凭证在 20 天调整窗口内仍可调整, 窗口结束后锁定. 反结账 必须填原因 (audit log)</li>
            <li><b>一键月结</b>: 月初触发 — 对账校验 → 生成利润表 → 快照锁定 → 结账 + 开启 20 天调整窗口 (月初出报表, 留调整窗口)</li>
            <li>每月 1 号 02:00 自动对上月 OPEN 期间发起结账请求</li>
          </ul>
        </template>
      </el-alert>

      <el-table
        v-loading="loading"
        :data="periods"
        style="margin-top: 16px;"
        empty-text="尚无期间记录 — 点击「打开期间」开始">
        <el-table-column label="期间" min-width="120">
          <template #default="{ row }">
            <span class="period-name">
              {{ row.year }}-{{ String(row.month).padStart(2, '0') }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG_TYPE[row.status as PeriodStatus]">
              {{ STATUS_LABEL[row.status as PeriodStatus] }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="打开时间" width="180">
          <template #default="{ row }">
            <span v-if="row.openedAt">{{ row.openedAt.replace('T', ' ').slice(0, 19) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="结账时间" width="180">
          <template #default="{ row }">
            <span v-if="row.closedAt">{{ row.closedAt.replace('T', ' ').slice(0, 19) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <!-- Wave2 月结: 调整窗口截止 + 倒计时 (邓总20天窗口) -->
        <el-table-column label="调整窗口" min-width="170">
          <template #default="{ row }">
            <template v-if="row.status === 'CLOSED'">
              <el-tag
                :type="computeAdjustWindow(row.status, row.adjustDeadline).state === 'OPEN_WINDOW' ? 'warning' : 'info'"
                size="small">
                {{ computeAdjustWindow(row.status, row.adjustDeadline).label }}
              </el-tag>
              <div v-if="row.adjustDeadline" class="muted small">
                截止 {{ row.adjustDeadline.replace('T', ' ').slice(0, 10) }}
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <!-- Wave2 月结: 净利润快照 + 对账结论 -->
        <el-table-column label="月结报表" min-width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'CLOSED' && row.reportReadyAt">
              <div>
                <el-tag :type="reconciliationTagType(row.reconciliationStatus)" size="small">
                  对账 {{ row.reconciliationStatus || '—' }}
                </el-tag>
              </div>
              <div class="muted small">
                净利 {{ row.netProfitSnapshot != null ? row.netProfitSnapshot : '—' }}
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最近反结账" min-width="200">
          <template #default="{ row }">
            <div v-if="row.reopenedAt">
              <div>{{ row.reopenedAt.replace('T', ' ').slice(0, 19) }}</div>
              <div class="muted small">{{ row.reopenReason }}</div>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <!-- Wave2 一键月结: OPEN / PENDING_CLOSE 行可触发 (CLOSED 隐藏, 防重复 R4) -->
            <el-button
              v-if="row.status !== 'CLOSED'"
              size="small"
              type="success"
              :icon="Finished"
              :disabled="!canWrite"
              @click="handleMonthClose(row)">
              一键月结
            </el-button>
            <el-button
              v-if="row.status === 'OPEN'"
              size="small"
              type="warning"
              :icon="Lock"
              :disabled="!canWrite"
              @click="handleRequestClose(row)">
              发起结账
            </el-button>
            <el-button
              v-if="row.status === 'PENDING_CLOSE'"
              size="small"
              type="danger"
              :icon="Lock"
              :disabled="!canWrite"
              @click="handleConfirmClose(row)">
              确认结账
            </el-button>
            <el-button
              v-if="row.status === 'CLOSED'"
              size="small"
              type="primary"
              :icon="Unlock"
              :disabled="!canWrite"
              @click="openReopenDialog(row)">
              反结账
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 打开期间 dialog -->
    <el-dialog
      v-model="openDialogVisible"
      title="打开期间"
      width="400px">
      <el-form label-width="80px">
        <el-form-item label="年度">
          <el-input-number v-model="openForm.year" :min="2020" :max="2100" />
        </el-form-item>
        <el-form-item label="月份">
          <el-input-number v-model="openForm.month" :min="1" :max="12" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleOpenPeriod">确认</el-button>
      </template>
    </el-dialog>

    <!-- 反结账 dialog — 防呆 Rule 3 dropdown + 其他 textarea -->
    <el-dialog
      v-model="reopenDialogVisible"
      title="反结账"
      width="500px">
      <template v-if="reopenForm.period">
        <el-alert
          :title="`反结账 ${reopenForm.period.year}-${String(reopenForm.period.month).padStart(2,'0')} 期间`"
          type="warning"
          :closable="false"
          show-icon>
          反结账后, 该期间凭证可继续修改 / 过账 / 作废. 操作会留 audit log (反结账人 / 时间 / 原因 永久保留).
        </el-alert>

        <el-form label-width="100px" style="margin-top: 16px;">
          <el-form-item label="原因类别" required>
            <el-select
              v-model="reopenForm.reasonType"
              placeholder="请选择反结账原因"
              style="width: 100%;">
              <el-option
                v-for="r in REOPEN_REASON_PRESETS"
                :key="r.value"
                :value="r.value"
                :label="r.label" />
            </el-select>
          </el-form-item>

          <el-form-item
            v-if="reopenForm.reasonType === '其他'"
            label="具体原因"
            required>
            <el-input
              v-model="reopenForm.reasonExtra"
              type="textarea"
              :rows="3"
              placeholder="请详细描述反结账原因 (audit log 永久保留)"
              maxlength="500"
              show-word-limit />
          </el-form-item>
        </el-form>
      </template>

      <template #footer>
        <el-button @click="reopenDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="handleReopen">确认反结账</el-button>
      </template>
    </el-dialog>

    <!-- Wave2 一键月结: 对账预显 dialog (防呆 R1 预先显示边界) -->
    <el-dialog
      v-model="monthClosePreviewVisible"
      title="月结对账预览"
      width="600px">
      <div v-loading="monthCloseLoading">
        <template v-if="monthClosePreview">
          <el-alert
            :title="`${monthClosePreview.year}-${String(monthClosePreview.month).padStart(2,'0')} 月结对账 — ${monthClosePreview.canClose ? '可执行结账' : '存在阻塞项, 暂不可结账'}`"
            :type="monthClosePreview.canClose ? (monthClosePreview.reconciliationStatus === 'PASS' ? 'success' : 'warning') : 'error'"
            :closable="false"
            show-icon>
            月结将: 对账校验 → 生成利润表 → 快照锁定 → 结账并开启 20 天调整窗口.
            结账后报表立即生成, 调整窗口内凭证仍可调整, 窗口结束后锁定.
          </el-alert>

          <el-table :data="monthClosePreview.checks" style="margin-top: 16px;" size="small">
            <el-table-column label="校验项" min-width="160">
              <template #default="{ row }">{{ row.name }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.passed ? 'success' : (row.severity === 'BLOCKING' ? 'danger' : 'warning')" size="small">
                  {{ row.passed ? '通过' : (row.severity === 'BLOCKING' ? '阻塞' : '提示') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="240">
              <template #default="{ row }">
                <span :class="{ muted: row.passed }">{{ row.detail }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!monthCloseLoading" description="无对账数据" />
      </div>

      <template #footer>
        <el-button @click="monthClosePreviewVisible = false">取消</el-button>
        <el-button
          type="success"
          :loading="monthCloseLoading"
          :disabled="!monthClosePreview?.canClose"
          @click="confirmMonthClose">
          确认月结
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.accounting-period-page {
  padding: 16px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header h2 {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.actions {
  display: flex;
  gap: 8px;
}

.period-name {
  font-family: 'Consolas', monospace;
  font-weight: 600;
  font-size: 15px;
}

.muted {
  color: #909399;
}

.small {
  font-size: 12px;
}
</style>
