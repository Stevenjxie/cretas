<template>
  <div class="profile-collections">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mirror-note"
      title="标记为「主」的联系人 / 地址 / 银行账户会同步到基本资料，采购单、打印和出纳付款都以它为准。"
    />

    <!-- ─────────────────────────── 联系人 ─────────────────────────── -->
    <section class="collection">
      <div class="collection-head">
        <h4>联系人<span class="count">（{{ contacts.length }}）</span></h4>
        <el-button v-if="canWrite" type="primary" plain size="small" @click="openContact()">
          新增联系人
        </el-button>
      </div>
      <el-empty
        v-if="!contacts.length"
        :image-size="72"
        description="还没有联系人。至少维护一位，采购单和打印才有人可联系。"
      >
        <el-button v-if="canWrite" type="primary" @click="openContact()">新增联系人</el-button>
      </el-empty>
      <el-table v-else :data="contacts" border stripe size="small">
        <el-table-column label="姓名" min-width="130">
          <template #default="{ row }">
            <el-tag v-if="row.isPrimary" type="success" size="small" effect="dark" class="primary-tag">主</el-tag>
            {{ row.name }}
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ contactTypeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="职务" width="120">
          <template #default="{ row }">{{ row.position || '-' }}</template>
        </el-table-column>
        <el-table-column label="电话" width="150">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="邮箱" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openContact(row)">编辑</el-button>
            <el-button v-if="!row.isPrimary" type="primary" link @click="makeContactPrimary(row)">设为主</el-button>
            <el-button type="danger" link @click="removeContact(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- ──────────────────────────── 地址 ──────────────────────────── -->
    <section class="collection">
      <div class="collection-head">
        <h4>地址<span class="count">（{{ addresses.length }}）</span></h4>
        <el-button v-if="canWrite" type="primary" plain size="small" @click="openAddress()">
          新增地址
        </el-button>
      </div>
      <el-empty
        v-if="!addresses.length"
        :image-size="72"
        description="还没有地址。注册地、发货地、开票地不同时，分开维护可避免采购单印错。"
      >
        <el-button v-if="canWrite" type="primary" @click="openAddress()">新增地址</el-button>
      </el-empty>
      <el-table v-else :data="addresses" border stripe size="small">
        <el-table-column label="标签" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.isPrimary" type="success" size="small" effect="dark" class="primary-tag">主</el-tag>
            {{ row.label || addressTypeLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ addressTypeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address }}</template>
        </el-table-column>
        <el-table-column label="收货联系人" width="160">
          <template #default="{ row }">
            {{ row.contactName || '-' }}<template v-if="row.contactPhone"> · {{ row.contactPhone }}</template>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openAddress(row)">编辑</el-button>
            <el-button v-if="!row.isPrimary" type="primary" link @click="makeAddressPrimary(row)">设为主</el-button>
            <el-button type="danger" link @click="removeAddress(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- ────────────────────────── 银行账户 ────────────────────────── -->
    <section class="collection">
      <div class="collection-head">
        <h4>银行账户<span class="count">（{{ bankAccounts.length }}）</span></h4>
        <el-button v-if="canWrite" type="primary" plain size="small" @click="openBank()">
          新增银行账户
        </el-button>
      </div>
      <el-empty
        v-if="!bankAccounts.length"
        :image-size="72"
        description="还没有银行账户。出纳付款时会以「主」账户为默认收款账号。"
      >
        <el-button v-if="canWrite" type="primary" @click="openBank()">新增银行账户</el-button>
      </el-empty>
      <el-table v-else :data="bankAccounts" border stripe size="small">
        <el-table-column label="户名" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.isPrimary" type="success" size="small" effect="dark" class="primary-tag">主</el-tag>
            {{ row.accountName }}
          </template>
        </el-table-column>
        <el-table-column label="开户行" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.bankName }}<template v-if="row.branchName"> · {{ row.branchName }}</template>
          </template>
        </el-table-column>
        <el-table-column label="账号" min-width="190">
          <template #default="{ row }">{{ row.accountNumber }}</template>
        </el-table-column>
        <el-table-column label="币种" width="80">
          <template #default="{ row }">{{ row.currency || 'CNY' }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openBank(row)">编辑</el-button>
            <el-button v-if="!row.isPrimary" type="primary" link @click="makeBankPrimary(row)">设为主</el-button>
            <el-button type="danger" link @click="removeBank(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- ─────────────────────────── 联系人弹窗 ─────────────────────────── -->
    <el-dialog
      v-model="contactDialogVisible"
      :title="`${contactForm.id ? '编辑' : '新增'}联系人 — ${supplierName}`"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form ref="contactFormRef" :model="contactForm" :rules="contactRules" label-width="100px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="contactForm.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="类型" prop="contactType">
          <el-select v-model="contactForm.contactType" class="full-width">
            <el-option
              v-for="option in SUPPLIER_CONTACT_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="职务">
          <el-input v-model="contactForm.position" maxlength="100" placeholder="选填，例如「销售经理」" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="contactForm.phone" maxlength="40" placeholder="大陆手机号或带区号座机，可含分机" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="contactForm.email" maxlength="100" />
        </el-form-item>
        <el-form-item label="设为主联系人">
          <el-switch v-model="contactForm.isPrimary" :disabled="isOnlyPrimary(contactForm, contacts)" />
          <div class="field-hint">
            {{ primaryHint(contactForm, contacts, '主联系人会同步到基本资料，采购单和打印都用它。') }}
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="contactForm.notes" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitContact">保存</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────────────────── 地址弹窗 ──────────────────────────── -->
    <el-dialog
      v-model="addressDialogVisible"
      :title="`${addressForm.id ? '编辑' : '新增'}地址 — ${supplierName}`"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form ref="addressFormRef" :model="addressForm" :rules="addressRules" label-width="100px">
        <el-form-item label="类型" prop="addressType">
          <el-select v-model="addressForm.addressType" class="full-width">
            <el-option
              v-for="option in SUPPLIER_ADDRESS_TYPE_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="addressForm.label" maxlength="60" placeholder="选填，例如「昆山仓」" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="addressForm.address" type="textarea" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="收货联系人">
          <el-input v-model="addressForm.contactName" maxlength="100" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="addressForm.contactPhone" maxlength="40" />
        </el-form-item>
        <el-form-item label="设为主地址">
          <el-switch v-model="addressForm.isPrimary" :disabled="isOnlyPrimary(addressForm, addresses)" />
          <div class="field-hint">
            {{ primaryHint(addressForm, addresses, '主地址会同步到基本资料，也是溯源产地的来源。') }}
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addressForm.notes" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAddress">保存</el-button>
      </template>
    </el-dialog>

    <!-- ─────────────────────────── 银行账户弹窗 ─────────────────────────── -->
    <el-dialog
      v-model="bankDialogVisible"
      :title="`${bankForm.id ? '编辑' : '新增'}银行账户 — ${supplierName}`"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="bank-warning"
        title="出纳付款单默认打到「主」账户，请核对户名与账号后再保存。"
      />
      <el-form ref="bankFormRef" :model="bankForm" :rules="bankRules" label-width="100px">
        <el-form-item label="户名" prop="accountName">
          <el-input v-model="bankForm.accountName" maxlength="200" :placeholder="`留空默认用「${supplierName}」`" />
        </el-form-item>
        <el-form-item label="开户行" prop="bankName">
          <el-input v-model="bankForm.bankName" maxlength="100" placeholder="例如「中国工商银行」" />
        </el-form-item>
        <el-form-item label="支行/网点">
          <el-input v-model="bankForm.branchName" maxlength="200" placeholder="选填，例如「北京朝阳支行」" />
        </el-form-item>
        <el-form-item label="账号" prop="accountNumber">
          <el-input v-model="bankForm.accountNumber" maxlength="32" placeholder="只填数字，不要带开户行名称或空格" />
        </el-form-item>
        <el-form-item label="币种" prop="currency">
          <el-select v-model="bankForm.currency" class="full-width">
            <el-option v-for="c in CURRENCIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="设为主账户">
          <el-switch v-model="bankForm.isPrimary" :disabled="isOnlyPrimary(bankForm, bankAccounts)" />
          <div class="field-hint">
            {{ primaryHint(bankForm, bankAccounts, '主账户是出纳付款单的默认收款账号。') }}
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="bankForm.notes" type="textarea" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bankDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitBank">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormItemRule } from 'element-plus';
import {
  SUPPLIER_ADDRESS_TYPE_OPTIONS,
  SUPPLIER_CONTACT_TYPE_OPTIONS,
  deleteSupplierAddress,
  deleteSupplierBankAccount,
  deleteSupplierContact,
  listSupplierAddresses,
  listSupplierBankAccounts,
  listSupplierContacts,
  saveSupplierAddress,
  saveSupplierBankAccount,
  saveSupplierContact,
  type SupplierAddress,
  type SupplierBankAccount,
  type SupplierContact,
} from '@/api/supplierManagement';
import { isValidSupplierPhone, isReadableSupplierAddress, normalizeText } from './supplierModel';

const props = defineProps<{
  factoryId: string;
  supplierId: string;
  supplierName: string;
  canWrite: boolean;
}>();

const emit = defineEmits<{ (e: 'changed'): void }>();

const CURRENCIES = ['CNY', 'USD', 'EUR', 'JPY', 'HKD'];

const contacts = ref<SupplierContact[]>([]);
const addresses = ref<SupplierAddress[]>([]);
const bankAccounts = ref<SupplierBankAccount[]>([]);
const saving = ref(false);

const contactDialogVisible = ref(false);
const addressDialogVisible = ref(false);
const bankDialogVisible = ref(false);
const contactFormRef = ref<FormInstance>();
const addressFormRef = ref<FormInstance>();
const bankFormRef = ref<FormInstance>();

const contactForm = reactive<SupplierContact>(emptyContact());
const addressForm = reactive<SupplierAddress>(emptyAddress());
const bankForm = reactive<SupplierBankAccount>(emptyBank());

function emptyContact(): SupplierContact {
  return {
    id: null, name: '', contactType: 'OTHER', phone: '', email: '',
    position: '', isPrimary: false, notes: '', version: null,
  };
}
function emptyAddress(): SupplierAddress {
  return {
    id: null, label: '', addressType: 'BUSINESS', address: '', contactName: '',
    contactPhone: '', isPrimary: false, notes: '', version: null,
  };
}
function emptyBank(): SupplierBankAccount {
  return {
    id: null, accountName: '', bankName: '', branchName: '', accountNumber: '',
    currency: 'CNY', isPrimary: false, notes: '', version: null,
  };
}

const optionalPhoneRule: FormItemRule = {
  validator: (_rule, value, callback) => {
    if (!normalizeText(value) || isValidSupplierPhone(value)) {
      callback();
      return;
    }
    callback(new Error('请输入大陆手机号或带区号座机，可带分机'));
  },
  trigger: ['blur', 'change'],
};

const contactRules: Record<string, FormItemRule[]> = {
  name: [{ required: true, whitespace: true, message: '请输入联系人姓名', trigger: ['blur', 'change'] }],
  phone: [optionalPhoneRule],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: ['blur', 'change'] }],
};

const addressRules: Record<string, FormItemRule[]> = {
  address: [
    { required: true, whitespace: true, message: '请输入地址', trigger: ['blur', 'change'] },
    {
      validator: (_rule, value, callback) => {
        if (isReadableSupplierAddress(value)) {
          callback();
          return;
        }
        callback(new Error('请输入可识别的地址（不超过 500 字，不能只填符号）'));
      },
      trigger: ['blur', 'change'],
    },
  ],
  contactPhone: [optionalPhoneRule],
};

const bankRules: Record<string, FormItemRule[]> = {
  bankName: [{ required: true, whitespace: true, message: '请输入开户行', trigger: ['blur', 'change'] }],
  accountNumber: [
    { required: true, whitespace: true, message: '请输入银行账号', trigger: ['blur', 'change'] },
    {
      validator: (_rule, value, callback) => {
        if (/^[0-9]{8,32}$/.test(normalizeText(value))) {
          callback();
          return;
        }
        callback(new Error('账号只能是 8-32 位数字，请勿包含空格、开户行名称或其他符号'));
      },
      trigger: ['blur', 'change'],
    },
  ],
};

watch(
  () => [props.factoryId, props.supplierId],
  () => { void reload(); },
  { immediate: true },
);

async function reload(): Promise<void> {
  if (!props.factoryId || !props.supplierId) return;
  const [c, a, b] = await Promise.all([
    listSupplierContacts(props.factoryId, props.supplierId),
    listSupplierAddresses(props.factoryId, props.supplierId),
    listSupplierBankAccounts(props.factoryId, props.supplierId),
  ]);
  contacts.value = c;
  addresses.value = a;
  bankAccounts.value = b;
}

function contactTypeLabel(row: SupplierContact): string {
  return row.contactTypeLabel
    || SUPPLIER_CONTACT_TYPE_OPTIONS.find((o) => o.value === row.contactType)?.label
    || '其他';
}

function addressTypeLabel(row: SupplierAddress): string {
  return row.addressTypeLabel
    || SUPPLIER_ADDRESS_TYPE_OPTIONS.find((o) => o.value === row.addressType)?.label
    || '其他';
}

/** 唯一一条 / 当前就是主的那条：不给关掉开关，否则会出现"一条都不是主"。 */
function isOnlyPrimary(form: { id?: string | null; isPrimary?: boolean | null }, list: unknown[]): boolean {
  return Boolean(form.isPrimary) && (list.length <= 1 || Boolean(form.id));
}

function primaryHint(
  form: { id?: string | null; isPrimary?: boolean | null },
  list: unknown[],
  base: string,
): string {
  if (!list.length) return `第一条会自动成为主记录。${base}`;
  if (isOnlyPrimary(form, list)) return `当前已是主记录，要换主请到目标那条点「设为主」。${base}`;
  return base;
}

// ─────────────────────────────── 联系人 ───────────────────────────────

function openContact(row?: SupplierContact): void {
  Object.assign(contactForm, row ? { ...row } : emptyContact());
  contactDialogVisible.value = true;
}

async function submitContact(): Promise<void> {
  if (!contactFormRef.value) return;
  await contactFormRef.value.validate();
  saving.value = true;
  try {
    contacts.value = await saveSupplierContact(props.factoryId, props.supplierId, { ...contactForm });
    contactDialogVisible.value = false;
    ElMessage.success('联系人已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeContactPrimary(row: SupplierContact): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.name}」设为主联系人？基本资料里的联系人、电话、邮箱会同步成它，采购单和打印都会改用它。`,
    '切换主联系人',
    { type: 'warning', confirmButtonText: '设为主联系人' },
  );
  contacts.value = await saveSupplierContact(props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主联系人');
  emit('changed');
}

async function removeContact(row: SupplierContact): Promise<void> {
  await ElMessageBox.confirm(
    row.isPrimary
      ? `「${row.name}」是主联系人，删除后会自动把下一位提升为主，基本资料随之改变。确定删除吗？`
      : `确定删除联系人「${row.name}」吗？`,
    '删除联系人',
    { type: 'warning', confirmButtonText: '删除' },
  );
  contacts.value = await deleteSupplierContact(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('联系人已删除');
  emit('changed');
}

// ──────────────────────────────── 地址 ────────────────────────────────

function openAddress(row?: SupplierAddress): void {
  Object.assign(addressForm, row ? { ...row } : emptyAddress());
  addressDialogVisible.value = true;
}

async function submitAddress(): Promise<void> {
  if (!addressFormRef.value) return;
  await addressFormRef.value.validate();
  saving.value = true;
  try {
    addresses.value = await saveSupplierAddress(props.factoryId, props.supplierId, { ...addressForm });
    addressDialogVisible.value = false;
    ElMessage.success('地址已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeAddressPrimary(row: SupplierAddress): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.label || row.address}」设为主地址？基本资料里的地址会同步成它，溯源产地也按它算。`,
    '切换主地址',
    { type: 'warning', confirmButtonText: '设为主地址' },
  );
  addresses.value = await saveSupplierAddress(props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主地址');
  emit('changed');
}

async function removeAddress(row: SupplierAddress): Promise<void> {
  await ElMessageBox.confirm(
    row.isPrimary
      ? `「${row.label || row.address}」是主地址，删除后会自动把下一条提升为主。确定删除吗？`
      : `确定删除地址「${row.label || row.address}」吗？`,
    '删除地址',
    { type: 'warning', confirmButtonText: '删除' },
  );
  addresses.value = await deleteSupplierAddress(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('地址已删除');
  emit('changed');
}

// ────────────────────────────── 银行账户 ──────────────────────────────

function openBank(row?: SupplierBankAccount): void {
  Object.assign(bankForm, row ? { ...row } : emptyBank());
  bankDialogVisible.value = true;
}

async function submitBank(): Promise<void> {
  if (!bankFormRef.value) return;
  await bankFormRef.value.validate();
  saving.value = true;
  try {
    bankAccounts.value = await saveSupplierBankAccount(props.factoryId, props.supplierId, { ...bankForm });
    bankDialogVisible.value = false;
    ElMessage.success('银行账户已保存');
    emit('changed');
  } finally {
    saving.value = false;
  }
}

async function makeBankPrimary(row: SupplierBankAccount): Promise<void> {
  await ElMessageBox.confirm(
    `把「${row.bankName} ${row.accountNumber}」设为主账户？出纳付款单的默认收款账号会改成它。`,
    '切换主账户',
    { type: 'warning', confirmButtonText: '设为主账户' },
  );
  bankAccounts.value = await saveSupplierBankAccount(
    props.factoryId, props.supplierId, { ...row, isPrimary: true });
  ElMessage.success('已切换主账户');
  emit('changed');
}

async function removeBank(row: SupplierBankAccount): Promise<void> {
  const isLast = bankAccounts.value.length <= 1;
  await ElMessageBox.confirm(
    isLast
      ? `这是最后一个银行账户，删除后基本资料的开户行和账号会一并清空，出纳将看不到默认收款账户。确定删除吗？`
      : row.isPrimary
        ? `「${row.bankName} ${row.accountNumber}」是主账户，删除后会自动把下一条提升为主，出纳的默认收款账号随之改变。确定删除吗？`
        : `确定删除银行账户「${row.bankName} ${row.accountNumber}」吗？`,
    '删除银行账户',
    { type: 'warning', confirmButtonText: '删除' },
  );
  bankAccounts.value = await deleteSupplierBankAccount(props.factoryId, props.supplierId, String(row.id));
  ElMessage.success('银行账户已删除');
  emit('changed');
}

defineExpose({ reload });
</script>

<style scoped lang="scss">
.profile-collections { display: flex; flex-direction: column; gap: 20px; }
.mirror-note { margin-bottom: 4px; }
.collection-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.collection-head h4 { margin: 0; font-size: 15px; }
.collection-head .count { font-weight: 400; color: var(--el-text-color-secondary); }
.primary-tag { margin-right: 6px; }
.field-hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.bank-warning { margin-bottom: 14px; }
.full-width { width: 100%; }
</style>
