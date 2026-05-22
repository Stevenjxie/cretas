<script setup lang="ts">
/**
 * #833 H-WAGE follow-up — 年度汇算 (China 综合所得 annual tax settlement)
 *
 * Aggregates monthly SalaryItem rows + AnnualBonus + SpecialDeduction for a tax year,
 * applies 7-bracket ANNUAL tax, diffs vs monthly prepaid sum → refund / owed / 平账.
 *
 * 防呆策略:
 *   - R1: "计算"button 先 preview (不写库) → dialog 显示应退/应补 → "保存" 才写库
 *   - R2: confirm dialog / toast 含 user+year+verdict context
 *   - R4: re-compute idempotent — 同 (user, year) UPDATE 不 INSERT
 *   - R5: REPORTED 状态锁住, list 行 disable 修改 button + 显示 "已申报税局"
 *
 * @since 2026-05-18
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Promotion } from '@element-plus/icons-vue';
import {
  listAnnualSettlements,
  previewAnnualSettlement,
  computeAnnualSettlement,
  confirmAnnualSettlement,
  reportAnnualSettlement,
  deleteAnnualSettlement,
  type AnnualTaxSettlement,
  type AnnualTaxSettlementStatus,
  type AnnualTaxPreview,
} from '@/api/annualTaxSettlement';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

// ============= state =============
const STATUS_LABEL: Record<AnnualTaxSettlementStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  REPORTED: '已申报税局',
};
const STATUS_TAG_TYPE: Record<AnnualTaxSettlementStatus, '' | 'success' | 'warning' | 'info'> = {
  DRAFT: 'info',
  CONFIRMED: 'warning',
  REPORTED: 'success',
};

const loading = ref(false);
const tableData = ref<AnnualTaxSettlement[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// 列表 filter
const filterYear = ref<number | null>(new Date().getFullYear() - 1);
const filterStatus = ref<AnnualTaxSettlementStatus | ''>('');
const filterUserId = ref<number | null>(null);

// ============= compute dialog =============
const computeDialogVisible = ref(false);
const computeSubmitting = ref(false);
const computeForm = ref({
  userId: 0,
  taxYear: new Date().getFullYear() - 1,
});
// R1 防呆 preview: 先算不写库
const previewData = ref<AnnualTaxPreview | null>(null);
const previewLoading = ref(false);

// ============= drilldown dialog =============
const drilldownVisible = ref(false);
const drilldownRow = ref<AnnualTaxSettlement | null>(null);

onMounted(() => {
  loadList();
});

watch([filterYear, filterStatus, filterUserId], () => {
  pagination.value.page = 1;
  loadList();
});

// ============= list =============
async function loadList() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listAnnualSettlements(factoryId.value, {
      taxYear: filterYear.value ?? undefined,
      status: filterStatus.value || undefined,
      userId: filterUserId.value ?? undefined,
      page: pagination.value.page - 1,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('加载年度汇算列表失败:', e);
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadList();
}

function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadList();
}

// ============= compute dialog =============
function openCompute() {
  computeForm.value = {
    userId: 0,
    taxYear: new Date().getFullYear() - 1,
  };
  previewData.value = null;
  computeDialogVisible.value = true;
}

// R1 防呆: 先 preview (不写库) — 用户点"预览"button 触发
async function handlePreview() {
  if (!factoryId.value) return;
  if (!computeForm.value.userId || computeForm.value.userId <= 0) {
    ElMessage.error('请填写员工 userId');
    return;
  }
  if (!computeForm.value.taxYear || computeForm.value.taxYear < 2000 || computeForm.value.taxYear > 2100) {
    ElMessage.error('税年必须在 2000-2100 范围内');
    return;
  }
  previewLoading.value = true;
  try {
    const res = await previewAnnualSettlement(
      factoryId.value, computeForm.value.userId, computeForm.value.taxYear);
    if (res.success && res.data) {
      previewData.value = res.data;
      if (previewData.value.monthsCovered === 0) {
        ElMessage.warning(
          `用户 ${computeForm.value.userId} 在 ${computeForm.value.taxYear} 年度无任何工资单`);
      }
    }
  } catch (e) {
    console.error('preview 失败:', e);
  } finally {
    previewLoading.value = false;
  }
}

// 用户已预览后"保存"才写库
async function handleSaveCompute() {
  if (!factoryId.value) return;
  if (!previewData.value) {
    ElMessage.warning('请先预览再保存');
    return;
  }
  computeSubmitting.value = true;
  try {
    const res = await computeAnnualSettlement(
      factoryId.value, computeForm.value.userId, computeForm.value.taxYear);
    if (res.success) {
      ElMessage.success(res.message || '已保存');
      computeDialogVisible.value = false;
      await loadList();
    }
  } catch (e) {
    // 4 位一体: 全局 axios interceptor 已 sticky display backend message (R5 已申报税局拒改)
    console.error('保存失败:', e);
  } finally {
    computeSubmitting.value = false;
  }
}

// ============= drilldown =============
function openDrilldown(row: AnnualTaxSettlement) {
  drilldownRow.value = row;
  drilldownVisible.value = true;
}

// ============= state machine actions =============
async function handleConfirm(row: AnnualTaxSettlement) {
  try {
    await ElMessageBox.confirm(
      `确认 ${row.taxYear} 年度汇算 [用户 ${row.userId} / ${formatRefundOwed(row.refundOwed)}] ?`,
      '确认汇算',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    );
    if (!factoryId.value) return;
    const res = await confirmAnnualSettlement(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success(res.message || '已确认');
      await loadList();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e);
  }
}

// R5 防呆: REPORTED 锁住, 二次确认明确警告"不可修改"
async function handleReport(row: AnnualTaxSettlement) {
  try {
    await ElMessageBox.confirm(
      `标记 ${row.taxYear} 年度汇算已申报税局 [用户 ${row.userId} / ${formatRefundOwed(row.refundOwed)}]\n\n` +
      `申报后将锁住, 不可再次计算或修改, 是否继续?`,
      '申报税局',
      {
        confirmButtonText: '已申报税局',
        cancelButtonText: '取消',
        type: 'warning',
      }
    );
    if (!factoryId.value) return;
    const res = await reportAnnualSettlement(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success(res.message || '已申报');
      await loadList();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e);
  }
}

async function handleDelete(row: AnnualTaxSettlement) {
  try {
    await ElMessageBox.confirm(
      `删除 ${row.taxYear} 年度汇算 [用户 ${row.userId}] ?`,
      '删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
    if (!factoryId.value) return;
    const res = await deleteAnnualSettlement(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success(res.message || '已删除');
      await loadList();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e);
  }
}

// ============= helpers =============
function formatRefundOwed(amount: number): string {
  if (amount == null || Number(amount) === 0) return '平账 (无补退)';
  if (Number(amount) > 0) return `应补缴 ¥${Number(amount).toFixed(2)}`;
  return `应退税 ¥${Math.abs(Number(amount)).toFixed(2)}`;
}

function refundOwedTagType(amount: number): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (amount == null || Number(amount) === 0) return 'info';
  if (Number(amount) > 0) return 'danger';  // 应补缴 红
  return 'success';                         // 应退税 绿
}

function fmtMoney(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${Number(v).toFixed(2)}`;
}
</script>

<template>
  <div class="annual-settlement-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span class="title">年度汇算 (综合所得)</span>
          <div class="actions">
            <el-button :icon="Refresh" size="small" @click="loadList">刷新</el-button>
            <el-button
              v-if="canWrite"
              type="primary"
              size="small"
              :icon="Promotion"
              @click="openCompute"
            >
              计算年度汇算
            </el-button>
          </div>
        </div>
      </template>

      <!-- 列表 filter -->
      <el-form inline :model="{}" size="small" class="filter-row">
        <el-form-item label="税年">
          <el-input-number
            v-model="filterYear"
            :min="2000"
            :max="2100"
            :step="1"
            :controls="false"
            placeholder="e.g. 2024"
            style="width: 140px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filterStatus" placeholder="全部" clearable style="width: 140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已申报税局" value="REPORTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="员工 ID">
          <el-input-number
            v-model="filterUserId"
            :min="1"
            :controls="false"
            placeholder="员工 ID"
            style="width: 140px"
          />
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <el-table
        :data="tableData"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="taxYear" label="税年" width="80" align="center" />
        <el-table-column prop="userId" label="员工 ID" width="100" align="center" />
        <el-table-column label="月数" width="70" align="center">
          <template #default="{ row }">{{ row.monthsCovered }}/12</template>
        </el-table-column>
        <el-table-column label="年度综合所得" width="140" align="right">
          <template #default="{ row }">{{ fmtMoney(row.totalSalary) }}</template>
        </el-table-column>
        <el-table-column label="应纳税额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.annualTaxDue) }}</template>
        </el-table-column>
        <el-table-column label="已预缴" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.monthlyPrepaidSum) }}</template>
        </el-table-column>
        <el-table-column label="汇算结果" width="180" align="center">
          <template #default="{ row }">
            <el-tag :type="refundOwedTagType(row.refundOwed)" effect="dark">
              {{ formatRefundOwed(row.refundOwed) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="档位" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.bracketLabel" class="bracket-label">
              {{ row.bracketRate }} ({{ row.bracketLabel }})
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG_TYPE[row.status as AnnualTaxSettlementStatus]">
              {{ STATUS_LABEL[row.status as AnnualTaxSettlementStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openDrilldown(row)">查看明细</el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              size="small"
              type="primary"
              link
              @click="handleConfirm(row)"
            >
              确认
            </el-button>
            <el-button
              v-if="canWrite && row.status === 'CONFIRMED'"
              size="small"
              type="warning"
              link
              @click="handleReport(row)"
            >
              申报税局
            </el-button>
            <el-button
              v-if="canWrite && row.status === 'DRAFT'"
              size="small"
              type="danger"
              link
              @click="handleDelete(row)"
            >
              删除
            </el-button>
            <!-- R5 防呆: REPORTED 时显示锁住提示 -->
            <span v-if="row.status === 'REPORTED'" class="locked-hint">
              已申报税局, 不可修改
            </span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        background
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <!-- ============= compute dialog (R1 防呆 preview + 保存) ============= -->
    <el-dialog
      v-model="computeDialogVisible"
      title="计算年度汇算"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form :model="computeForm" label-width="100px">
        <el-form-item label="员工 ID" required>
          <el-input-number
            v-model="computeForm.userId"
            :min="1"
            :controls="false"
            placeholder="员工 ID"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="税年" required>
          <el-input-number
            v-model="computeForm.taxYear"
            :min="2000"
            :max="2100"
            :step="1"
            :controls="false"
            placeholder="e.g. 2024"
            style="width: 200px"
          />
          <el-text class="ml-2" type="info" size="small">
            汇算 {{ computeForm.taxYear }}-01 至 {{ computeForm.taxYear }}-12 全年数据
          </el-text>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            plain
            :loading="previewLoading"
            @click="handlePreview"
          >
            预览汇算 (不写库)
          </el-button>
          <el-text class="ml-2" type="info" size="small">
            R1 防呆: 先预览确认数据无误, 再点"保存"写库
          </el-text>
        </el-form-item>
      </el-form>

      <!-- R1 preview 结果显示 -->
      <el-card v-if="previewData" shadow="never" class="preview-card">
        <template #header>
          <span class="preview-title">
            {{ computeForm.taxYear }} 年度汇算预览
            (覆盖 {{ previewData.monthsCovered }}/12 月)
          </span>
        </template>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="年度综合所得">
            {{ fmtMoney(previewData.totalSalary) }}
          </el-descriptions-item>
          <el-descriptions-item label="年度年终奖 (info)">
            {{ fmtMoney(previewData.totalBonus) }}
          </el-descriptions-item>
          <el-descriptions-item label="年度个人社保">
            -{{ fmtMoney(previewData.totalSocialInsurance) }}
          </el-descriptions-item>
          <el-descriptions-item label="年度个人公积金">
            -{{ fmtMoney(previewData.totalProvidentFund) }}
          </el-descriptions-item>
          <el-descriptions-item label="年度专项附加扣除">
            -{{ fmtMoney(previewData.totalSpecialDeductions) }}
          </el-descriptions-item>
          <el-descriptions-item label="起征点">
            -¥60000.00
          </el-descriptions-item>
          <el-descriptions-item label="年度应纳税所得额">
            <strong>{{ fmtMoney(previewData.annualTaxableIncome) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item label="适用档位">
            {{ previewData.bracketRate }} ({{ previewData.bracketLabel }})
          </el-descriptions-item>
          <el-descriptions-item label="年度应纳税额">
            <strong>{{ fmtMoney(previewData.annualTaxDue) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item label="月度已预缴">
            -{{ fmtMoney(previewData.monthlyPrepaidSum) }}
          </el-descriptions-item>
          <el-descriptions-item label="汇算结果" :span="2">
            <el-tag
              :type="refundOwedTagType(previewData.refundOwed)"
              effect="dark"
              size="large"
            >
              {{ formatRefundOwed(previewData.refundOwed) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="年终奖个税 (独立)" :span="2">
            {{ fmtMoney(previewData.annualBonusTax) }}
            <el-text type="info" size="small" class="ml-2">
              info-only, 一次性年终奖独立计税, 不并入综合所得汇算
            </el-text>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <template #footer>
        <el-button @click="computeDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="computeSubmitting"
          :disabled="!previewData"
          @click="handleSaveCompute"
        >
          保存汇算结果
        </el-button>
      </template>
    </el-dialog>

    <!-- ============= drilldown dialog (查看明细) ============= -->
    <el-dialog
      v-model="drilldownVisible"
      title="年度汇算明细"
      width="640px"
    >
      <el-descriptions v-if="drilldownRow" :column="2" border size="small">
        <el-descriptions-item label="税年">{{ drilldownRow.taxYear }}</el-descriptions-item>
        <el-descriptions-item label="员工 ID">{{ drilldownRow.userId }}</el-descriptions-item>
        <el-descriptions-item label="覆盖月数">
          {{ drilldownRow.monthsCovered }}/12
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="STATUS_TAG_TYPE[drilldownRow.status]">
            {{ STATUS_LABEL[drilldownRow.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="年度综合所得">
          {{ fmtMoney(drilldownRow.totalSalary) }}
        </el-descriptions-item>
        <el-descriptions-item label="年度年终奖">
          {{ fmtMoney(drilldownRow.totalBonus) }}
        </el-descriptions-item>
        <el-descriptions-item label="年度个人社保">
          {{ fmtMoney(drilldownRow.totalSocialInsurance) }}
        </el-descriptions-item>
        <el-descriptions-item label="年度个人公积金">
          {{ fmtMoney(drilldownRow.totalProvidentFund) }}
        </el-descriptions-item>
        <el-descriptions-item label="年度专项附加扣除">
          {{ fmtMoney(drilldownRow.totalSpecialDeductions) }}
        </el-descriptions-item>
        <el-descriptions-item label="起征点">¥60000.00</el-descriptions-item>
        <el-descriptions-item label="年度应纳税所得额">
          <strong>{{ fmtMoney(drilldownRow.annualTaxableIncome) }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="适用档位">
          {{ drilldownRow.bracketRate || '—' }}
          <span v-if="drilldownRow.bracketLabel"> ({{ drilldownRow.bracketLabel }})</span>
        </el-descriptions-item>
        <el-descriptions-item label="年度应纳税额">
          <strong>{{ fmtMoney(drilldownRow.annualTaxDue) }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="月度已预缴">
          {{ fmtMoney(drilldownRow.monthlyPrepaidSum) }}
        </el-descriptions-item>
        <el-descriptions-item label="汇算结果" :span="2">
          <el-tag :type="refundOwedTagType(drilldownRow.refundOwed)" effect="dark" size="large">
            {{ formatRefundOwed(drilldownRow.refundOwed) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="年终奖个税 (独立)" :span="2">
          {{ fmtMoney(drilldownRow.annualBonusTax) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="drilldownRow.computedAt" label="最后计算时间">
          {{ drilldownRow.computedAt }}
        </el-descriptions-item>
        <el-descriptions-item v-if="drilldownRow.confirmedAt" label="确认时间">
          {{ drilldownRow.confirmedAt }}
        </el-descriptions-item>
        <el-descriptions-item v-if="drilldownRow.reportedAt" label="申报时间" :span="2">
          {{ drilldownRow.reportedAt }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="drilldownVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.annual-settlement-page {
  padding: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-weight: 600;
  font-size: 16px;
}
.actions {
  display: flex;
  gap: 8px;
}
.filter-row {
  margin-bottom: 16px;
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.preview-card {
  margin-top: 12px;
  border-left: 3px solid var(--el-color-primary);
}
.preview-title {
  font-weight: 600;
}
.bracket-label {
  font-family: 'Menlo', 'Consolas', monospace;
  font-size: 12px;
}
.locked-hint {
  font-size: 12px;
  color: var(--el-color-info);
  margin-left: 8px;
}
.ml-2 {
  margin-left: 8px;
}
</style>
