<template>
  <div class="income-statement">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div class="title">
            <h3>利润表</h3>
            <span class="subtitle">
              期间累计: {{ startYear }}-{{ String(startMonth).padStart(2, '0') }} 至
              {{ endYear }}-{{ String(endMonth).padStart(2, '0') }}
            </span>
          </div>
          <div class="actions">
            <el-date-picker
              v-model="dateRange"
              type="monthrange"
              format="YYYY-MM"
              value-format="YYYY-MM"
              range-separator="至"
              start-placeholder="起始月"
              end-placeholder="结束月"
              :disabled-date="disabledFuture"
              style="margin-right: 12px;"
            />
            <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loading" :rows="10" animated />
      <div v-else-if="!data" class="empty">
        <el-empty description="暂无数据 — 请选择期间后点击刷新" />
      </div>
      <div v-else class="statement-body">
        <!-- F006 财务审计 Bug 6 (2026-07-04): 待过账提示 — 官方报表已切 POSTED-only. -->
        <el-alert
          v-if="(data.pendingDraftVoucherCount ?? 0) > 0"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 16px;"
        >
          <template #title>
            本报表仅含已过账 (POSTED) 凭证; 另有 {{ data.pendingDraftVoucherCount }} 张凭证待过账 (未计入本报表)
          </template>
        </el-alert>

        <!-- 4 张关键 KPI 卡片 -->
        <el-row :gutter="16" class="kpi-row">
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">营业收入</div>
              <div class="kpi-value revenue">¥ {{ fmt(data.totalRevenue) }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">营业毛利</div>
              <div class="kpi-value gross">¥ {{ fmt(data.grossProfit) }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">营业利润</div>
              <div class="kpi-value operating">¥ {{ fmt(data.operatingProfit) }}</div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">净利润</div>
              <div class="kpi-value net" :class="netProfitClass">¥ {{ fmt(data.netProfit) }}</div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 利润表明细 -->
        <el-table :data="rows" border stripe size="default" class="statement-table">
          <el-table-column label="项目" prop="label">
            <template #default="{ row }">
              <span :class="{ 'sub-item': row.indent, 'total-item': row.isTotal }">
                {{ row.label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="金额 (元)" prop="value" align="right" width="200">
            <template #default="{ row }">
              <span :class="{ 'total-item': row.isTotal }">
                ¥ {{ fmt(row.value) }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 明细展开 -->
        <el-collapse style="margin-top: 16px;">
          <el-collapse-item title="营业收入明细" name="revenues">
            <DetailTable :items="data.revenues" />
          </el-collapse-item>
          <el-collapse-item title="营业成本明细" name="costs">
            <DetailTable :items="data.costs" />
          </el-collapse-item>
          <el-collapse-item title="营业费用明细" name="expenses">
            <DetailTable :items="data.expenses" />
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, defineComponent, h } from 'vue';
import { ElMessage, ElTable, ElTableColumn } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { getIncomeStatement, type IncomeStatementDTO, type LineItem } from '@/api/financeReport';

// Inline tiny detail-table component
const DetailTable = defineComponent({
  name: 'IncomeStatementDetailTable',
  props: { items: { type: Array as () => LineItem[], required: true } },
  setup(props) {
    return () => h(ElTable, { data: props.items, size: 'small', border: true, stripe: true }, () => [
      h(ElTableColumn, { label: '科目编码', prop: 'accountCode', width: 100 }),
      h(ElTableColumn, { label: '科目名称', prop: 'accountName' }),
      h(ElTableColumn, {
        label: '金额 (元)',
        prop: 'amount',
        align: 'right',
        width: 160,
      }, {
        default: ({ row }: { row: LineItem }) =>
          h('span', (row.amount ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })),
      }),
    ]);
  },
});

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const today = new Date();
const defaultStart = `${today.getFullYear()}-01`;
const defaultEnd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

const dateRange = ref<[string, string]>([defaultStart, defaultEnd]);
const data = ref<IncomeStatementDTO | null>(null);
const loading = ref(false);

const startYear = computed(() => parseInt(dateRange.value[0].split('-')[0], 10));
const startMonth = computed(() => parseInt(dateRange.value[0].split('-')[1], 10));
const endYear = computed(() => parseInt(dateRange.value[1].split('-')[0], 10));
const endMonth = computed(() => parseInt(dateRange.value[1].split('-')[1], 10));

function disabledFuture(d: Date) {
  return d.getTime() > today.getTime();
}

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

interface Row {
  label: string;
  value: number;
  indent?: boolean;
  isTotal?: boolean;
}

const rows = computed<Row[]>(() => {
  if (!data.value) return [];
  const d = data.value;
  return [
    { label: '一、营业收入', value: d.totalRevenue, isTotal: false },
    { label: '减: 营业成本', value: d.totalCost, indent: true },
    { label: '二、营业毛利 (= 营业收入 - 营业成本)', value: d.grossProfit, isTotal: true },
    { label: '减: 营业费用 / 管理费用 / 财务费用', value: d.totalExpense, indent: true },
    { label: '三、营业利润 (= 营业毛利 - 营业费用)', value: d.operatingProfit, isTotal: true },
    { label: '减: 所得税', value: d.incomeTax, indent: true },
    { label: '四、净利润 (= 营业利润 - 所得税)', value: d.netProfit, isTotal: true },
  ];
});

const netProfitClass = computed(() => {
  if (!data.value) return '';
  return data.value.netProfit < 0 ? 'negative' : '';
});

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const resp = await getIncomeStatement(
      factoryId.value,
      startYear.value,
      startMonth.value,
      endYear.value,
      endMonth.value,
    );
    if (resp.success && resp.data) {
      data.value = resp.data;
    } else {
      ElMessage({
        message: resp.message || '加载利润表失败',
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

onMounted(() => {
  load();
});
</script>

<style scoped>
.income-statement {
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

.kpi-row {
  margin-bottom: 20px;
}

.kpi-card {
  text-align: center;
}

.kpi-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}

.kpi-value {
  font-size: 22px;
  font-weight: 700;
  font-family: 'Courier New', monospace;
}

.kpi-value.revenue {
  color: #409eff;
}

.kpi-value.gross {
  color: #67c23a;
}

.kpi-value.operating {
  color: #e6a23c;
}

.kpi-value.net {
  color: #67c23a;
}

.kpi-value.net.negative {
  color: #f56c6c;
}

.statement-table .sub-item {
  padding-left: 24px;
  color: var(--el-text-color-regular);
}

.statement-table .total-item {
  font-weight: 700;
  color: var(--el-color-primary);
}

.empty {
  padding: 40px 0;
}
</style>
