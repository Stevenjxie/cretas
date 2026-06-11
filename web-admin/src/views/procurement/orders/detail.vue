<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import request, { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Download, InfoFilled } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import { formatAmount } from '@/utils/tableFormatters';
import NotFoundEmpty from '@/components/common/NotFoundEmpty.vue';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentUploadButton from '@/components/attachment/AttachmentUploadButton.vue';
import type { TableRow } from '@/types/api';

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
const receiveDialogVisible = ref(false);
// Issue #741 — Preview hint 字段 (orderedQuantity / alreadyReceived) carried forward to dialog so
// 用户可在输入收货数前看到 上限 (含 30% 抄收). Backend validates same upper bound at /confirm step.
interface ReceiveFormItem {
  materialTypeId: string;
  materialName: string;
  receivedQuantity: number;
  unit: string;
  unitPrice: number;
  orderedQuantity: number;      // 下单总量 (从 PO item.quantity)
  alreadyReceived: number;      // 已收 (PO item.receivedQuantity)
  maxAllowed: number;           // 含 30% 抄收上限 = orderedQuantity * 1.3 - alreadyReceived
}
const receiveForm = ref<{ supplierId: string; receiveDate: string; items: ReceiveFormItem[] }>({ supplierId: '', receiveDate: '', items: [] });

// Issue #741: 30% 抄收宽容上限 — 与后端 PurchaseReceiveService 一致.
const OVER_RECEIVE_TOLERANCE = 1.3;
const attachmentRefreshKey = ref(0);

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

onMounted(() => { loadOrder(); loadReceives(); });

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
    const res = await get(`/${factoryId.value}/purchase/receives/by-order/${orderId.value}`);
    if (res.success) receives.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore */ }
}

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    submit: { label: '提交', url: `/${factoryId.value}/purchase/orders/${orderId.value}/submit` },
    approve: { label: '审批通过', url: `/${factoryId.value}/purchase/orders/${orderId.value}/approve` },
    cancel: { label: '取消', url: `/${factoryId.value}/purchase/orders/${orderId.value}/cancel` },
    submitFinance: { label: '提交财务审核', url: `/${factoryId.value}/purchase/orders/${orderId.value}/submit-for-finance-review` },
    financeApprove: { label: '财务审核通过', url: `/${factoryId.value}/purchase/orders/${orderId.value}/finance-approve` },
    financeReject: { label: '财务驳回', url: `/${factoryId.value}/purchase/orders/${orderId.value}/finance-reject` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(`确认${a.label}？`, '操作确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) { ElMessage.success(`${a.label}成功`); loadOrder(); }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

function openReceiveDialog() {
  if (!order.value?.items?.length) return;
  // Auto-populate supplierId from PO and default receiveDate to today
  receiveForm.value.supplierId = (order.value.supplierId as string) || '';
  receiveForm.value.receiveDate = new Date().toISOString().slice(0, 10);
  receiveForm.value.items = (order.value.items as TableRow[]).map((it) => {
    const ordered = Number(it.quantity) || 0;
    const already = Number(it.receivedQuantity) || 0;
    const maxAllowed = Math.max(0, +(ordered * OVER_RECEIVE_TOLERANCE - already).toFixed(3));
    return {
      materialTypeId: it.materialTypeId,
      materialName: it.materialName,
      // Default to remaining (ordered - already), capped at maxAllowed
      receivedQuantity: Math.min(Math.max(0, ordered - already), maxAllowed),
      unit: it.unit,
      unitPrice: it.unitPrice,
      orderedQuantity: ordered,
      alreadyReceived: already,
      maxAllowed,
    };
  });
  receiveDialogVisible.value = true;
}

// Issue #741: 计算剩余可入余量 (>= 0)
function remainingAllowed(row: ReceiveFormItem): number {
  return Math.max(0, +(row.maxAllowed - (Number(row.receivedQuantity) || 0)).toFixed(3));
}

function isOverLimit(row: ReceiveFormItem): boolean {
  return (Number(row.receivedQuantity) || 0) > row.maxAllowed;
}

async function handleCreateReceive() {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/purchase/receives`, {
      purchaseOrderId: orderId.value,
      supplierId: receiveForm.value.supplierId,
      receiveDate: receiveForm.value.receiveDate,
      items: receiveForm.value.items.filter(i => i.receivedQuantity > 0),
    });
    if (res.success) {
      ElMessage.success('收货单创建成功');
      receiveDialogVisible.value = false;
      loadOrder();
      loadReceives();
      // Issue #781: 入库 (新建收货) 后强制刷新三价对比缓存.
      // 入库价是三价对比的"当前价"数据源 (May 7 part1 line 27-31), 入库完必须重拉,
      // 否则 collapse 已展开过的用户看到的还是上一次的 stale comparison.
      if (priceComparisons.value.length > 0 || priceLoading.value) {
        loadPriceComparison();
      }
    } else { ElMessage.error(res.message || '创建失败，请重试'); }
  } catch (e) { handleCatchError(e, '创建失败，请检查网络'); }
  finally { submitting.value = false; }
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

function priceRowClassName({ row }: { row: PriceComparison }): string {
  return row.priceAlert ? 'price-alert-row' : '';
}

// P0 (六扇门 May 7 transcript): 下载 PDF (供货单) — 含 Code128 + QR 条码,
// 供应商打印后送货员带过来,仓管员扫码进入入库流程。
const pdfDownloading = ref(false);
async function handleDownloadPdf() {
  if (!factoryId.value || !orderId.value || pdfDownloading.value) return;
  pdfDownloading.value = true;
  try {
    const response = await request.get(
      `/${factoryId.value}/purchase/orders/${orderId.value}/pdf`,
      { responseType: 'blob' }
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const orderNum = order.value?.orderNumber || orderId.value;
    link.download = `供货单_${orderNum}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success('PDF 下载成功');
  } catch (e) {
    // axios interceptor 已经 toast — 但 blob 响应 error 跳过 interceptor json parse, 兜底
    console.error('[PDF 下载失败]', e);
    ElMessage.error('PDF 下载失败,请稍后重试');
  } finally {
    pdfDownloading.value = false;
  }
}

async function confirmReceive(receiveId: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm('确认入库？将生成物料批次', '确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/purchase/receives/${receiveId}/confirm`);
    if (res.success) {
      ElMessage.success('入库确认成功');
      loadReceives();
      loadOrder();
      // Issue #781: 确认入库后入库价被记入 raw_material — 强制刷新三价对比.
      if (priceComparisons.value.length > 0 || priceLoading.value) {
        loadPriceComparison();
      }
    }
    else { ElMessage.error(res.message || '入库确认失败，请重试'); }
  } catch (e) { handleCatchError(e, '入库确认失败，请检查网络'); }
  finally { submitting.value = false; }
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
              {{ statusMap[order.status]?.text || order.status }}
            </el-tag>
          </div>
          <div class="header-right" v-if="order">
            <!-- P0 (六扇门 May 7 transcript): 下载 PDF 供货单 (含条码) 给所有读权限角色可见 -->
            <el-button :icon="Download" :loading="pdfDownloading" @click="handleDownloadPdf">下载 PDF</el-button>
            <template v-if="canWrite">
            <el-button v-if="order.status === 'DRAFT'" type="warning" :loading="submitting" @click="handleAction('submit')">提交审批</el-button>
            <el-button v-if="order.status === 'SUBMITTED'" type="success" :loading="submitting" @click="handleAction('approve')">审批通过</el-button>
            <el-button v-if="order.status === 'APPROVED'" type="warning" :loading="submitting" @click="handleAction('submitFinance')">提交财务审核</el-button>
            <el-button v-if="order.status === 'PENDING_FINANCE_REVIEW'" type="success" :loading="submitting" @click="handleAction('financeApprove')">财务通过</el-button>
            <el-button v-if="order.status === 'PENDING_FINANCE_REVIEW'" type="danger" :loading="submitting" @click="handleAction('financeReject')">财务驳回</el-button>
            <el-button v-if="['FINANCE_APPROVED','PARTIAL_RECEIVED'].includes(order.status)" type="primary" :loading="submitting" @click="openReceiveDialog">{{ label('receive') }}</el-button>
            <el-button v-if="['DRAFT','SUBMITTED'].includes(order.status)" type="danger" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
            </template>
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
          <el-descriptions-item label="审批人">{{ order.approvedBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="合同号">{{ order.contractNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="结算方式">
            <span v-if="order.settlementType">{{ ({ PREPAID: '预付', CREDIT_FIRST: '赊销先入库', NO_INVOICE: '未到票', MONTHLY: '月结', CREDIT_PERIOD: '账期', IMMEDIATE: '现结' } as Record<string, string>)[order.settlementType] || order.settlementType }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="开票提醒">
            <span v-if="order.invoiceReminderDays != null">{{ order.invoiceReminderDays === 0 ? '不提醒' : `${order.invoiceReminderDays} 天` }}</span>
            <span v-else>使用工厂默认</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
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
            <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column label="已收货" width="120" align="right">
            <template #default="{ row }">{{ row.receivedQuantity || 0 }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="130" align="right">
            <template #default="{ row }">{{ formatAmount(row.quantity * row.unitPrice) }}</template>
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

        <h3 style="margin: 20px 0 12px">{{ label('receive') }}记录</h3>
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
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button v-if="['DRAFT','PENDING_QC'].includes(row.status) && canWrite" type="success" link size="small" :disabled="submitting" @click="confirmReceive(row.id)">确认入库</el-button>
            </template>
          </el-table-column>
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
          <AttachmentUploadButton
            entity-type="PURCHASE_ORDER"
            :entity-id="String(orderId)"
            :factory-id="factoryId"
            business-tag="PURCHASE_DOC"
            @uploaded="attachmentRefreshKey++"
          />
        </div>
      </template>
    </el-card>

    <el-dialog v-model="receiveDialogVisible" title="创建收货单" width="820px" destroy-on-close>
      <!-- Issue #741: 入库防呆 — 在输入收货数前预先显示 下单/已收/可入(含30%抄收), 避免 submit 后才看到 toast -->
      <el-alert type="info" show-icon :closable="false" style="margin-bottom: 12px"
        title="入库上限说明"
        description="系统允许超收 30% 作为抄收宽容 (与采购订单一致). 「可入」= 下单量 × 1.3 − 已收量. 超过此上限提交将被驳回." />
      <el-table :data="receiveForm.items" border>
        <el-table-column prop="materialName" :label="label('rawMaterial')" min-width="140" show-overflow-tooltip />
        <el-table-column label="下单" width="80" align="right">
          <template #default="{ row }">{{ row.orderedQuantity }}</template>
        </el-table-column>
        <el-table-column label="已收" width="80" align="right">
          <template #default="{ row }">{{ row.alreadyReceived }}</template>
        </el-table-column>
        <el-table-column label="可入 (含30%抄收)" width="140" align="right">
          <template #default="{ row }">
            <span :style="{ color: '#67c23a', fontWeight: 600 }">{{ row.maxAllowed }}</span>
          </template>
        </el-table-column>
        <el-table-column label="本次收货" width="180">
          <template #default="{ row }">
            <el-input-number
              v-model="row.receivedQuantity"
              :min="0"
              :max="row.maxAllowed"
              :precision="3"
              :controls="false"
              size="small"
              style="width: 130px" />
            <div v-if="isOverLimit(row)" style="color:#f56c6c; font-size:12px; margin-top:2px">
              超出上限 (最多 {{ row.maxAllowed }})
            </div>
            <div v-else-if="row.receivedQuantity > 0" style="color:#909399; font-size:12px; margin-top:2px">
              剩余可入 {{ remainingAllowed(row) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="70" align="center" />
        <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="receiveDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="receiveForm.items.some(isOverLimit)"
          @click="handleCreateReceive">创建收货单</el-button>
      </template>
    </el-dialog>
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
// 三价对比样式
:deep(.price-alert-row) {
  background-color: #fef0f0 !important;
  td { background-color: #fef0f0 !important; }
}
.variance-up { color: #f56c6c; font-weight: 600; }
.variance-down { color: #67c23a; font-weight: 600; }
</style>
