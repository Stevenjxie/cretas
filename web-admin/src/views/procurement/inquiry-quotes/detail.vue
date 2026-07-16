<script setup lang="ts">
/**
 * P-NUCLEAR-1 (28-Backlog #30) — 核价单详情 (PC).
 *
 * 三阶段操作:
 *   1. DRAFT → INQUIRING: 提交询价 (submit button)
 *   2. INQUIRING/QUOTED: 添加供应商报价 (loops; UPDATE if same supplier)
 *   3. QUOTED → CONVERTED: 选定中标供应商 + 生成 PO (idempotent)
 *
 * 防呆 R2: 所有 toast 含 inquiryNumber + supplierName context.
 * 防呆 R4: select-and-convert 已转化拒绝重复 — 后端 409 携带 PO 号, 前端 catch 跳转.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { buildSupplierPricePayload } from './quotePayload';
import { Refresh } from '@element-plus/icons-vue';
import {
  getInquiry,
  submitInquiry,
  cancelInquiry,
  addSupplierPrice,
  listSupplierPrices,
  selectAndConvert,
  type InquiryQuote,
  type InquiryQuoteSupplierPrice,
} from '@/api/inquiryQuote';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const inquiryId = computed(() => String(route.params.id));
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const quote = ref<InquiryQuote | null>(null);
const prices = ref<InquiryQuoteSupplierPrice[]>([]);

// Add price form
const addPriceForm = ref({ supplierId: '', unitPrice: 0, taxRate: 0, deliveryDays: 0, remark: '' });
const addPriceSubmitting = ref(false);

// Select & convert
const selectedSupplierId = ref<string>('');
const convertSubmitting = ref(false);

const isEditable = computed(() => {
  if (!quote.value) return false;
  return ['INQUIRING', 'QUOTED', 'SELECTED'].includes(quote.value.status);
});

const canConvert = computed(() => {
  if (!quote.value) return false;
  return (
    (quote.value.status === 'QUOTED' || quote.value.status === 'SELECTED') &&
    prices.value.length > 0
  );
});

const isConverted = computed(() => quote.value?.status === 'CONVERTED');

async function load() {
  if (!factoryId.value || !inquiryId.value) return;
  loading.value = true;
  try {
    const qRes = await getInquiry(factoryId.value, inquiryId.value);
    if (qRes.success && qRes.data) {
      quote.value = qRes.data;
    }
    const pRes = await listSupplierPrices(factoryId.value, inquiryId.value);
    if (pRes.success && pRes.data) {
      prices.value = pRes.data;
    }
  } finally {
    loading.value = false;
  }
}

async function doSubmit() {
  if (!factoryId.value || !quote.value) return;
  try {
    await ElMessageBox.confirm(
      `确认提交核价单 ${quote.value.inquiryNumber} 询价?`,
      '提交询价',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await submitInquiry(factoryId.value, inquiryId.value);
  if (res.success) {
    ElMessage.success(res.message || '已提交询价');
    load();
  }
}

async function doCancel() {
  if (!factoryId.value || !quote.value) return;
  try {
    await ElMessageBox.confirm(
      `确认取消核价单 ${quote.value.inquiryNumber}? 取消后不可恢复.`,
      '取消核价单',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await cancelInquiry(factoryId.value, inquiryId.value);
  if (res.success) {
    ElMessage.success(res.message || '已取消');
    load();
  }
}

async function submitAddPrice() {
  if (!factoryId.value || !quote.value) return;
  if (!addPriceForm.value.supplierId) {
    ElMessage.warning('请填写供应商 ID');
    return;
  }
  if (!addPriceForm.value.unitPrice || addPriceForm.value.unitPrice <= 0) {
    ElMessage.warning('报价必须 > 0');
    return;
  }
  addPriceSubmitting.value = true;
  try {
    const res = await addSupplierPrice(
      factoryId.value,
      inquiryId.value,
      buildSupplierPricePayload(addPriceForm.value),
    );
    if (res.success) {
      ElMessage.success(res.message || '报价提交成功');
      addPriceForm.value = { supplierId: '', unitPrice: 0, taxRate: 0, deliveryDays: 0, remark: '' };
      load();
    }
  } finally {
    addPriceSubmitting.value = false;
  }
}

async function doSelectAndConvert() {
  if (!factoryId.value || !quote.value) return;
  if (!selectedSupplierId.value) {
    ElMessage.warning('请先勾选中标供应商');
    return;
  }
  const selected = prices.value.find((p) => p.supplierId === selectedSupplierId.value);
  if (!selected) return;

  try {
    await ElMessageBox.confirm(
      `确认选定供应商 "${selected.supplierName || selected.supplierId}" (单价 ¥${selected.unitPrice}) ` +
        `生成采购单? 此操作幂等, 重复点击会被服务端拒绝.`,
      '选定中标供应商 + 生成采购单',
      { type: 'warning', confirmButtonText: '确认生成' },
    );
  } catch {
    return;
  }

  convertSubmitting.value = true;
  try {
    const res = await selectAndConvert(factoryId.value, inquiryId.value, {
      selectedSupplierId: selectedSupplierId.value,
    });
    if (res.success && res.data) {
      ElMessage.success(res.message || `已生成采购单 ${res.data.orderNumber}`);
      load();
    }
  } catch (err: unknown) {
    // 防呆 R4: 后端 409 携带已生成 PO 号, 提示用户跳转
    const e = err as { response?: { status?: number; data?: { message?: string } } };
    if (e.response?.status === 409 && e.response.data?.message) {
      try {
        await ElMessageBox.confirm(
          e.response.data.message + ' 是否跳转查看?',
          '已生成采购单',
          { type: 'info', confirmButtonText: '查看采购单', cancelButtonText: '取消' },
        );
        load();
      } catch {
        /* user dismissed */
      }
    }
  } finally {
    convertSubmitting.value = false;
  }
}

function goPurchaseOrder() {
  if (!quote.value?.purchaseOrderId) return;
  router.push({ name: 'ProcurementOrderDetail', params: { id: quote.value.purchaseOrderId } });
}

function formatPrice(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`;
}

onMounted(load);
</script>

<template>
  <div class="inquiry-quote-detail" v-loading="loading">
    <div class="page-header">
      <div>
        <el-button text @click="router.back()">‹ 返回</el-button>
        <h2 v-if="quote" class="title">
          {{ quote.inquiryNumber }}
          <el-tag class="status-tag" effect="light" :type="isConverted ? 'success' : 'primary'">
            {{ quote.status }}
          </el-tag>
        </h2>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button
          v-if="quote && quote.status === 'DRAFT'"
          type="primary"
          @click="doSubmit"
        >
          提交询价
        </el-button>
        <el-button
          v-if="quote && !isConverted && quote.status !== 'CANCELLED'"
          type="danger"
          plain
          @click="doCancel"
        >
          取消核价单
        </el-button>
        <el-button
          v-if="isConverted && quote?.purchaseOrderId"
          type="success"
          @click="goPurchaseOrder"
        >
          查看采购单 {{ quote.purchaseOrderNumber }}
        </el-button>
      </div>
    </div>

    <el-card v-if="quote" class="info-card" shadow="never">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题">{{ quote.title || '-' }}</el-descriptions-item>
        <el-descriptions-item label="物料">{{ quote.materialName || quote.materialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="规格">{{ quote.specification || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ quote.quantity }} {{ quote.unit }}</el-descriptions-item>
        <el-descriptions-item label="询价日期">{{ quote.inquiryDate }}</el-descriptions-item>
        <el-descriptions-item label="期望到货">{{ quote.requiredDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="中标供应商" :span="2">
          {{ quote.selectedSupplierName || quote.selectedSupplierId || '尚未选定' }}
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ quote.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="prices-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>供应商报价 ({{ prices.length }})</span>
          <span class="hint">按价格升序, 第一行为最低报价</span>
        </div>
      </template>

      <el-table :data="prices" stripe empty-text="尚无报价">
        <el-table-column v-if="canConvert" label="选定" width="60" align="center">
          <template #default="{ row }">
            <el-radio v-model="selectedSupplierId" :value="row.supplierId" />
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="180">
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </el-table-column>
        <el-table-column prop="unitPrice" label="单价" min-width="140" align="right">
          <template #default="{ row }">{{ formatPrice(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column prop="taxRate" label="税率(%)" min-width="100" align="right">
          <template #default="{ row }">{{ row.taxRate ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="validUntil" label="报价有效期" min-width="120">
          <template #default="{ row }">{{ row.validUntil || '-' }}</template>
        </el-table-column>
        <el-table-column prop="deliveryDays" label="交货天数" min-width="100" align="right">
          <template #default="{ row }">{{ row.deliveryDays ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="quotedAt" label="报价时间" min-width="180" />
      </el-table>

      <div v-if="canConvert" class="convert-bar">
        <el-button type="primary" :loading="convertSubmitting" @click="doSelectAndConvert">
          选定中标供应商 + 生成采购单
        </el-button>
        <span class="hint">幂等操作: 重复提交不会创建多个采购单</span>
      </div>
    </el-card>

    <el-card v-if="isEditable" class="add-price-card" shadow="never">
      <template #header>
        <span>添加 / 更新供应商报价</span>
      </template>
      <el-form :model="addPriceForm" inline>
        <el-form-item label="供应商 ID" required>
          <el-input v-model="addPriceForm.supplierId" maxlength="191" style="width: 280px" />
        </el-form-item>
        <el-form-item label="单价" required>
          <el-input-number v-model="addPriceForm.unitPrice" :min="0" :precision="4" />
        </el-form-item>
        <el-form-item label="税率(%)">
          <el-input-number v-model="addPriceForm.taxRate" :min="0" :max="100" :precision="2" />
        </el-form-item>
        <el-form-item label="交货天数">
          <el-input-number v-model="addPriceForm.deliveryDays" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addPriceForm.remark" maxlength="500" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="addPriceSubmitting" @click="submitAddPrice">
            提交报价
          </el-button>
        </el-form-item>
      </el-form>
      <p class="hint">同供应商已报价则 UPDATE; 否则 INSERT (幂等)</p>
    </el-card>
  </div>
</template>

<style scoped>
.inquiry-quote-detail {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 4px 0 0;
  color: #303133;
}
.status-tag {
  margin-left: 8px;
  vertical-align: middle;
}
.info-card,
.prices-card,
.add-price-card {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.hint {
  font-size: 12px;
  color: #909399;
}
.convert-bar {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
