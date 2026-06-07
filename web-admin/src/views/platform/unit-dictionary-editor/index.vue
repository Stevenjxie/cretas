<script setup lang="ts">
/**
 * 计量单位字典管理 — T123 单位字典
 *
 * 客户原话: "留个给我自己修改", "经向单位统一走的, 后台自动折算"
 * 功能: 工厂级 UnitOfMeasurement CRUD — 单位名/类别/conversionFactor/启用
 * 后端: GET/POST/PUT/DELETE /api/mobile/{factoryId}/system-config/units
 * 菜单位置: 系统管理 / 平台配置 / 计量单位字典
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';
import { Plus, Refresh } from '@element-plus/icons-vue';

interface UnitOfMeasurement {
  id?: string;
  factoryId?: string;
  unitCode: string;
  unitName: string;
  unitSymbol?: string;
  baseUnit?: string;
  conversionFactor?: number;
  category?: string;
  decimalPlaces?: number;
  isBaseUnit?: boolean;
  isActive?: boolean;
  isSystem?: boolean;
  sortOrder?: number;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// 单位分类选项
const CATEGORY_OPTIONS = [
  { value: 'WEIGHT', label: '重量 (kg/g/t)' },
  { value: 'VOLUME', label: '体积 (L/mL)' },
  { value: 'COUNT', label: '数量 (件/箱/筐/盒/袋/桶)' },
  { value: 'LENGTH', label: '长度 (m/cm)' },
  { value: 'TEMPERATURE', label: '温度 (℃/℉)' },
] as const;

// 状态
const loading = ref(false);
const units = ref<UnitOfMeasurement[]>([]);
const dialogVisible = ref(false);
const formRef = ref();
const isEditing = ref(false);
const submitting = ref(false);

// 分类筛选
const categoryFilter = ref('');

const filteredUnits = computed(() => {
  if (!categoryFilter.value) return units.value;
  return units.value.filter(u => u.category === categoryFilter.value);
});

const formData = ref<UnitOfMeasurement>({
  unitCode: '',
  unitName: '',
  unitSymbol: '',
  baseUnit: 'g',
  conversionFactor: undefined,
  category: 'COUNT',
  decimalPlaces: 2,
  isBaseUnit: false,
  isActive: true,
  isSystem: false,
  sortOrder: 0,
});

const formRules = {
  unitCode: [
    { required: true, message: '请输入单位代码', trigger: 'blur' },
    { max: 20, message: '单位代码不超过 20 个字符', trigger: 'blur' },
    { pattern: /^[\w一-龥]+$/, message: '代码只能包含字母、数字、下划线、中文', trigger: 'blur' },
  ],
  unitName: [
    { required: true, message: '请输入单位名称', trigger: 'blur' },
    { max: 100, message: '单位名称不超过 100 个字符', trigger: 'blur' },
  ],
  category: [
    { required: true, message: '请选择单位分类', trigger: 'change' },
  ],
};

onMounted(() => loadUnits());

async function loadUnits() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<UnitOfMeasurement[]>(`/${factoryId.value}/system-config/units`);
    if (res.success) {
      units.value = res.data || [];
    }
  } catch (e) {
    handleCatchError(e, '加载计量单位失败');
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  formData.value = {
    unitCode: '',
    unitName: '',
    unitSymbol: '',
    baseUnit: 'g',
    conversionFactor: undefined,
    category: 'COUNT',
    decimalPlaces: 2,
    isBaseUnit: false,
    isActive: true,
    isSystem: false,
    sortOrder: 0,
  };
}

function handleAdd() {
  resetForm();
  isEditing.value = false;
  dialogVisible.value = true;
}

function handleEdit(row: UnitOfMeasurement) {
  formData.value = { ...row };
  isEditing.value = true;
  dialogVisible.value = true;
}

async function handleDelete(row: UnitOfMeasurement) {
  if (row.isSystem) {
    ElMessage({ message: '系统内置单位不可删除（可将其停用）', type: 'warning', duration: 0, showClose: true });
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确定删除单位「${row.unitName} (${row.unitCode})」？删除后无法恢复。`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
    const res = await del(`/${factoryId.value}/system-config/units/${row.unitCode}`);
    if (res.success) {
      ElMessage.success('已删除');
      loadUnits();
    }
  } catch (e) {
    if (e !== 'cancel') handleCatchError(e, '删除失败');
  }
}

async function handleSubmit() {
  if (!formRef.value) return;
  try {
    await formRef.value.validate();
    submitting.value = true;
    const payload = { ...formData.value };

    let res;
    if (isEditing.value) {
      res = await put(`/${factoryId.value}/system-config/units/${payload.unitCode}`, payload);
    } else {
      res = await post(`/${factoryId.value}/system-config/units`, payload);
    }

    if (res.success) {
      ElMessage.success(isEditing.value ? '更新成功' : '创建成功');
      dialogVisible.value = false;
      loadUnits();
    }
  } catch (e) {
    handleCatchError(e, isEditing.value ? '更新失败' : '创建失败');
  } finally {
    submitting.value = false;
  }
}

function getCategoryLabel(category?: string) {
  return CATEGORY_OPTIONS.find(c => c.value === category)?.label || category || '-';
}
</script>

<template>
  <div class="unit-dictionary-editor">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">计量单位字典</span>
            <span class="subtitle">管理工厂使用的一级/二级单位 (筐/箱/盒等)</span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="loadUnits">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">
              新增单位
            </el-button>
          </div>
        </div>
      </template>

      <!-- 分类筛选 -->
      <div class="filter-bar">
        <el-radio-group v-model="categoryFilter" @change="() => {}">
          <el-radio-button label="" value="">全部</el-radio-button>
          <el-radio-button
            v-for="cat in CATEGORY_OPTIONS"
            :key="cat.value"
            :label="cat.value"
            :value="cat.value"
          >
            {{ cat.label.split(' ')[0] }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <!-- 单位表格 -->
      <el-table
        :data="filteredUnits"
        v-loading="loading"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="unitCode" label="单位代码" width="120" />
        <el-table-column prop="unitName" label="单位名称" width="120" />
        <el-table-column prop="unitSymbol" label="符号" width="80" align="center">
          <template #default="{ row }">{{ row.unitSymbol || '-' }}</template>
        </el-table-column>
        <el-table-column label="分类" width="130">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ getCategoryLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUnit" label="基础单位" width="100" align="center">
          <template #default="{ row }">{{ row.baseUnit || '-' }}</template>
        </el-table-column>
        <el-table-column prop="conversionFactor" label="转换系数" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.conversionFactor != null">{{ row.conversionFactor }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
              {{ row.isActive ? '启用' : '停用' }}
            </el-tag>
            <el-tag v-if="row.isSystem" size="small" type="warning" style="margin-left:4px">
              内置
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="canWrite" type="primary" link size="small" @click="handleEdit(row)">
              编辑
            </el-button>
            <el-button
              v-if="canWrite"
              type="danger"
              link
              size="small"
              :disabled="row.isSystem"
              :title="row.isSystem ? '系统内置不可删除' : ''"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="units.length === 0 && !loading" class="empty-hint">
        <el-empty description="暂无单位配置，点击「新增单位」添加" />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? `编辑单位: ${formData.unitCode}` : '新增计量单位'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="110px"
      >
        <el-form-item label="单位代码" prop="unitCode">
          <el-input
            v-model="formData.unitCode"
            :disabled="isEditing"
            placeholder="如: kuang / he / xiang"
          />
          <div class="form-tip">代码唯一标识，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="单位名称" prop="unitName">
          <el-input v-model="formData.unitName" placeholder="如: 筐, 盒, 箱" />
          <div class="form-tip">默认单位预设: 筐/箱/件/袋/桶/盒</div>
        </el-form-item>
        <el-form-item label="单位符号">
          <el-input v-model="formData.unitSymbol" placeholder="如: 筐, kg, L" />
        </el-form-item>
        <el-form-item label="单位分类" prop="category">
          <el-select v-model="formData.category" style="width: 100%" placeholder="选择分类">
            <el-option
              v-for="cat in CATEGORY_OPTIONS"
              :key="cat.value"
              :label="cat.label"
              :value="cat.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="基础单位">
          <el-input v-model="formData.baseUnit" placeholder="如: kg, g, L, pcs" />
          <div class="form-tip">同分类的换算基准单位</div>
        </el-form-item>
        <el-form-item label="转换系数">
          <el-input-number
            v-model="formData.conversionFactor"
            :precision="6"
            :min="0"
            :step="0.001"
            style="width: 100%"
            placeholder="相对于基础单位的系数"
          />
          <div class="form-tip">如: 1 筐 = 10 盒 → 转换系数 10 (相对于盒)</div>
        </el-form-item>
        <el-form-item label="小数位数">
          <el-input-number
            v-model="formData.decimalPlaces"
            :min="0"
            :max="6"
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="formData.sortOrder"
            :min="0"
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="formData.isActive"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.unit-dictionary-editor {
  height: 100%;
  width: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .subtitle {
      font-size: 12px;
      color: var(--text-color-secondary, #909399);
    }
  }

  .header-right {
    display: flex;
    gap: 8px;
  }
}

.filter-bar {
  margin-bottom: 16px;
}

.text-muted {
  color: #909399;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.4;
}

.empty-hint {
  padding: 32px 0;
  text-align: center;
}
</style>
