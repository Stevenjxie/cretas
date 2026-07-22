<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import request, { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Download, Edit, InfoFilled } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import { formatAmount } from '@/utils/tableFormatters';
import NotFoundEmpty from '@/components/common/NotFoundEmpty.vue';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import type { TableRow } from '@/types/api';
import { enumLabel } from '@/utils/enumDisplay';
import { canonicalUnitCode, formatPriceUnit, pricingAmountPreview } from '@/utils/unitPricing';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const orderId = computed(() => route.params.id as string);

const loading = ref(false);
const submitting = ref(false);
const order = ref<TableRow | null>(null);
const notFound = ref(false);
const notFoundMessage = ref('');
const receives = ref<TableRow[]>([]);
const attachmentRefreshKey = ref(0);

interface ApprovalProgress {
  hasInstance: boolean;
  instanceId?: string;
  status?: string;
  currentNodeNames?: string[];
  approverRoles?: string[];
  assignees?: string[];
  initiatedAt?: string;
  completedAt?: string;
  stayMinutes?: number;
  deepLink?: string;
  message?: string;
}
const approvalProgress = ref<ApprovalProgress>({ hasInstance: false });

// 三价对比
interface PriceComparison {
  materialTypeId: string;
  materialName: string;
  materialCode: string;
  unit: string;
  bomStandardPrice: number | null;
  movingAvgPrice: number | null;
  currentPrice: number | null;
  varianceFromBom: number | null;
  varianceFromAvg: number | null;
  priceAlert: boolean;
  bomProductNames: string;
  // Day 8-9 (三价对比 bug): 数据源缺失诊断 — 解释为何某价 null,
  // 帮 F006 仓管区分"数据 bug"和"业务正常空态"
  dataSourceHint: string | null;
}
const priceComparisons = ref<PriceComparison[]>([]);
const priceLoading = ref(false);
// Day 8-9 三价对比 bug 修复: 移除 priceLoaded 短路.
// 旧逻辑: 一次加载后 priceLoaded=true, 用户再展开 collapse 不重拉, 导致新建采购单
// → 入库 → 回此页, 用户看到旧三价对比 (空 / stale). 新逻辑: 每次展开都重拉,
// 保证用户看到的就是当前后端真实状态. 无明显性能影响 (查询走 BOM + raw_material
// 两表, 已有索引).

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  SUBMITTED: { text: '已提交', type: 'warning' },
  WORKFLOW_RUNNING: { text: '审批中', type: 'warning' },
  APPROVED: { text: '已审批', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待财务审核', type: 'warning' },
  FINANCE_APPROVED: { text: '财务已审核', type: 'success' },
  FINANCE_REJECTED: { text: '财务驳回', type: 'danger' },
  PARTIAL_RECEIVED: { text: '部分收货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
};

const receiveStatusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  PENDING_QC: { text: '待质检', type: 'warning' },
  CONFIRMED: { text: '已确认', type: 'success' },
  REJECTED: { text: '已拒绝', type: 'danger' },
};

function purchaseLineAmount(row: TableRow) {
  return pricingAmountPreview({
    quantity: row.quantity as number,
    unit: String(row.unit || ''),
    quantityUnit: String(row.quantityUnit || row.unit || ''),
    unitPrice: row.unitPrice as number,
    priceUnit: String(row.priceUnit || row.unit || ''),
    lineAmount: row.lineAmount as number,
    convertedPricingQuantity: row.convertedPricingQuantity as number,
  });
}

function goEditDraft() {
  router.push({ path: '/procurement/orders', query: { edit: orderId.value } });
}

onMounted(() => { loadOrder(); loadReceives(); loadApprovalProgress(); });

async function loadOrder() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}`);
    if (res.success) order.value = res.data;
  } catch (err: any) {
    const status = err?.status ?? err?.response?.status;
    const code = err?.code ?? err?.response?.data?.code;
    if (status === 404 || status === 403 || code === 'NOT_FOUND' || code === 'FORBIDDEN') {
      notFound.value = true;
      notFoundMessage.value = err?.message || err?.response?.data?.message || '记录不存在';
    }
    // axios interceptor shows toast already (Bug #319 fix), component doesn't add fallback
  }
  finally { loading.value = false; }
}

async function loadReceives() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/warehouse/receiving/receipts/by-order/${orderId.value}`);
    if (res.success) receives.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore */ }
}

function goFinanceDetails(): void {
  router.push({
    path: '/finance/ar-ap',
    query: {
      direction: 'AP',
      purchaseOrderId: orderId.value,
    },
  });
}

async function loadApprovalProgress() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}/approval-progress`);
    if (res.success) approvalProgress.value = res.data || { hasInstance: false };
  } catch {
    approvalProgress.value = { hasInstance: false, message: '审批进度暂不可用' };
  }
}

function goApprovalProgress(): void {
  router.push({
    path: '/workflow/my-created',
    query: approvalProgress.value.instanceId
      ? { instanceId: approvalProgress.value.instanceId }
      : { businessEntityType: 'PURCHASE_ORDER', businessEntityId: orderId.value },
  });
}

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    submit: { label: '提交', url: `/${factoryId.value}/purchase/orders/${orderId.value}/submit` },
    cancel: { label: '取消', url: `/${factoryId.value}/purchase/orders/${orderId.value}/cancel` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(`确认${a.label}？`, '操作确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) {
      ElMessage.success(`${a.label}成功`);
      await Promise.all([loadOrder(), loadApprovalProgress()]);
    }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

function goWarehouseReceive(): void {
  router.push({
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      purchaseOrderId: orderId.value,
      orderNo: String(order.value?.orderNumber || ''),
    },
  });
}

async function loadPriceComparison() {
  // Day 8-9 三价对比 bug fix: 不再用 priceLoaded 短路, 每次展开都重拉.
  // 防御 priceLoading reentrancy 即可.
  if (priceLoading.value || !factoryId.value || !orderId.value) return;
  priceLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}/price-comparison`);
    if (res.success) {
      priceComparisons.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { priceLoading.value = false; }
}

// Day 8-9 三价对比 bug fix: 计算"是否多数行缺失数据源" — 用于显示 banner 解释,
// 区分"业务正常 (新原料/未入库)"和"系统 bug".
const priceDataSourceHintBanner = computed(() => {
  if (priceComparisons.value.length === 0) return null;
  const missing = priceComparisons.value.filter(p => p.dataSourceHint != null).length;
  if (missing === 0) return null;
  // 至少 1 行有 hint → 显示通用解释 banner
  return `三价对比基于"BOM 标准价 + 历次入库的移动均价". 新原料 / 尚未入库 / 未配 BOM 时部分对比项为空 — 这是业务正常状态, 不是数据 bug. ${missing}/${priceComparisons.value.length} 行有诊断信息, 鼠标悬停查看.`;
});

function formatVariance(val: number | null): string {
  if (val == null) return '-';
  const sign = val > 0 ? '+' : '';
  return `${sign}${val.toFixed(1)}%`;
}

function varianceClass(val: number | null): string {
  if (val == null) return '';
  return val > 0 ? 'variance-up' : val < 0 ? 'variance-down' : '';
}

function formatTaxRate(rate: unknown): string {
  if (rate == null || rate === '') return '-';
  const numeric = Number(rate);
  if (!Number.isFinite(numeric)) return '-';
  const percent = numeric <= 1 ? numeric * 100 : numeric;
  return `${percent.toFixed(2).replace(/\.?0+$/, '')}%`;
}

function priceRowClassName({ row }: { row: PriceComparison }): string {
  return row.priceAlert ? 'price-alert-row' : '';
}

// P0/N3 (六扇门): 下载 PDF — 对外供货单必须完全移除价格列,
// 内部审批版才允许有价格列且继续受 procurement:price:view 保护。
const pdfDownloading = ref(false);
async function handleDownloadPdf(externalVersion: boolean) {
  if (!factoryId.value || !orderId.value || pdfDownloading.value) return;
  pdfDownloading.value = true;
  try {
    const response = await request.get(
      `/${factoryId.value}/purchase/orders/${orderId.value}/pdf`,
      {
        params: { external: externalVersion },
        responseType: 'blob',
      }
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const orderNum = order.value?.orderNumber || orderId.value;
    link.download = externalVersion ? `供货单_对外_${orderNum}.pdf` : `采购订单_内部_${orderNum}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success(externalVersion ? '对外供货单下载成功' : '内部审批版下载成功');
  } catch (e) {
    // axios interceptor 已经 toast — 但 blob 响应 error 跳过 interceptor json parse, 兜底
    console.error('[PDF 下载失败]', e);
    ElMessage({
      type: 'error',
      message: 'PDF 下载失败,请稍后重试',
      duration: 0,
      showClose: true,
    });
  } finally {
    pdfDownloading.value = false;
  }
}

</script>

<template>
  <NotFoundEmpty v-if="notFound"
    :description="notFoundMessage"
    return-path="/procurement/orders" />
  <div v-else class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button :icon="ArrowLeft" @click="router.push('/procurement/orders')">返回</el-button>
            <span class="page-title">{{ label('purchaseOrder') }}详情</span>
            <el-tag v-if="order" :type="(statusMap[order.status]?.type) || 'info'" size="large">
              {{ statusMap[order.status]?.text || enumLabel(order.status) }}
            </el-tag>
          </div>
          <div class="header-right" v-if="order">
            <!-- N3: 对外供货单给供应商, 后端 external=true 会完全移除价格列. -->
            <el-button type="primary" :icon="Download" :loading="pdfDownloading" @click="handleDownloadPdf(true)">对外供货单</el-button>
            <el-button v-if="canViewPrice" :icon="Download" :loading="pdfDownloading" @click="handleDownloadPdf(false)">内部审批版</el-button>
            <template v-if="canWrite">
            <el-button v-if="order.status === 'DRAFT'" :icon="Edit" @click="goEditDraft">编辑草稿</el-button>
            <el-button v-if="order.status === 'DRAFT'" type="warning" :loading="submitting" @click="handleAction('submit')">提交审批</el-button>
            <el-button v-if="['DRAFT','SUBMITTED'].includes(order.status)" type="danger" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
            </template>
            <el-button v-if="order.status !== 'DRAFT'" plain @click="goApprovalProgress">查看审批进度 / 前往 OA</el-button>
          </div>
        </div>
      </template>

      <template v-if="order">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="订单编号">{{ order.orderNumber }}</el-descriptions-item>
          <el-descriptions-item :label="label('supplier')">{{ order.supplierName || order.supplier?.name || order.supplierId }}</el-descriptions-item>
          <el-descriptions-item label="采购类型">{{ order.purchaseType === 'DIRECT' ? '直接采购' : order.purchaseType === 'URGENT' ? '紧急采购' : '总部统采' }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ order.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="期望交货">{{ order.expectedDeliveryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="总金额">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="税额">{{ formatAmount(order.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item label="OA 状态">{{ approvalProgress.hasInstance ? enumLabel(approvalProgress.status) : (approvalProgress.message || '尚未发起') }}</el-descriptions-item>
          <el-descriptions-item label="当前审批节点">{{ approvalProgress.currentNodeNames?.join('、') || (approvalProgress.status === 'APPROVED' ? '审批完成' : '-') }}</el-descriptions-item>
          <el-descriptions-item label="待处理角色/人员">
            {{ approvalProgress.assignees?.join('、') || approvalProgress.approverRoles?.map(role => enumLabel(role)).join('、') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ approvalProgress.initiatedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="节点停留">{{ approvalProgress.hasInstance && approvalProgress.status === 'RUNNING' ? `${approvalProgress.stayMinutes || 0} 分钟` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="合同号">{{ order.contractNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="结算方式">
            <span v-if="order.settlementType">{{ ({ PREPAID: '预付', CREDIT_FIRST: '赊销先入库', NO_INVOICE: '未到票', MONTHLY: '月结', CREDIT_PERIOD: '账期', IMMEDIATE: '现结' } as Record<string, string>)[order.settlementType] || enumLabel(order.settlementType) }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="开票提醒">
            <span v-if="order.invoiceReminderDays != null">{{ order.invoiceReminderDays === 0 ? '不提醒' : `${order.invoiceReminderDays} 天` }}</span>
            <span v-else>使用工厂默认</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-heading finance-heading">
          <h3>采购履约与财务状态（只读）</h3>
          <el-button plain @click="goFinanceDetails">查看财务详情</el-button>
        </div>
        <el-descriptions :column="4" border>
          <el-descriptions-item label="采购状态">{{ statusMap[order.status]?.text || enumLabel(order.status) }}</el-descriptions-item>
          <el-descriptions-item label="收货状态">{{ order.receiptStatus ? enumLabel(order.receiptStatus) : (receives.length ? '已有收货记录' : '尚无收货记录') }}</el-descriptions-item>
          <el-descriptions-item label="发票状态">{{ order.invoiceStatus ? enumLabel(order.invoiceStatus) : '未归集' }}</el-descriptions-item>
          <el-descriptions-item label="付款状态">{{ order.paymentStatus ? enumLabel(order.paymentStatus) : '未归集' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="应付金额">{{ order.payableAmount == null ? '-' : formatAmount(order.payableAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已付金额">{{ order.paidAmount == null ? '-' : formatAmount(order.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="未付金额">{{ order.unpaidAmount == null ? '-' : formatAmount(order.unpaidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="付款到期日">{{ order.paymentDueDate || order.dueDate || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">{{ label('rawMaterial') }}明细</h3>
        <!-- T3-6 fix (issue #523): widen 规格 + 原料名称 columns + scroll-x fallback to prevent 字段挤压 / tooltip overlay 盖住邻列. -->
        <el-table :data="order.items || []" border stripe style="width: 100%" :scrollbar-always-on="true">
          <el-table-column prop="materialName" :label="label('rawMaterial')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="specification" label="规格" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.specification || '-' }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="采购数量" width="110" align="right" />
          <el-table-column prop="boxQuantity" label="箱数" width="80" align="right">
            <template #default="{ row }">{{ row.boxQuantity || '-' }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="80" align="center" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="120" align="right">
            <template #default="{ row }">
              {{ Number(row.unitPrice || 0).toFixed(4) }} {{ formatPriceUnit(canonicalUnitCode(row.priceUnit || row.unit)) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="taxRate" label="税率" width="90" align="right">
            <template #default="{ row }">{{ formatTaxRate(row.taxRate) }}</template>
          </el-table-column>
          <el-table-column label="已收货" width="120" align="right">
            <template #default="{ row }">{{ row.receivedQuantity || 0 }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="130" align="right">
            <template #default="{ row }">
              <span v-if="purchaseLineAmount(row).amount != null">{{ formatAmount(purchaseLineAmount(row).amount) }}</span>
              <span v-else class="text-secondary">{{ purchaseLineAmount(row).message }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-collapse v-if="canViewPrice" style="margin: 20px 0 12px" @change="(val: string[]) => { if (val.includes('price')) loadPriceComparison(); }">
          <el-collapse-item title="三价对比分析" name="price">
            <div v-loading="priceLoading">
              <!-- Day 8-9 三价对比 bug fix: 数据源缺失时显示 banner 解释, 区分业务空态 vs bug -->
              <el-alert
                v-if="priceDataSourceHintBanner"
                type="info"
                :closable="false"
                show-icon
                style="margin-bottom: 12px"
                :title="priceDataSourceHintBanner"
              />
              <el-alert v-if="priceComparisons.some(p => p.priceAlert)" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
                存在价格偏差超过10%的原料，请关注标红行
              </el-alert>
              <el-table :data="priceComparisons" border stripe :row-class-name="priceRowClassName" size="small" v-if="priceComparisons.length">
                <el-table-column prop="materialName" label="原料名称" min-width="140" />
                <el-table-column label="BOM标准价" width="120" align="right">
                  <template #default="{ row }">{{ row.bomStandardPrice != null ? formatAmount(row.bomStandardPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="移动均价" width="120" align="right">
                  <template #default="{ row }">{{ row.movingAvgPrice != null ? formatAmount(row.movingAvgPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="当前采购价" width="120" align="right">
                  <template #default="{ row }">{{ row.currentPrice != null ? formatAmount(row.currentPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="BOM偏差" width="100" align="center">
                  <template #default="{ row }">
                    <span :class="varianceClass(row.varianceFromBom)">{{ formatVariance(row.varianceFromBom) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="均价偏差" width="100" align="center">
                  <template #default="{ row }">
                    <span :class="varianceClass(row.varianceFromAvg)">{{ formatVariance(row.varianceFromAvg) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="预警" width="70" align="center">
                  <template #default="{ row }">
                    <el-tag v-if="row.priceAlert" type="danger" size="small">异常</el-tag>
                    <el-tag v-else type="success" size="small">正常</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="bomProductNames" label="关联产品" min-width="120" show-overflow-tooltip />
                <!-- Day 8-9 三价对比 bug fix: 数据源诊断列 (仅有 hint 行显示 ⓘ icon + tooltip) -->
                <el-table-column label="说明" width="60" align="center">
                  <template #default="{ row }">
                    <el-tooltip v-if="row.dataSourceHint" :content="row.dataSourceHint" placement="left">
                      <el-icon style="color: #409eff; cursor: help"><InfoFilled /></el-icon>
                    </el-tooltip>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
              </el-table>
              <!-- Issue #781: priceLoaded 短路已 Day 8-9 移除 (line 71-75 comment).
                   旧 template 仍引用 priceLoaded 是死引用 (undefined), 始终为 falsy
                   → empty 永不显示. 修法: 直接用 !priceLoading + length===0 判定. -->
              <el-empty v-else-if="!priceLoading && priceComparisons.length === 0" description="暂无三价对比数据" :image-size="60" />
            </div>
          </el-collapse-item>
        </el-collapse>

        <div class="section-heading">
          <h3>{{ label('receive') }}记录（只读）</h3>
          <el-button type="primary" plain @click="goWarehouseReceive">前往仓储收货任务</el-button>
        </div>
        <el-table :data="receives" border stripe>
          <el-table-column prop="receiveNumber" label="收货单号" width="170" />
          <el-table-column prop="receiveDate" label="收货日期" width="120" />
          <el-table-column prop="status" label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="(receiveStatusMap[row.status]?.type) || 'info'" size="small">
                {{ receiveStatusMap[row.status]?.text || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="130" align="right">
            <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column prop="warehouseName" label="收货仓库" min-width="120"><template #default="{ row }">{{ row.warehouseName || '-' }}</template></el-table-column>
          <el-table-column prop="receivedByName" label="收货人" min-width="110"><template #default="{ row }">{{ row.receivedByName || row.receivedBy || '-' }}</template></el-table-column>
          <el-table-column prop="confirmedAt" label="确认时间" min-width="160"><template #default="{ row }">{{ row.confirmedAt || '-' }}</template></el-table-column>
        </el-table>

        <!-- 附件 (C-ATT-1) — 收货单 / 合同 / 现场照片 -->
        <h3 style="margin: 20px 0 12px">附件</h3>
        <AttachmentList
          entity-type="PURCHASE_ORDER"
          :entity-id="String(orderId)"
          :factory-id="factoryId"
          :refresh-key="attachmentRefreshKey"
        />
        <div style="margin-top: 12px">
          <AttachmentDropZone
            entity-type="PURCHASE_ORDER"
            :entity-id="String(orderId)"
            :factory-id="factoryId"
            business-tag="PURCHASE_DOC"
            @uploaded="attachmentRefreshKey++"
          />
        </div>
      </template>
    </el-card>

  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; }
}
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 20px 0 12px; }
.section-heading h3 { margin: 0; }
.finance-heading { margin-top: 20px; }
// 三价对比样式
:deep(.price-alert-row) {
  background-color: #fef0f0 !important;
  td { background-color: #fef0f0 !important; }
}
.variance-up { color: #f56c6c; font-weight: 600; }
.variance-down { color: #67c23a; font-weight: 600; }
</style>
