<template>
  <div class="balance-sheet">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div class="title">
            <h3>资产负债表</h3>
            <span class="subtitle">截至 {{ year }}-{{ String(month).padStart(2, '0') }} 月底</span>
          </div>
          <div class="actions">
            <el-date-picker
              v-model="selectedMonth"
              type="month"
              format="YYYY-MM"
              value-format="YYYY-MM"
              placeholder="选择月份"
              :disabled-date="disabledFuture"
              style="margin-right: 12px;"
            />
            <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
            <el-button
              type="success"
              :loading="exporting"
              :disabled="!data"
              @click="doExport"
              style="margin-left: 8px;"
            >导出 Excel</el-button>
          </div>
        </div>
      </template>

      <!-- 防呆 R5: 期间未结账 WARN — 跳到 period 页 -->
      <el-alert
        v-if="data && data.periodStatus !== 'CLOSED'"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      >
        <template #title>
          数据未结账 ({{ statusLabel(data.periodStatus) }}) — 报表数字可能不准
        </template>
        <template #default>
          建议先完成期间结账以确保数据稳定:
          <el-link type="primary" underline="hover" @click="goToPeriodClose">前往期间结账</el-link>
        </template>
      </el-alert>

      <!-- F006 财务审计 Bug 6 (2026-07-04): 待过账提示 — 官方报表已切 POSTED-only, 让财务人员
           知道"为什么数字看着少" (fool-proof-design Rule 5: 空/低数据必须给原因). -->
      <el-alert
        v-if="data && (data.pendingDraftVoucherCount ?? 0) > 0"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      >
        <template #title>
          本报表仅含已过账 (POSTED) 凭证; 另有 {{ data.pendingDraftVoucherCount }} 张凭证待过账 (未计入本报表)
        </template>
      </el-alert>

      <!-- 平衡校验 -->
      <el-alert
        v-if="data && data.balanceCheck === false"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      >
        <template #title>
          平衡校验失败: 资产合计 ¥{{ fmt(data.totalAssets) }} ≠ 负债+所有者权益 ¥{{ fmt(data.totalLiabilitiesAndEquity) }}
        </template>
        <template #default>
          可能原因: 凭证未平 / 科目分类错误 / 历史数据缺失. 请检查 voucher 完整性.
        </template>
      </el-alert>

      <el-skeleton v-if="loading" :rows="10" animated />
      <div v-else-if="!data" class="empty">
        <el-empty description="暂无数据 — 请选择月份后点击刷新" />
      </div>
      <div v-else class="balance-sheet-body">
        <el-row :gutter="20">
          <!-- 资产部分 (左) -->
          <el-col :span="12">
            <el-table :data="data.assets" stripe size="small" :show-header="true" border>
              <el-table-column label="科目编码" prop="accountCode" width="100" />
              <el-table-column label="资产" prop="accountName" />
              <el-table-column label="金额 (元)" prop="amount" align="right" width="160">
                <template #default="{ row }">{{ fmt(row.amount) }}</template>
              </el-table-column>
            </el-table>
            <div class="total-row total-assets">
              <span>资产合计</span>
              <span class="amount">¥ {{ fmt(data.totalAssets) }}</span>
            </div>
          </el-col>

          <!-- 负债 + 所有者权益 部分 (右) -->
          <el-col :span="12">
            <el-table :data="data.liabilities" stripe size="small" :show-header="true" border>
              <el-table-column label="科目编码" prop="accountCode" width="100" />
              <el-table-column label="负债" prop="accountName" />
              <el-table-column label="金额 (元)" prop="amount" align="right" width="160">
                <template #default="{ row }">{{ fmt(row.amount) }}</template>
              </el-table-column>
            </el-table>
            <div class="total-row subtotal">
              <span>负债合计</span>
              <span class="amount">¥ {{ fmt(data.totalLiabilities) }}</span>
            </div>

            <el-table :data="data.equity" stripe size="small" :show-header="true" border style="margin-top: 12px;">
              <el-table-column label="科目编码" prop="accountCode" width="100" />
              <el-table-column label="所有者权益" prop="accountName" />
              <el-table-column label="金额 (元)" prop="amount" align="right" width="160">
                <template #default="{ row }">{{ fmt(row.amount) }}</template>
              </el-table-column>
            </el-table>
            <div class="total-row subtotal">
              <span>所有者权益合计</span>
              <span class="amount">¥ {{ fmt(data.totalEquity) }}</span>
            </div>

            <div class="total-row total-rhs">
              <span>负债 + 所有者权益 合计</span>
              <span class="amount">¥ {{ fmt(data.totalLiabilitiesAndEquity) }}</span>
            </div>
          </el-col>
        </el-row>

        <!-- 平衡 OK 提示 -->
        <el-alert
          v-if="data.balanceCheck"
          type="success"
          :title="'平衡校验通过: 资产 = 负债 + 所有者权益'"
          show-icon
          :closable="false"
          style="margin-top: 20px;"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { getBalanceSheet, exportBalanceSheet, type BalanceSheetDTO } from '@/api/financeReport';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const today = new Date();
const selectedMonth = ref<string>(
  `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
);
const data = ref<BalanceSheetDTO | null>(null);
const loading = ref(false);
const exporting = ref(false);

const year = computed(() => parseInt(selectedMonth.value.split('-')[0], 10));
const month = computed(() => parseInt(selectedMonth.value.split('-')[1], 10));

function disabledFuture(d: Date) {
  return d.getTime() > today.getTime();
}

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function statusLabel(s: string): string {
  if (s === 'OPEN') return '开账中';
  if (s === 'PENDING_CLOSE') return '审批中';
  return s;
}

function goToPeriodClose() {
  router.push({
    name: 'FinanceAccountingPeriod',
    query: { year: String(year.value), month: String(month.value) },
  });
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const resp = await getBalanceSheet(factoryId.value, year.value, month.value);
    if (resp.success && resp.data) {
      data.value = resp.data;
    } else {
      ElMessage({
        message: resp.message || '加载资产负债表失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

async function doExport() {
  if (!factoryId.value) return;
  exporting.value = true;
  try {
    await exportBalanceSheet(factoryId.value, year.value, month.value);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: '导出失败: ' + msg, type: 'error', duration: 0, showClose: true });
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  load();
});
</script>

<style scoped>
.balance-sheet {
  padding: 16px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title h3 {
  margin: 0;
  font-size: 18px;
}

.subtitle {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-left: 8px;
}

.total-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 16px;
  margin-top: 4px;
  font-weight: 600;
  font-size: 14px;
  border-radius: 4px;
}

.subtotal {
  background-color: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
}

.total-assets,
.total-rhs {
  background-color: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 15px;
  border: 1px solid var(--el-color-primary-light-7);
}

.amount {
  font-family: 'Courier New', monospace;
  font-weight: 700;
}

.empty {
  padding: 40px 0;
  text-align: center;
}
</style>
