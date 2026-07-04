<template>
  <div class="cash-flow">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div class="title">
            <h3>现金流量表</h3>
            <span class="subtitle">
              期间累计 (直接法): {{ startYear }}-{{ String(startMonth).padStart(2, '0') }} 至
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

      <el-alert
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      >
        <template #title>
          直接法 — 基于现金账户 (1001/1002/1012) 与对手科目自动分类
        </template>
        <template #default>
          复杂业务 (e.g. 应收账款回款时间差) 可能需手工调整. 投资 / 筹资分类按科目 code 前缀自动判定.
        </template>
      </el-alert>

      <!-- F006 财务审计 Bug 6 follow-up (2026-07-04): 待过账提示 — 现金流量表已切 POSTED-only,
           让财务人员知道"为什么数字看着少" (fool-proof-design Rule 5: 空/低数据必须给原因). -->
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

      <el-skeleton v-if="loading" :rows="10" animated />
      <div v-else-if="!data" class="empty">
        <el-empty description="暂无数据" />
      </div>
      <div v-else>
        <!-- 3 张总览卡片 -->
        <el-row :gutter="16" class="kpi-row">
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">经营活动现金流</div>
              <div class="kpi-value" :class="cls(data.operatingNetCashFlow)">
                ¥ {{ fmt(data.operatingNetCashFlow) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">投资活动现金流</div>
              <div class="kpi-value" :class="cls(data.investingNetCashFlow)">
                ¥ {{ fmt(data.investingNetCashFlow) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">筹资活动现金流</div>
              <div class="kpi-value" :class="cls(data.financingNetCashFlow)">
                ¥ {{ fmt(data.financingNetCashFlow) }}
              </div>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card class="kpi-card">
              <div class="kpi-label">现金净增加额</div>
              <div class="kpi-value" :class="cls(data.netIncreaseInCash)">
                ¥ {{ fmt(data.netIncreaseInCash) }}
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 三大活动明细 -->
        <el-collapse v-model="activeSection">
          <el-collapse-item title="一、经营活动现金流明细" name="operating">
            <ActivityTable :activities="data.operatingActivities" :net-total="data.operatingNetCashFlow" />
          </el-collapse-item>
          <el-collapse-item title="二、投资活动现金流明细" name="investing">
            <ActivityTable :activities="data.investingActivities" :net-total="data.investingNetCashFlow" />
          </el-collapse-item>
          <el-collapse-item title="三、筹资活动现金流明细" name="financing">
            <ActivityTable :activities="data.financingActivities" :net-total="data.financingNetCashFlow" />
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
import { getCashFlow, exportCashFlow, type CashFlowDTO, type CashFlowActivity } from '@/api/financeReport';

const ActivityTable = defineComponent({
  name: 'CashFlowActivityTable',
  props: {
    activities: { type: Array as () => CashFlowActivity[], required: true },
    netTotal: { type: Number, required: true },
  },
  setup(props) {
    return () => {
      if (props.activities.length === 0) {
        return h('div', { class: 'empty-row' }, '无相关活动');
      }
      return h('div', [
        h(ElTable, { data: props.activities, size: 'small', border: true, stripe: true }, () => [
          h(ElTableColumn, { label: '科目编码', prop: 'counterpartCode', width: 100 }),
          h(ElTableColumn, { label: '对手科目', prop: 'counterpartName' }),
          h(ElTableColumn, {
            label: '流入',
            prop: 'inflow',
            align: 'right',
            width: 120,
          }, {
            default: ({ row }: { row: CashFlowActivity }) =>
              h('span', { class: 'inflow' }, (row.inflow ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })),
          }),
          h(ElTableColumn, {
            label: '流出',
            prop: 'outflow',
            align: 'right',
            width: 120,
          }, {
            default: ({ row }: { row: CashFlowActivity }) =>
              h('span', { class: 'outflow' }, (row.outflow ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })),
          }),
          h(ElTableColumn, {
            label: '净额',
            prop: 'netAmount',
            align: 'right',
            width: 140,
          }, {
            default: ({ row }: { row: CashFlowActivity }) =>
              h('span', { class: row.netAmount >= 0 ? 'positive' : 'negative' },
                '¥ ' + (row.netAmount ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })),
          }),
        ]),
        h('div', { class: 'subtotal-row' }, [
          h('span', '小计'),
          h('span', { class: props.netTotal >= 0 ? 'positive' : 'negative' },
            '¥ ' + props.netTotal.toLocaleString('zh-CN', { minimumFractionDigits: 2 })),
        ]),
      ]);
    };
  },
});

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const today = new Date();
const defaultStart = `${today.getFullYear()}-01`;
const defaultEnd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

const dateRange = ref<[string, string]>([defaultStart, defaultEnd]);
const data = ref<CashFlowDTO | null>(null);
const loading = ref(false);
const exporting = ref(false);
const activeSection = ref<string[]>(['operating']);

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

function cls(v: number | null | undefined): string {
  if (v == null) return '';
  return v >= 0 ? 'positive' : 'negative';
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const resp = await getCashFlow(
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
        message: resp.message || '加载现金流量表失败',
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
    await exportCashFlow(
      factoryId.value,
      startYear.value,
      startMonth.value,
      endYear.value,
      endMonth.value,
    );
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
.cash-flow {
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
  margin-bottom: 16px;
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

.kpi-value.positive {
  color: #67c23a;
}

.kpi-value.negative {
  color: #f56c6c;
}

:deep(.subtotal-row) {
  display: flex;
  justify-content: space-between;
  padding: 10px 16px;
  font-weight: 700;
  background-color: var(--el-fill-color-light);
  border-top: 1px solid var(--el-border-color);
  margin-top: -1px;
}

:deep(.inflow) {
  color: #67c23a;
  font-family: 'Courier New', monospace;
}

:deep(.outflow) {
  color: #f56c6c;
  font-family: 'Courier New', monospace;
}

:deep(.positive) {
  color: #67c23a;
  font-family: 'Courier New', monospace;
  font-weight: 600;
}

:deep(.negative) {
  color: #f56c6c;
  font-family: 'Courier New', monospace;
  font-weight: 600;
}

:deep(.empty-row) {
  padding: 20px;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.empty {
  padding: 40px 0;
}
</style>
