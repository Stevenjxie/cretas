<script setup lang="ts">
/**
 * SharePage — 公开分享只读视图 (PR #872 follow-up to #860).
 *
 * URL: /share/:token  (router meta.requiresAuth = false → guard bypasses).
 * Renders PO 或 SO read-only based on backend payload.entityType.
 *
 * Minimal layout — no sidebar / no top nav (viewers may not be logged in).
 * On 410 or 404 from the backend → show "链接已过期或不存在" error page.
 *
 * Backend: GET /api/mobile/public/share/{token}
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { isAxiosError } from 'axios';
import { resolvePublicShare, type PublicSharePayload } from '@/api/shareLink';

const route = useRoute();

const loading = ref(true);
const errorMessage = ref<string | null>(null);
const errorCode = ref<number | null>(null);
const data = ref<PublicSharePayload | null>(null);

const token = computed(() => String(route.params.token || ''));

interface PurchaseOrderItemView {
  id?: string | number | null;
  materialTypeId?: string | null;
  materialName?: string | null;
  specification?: string | null;
  quantity?: number | string | null;
  unit?: string | null;
  receivedQuantity?: number | string | null;
  remark?: string | null;
  unitPrice?: number | string | null;
  taxRate?: number | string | null;
}

interface PurchaseOrderView {
  id?: string;
  orderNumber?: string;
  supplierId?: string | null;
  supplierName?: string | null;
  purchaseType?: string | null;
  orderDate?: string | null;
  expectedDeliveryDate?: string | null;
  status?: string | null;
  remark?: string | null;
  createdAt?: string | null;
  totalAmount?: number | string | null;
  taxAmount?: number | string | null;
  items?: PurchaseOrderItemView[];
}

interface SalesOrderItemView {
  id?: string | number | null;
  productTypeId?: string | null;
  productName?: string | null;
  quantity?: number | string | null;
  unit?: string | null;
  deliveredQuantity?: number | string | null;
  unitPrice?: number | string | null;
  discountRate?: number | string | null;
}

interface SalesOrderView {
  id?: string;
  orderNumber?: string;
  customerId?: string | null;
  customerName?: string | null;
  orderDate?: string | null;
  requiredDeliveryDate?: string | null;
  deliveryAddress?: string | null;
  status?: string | null;
  remark?: string | null;
  createdAt?: string | null;
  totalAmount?: number | string | null;
  items?: SalesOrderItemView[];
}

const purchasePayload = computed<PurchaseOrderView | null>(() => {
  if (!data.value || data.value.entityType !== 'PurchaseOrder') return null;
  return data.value.payload as unknown as PurchaseOrderView;
});

const salesPayload = computed<SalesOrderView | null>(() => {
  if (!data.value || data.value.entityType !== 'SalesOrder') return null;
  return data.value.payload as unknown as SalesOrderView;
});

const includePrice = computed(() => data.value?.includePrice === true);

const expiryLabel = computed(() => {
  if (!data.value) return '';
  if (!data.value.expiresAt) return '永久有效';
  try {
    const d = new Date(data.value.expiresAt);
    if (Number.isNaN(d.getTime())) return data.value.expiresAt;
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day} 过期`;
  } catch {
    return data.value.expiresAt;
  }
});

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = null;
  errorCode.value = null;
  if (!token.value) {
    errorMessage.value = 'URL 中缺少 token, 请检查链接是否完整';
    loading.value = false;
    return;
  }
  try {
    data.value = await resolvePublicShare(token.value);
  } catch (e: unknown) {
    if (isAxiosError(e)) {
      errorCode.value = e.response?.status ?? null;
      const apiMsg = (e.response?.data as { message?: string } | undefined)?.message;
      if (errorCode.value === 410) {
        errorMessage.value = apiMsg || '链接已过期或已被撤销';
      } else if (errorCode.value === 404) {
        errorMessage.value = apiMsg || '链接不存在或已被删除';
      } else {
        errorMessage.value = apiMsg || '加载分享内容失败';
      }
    } else {
      errorMessage.value = e instanceof Error ? e.message : '加载分享内容失败';
    }
  } finally {
    loading.value = false;
  }
}

function fmtNumber(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '-';
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

function fmtAmount(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '-';
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return `¥ ${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function fmtDate(v: string | null | undefined): string {
  if (!v) return '-';
  // Accept ISO date or datetime, strip seconds/T
  return String(v).slice(0, 10);
}

onMounted(load);
</script>

<template>
  <div class="share-page">
    <header class="share-header">
      <div class="brand">白垩纪 · 食品溯源系统</div>
      <div v-if="data" class="meta">
        <el-tag size="small">{{ data.entityType === 'PurchaseOrder' ? '采购单' : '销售订单' }}</el-tag>
        <span class="expiry">{{ expiryLabel }}</span>
        <el-tag v-if="!includePrice" type="info" size="small">价格已隐藏</el-tag>
      </div>
    </header>

    <main class="share-body">
      <el-card v-if="loading" class="state-card" shadow="never">
        <el-skeleton :rows="6" animated />
      </el-card>

      <el-card v-else-if="errorMessage" class="state-card" shadow="never">
        <el-empty
          :description="errorMessage"
          :image-size="120"
        >
          <template #image>
 <span style="font-size: 64px; color: #c0c4cc;">️</span>
          </template>
          <div class="error-hint">
            如需重新获取链接, 请联系发送方.
          </div>
        </el-empty>
      </el-card>

      <!-- PurchaseOrder view -->
      <el-card v-else-if="purchasePayload" class="detail-card" shadow="never">
        <template #header>
          <div class="detail-header">
            <span class="title">采购单 #{{ purchasePayload.orderNumber || purchasePayload.id }}</span>
            <el-tag v-if="purchasePayload.status" size="small">{{ purchasePayload.status }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单编号">{{ purchasePayload.orderNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ purchasePayload.supplierName || purchasePayload.supplierId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="采购类型">{{ purchasePayload.purchaseType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ fmtDate(purchasePayload.orderDate) }}</el-descriptions-item>
          <el-descriptions-item label="期望交货">{{ fmtDate(purchasePayload.expectedDeliveryDate) }}</el-descriptions-item>
          <el-descriptions-item v-if="includePrice" label="总金额">
            {{ fmtAmount(purchasePayload.totalAmount) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="includePrice" label="税额">
            {{ fmtAmount(purchasePayload.taxAmount) }}
          </el-descriptions-item>
          <el-descriptions-item :span="2" label="备注">
            {{ purchasePayload.remark || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="section-title">明细项</h3>
        <el-table :data="purchasePayload.items || []" border stripe size="small">
          <el-table-column label="物料" prop="materialName" min-width="160">
            <template #default="{ row }">
              <span>{{ row.materialName || row.materialTypeId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" prop="specification" width="140" />
          <el-table-column label="数量" prop="quantity" width="120" align="right">
            <template #default="{ row }">{{ fmtNumber(row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="单位" prop="unit" width="80" />
          <el-table-column label="已收货" prop="receivedQuantity" width="120" align="right">
            <template #default="{ row }">{{ fmtNumber(row.receivedQuantity) }}</template>
          </el-table-column>
          <el-table-column v-if="includePrice" label="单价" prop="unitPrice" width="120" align="right">
            <template #default="{ row }">{{ fmtAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="includePrice" label="税率(%)" prop="taxRate" width="100" align="right">
            <template #default="{ row }">{{ fmtNumber(row.taxRate) }}</template>
          </el-table-column>
          <el-table-column label="备注" prop="remark" min-width="120" />
        </el-table>
      </el-card>

      <!-- SalesOrder view -->
      <el-card v-else-if="salesPayload" class="detail-card" shadow="never">
        <template #header>
          <div class="detail-header">
            <span class="title">销售订单 #{{ salesPayload.orderNumber || salesPayload.id }}</span>
            <el-tag v-if="salesPayload.status" size="small">{{ salesPayload.status }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单编号">{{ salesPayload.orderNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ salesPayload.customerName || salesPayload.customerId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ fmtDate(salesPayload.orderDate) }}</el-descriptions-item>
          <el-descriptions-item label="要求交货">{{ fmtDate(salesPayload.requiredDeliveryDate) }}</el-descriptions-item>
          <el-descriptions-item :span="2" label="收货地址">
            {{ salesPayload.deliveryAddress || '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="includePrice" :span="2" label="总金额">
            {{ fmtAmount(salesPayload.totalAmount) }}
          </el-descriptions-item>
          <el-descriptions-item :span="2" label="备注">
            {{ salesPayload.remark || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="section-title">明细项</h3>
        <el-table :data="salesPayload.items || []" border stripe size="small">
          <el-table-column label="产品" prop="productName" min-width="160">
            <template #default="{ row }">
              <span>{{ row.productName || row.productTypeId || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="数量" prop="quantity" width="120" align="right">
            <template #default="{ row }">{{ fmtNumber(row.quantity) }}</template>
          </el-table-column>
          <el-table-column label="单位" prop="unit" width="80" />
          <el-table-column label="已发货" prop="deliveredQuantity" width="120" align="right">
            <template #default="{ row }">{{ fmtNumber(row.deliveredQuantity) }}</template>
          </el-table-column>
          <el-table-column v-if="includePrice" label="单价" prop="unitPrice" width="120" align="right">
            <template #default="{ row }">{{ fmtAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="includePrice" label="折扣(%)" prop="discountRate" width="100" align="right">
            <template #default="{ row }">{{ fmtNumber(row.discountRate) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-else class="state-card" shadow="never">
        <el-empty description="暂无内容" />
      </el-card>
    </main>

    <footer class="share-footer">
      <span>本页面由白垩纪生成, 仅用于只读分享.</span>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.share-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

.share-header {
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  padding: 14px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  .brand {
    font-size: 15px;
    font-weight: 600;
    color: #303133;
  }
  .meta {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 13px;
    color: #606266;
    .expiry {
      color: #909399;
    }
  }
}

.share-body {
  flex: 1;
  padding: 24px;
  max-width: 1000px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
}

.state-card, .detail-card {
  border-radius: 6px;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  .title {
    font-size: 16px;
    font-weight: 600;
    color: #303133;
  }
}

.section-title {
  font-size: 14px;
  color: #303133;
  margin: 20px 0 12px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
}

.error-hint {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}

.share-footer {
  text-align: center;
  padding: 16px;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
