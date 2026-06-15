<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listVoucherTemplates,
  createVoucherTemplate,
  updateVoucherTemplate,
  deleteVoucherTemplate,
  setVoucherTemplateDefault,
  VOUCHER_TYPE_LABEL,
  DIRECTION_LABEL,
  type VoucherTemplate,
  type VoucherType,
  type TemplateEntry,
  type TemplateEntryDirection,
} from '@/api/voucher';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ==================== State ====================

const loading = ref(false);
const tableData = ref<VoucherTemplate[]>([]);

// Dialog state
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const saving = ref(false);

const emptyEntry = (): TemplateEntry => ({
  sortOrder: null,
  subjectCode: '',
  subjectName: '',
  direction: 'DEBIT',
  amountExpression: '',
  description: '',
  omitWhenZero: false,
});

const formData = reactive<{
  id: string;
  voucherType: VoucherType | '';
  name: string;
  description: string;
  isActive: boolean;
  entries: TemplateEntry[];
}>({
  id: '',
  voucherType: '',
  name: '',
  description: '',
  isActive: true,
  entries: [emptyEntry()],
});

// ==================== Load ====================

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    tableData.value = await listVoucherTemplates(factoryId.value);
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '凭证模板加载失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

// ==================== Dialog ====================

function openCreate() {
  dialogMode.value = 'create';
  formData.id = '';
  formData.voucherType = '';
  formData.name = '';
  formData.description = '';
  formData.isActive = true;
  formData.entries = [emptyEntry()];
  dialogVisible.value = true;
}

function openEdit(row: VoucherTemplate) {
  dialogMode.value = 'edit';
  formData.id = row.id;
  formData.voucherType = row.voucherType;
  formData.name = row.name;
  formData.description = row.description ?? '';
  formData.isActive = row.isActive;
  formData.entries = row.entries.length > 0
    ? row.entries.map((e) => ({ ...e }))
    : [emptyEntry()];
  dialogVisible.value = true;
}

function addEntry() {
  formData.entries.push(emptyEntry());
}

function removeEntry(index: number) {
  if (formData.entries.length <= 1) {
    ElMessage.warning('至少需要一条分录行');
    return;
  }
  formData.entries.splice(index, 1);
}

async function submitForm() {
  if (!formData.name.trim()) { ElMessage.warning('请填写模板名称'); return; }
  if (!formData.voucherType) { ElMessage.warning('请选择凭证类型'); return; }
  const hasInvalidEntry = formData.entries.some(
    (e) => !e.subjectCode.trim() || !e.subjectName.trim(),
  );
  if (hasInvalidEntry) { ElMessage.warning('请填写所有分录行的科目代码和科目名称'); return; }

  saving.value = true;
  try {
    const payload: Partial<VoucherTemplate> = {
      voucherType: formData.voucherType as VoucherType,
      name: formData.name.trim(),
      description: formData.description.trim() || undefined,
      isActive: formData.isActive,
      entries: formData.entries.map((e, idx) => ({
        ...e,
        sortOrder: e.sortOrder ?? idx,
      })),
    };

    if (dialogMode.value === 'create') {
      await createVoucherTemplate(factoryId.value, payload);
      ElMessage.success('凭证模板创建成功');
    } else {
      await updateVoucherTemplate(factoryId.value, formData.id, payload);
      ElMessage.success('凭证模板更新成功');
    }
    dialogVisible.value = false;
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '保存失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    saving.value = false;
  }
}

// ==================== Actions ====================

async function handleDelete(row: VoucherTemplate) {
  try {
    await ElMessageBox.confirm(
      `确认删除凭证模板「${row.name}」？删除后不可恢复。`,
      '确认删除',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    );
    await deleteVoucherTemplate(factoryId.value, row.id);
    ElMessage.success('凭证模板已删除');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage({
      message: e instanceof Error ? e.message : '删除失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

async function handleSetDefault(row: VoucherTemplate) {
  if (row.isDefault) return;
  try {
    await ElMessageBox.confirm(
      `将「${row.name}」设为 ${VOUCHER_TYPE_LABEL[row.voucherType]} 类型的默认模板？` +
        '同类型的其他默认模板将被自动取消。',
      '确认设为默认',
      { confirmButtonText: '确认', cancelButtonText: '取消' },
    );
    await setVoucherTemplateDefault(factoryId.value, row.id);
    ElMessage.success('已设为默认模板');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage({
      message: e instanceof Error ? e.message : '操作失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <span style="font-size:16px;font-weight:600">凭证模板管理</span>
            <span style="font-size:13px;color:#909399;margin-left:12px">
              配置科目↔付款类型映射，凭证生成时优先使用模板
            </span>
          </div>
          <el-button v-if="canWrite" type="primary" @click="openCreate">
            + 新建模板
          </el-button>
        </div>
      </template>

      <!-- 空状态 -->
      <el-empty
        v-if="!loading && tableData.length === 0"
        description="暂无凭证模板。点击「新建模板」配置科目↔付款类型映射，凭证生成时将优先匹配模板规则。"
        style="padding:40px 0"
      />

      <el-table v-else :data="tableData" border stripe>
        <el-table-column prop="name" label="模板名称" min-width="160">
          <template #default="{ row }">
            <span style="font-weight:500">{{ row.name }}</span>
            <el-tag v-if="row.isDefault" type="success" size="small" style="margin-left:6px">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="voucherType" label="凭证类型" width="130" align="center">
          <template #default="{ row }">
            {{ VOUCHER_TYPE_LABEL[row.voucherType as VoucherType] || row.voucherType }}
          </template>
        </el-table-column>
        <el-table-column label="分录数" width="90" align="center">
          <template #default="{ row }">
            {{ row.entries?.length ?? 0 }} 行
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160">
          <template #default="{ row }">
            <span style="color:#606266;font-size:13px">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="isActive" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="canWrite && !row.isDefault"
              link
              type="success"
              size="small"
              @click="handleSetDefault(row)"
            >
              设为默认
            </el-button>
            <el-button
              v-if="canWrite"
              link
              type="danger"
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建凭证模板' : `编辑模板 — ${formData.name}`"
      width="720px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="模板名称" required>
          <el-input
            v-model="formData.name"
            placeholder="例如：销售收款-标准模板"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="凭证类型" required>
          <el-select v-model="formData.voucherType" placeholder="请选择凭证类型" style="width:100%">
            <el-option
              v-for="(label, k) in VOUCHER_TYPE_LABEL"
              :key="k"
              :label="label"
              :value="k"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            placeholder="可选，填写模板用途说明"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="formData.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>

        <!-- 分录行 -->
        <el-divider>分录明细</el-divider>
        <div style="margin-bottom:8px;color:#606266;font-size:13px">
          配置借贷分录规则。amountExpression 使用 SpEL，如
          <code>#order.totalAmount</code>、<code>#payroll.netAmount</code>。
        </div>

        <div
          v-for="(entry, idx) in formData.entries"
          :key="idx"
          style="border:1px solid #e4e7ed;border-radius:4px;padding:12px;margin-bottom:8px;background:#fafafa"
        >
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
            <span style="font-size:13px;font-weight:500;color:#303133">
              第 {{ idx + 1 }} 行
            </span>
            <el-button
              v-if="formData.entries.length > 1"
              link
              type="danger"
              size="small"
              @click="removeEntry(idx)"
            >
              删除此行
            </el-button>
          </div>
          <el-row :gutter="12">
            <el-col :span="5">
              <el-form-item label="科目代码" label-width="70px" required>
                <el-input v-model="entry.subjectCode" placeholder="如 1122" />
              </el-form-item>
            </el-col>
            <el-col :span="7">
              <el-form-item label="科目名称" label-width="70px" required>
                <el-input v-model="entry.subjectName" placeholder="如 应收账款" />
              </el-form-item>
            </el-col>
            <el-col :span="5">
              <el-form-item label="借贷方向" label-width="70px" required>
                <el-select v-model="entry.direction" style="width:100%">
                  <el-option
                    v-for="(label, k) in DIRECTION_LABEL"
                    :key="k"
                    :label="label"
                    :value="k as TemplateEntryDirection"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="7">
              <el-form-item label="金额表达式" label-width="80px">
                <el-input
                  v-model="entry.amountExpression"
                  placeholder="#order.totalAmount"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="14">
              <el-form-item label="摘要描述" label-width="70px">
                <el-input v-model="entry.description" placeholder="可选，凭证打印时显示" />
              </el-form-item>
            </el-col>
            <el-col :span="10">
              <el-form-item label="零时省略" label-width="70px">
                <el-switch
                  v-model="entry.omitWhenZero"
                  active-text="是（税额为0时省略）"
                  inactive-text="否"
                />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <el-button plain @click="addEntry" style="width:100%;margin-top:4px">
          + 添加分录行
        </el-button>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
