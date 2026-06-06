<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never" v-loading="loading">
      <template #header>
        <div class="card-header">
          <!-- Rule 2: context header — 供应商 / 单号 / 金额 -->
          <div class="header-left">
            <el-button :icon="ArrowLeft" link @click="goBack">返回</el-button>
            <span class="page-title">
              {{ isManual ? '手动录入送货单' : '送货单详情' }}
              <template v-if="note">— {{ note.supplierName || '未指定供应商' }}</template>
            </span>
            <el-tag v-if="note" size="small" :type="statusTagType(note.status)">
              {{ statusText(note.status) }}
            </el-tag>
            <el-tag v-if="note" size="small" :type="postingTagType(note.postingStatus)">
              {{ postingStatusText(note.postingStatus) }}
            </el-tag>
          </div>
        </div>
      </template>

      <!-- Rule 5: 低置信橙色提示 + 重拍 -->
      <el-alert
        v-if="note && note.lowConfidenceWarning"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
      >
        <template #title>
          识别置信度较低 ({{ Math.round((note.ocrConfidence || 0) * 100) }}%)，建议核对行项或重拍：确保单据平整、光线充足、文字清晰
        </template>
        <el-button size="small" type="warning" plain style="margin-top: 8px" @click="goBack">
          返回重新上传
        </el-button>
      </el-alert>

      <!-- OCR 错误提示 -->
      <el-alert
        v-if="note && note.ocrErrorMessage"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        :title="note.ocrErrorMessage"
      />

      <el-alert
        v-if="note && note.postingStatus === 'FAILED'"
        type="error"
        show-icon
        :closable="false"
        class="posting-failed-alert"
      >
        <template #title>
          库存批次生成失败：{{ note.postingError || '未返回具体原因' }}
        </template>
        <template #default>
          请修正原料匹配、数量或单价后重试确认验收入库；如果数据无误仍失败，请联系管理员处理。
        </template>
      </el-alert>

      <el-alert
        v-if="unexplainedPriceAnomalies.length > 0"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
      >
        <template #title>
          有 {{ unexplainedPriceAnomalies.length }} 行进价超过历史基线 5%，请在下方“进价异常处理”填写原因和现场解释后再提交老板审批。
        </template>
      </el-alert>

      <el-alert
        v-if="priceAnomalyRows.length > 0"
        :type="approvalAlertType"
        show-icon
        :closable="false"
        class="approval-status-alert"
      >
        <template #title>
          价格异常审批：{{ approvalStatusText }}
        </template>
        <template #default>
          <p v-if="note?.priceAnomalyApprovalComment" class="approval-comment">
            审批意见：{{ note.priceAnomalyApprovalComment }}
          </p>
          <p v-if="approvalStatus === 'PENDING'">已提交老板审批，批准前不能确认入库。</p>
          <p v-else-if="approvalStatus === 'REJECTED'">老板已驳回，请重新核对价格或联系采购。</p>
          <p v-else-if="canSubmitApproval">解释已填写，请提交老板审批后再确认入库。</p>
          <el-input
            v-if="canBossApprove && approvalStatus === 'PENDING'"
            v-model="approvalComment"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="审批意见（驳回必填）"
            style="margin-top: 8px"
          />
        </template>
      </el-alert>

      <section
        v-if="priceAnomalyRows.length > 0"
        ref="priceAnomalyPanelRef"
        class="price-anomaly-panel"
      >
        <div class="anomaly-panel-head">
          <div>
            <h3>进价异常处理</h3>
            <p>仓管先核对供应商解释和现场询价情况，处理完成后才能入库。</p>
          </div>
          <el-tag type="danger" effect="plain">{{ priceAnomalyRows.length }} 行异常</el-tag>
        </div>
        <div
          v-for="{ line, index } in priceAnomalyRows"
          :key="line.id || `${line.ingredientName}-${index}`"
          class="anomaly-item"
          :class="{ 'is-missing': isPriceAnomalyMissing(line) }"
        >
          <div class="anomaly-item-head">
            <strong>{{ line.ingredientName || `第 ${index + 1} 行食材` }}</strong>
            <el-tag type="danger" size="small">{{ formatVariance(line.priceVarianceRate) }}</el-tag>
          </div>
          <div class="anomaly-facts">
            <span>历史基线：{{ formatMoney(line.baselineUnitPrice) }}</span>
            <span>本次单价：{{ formatMoney(line.unitPrice) }}</span>
            <span>数量：{{ line.quantity ?? '—' }} {{ line.unit || '' }}</span>
            <span>供应商：{{ note?.supplierName || '未指定供应商' }}</span>
          </div>
          <div v-if="editable" class="anomaly-controls">
            <el-form-item label="涨价原因" required>
              <el-select
                v-model="line.priceAnomalyReasonCode"
                placeholder="选择涨价原因"
                style="width: 100%"
              >
                <el-option label="市场涨价" value="MARKET_PRICE_UP" />
                <el-option label="规格升级" value="SPEC_UPGRADE" />
                <el-option label="临时缺货换货" value="SUBSTITUTE_SHORTAGE" />
                <el-option label="供应商补充说明" value="SUPPLIER_EXPLAINED" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="现场解释" required>
              <el-input
                v-model="line.priceAnomalyExplanation"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="例如：供应商说明菜场今日涨价，仓管已现场询价确认。"
              />
            </el-form-item>
          </div>
          <p v-else class="anomaly-readonly">
            解释：{{ line.priceAnomalyExplanation || '—' }}
          </p>
        </div>
      </section>

      <el-descriptions v-if="note" :column="3" border class="posting-summary">
        <el-descriptions-item label="业务状态">
          <el-tag size="small" :type="statusTagType(note.status)">
            {{ statusText(note.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="库存过账">
          <el-tag size="small" :type="postingTagType(note.postingStatus)">
            {{ postingStatusText(note.postingStatus) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="收货记录">
          <span v-if="note.receiveRecordId" class="mono-text">{{ note.receiveRecordId }}</span>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="过账时间">
          {{ note.postedAt || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="过账人">
          {{ note.postedBy || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="下一步">
          <span v-if="note.postingStatus === 'POSTED'">已生成库存批次，可用于后续领料/损耗扣减。</span>
          <span v-else-if="note.postingStatus === 'FAILED'">修正原料匹配/数量后重试，或联系管理员。</span>
          <span v-else>确认验收入库后生成库存批次。</span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 头部信息 (Rule 2 context) -->
      <el-form :inline="false" label-width="90px" class="head-form">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="供应商">
              <el-select v-model="form.supplierId" filterable clearable :disabled="!editable"
                placeholder="选择供应商" style="width: 100%" @change="onSupplierChange">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="送货日期">
              <el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD"
                :disabled="!editable" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="送货单号">
              <el-input v-model="form.noteNumber" :disabled="!editable" placeholder="送货单号" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 行项表 (Rule 3: qty×price 联动) -->
      <el-table :data="form.lines" border style="width: 100%">
        <el-table-column label="食材名称" min-width="160">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.ingredientName" placeholder="食材名称" />
            <span v-else>{{ row.ingredientName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对应原料" min-width="160">
          <template #default="{ row }">
            <el-select v-if="editable" v-model="row.rawMaterialTypeId" filterable clearable
              placeholder="(自动匹配)" style="width: 100%">
              <el-option v-for="m in materialTypes" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <span v-else>{{ materialNameMap[row.rawMaterialTypeId] || row.rawMaterialTypeId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.quantity" :precision="4" :step="0.5" :min="0"
              size="small" controls-position="right" style="width: 100px" @change="recalcLine(row)" />
            <span v-else>{{ row.quantity ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.unit" size="small" style="width: 60px" />
            <span v-else>{{ row.unit ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">
            <el-input-number v-if="editable" v-model="row.unitPrice" :precision="4" :step="0.5" :min="0"
              size="small" controls-position="right" style="width: 100px" @change="recalcLine(row)" />
            <span v-else>{{ row.unitPrice ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="历史基线" width="120" align="right">
          <template #default="{ row }">
            <span>{{ row.baselineUnitPrice != null ? '¥' + row.baselineUnitPrice : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="价格差异" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.priceAnomalyFlag" type="danger" size="small">
              {{ formatVariance(row.priceVarianceRate) }}
            </el-tag>
            <span v-else>{{ row.priceVarianceRate != null ? formatVariance(row.priceVarianceRate) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span :class="{ 'auto-calc': editable && row.quantity != null && row.unitPrice != null }">
              {{ row.lineAmount != null ? '¥' + row.lineAmount : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="质检" width="110">
          <template #default="{ row }">
            <span>{{ row.qcResult || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="库存批次" min-width="170">
          <template #default="{ row }">
            <span v-if="row.materialBatchId" class="mono-text">{{ row.materialBatchId }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="140">
          <template #default="{ row }">
            <el-input v-if="editable" v-model="row.remark" placeholder="备注" />
            <span v-else>{{ row.remark || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="涨价解释" min-width="220">
          <template #default="{ row }">
            <template v-if="editable && row.priceAnomalyFlag">
              <el-select
                v-model="row.priceAnomalyReasonCode"
                placeholder="选择原因"
                size="small"
                style="width: 100%; margin-bottom: 6px"
              >
                <el-option label="市场涨价" value="MARKET_PRICE_UP" />
                <el-option label="规格升级" value="SPEC_UPGRADE" />
                <el-option label="临时缺货换货" value="SUBSTITUTE_SHORTAGE" />
                <el-option label="供应商补充说明" value="SUPPLIER_EXPLAINED" />
                <el-option label="其他" value="OTHER" />
              </el-select>
              <el-input
                v-model="row.priceAnomalyExplanation"
                size="small"
                placeholder="填写供应商解释"
              />
            </template>
            <span v-else>{{ row.priceAnomalyExplanation || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="editable" label="操作" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeLine($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-button v-if="editable" link type="primary" :icon="Plus" style="margin-top: 8px" @click="addLine">
        添加行项
      </el-button>

      <div class="total-row">
        合计金额：<strong>¥{{ totalAmount }}</strong>
      </div>

      <!-- 操作按钮 -->
      <div class="action-bar" v-if="editable">
        <el-button @click="goBack">取消</el-button>
        <el-button v-if="!isManual" :loading="saving" @click="saveLines">保存行项</el-button>
        <el-button v-if="isManual" type="primary" :loading="saving" @click="saveManual">保存录入</el-button>
        <el-button
          v-if="!isManual && canSubmitApproval"
          type="warning"
          :loading="submittingApproval"
          @click="submitApproval"
        >
          提交老板审批
        </el-button>
        <template v-if="!isManual && canBossApprove && approvalStatus === 'PENDING'">
          <el-button type="success" :loading="approving" @click="approveAnomaly">批准</el-button>
          <el-button type="danger" plain :loading="rejectingApproval" @click="rejectAnomaly">驳回</el-button>
        </template>
        <el-button v-if="!isManual" type="danger" plain @click="openReject">拒绝</el-button>
        <el-button v-if="!isManual" type="primary" :loading="confirming" @click="confirm">
          确认验收入库 / 生成库存批次
        </el-button>
      </div>
    </el-card>

    <!-- Rule 3: 拒绝原因 dropdown + OTHER 才显 textarea -->
    <el-dialog v-model="rejectVisible" title="拒绝送货单" width="440px">
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-select v-model="rejectForm.code" placeholder="选择原因" style="width: 100%">
            <el-option label="图片模糊" value="IMAGE_BLUR" />
            <el-option label="光线不足" value="LOW_LIGHT" />
            <el-option label="单据不对" value="WRONG_DOCUMENT" />
            <el-option label="供应商不存在" value="SUPPLIER_NOT_FOUND" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectForm.code === 'OTHER'" label="补充说明" required>
          <el-input v-model="rejectForm.note" type="textarea" :rows="2" placeholder="请说明具体原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" :disabled="!canReject" @click="doReject">
          确认拒绝
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useFactoryId } from '@/composables/useFactoryId';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  getNoteDetail, confirmNote, rejectNote, updateNoteLines, createManualNote,
  submitPriceAnomalyApproval, approvePriceAnomaly, rejectPriceAnomaly,
  type DeliveryPostingStatus,
  type PriceAnomalyApprovalStatus,
  type SupplierDeliveryNoteDto, type SupplierDeliveryNoteLineDto,
} from '@/api/restaurant/supplierDeliveryNote';
import { handleCatchError } from '@/utils/errorToast';

const route = useRoute();
const router = useRouter();
const factoryId = useFactoryId();
const authStore = useAuthStore();

const noteId = computed(() => route.params.id as string);
const isManual = computed(() => noteId.value === 'new' || route.query.mode === 'manual');

const loading = ref(false);
const saving = ref(false);
const confirming = ref(false);
const rejecting = ref(false);
const submittingApproval = ref(false);
const approving = ref(false);
const rejectingApproval = ref(false);
const approvalComment = ref('');
const note = ref<SupplierDeliveryNoteDto | null>(null);
const suppliers = ref<Array<{ id: string; name: string }>>([]);
const materialTypes = ref<Array<{ id: string; name: string }>>([]);
const priceAnomalyPanelRef = ref<HTMLElement | null>(null);

const form = reactive<{
  supplierId: string;
  deliveryDate: string;
  noteNumber: string;
  lines: SupplierDeliveryNoteLineDto[];
}>({ supplierId: '', deliveryDate: new Date().toISOString().slice(0, 10), noteNumber: '', lines: [] });

const rejectVisible = ref(false);
const rejectForm = reactive({ code: '', note: '' });
const moneyFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 4,
});

const editable = computed(() => isManual.value || note.value?.status === 'DRAFT');
const canReject = computed(() => !!rejectForm.code && (rejectForm.code !== 'OTHER' || !!rejectForm.note));
const operatorLabel = computed(() => {
  const username = authStore.user?.username || '当前账号';
  return `${username} / ${authStore.currentRole}`;
});

const materialNameMap = computed<Record<string, string>>(() => {
  const m: Record<string, string> = {};
  materialTypes.value.forEach((x) => (m[x.id] = x.name));
  return m;
});

const totalAmount = computed(() =>
  form.lines.reduce((sum, l) => sum + (Number(l.lineAmount) || 0), 0).toFixed(2),
);

const unexplainedPriceAnomalies = computed(() =>
  form.lines.filter((l) => l.priceAnomalyFlag && isPriceAnomalyMissing(l)),
);

const priceAnomalyRows = computed(() =>
  form.lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => line.priceAnomalyFlag),
);

const approvalStatus = computed<PriceAnomalyApprovalStatus>(() =>
  note.value?.priceAnomalyApprovalStatus || 'NONE',
);

const BOSS_ROLES = new Set(['factory_super_admin', 'restaurant_manager', 'platform_admin']);
const canBossApprove = computed(() => BOSS_ROLES.has(authStore.currentRole || ''));
const canSubmitApproval = computed(() =>
  editable.value
  && priceAnomalyRows.value.length > 0
  && unexplainedPriceAnomalies.value.length === 0
  && (approvalStatus.value === 'NONE' || approvalStatus.value === 'REJECTED'),
);

const approvalStatusText = computed(() => ({
  NONE: '未提交审批',
  PENDING: '等待老板审批',
  APPROVED: '老板已批准',
  REJECTED: '老板已驳回',
}[approvalStatus.value] || approvalStatus.value));

const approvalAlertType = computed(() => {
  if (approvalStatus.value === 'APPROVED') return 'success';
  if (approvalStatus.value === 'REJECTED') return 'error';
  if (approvalStatus.value === 'PENDING') return 'warning';
  return 'info';
});

function statusText(s?: string): string {
  return { DRAFT: '草稿', CONFIRMED: '已确认', REJECTED: '已拒绝' }[s || ''] || s || '';
}
function statusTagType(s?: string): string {
  return { DRAFT: 'info', CONFIRMED: 'success', REJECTED: 'danger' }[s || ''] || 'info';
}

function postingStatusText(s?: DeliveryPostingStatus | null): string {
  const normalized = s === 'UNPOSTED' || s === 'POSTING' ? 'PENDING' : s;
  return {
    PENDING: 'PENDING 待生成库存批次',
    POSTED: 'POSTED 已生成库存批次',
    FAILED: 'FAILED 过账失败',
  }[normalized || 'PENDING'] || String(s || 'PENDING');
}

function postingTagType(s?: DeliveryPostingStatus | null): string {
  if (s === 'POSTED') return 'success';
  if (s === 'FAILED') return 'danger';
  if (s === 'POSTING') return 'warning';
  return 'info';
}

function syncNote(nextNote?: SupplierDeliveryNoteDto | null) {
  if (!nextNote) return;
  note.value = nextNote;
  form.supplierId = nextNote.supplierId || '';
  form.deliveryDate = nextNote.deliveryDate;
  form.noteNumber = nextNote.noteNumber || '';
  form.lines = (nextNote.lines || []).map((l) => ({ ...l }));
}

/** Rule 3 数字联动: 数量 × 单价 → 金额自动计算。 */
function recalcLine(row: SupplierDeliveryNoteLineDto) {
  if (row.quantity != null && row.unitPrice != null) {
    row.lineAmount = Number((Number(row.quantity) * Number(row.unitPrice)).toFixed(2));
  }
  if (row.unitPrice != null && row.baselineUnitPrice != null && Number(row.baselineUnitPrice) > 0) {
    const rate = (Number(row.unitPrice) - Number(row.baselineUnitPrice)) / Number(row.baselineUnitPrice);
    row.priceVarianceRate = Number(rate.toFixed(4));
    row.priceAnomalyFlag = rate > 0.05;
    if (!row.priceAnomalyFlag) {
      row.priceAnomalyReasonCode = null;
      row.priceAnomalyExplanation = null;
    }
  }
}

function formatVariance(rate?: number | null): string {
  if (rate == null) return '—';
  return `${rate > 0 ? '+' : ''}${(rate * 100).toFixed(1)}%`;
}

function formatMoney(value?: number | null): string {
  if (value == null) return '—';
  return moneyFormatter.format(Number(value));
}

function buildConfirmPreviewMessage(): string {
  const supplierName = note.value?.supplierName
    || suppliers.value.find((x) => x.id === form.supplierId)?.name
    || form.supplierId
    || '未指定供应商';
  const lineSummary = form.lines
    .map((line, index) => {
      const name = line.ingredientName || materialNameMap.value[line.rawMaterialTypeId || ''] || `第 ${index + 1} 行食材`;
      const qty = line.quantity ?? '未填数量';
      const unit = line.unit || '';
      const unitPrice = line.unitPrice != null ? `，单价 ${formatMoney(line.unitPrice)}` : '';
      const amount = line.lineAmount != null ? `，金额 ${formatMoney(line.lineAmount)}` : '';
      return `${index + 1}. ${name}：${qty}${unit}${unitPrice}${amount}`;
    })
    .join('\n');
  return [
    `送货单：${form.noteNumber || note.value?.noteNumber || note.value?.id || noteId.value}`,
    `供应商：${supplierName}`,
    `送货日期：${form.deliveryDate || note.value?.deliveryDate || '未填写'}`,
    `操作人：${operatorLabel.value}`,
    `将生成 ${form.lines.length} 个库存批次，入库仓：${note.value?.warehouseId || '默认餐饮仓库'}`,
    `合计金额：${formatMoney(Number(totalAmount.value))}`,
    '',
    '入库明细：',
    lineSummary || '无行项目',
    '',
    '确认后会生成真实库存批次，并用于后续领料、损耗、盘点扣减。请先核对供应商、数量和单价。'
  ].join('\n');
}

function isPriceAnomalyMissing(line: SupplierDeliveryNoteLineDto): boolean {
  return !String(line.priceAnomalyReasonCode || '').trim()
    || !String(line.priceAnomalyExplanation || '').trim();
}

function addLine() {
  form.lines.push({ ingredientName: '', quantity: null, unit: '', unitPrice: null, lineAmount: null });
}
function removeLine(idx: number) {
  form.lines.splice(idx, 1);
}

function onSupplierChange(id: string) {
  const s = suppliers.value.find((x) => x.id === id);
  if (s) note.value && (note.value.supplierName = s.name);
}

async function loadSuppliers() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(`/${factoryId.value}/suppliers/active`);
    if (resp.success && Array.isArray(resp.data)) suppliers.value = resp.data;
  } catch { suppliers.value = []; }
}

async function loadMaterials() {
  try {
    const resp = await get<Array<{ id: string; name: string }>>(`/${factoryId.value}/raw-material-types/active`);
    if (resp.success && Array.isArray(resp.data)) materialTypes.value = resp.data;
  } catch { materialTypes.value = []; }
}

async function loadDetail() {
  if (isManual.value) {
    // 手动录入预填 query
    form.supplierId = (route.query.supplierId as string) || '';
    form.deliveryDate = (route.query.deliveryDate as string) || new Date().toISOString().slice(0, 10);
    if (form.lines.length === 0) addLine();
    return;
  }
  loading.value = true;
  try {
    const resp = await getNoteDetail(factoryId.value, noteId.value);
    if (resp.success && resp.data) {
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '加载送货单详情失败');
  } finally {
    loading.value = false;
  }
}

async function saveLines() {
  saving.value = true;
  try {
    const resp = await updateNoteLines(factoryId.value, noteId.value, form.lines);
    if (resp.success) {
      ElMessage.success('行项已保存');
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '保存行项失败');
  } finally {
    saving.value = false;
  }
}

async function saveManual() {
  if (form.lines.length === 0 || form.lines.every((l) => !l.ingredientName)) {
    ElMessage.warning('请至少填写一行食材');
    return;
  }
  saving.value = true;
  try {
    const s = suppliers.value.find((x) => x.id === form.supplierId);
    const resp = await createManualNote(factoryId.value, {
      supplierId: form.supplierId || undefined,
      supplierName: s?.name,
      deliveryDate: form.deliveryDate,
      noteNumber: form.noteNumber || undefined,
      lines: form.lines,
    });
    if (resp.success && resp.data) {
      ElMessage.success('录入成功');
      router.replace({ name: 'SupplierDeliveryNoteDetail', params: { id: resp.data.id } });
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '录入失败');
  } finally {
    saving.value = false;
  }
}

async function submitApproval() {
  submittingApproval.value = true;
  try {
    if (editable.value && !isManual.value) {
      const saved = await updateNoteLines(factoryId.value, noteId.value, form.lines);
      if (saved.success && saved.data) syncNote(saved.data);
    }
    const resp = await submitPriceAnomalyApproval(factoryId.value, noteId.value);
    if (resp.success) {
      ElMessage.success('已提交老板审批');
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '提交审批失败');
  } finally {
    submittingApproval.value = false;
  }
}

async function approveAnomaly() {
  approving.value = true;
  try {
    const resp = await approvePriceAnomaly(factoryId.value, noteId.value, {
      comment: approvalComment.value.trim() || undefined,
    });
    if (resp.success) {
      ElMessage.success('已批准价格异常，仓管可确认入库');
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '批准失败');
  } finally {
    approving.value = false;
  }
}

async function rejectAnomaly() {
  if (!approvalComment.value.trim()) {
    ElMessage.warning('驳回时必须填写审批意见');
    return;
  }
  rejectingApproval.value = true;
  try {
    const resp = await rejectPriceAnomaly(factoryId.value, noteId.value, {
      comment: approvalComment.value.trim(),
    });
    if (resp.success) {
      ElMessage.success('已驳回价格异常');
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '驳回失败');
  } finally {
    rejectingApproval.value = false;
  }
}

async function confirm() {
  if (unexplainedPriceAnomalies.value.length > 0) {
    ElMessage({
      message: '进价异常需先填写涨价原因和现场解释，再提交老板审批',
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    priceAnomalyPanelRef.value?.scrollIntoView({ block: 'center' });
    return;
  }
  if (priceAnomalyRows.value.length > 0 && approvalStatus.value !== 'APPROVED') {
    ElMessage({
      message: approvalStatus.value === 'PENDING'
        ? '等待老板审批，暂不能确认入库'
        : '价格异常需先提交并获得老板批准，才能确认入库',
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return;
  }
  if (!note.value) {
    ElMessage.warning('送货单尚未加载完成，请稍后再确认验收入库');
    return;
  }
  try {
    await ElMessageBox.confirm(
      buildConfirmPreviewMessage(),
      '确认验收入库',
      {
        type: 'warning',
        confirmButtonText: '确认入库',
        cancelButtonText: '再核对',
        closeOnClickModal: false,
      },
    );
  } catch {
    return;
  }
  confirming.value = true;
  try {
    if (editable.value && !isManual.value) {
      const saved = await updateNoteLines(factoryId.value, noteId.value, form.lines);
      if (saved.success && saved.data) syncNote(saved.data);
    }
    const resp = await confirmNote(factoryId.value, noteId.value);
    if (resp.success) {
      ElMessage.success('已确认验收入库，库存批次已生成');
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '确认失败');
  } finally {
    confirming.value = false;
  }
}

function openReject() {
  rejectForm.code = '';
  rejectForm.note = '';
  rejectVisible.value = true;
}

async function doReject() {
  rejecting.value = true;
  try {
    const resp = await rejectNote(factoryId.value, noteId.value, {
      rejectReasonCode: rejectForm.code,
      rejectReasonNote: rejectForm.note || undefined,
    });
    if (resp.success) {
      ElMessage.success('已拒绝');
      rejectVisible.value = false;
      syncNote(resp.data);
    }
  } catch (e) {
    handleCatchError(e, '拒绝失败');
  } finally {
    rejecting.value = false;
  }
}

function goBack() {
  router.push({ name: 'SupplierDeliveryNoteList' });
}

onMounted(async () => {
  await Promise.all([loadSuppliers(), loadMaterials()]);
  await loadDetail();
});
</script>

<style scoped lang="scss">
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.head-form {
  margin-bottom: 8px;
}
.posting-failed-alert,
.approval-status-alert {
  margin-bottom: 16px;
}
.approval-comment {
  margin: 0 0 6px;
}
.posting-summary {
  margin-bottom: 16px;
}
.price-anomaly-panel {
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
}
.anomaly-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  h3 {
    margin: 0 0 4px;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.3;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}
.anomaly-item {
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);

  & + & {
    margin-top: 10px;
  }

  &.is-missing {
    border-color: var(--el-color-danger-light-5);
  }
}
.anomaly-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.anomaly-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 12px;
  margin-bottom: 12px;
  color: var(--el-text-color-regular);
  font-size: 13px;

  span {
    min-width: 0;
    overflow-wrap: anywhere;
  }
}
.anomaly-controls {
  display: grid;
  grid-template-columns: minmax(180px, 240px) minmax(280px, 1fr);
  gap: 12px;

  :deep(.el-form-item) {
    margin-bottom: 0;
  }
}
.anomaly-readonly {
  margin: 0;
  color: var(--el-text-color-regular);
}
.mono-text {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  word-break: break-all;
}
.total-row {
  text-align: right;
  margin-top: 12px;
  font-size: 15px;
}
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
.auto-calc {
  color: var(--el-color-primary);
}
@media (max-width: 900px) {
  .anomaly-panel-head,
  .anomaly-controls {
    display: block;
  }

  .anomaly-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .anomaly-controls :deep(.el-form-item) {
    margin-bottom: 12px;
  }
}
</style>
