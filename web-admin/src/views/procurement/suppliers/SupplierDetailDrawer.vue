<template>
  <el-drawer
    :model-value="modelValue"
    size="760px"
    :before-close="beforeClose"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="drawer-header">
        <div>
          <div class="drawer-title">{{ detail?.name || supplier?.name || '供应商详情' }}</div>
          <div class="drawer-meta">
            {{ detail?.supplierCode || detail?.code || '-' }}
            <el-tag :type="isActive ? 'success' : 'info'" size="small">{{ isActive ? '合作中' : '暂停合作' }}</el-tag>
            <el-tag v-if="detail && !supplierProfileComplete(detail)" type="warning" size="small">资料不完整</el-tag>
          </div>
        </div>
        <div v-if="canWrite" class="header-actions">
          <el-button v-if="!editing" @click="startEdit">编辑资料</el-button>
          <el-button v-if="!editing" :type="isActive ? 'warning' : 'success'" plain @click="changeStatus">
            {{ isActive ? '暂停合作' : '恢复合作' }}
          </el-button>
        </div>
      </div>
    </template>

    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="detail">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本资料" name="profile">
          <el-alert
            v-if="!supplierProfileComplete(detail) && !editing"
            title="资料不完整：历史数据可继续查看，编辑保存时必须补齐联系人、电话和地址。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-form
            v-if="editing"
            ref="formRef"
            :model="form"
            :rules="supplierFormRules"
            label-width="110px"
            class="profile-form"
          >
            <el-form-item label="供应商名称" prop="name"><el-input v-model="form.name" maxlength="200" /></el-form-item>
            <el-form-item label="联系人" prop="contactPerson"><el-input v-model="form.contactPerson" maxlength="100" /></el-form-item>
            <el-form-item label="联系电话" prop="phone"><el-input v-model="form.phone" maxlength="40" /></el-form-item>
            <el-form-item label="地址" prop="address"><el-input v-model="form.address" type="textarea" maxlength="500" show-word-limit /></el-form-item>
            <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="100" /></el-form-item>
            <el-form-item label="银行账户"><el-input v-model="form.bankAccount" maxlength="100" /></el-form-item>
            <el-form-item label="税号"><el-input v-model="form.taxNumber" maxlength="50" /></el-form-item>
            <el-form-item label="备注"><el-input v-model="form.notes" type="textarea" maxlength="5000" show-word-limit /></el-form-item>
            <div class="edit-actions">
              <el-button @click="cancelEdit">取消</el-button>
              <el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button>
            </div>
          </el-form>
          <el-descriptions v-else :column="2" border class="profile-descriptions">
            <el-descriptions-item label="供应商名称">{{ detail.name || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="联系人">{{ detail.contactPerson || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.phone || detail.contactPhone || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ detail.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="地址" :span="2">{{ detail.address || '资料不完整' }}</el-descriptions-item>
            <el-descriptions-item label="银行账户">{{ detail.bankAccount || '-' }}</el-descriptions-item>
            <el-descriptions-item label="税号">{{ detail.taxNumber || detail.taxId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">{{ detail.notes || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <el-tab-pane label="供应原料" name="materials">
          <el-alert
            v-if="!isActive"
            type="warning"
            :closable="false"
            show-icon
            title="供应商已暂停合作，供应原料关系仅可查看；恢复合作后才能新增或修改。"
            class="relation-status-alert"
          />
          <div class="tab-toolbar">
            <span>独立供应关系主数据；采购历史在“采购记录”中展示。</span>
            <el-button
              v-if="canWrite"
              type="primary"
              plain
              :disabled="!isActive"
              @click="openRelationCreate"
            >管理供应原料</el-button>
          </div>
          <el-table :data="materialRelations" border stripe empty-text="暂无供应原料关系">
            <el-table-column label="物料" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.materialName || row.materialTypeId }}</div>
                <small>{{ row.materialCode || row.materialTypeId }}</small>
              </template>
            </el-table-column>
            <el-table-column label="供应商料号" width="130">
              <template #default="{ row }">{{ row.supplierMaterialCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="默认采购价" width="145" align="right">
              <template #default="{ row }">
                {{ row.defaultPurchasePrice == null ? '未设置' : `${row.currency || 'CNY'} ${Number(row.defaultPurchasePrice).toFixed(2)}/${displayUnit(row.purchaseUnit)}` }}
              </template>
            </el-table-column>
            <el-table-column label="起订量" width="105" align="right">
              <template #default="{ row }">{{ row.minOrderQuantity ?? '-' }} {{ displayUnit(row.purchaseUnit) }}</template>
            </el-table-column>
            <el-table-column label="交期" width="85">
              <template #default="{ row }">{{ row.leadTimeDays == null ? '-' : `${row.leadTimeDays}天` }}</template>
            </el-table-column>
            <el-table-column label="关系状态" width="105">
              <template #default="{ row }">
                <el-tag :type="row.active === false ? 'info' : 'success'" size="small">
                  {{ row.active === false ? '已停用' : row.preferred ? '首选' : '启用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="openSpecManager(row)">采购规格</el-button>
                <el-button v-if="canWrite && isActive" type="primary" link @click="openRelationEdit(row)">编辑</el-button>
                <el-button v-if="canWrite && isActive && row.active !== false" type="warning" link @click="removeRelation(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="价格/报价" name="prices">
          <el-empty description="当前后端未提供独立供应商报价台账；历史价格仍保留在采购单据中。" />
        </el-tab-pane>
        <el-tab-pane label="采购记录" name="purchases">
          <el-table :data="history" border stripe empty-text="暂无采购记录摘要">
            <el-table-column prop="materialName" label="物料" min-width="180" />
            <el-table-column prop="purchaseCount" label="采购次数" width="100" />
            <el-table-column prop="lastPurchaseDate" label="最近采购日期" width="140" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="对账/应付" name="payables">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="采购单数">{{ detail.totalOrders ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近采购">{{ detail.lastOrderDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="累计采购金额">{{ formatMoney(detail.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="当前应付余额">{{ formatMoney(detail.currentBalance) }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="变更记录" name="changes">
          <el-timeline>
            <el-timeline-item v-if="detail.createdAt" :timestamp="detail.createdAt">
              创建供应商 · {{ detail.createdByName || '系统记录' }}
            </el-timeline-item>
            <el-timeline-item v-if="detail.updatedAt" :timestamp="detail.updatedAt" type="primary">
              最近更新
            </el-timeline-item>
          </el-timeline>
          <el-empty v-if="!detail.createdAt && !detail.updatedAt" description="暂无可展示的变更记录" />
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog
      v-model="relationDialogVisible"
      :title="relationEditingId ? '编辑供应原料关系' : '新增供应原料关系'"
      width="620px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-width="120px">
        <el-form-item label="物料" required>
          <el-select v-model="relationForm.materialTypeId" filterable :disabled="Boolean(relationEditingId)" style="width: 100%" @change="onRelationMaterialChange">
            <el-option
              v-for="material in materialOptions"
              :key="material.id"
              :label="`${material.name} (${material.code || material.id})`"
              :value="material.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商料号"><el-input v-model="relationForm.supplierMaterialCode" maxlength="100" /></el-form-item>
        <el-form-item label="采购单位" required>
          <UnitSelect
            v-model="relationForm.purchaseUnit"
            :factory-id="factoryId"
            usage-scope="PURCHASE_QUANTITY"
            placeholder="请选择采购单位"
          />
          <div class="field-hint">采购订单将沿用该单位；系统内部保存标准单位代码，页面只显示中文业务单位。</div>
        </el-form-item>
        <el-form-item :label="`默认采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`">
          <el-input-number v-model="relationForm.defaultPurchasePrice" :min="0" :precision="4" controls-position="right" style="width: 100%" />
          <div class="field-hint">价格与采购单位绑定；未配置时采购单会明确提示，不会静默按 0 元处理。</div>
        </el-form-item>
        <el-form-item label="币种" required><el-input v-model="relationForm.currency" maxlength="10" /></el-form-item>
        <el-form-item label="最小起订量"><el-input-number v-model="relationForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 100%" /></el-form-item>
        <el-form-item label="交期（天）"><el-input-number v-model="relationForm.leadTimeDays" :min="0" :precision="0" style="width: 100%" /></el-form-item>
        <el-form-item label="首选供应商"><el-switch v-model="relationForm.preferred" /></el-form-item>
        <el-form-item label="关系启用"><el-switch v-model="relationForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="relationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="relationSaving" @click="saveRelation">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="specDialogVisible"
      title="采购规格"
      width="760px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert
        :closable="false"
        type="info"
        show-icon
        :title="`${specRelation?.materialName || '当前物料'} · 库存基本单位 ${displayUnit(specRelation?.baseUnit)}`"
        description="采购订单选择规格后按包装数量下单，系统按换算系数折合库存基本单位；换算系数由后端快照锁定。"
        style="margin-bottom: 12px"
      />
      <el-table :data="purchaseSpecs" border stripe empty-text="暂无采购规格；采购订单将按库存基本单位直采">
        <el-table-column prop="name" label="规格名称" min-width="130" />
        <el-table-column label="采购包装单位" width="120"><template #default="{ row }">{{ displayUnit(row.purchasePackageUnit) }}</template></el-table-column>
        <el-table-column label="换算" min-width="150"><template #default="{ row }">1 {{ displayUnit(row.purchasePackageUnit) }} = {{ row.factor }} {{ displayUnit(row.inventoryBaseUnit) }}</template></el-table-column>
        <el-table-column label="报价" width="150"><template #default="{ row }">{{ row.quotedPrice == null ? '-' : `${row.currency || 'CNY'} ${Number(row.quotedPrice).toFixed(2)}/${displayUnit(row.purchasePackageUnit)}` }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.active === false ? 'info' : 'success'">{{ row.active === false ? '停用' : row.defaultSpec ? '默认' : '启用' }}</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite && isActive" label="操作" width="130">
          <template #default="{ row }">
            <el-button type="primary" link @click="editPurchaseSpec(row)">编辑</el-button>
            <el-button v-if="row.active !== false" type="warning" link @click="removePurchaseSpec(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider v-if="canWrite && isActive">{{ specEditingId ? '编辑规格' : '新增规格' }}</el-divider>
      <el-form v-if="canWrite && isActive" label-width="125px" :model="specForm">
        <el-form-item label="规格名称" required><el-input v-model="specForm.name" maxlength="100" placeholder="例如 10kg/箱" /></el-form-item>
        <el-form-item label="采购包装单位" required>
          <UnitSelect v-model="specForm.purchasePackageUnit" :factory-id="factoryId" usage-scope="PURCHASE_QUANTITY" />
        </el-form-item>
        <el-form-item label="换算系数" required>
          <el-input-number v-model="specForm.factor" :min="0.0001" :precision="4" style="width: 220px" />
          <span class="field-hint">1 {{ displayUnit(specForm.purchasePackageUnit) }} = {{ specForm.factor || '?' }} {{ displayUnit(specRelation?.baseUnit) }}</span>
        </el-form-item>
        <el-form-item :label="`未税报价（元/${displayUnit(specForm.purchasePackageUnit) || '采购包装单位'}）`"><el-input-number v-model="specForm.quotedPrice" :min="0" :precision="4" style="width: 220px" /></el-form-item>
        <el-form-item label="币种" required><el-input v-model="specForm.currency" maxlength="10" style="width: 220px" /></el-form-item>
        <el-form-item label="最小起订量"><el-input-number v-model="specForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 220px" /></el-form-item>
        <el-form-item label="交期（天）"><el-input-number v-model="specForm.leadTimeDays" :min="0" :precision="0" style="width: 220px" /></el-form-item>
        <el-form-item label="默认规格"><el-switch v-model="specForm.defaultSpec" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="specForm.active" /></el-form-item>
        <el-form-item>
          <el-button v-if="specEditingId" @click="resetSpecForm">取消编辑</el-button>
          <el-button type="primary" :loading="specSaving" @click="savePurchaseSpec">{{ specEditingId ? '保存规格' : '新增规格' }}</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { get } from '@/api/request';
import UnitSelect from '@/components/common/UnitSelect.vue';
import {
  getSupplier,
  listSupplierMaterials,
  createSupplierMaterial,
  updateSupplierMaterial,
  deleteSupplierMaterial,
  listSupplierPurchaseSpecs,
  createSupplierPurchaseSpec,
  updateSupplierPurchaseSpec,
  deleteSupplierPurchaseSpec,
  updateSupplier,
  updateSupplierStatus,
  type SupplierMaterialPayload,
  type SupplierMaterialRelation,
  type SupplierPurchaseSpec,
  type SupplierPurchaseSpecPayload,
  type SupplierRecord,
  type SupplierSavePayload,
} from '@/api/supplierManagement';
import { displayUnit } from '@/utils/unitPricing';
import { supplierFormRules, supplierProfileComplete, supplierStatus, normalizeSupplierPayload } from './supplierModel';
import { toSupplierHistoryViewRow, type SupplierHistoryApiRow, type SupplierHistoryViewRow } from './supplierHistory';

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  supplier: SupplierRecord | null;
  canWrite: boolean;
  initialTab?: string;
}>();
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'changed'): void }>();

const loading = ref(false);
const saving = ref(false);
const editing = ref(false);
const activeTab = ref('profile');
const detail = ref<SupplierRecord | null>(null);
const history = ref<SupplierHistoryViewRow[]>([]);
const materialRelations = ref<SupplierMaterialRelation[]>([]);
const materialOptions = ref<Array<{ id: string; name: string; code?: string | null; unit?: string | null }>>([]);
const relationDialogVisible = ref(false);
const relationEditingId = ref('');
const relationSaving = ref(false);
const relationForm = reactive<SupplierMaterialPayload>({
  materialTypeId: '', supplierMaterialCode: '', defaultPurchasePrice: null, currency: 'CNY', purchaseUnit: '',
  minOrderQuantity: null, leadTimeDays: null, preferred: false, active: true,
});
const specDialogVisible = ref(false);
const specRelation = ref<SupplierMaterialRelation | null>(null);
const purchaseSpecs = ref<SupplierPurchaseSpec[]>([]);
const specEditingId = ref('');
const specSaving = ref(false);
const specForm = reactive<SupplierPurchaseSpecPayload>({
  name: '', purchasePackageUnit: '', inventoryBaseUnit: '', factor: 1, quotedPrice: null, currency: 'CNY',
  minOrderQuantity: null, leadTimeDays: null, defaultSpec: false, active: true,
});
const formRef = ref<FormInstance>();
const originalForm = ref('');
const form = reactive<SupplierSavePayload>({
  name: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
});

const isActive = computed(() => detail.value ? supplierStatus(detail.value) === 'ACTIVE' : false);
const dirty = computed(() => editing.value && JSON.stringify(normalizeSupplierPayload(form)) !== originalForm.value);

watch(() => props.modelValue, (open) => {
  if (open && props.supplier?.id) {
    activeTab.value = props.initialTab || 'profile';
    void loadDetail();
  }
  if (!open) resetState();
});

async function loadDetail(): Promise<void> {
  if (!props.supplier?.id) return;
  loading.value = true;
  try {
    const [supplierData, historyResponse, relationRows] = await Promise.all([
      getSupplier(props.factoryId, props.supplier.id),
      get<SupplierHistoryApiRow[]>(`/${props.factoryId}/suppliers/${props.supplier.id}/history`),
      listSupplierMaterials(props.factoryId, props.supplier.id),
    ]);
    detail.value = supplierData;
    history.value = (historyResponse.data ?? []).map(toSupplierHistoryViewRow);
    materialRelations.value = relationRows;
  } finally {
    loading.value = false;
  }
}

function fillForm(): void {
  if (!detail.value) return;
  Object.assign(form, {
    name: detail.value.name ?? '',
    contactPerson: detail.value.contactPerson ?? '',
    phone: detail.value.phone || detail.value.contactPhone || '',
    address: detail.value.address ?? '',
    email: detail.value.email ?? '',
    bankAccount: detail.value.bankAccount ?? '',
    taxNumber: detail.value.taxNumber || detail.value.taxId || '',
    notes: detail.value.notes ?? '',
    version: detail.value.version ?? undefined,
  });
  originalForm.value = JSON.stringify(normalizeSupplierPayload(form));
}

function startEdit(): void {
  fillForm();
  editing.value = true;
  activeTab.value = 'profile';
}

async function saveProfile(): Promise<void> {
  if (!detail.value || !formRef.value) return;
  await formRef.value.validate();
  saving.value = true;
  try {
    detail.value = await updateSupplier(props.factoryId, detail.value.id, normalizeSupplierPayload(form));
    editing.value = false;
    ElMessage.success('供应商资料已更新');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function cancelEdit(): Promise<void> {
  if (dirty.value) {
    await ElMessageBox.confirm('尚有未保存修改，确定放弃吗？', '放弃修改', { type: 'warning' });
  }
  editing.value = false;
}

async function beforeClose(done: () => void): Promise<void> {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('尚有未保存修改，关闭后将丢失，确定关闭吗？', '未保存修改', { type: 'warning' });
    } catch { return; }
  }
  done();
}

async function changeStatus(): Promise<void> {
  if (!detail.value) return;
  const active = !isActive.value;
  const action = active ? '恢复合作' : '暂停合作';
  try {
    const prompt = await ElMessageBox.prompt(
      active ? '请填写恢复合作原因，恢复后可继续新建采购业务。' : '请填写暂停合作原因，历史单据仍可查看。',
      action,
      { inputPlaceholder: `请输入${action}原因`, inputValidator: (value) => Boolean(value?.trim()) || '原因不能为空' },
    );
    detail.value = await updateSupplierStatus(props.factoryId, detail.value, active, prompt.value);
    ElMessage.success(`${action}成功`);
    emit('changed');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') console.error('Supplier status update failed', error);
  }
}

function resetState(): void {
  editing.value = false;
  activeTab.value = 'profile';
  detail.value = null;
  history.value = [];
  materialRelations.value = [];
  originalForm.value = '';
}

async function ensureMaterialOptions(): Promise<void> {
  if (materialOptions.value.length) return;
  const response = await get<Array<{ id: string; name: string; code?: string | null; unit?: string | null }>>(
    `/${props.factoryId}/raw-material-types/active`,
  );
  materialOptions.value = response.data ?? [];
}

async function openRelationCreate(): Promise<void> {
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能新增供应原料关系');
  await ensureMaterialOptions();
  relationEditingId.value = '';
  Object.assign(relationForm, {
    materialTypeId: '', supplierMaterialCode: '', defaultPurchasePrice: null, currency: 'CNY', purchaseUnit: '',
    minOrderQuantity: null, leadTimeDays: null, preferred: false, active: true, version: undefined,
  });
  relationDialogVisible.value = true;
}

function onRelationMaterialChange(materialTypeId: string): void {
  if (relationEditingId.value || relationForm.purchaseUnit) return;
  const material = materialOptions.value.find((row) => row.id === materialTypeId);
  relationForm.purchaseUnit = material?.unit || '';
}

async function openRelationEdit(row: SupplierMaterialRelation): Promise<void> {
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能修改供应原料关系');
  await ensureMaterialOptions();
  relationEditingId.value = row.id;
  Object.assign(relationForm, {
    materialTypeId: row.materialTypeId,
    supplierMaterialCode: row.supplierMaterialCode ?? '',
    defaultPurchasePrice: row.defaultPurchasePrice ?? null,
    currency: row.currency || 'CNY',
    purchaseUnit: row.purchaseUnit || row.baseUnit || '',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
    preferred: Boolean(row.preferred),
    active: row.active !== false,
    version: row.version ?? undefined,
  });
  relationDialogVisible.value = true;
}

async function saveRelation(): Promise<void> {
  if (!detail.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能保存供应原料关系');
  if (!relationForm.materialTypeId) return void ElMessage.warning('请选择物料');
  if (!relationForm.purchaseUnit.trim()) return void ElMessage.warning('请输入采购单位');
  if (!relationForm.currency.trim()) return void ElMessage.warning('请输入币种');
  const payload: SupplierMaterialPayload = {
    ...relationForm,
    supplierMaterialCode: relationForm.supplierMaterialCode?.trim(),
    currency: relationForm.currency.trim().toUpperCase(),
    purchaseUnit: relationForm.purchaseUnit.trim(),
  };
  relationSaving.value = true;
  try {
    if (relationEditingId.value) {
      await updateSupplierMaterial(props.factoryId, detail.value.id, relationEditingId.value, payload);
    } else {
      await createSupplierMaterial(props.factoryId, detail.value.id, payload);
    }
    materialRelations.value = await listSupplierMaterials(props.factoryId, detail.value.id);
    relationDialogVisible.value = false;
    ElMessage.success('供应原料关系已保存');
  } finally {
    relationSaving.value = false;
  }
}

async function removeRelation(row: SupplierMaterialRelation): Promise<void> {
  if (!detail.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能修改供应原料关系');
  await ElMessageBox.confirm('确定停用此供应原料关系吗？历史采购记录不会删除，后续可编辑恢复。', '停用关系', { type: 'warning' });
  await deleteSupplierMaterial(props.factoryId, detail.value.id, row.id, row.version);
  materialRelations.value = await listSupplierMaterials(props.factoryId, detail.value.id);
  ElMessage.success('供应原料关系已停用');
}

async function openSpecManager(row: SupplierMaterialRelation): Promise<void> {
  if (!detail.value) return;
  specRelation.value = row;
  purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, row.id);
  resetSpecForm();
  specDialogVisible.value = true;
}

function resetSpecForm(): void {
  specEditingId.value = '';
  Object.assign(specForm, {
    name: '', purchasePackageUnit: '', inventoryBaseUnit: specRelation.value?.baseUnit || '', factor: 1, quotedPrice: null, currency: 'CNY',
    minOrderQuantity: null, leadTimeDays: null, defaultSpec: false, active: true, version: undefined,
  });
}

function editPurchaseSpec(row: SupplierPurchaseSpec): void {
  specEditingId.value = row.id;
  Object.assign(specForm, {
    name: row.name,
    purchasePackageUnit: row.purchasePackageUnit,
    inventoryBaseUnit: row.inventoryBaseUnit,
    factor: Number(row.factor),
    quotedPrice: row.quotedPrice ?? null,
    currency: row.currency || 'CNY',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
    defaultSpec: Boolean(row.defaultSpec),
    active: row.active !== false,
    version: row.version ?? undefined,
  });
}

async function savePurchaseSpec(): Promise<void> {
  if (!detail.value || !specRelation.value) return;
  if (!isActive.value) return void ElMessage.warning('供应商已暂停合作，不能维护采购规格');
  if (!specForm.name.trim()) return void ElMessage.warning('请输入规格名称');
  if (!specForm.purchasePackageUnit) return void ElMessage.warning('请选择采购包装单位');
  if (!(Number(specForm.factor) > 0)) return void ElMessage.warning('换算系数必须大于 0');
  const payload: SupplierPurchaseSpecPayload = {
    ...specForm,
    name: specForm.name.trim(),
    inventoryBaseUnit: specRelation.value.baseUnit || specForm.inventoryBaseUnit,
    currency: specForm.currency.trim().toUpperCase(),
  };
  specSaving.value = true;
  try {
    if (specEditingId.value) {
      await updateSupplierPurchaseSpec(
        props.factoryId, detail.value.id, specRelation.value.id, specEditingId.value, payload,
      );
    } else {
      await createSupplierPurchaseSpec(props.factoryId, detail.value.id, specRelation.value.id, payload);
    }
    purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, specRelation.value.id);
    resetSpecForm();
    ElMessage.success('采购规格已保存');
  } finally {
    specSaving.value = false;
  }
}

async function removePurchaseSpec(row: SupplierPurchaseSpec): Promise<void> {
  if (!detail.value || !specRelation.value) return;
  await ElMessageBox.confirm('停用后，新采购订单不能再选择该规格；历史订单快照不受影响。', '停用采购规格', { type: 'warning' });
  await deleteSupplierPurchaseSpec(
    props.factoryId, detail.value.id, specRelation.value.id, row.id, row.version,
  );
  purchaseSpecs.value = await listSupplierPurchaseSpecs(props.factoryId, detail.value.id, specRelation.value.id);
}

function formatMoney(value: number | null | undefined): string {
  return value == null ? '-' : `¥${Number(value).toFixed(2)}`;
}
</script>

<style scoped lang="scss">
.drawer-header { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-title { color: var(--el-text-color-primary); font-size: 18px; font-weight: 600; }
.drawer-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; color: var(--el-text-color-secondary); font-size: 13px; }
.header-actions, .edit-actions, .tab-toolbar { display: flex; align-items: center; gap: 8px; }
.profile-form { margin-top: 16px; }
.profile-descriptions { margin-top: 16px; }
.edit-actions { justify-content: flex-end; }
.tab-toolbar { justify-content: space-between; margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.contract-alert { margin-top: 12px; }
.field-hint { margin-left: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
