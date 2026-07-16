<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  createSystemUnit,
  defaultUnitCode,
  findDuplicateUnit,
  listSystemUnits,
  normalizeUnitIdentity,
  unitAliases,
  type SystemUnit,
  type SystemUnitCategory,
} from '@/api/systemUnits';
import { handleCatchError } from '@/utils/errorToast';

const props = withDefaults(defineProps<{
  modelValue?: string | null;
  factoryId?: string | null;
  placeholder?: string;
  clearable?: boolean;
  disabled?: boolean;
}>(), {
  modelValue: '',
  factoryId: '',
  placeholder: '请选择单位',
  clearable: true,
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  change: [value: string];
  created: [unit: SystemUnit];
}>();

const CREATE_VALUE = '__CREATE_SYSTEM_UNIT__';
const units = ref<SystemUnit[]>([]);
const loading = ref(false);
const query = ref('');
const dialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref();
const form = reactive({
  unitCode: '',
  unitName: '',
  unitSymbol: '',
  category: 'COUNT' as SystemUnitCategory,
});

const activeUnits = computed(() => units.value.filter((unit) => unit.isActive !== false && unit.unitName?.trim()));
const filteredUnits = computed(() => {
  const needle = normalizeUnitIdentity(query.value);
  if (!needle) return activeUnits.value;
  return activeUnits.value.filter((unit) => unitAliases(unit).some((alias) => normalizeUnitIdentity(alias).includes(needle)));
});
const exactMatch = computed(() => findDuplicateUnit(activeUnits.value, [query.value]));
const canCreate = computed(() => Boolean(query.value.trim()) && !exactMatch.value);

const categoryOptions: Array<{ value: SystemUnitCategory; label: string }> = [
  { value: 'COUNT', label: '数量' },
  { value: 'WEIGHT', label: '重量' },
  { value: 'VOLUME', label: '体积' },
  { value: 'LENGTH', label: '长度' },
  { value: 'TEMPERATURE', label: '温度' },
];

const rules = {
  unitCode: [{ required: true, message: '请输入单位代码', trigger: 'blur' }],
  unitName: [{ required: true, message: '请输入单位名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择单位分类', trigger: 'change' }],
};

async function loadUnits(): Promise<void> {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const response = await listSystemUnits(props.factoryId);
    units.value = response.success && Array.isArray(response.data) ? response.data : [];
  } catch (error) {
    handleCatchError(error, '加载计量单位失败');
  } finally {
    loading.value = false;
  }
}

function filterUnits(value: string): void {
  query.value = value;
}

function selectValue(value: string): void {
  if (value === CREATE_VALUE) {
    openCreateDialog(query.value);
    return;
  }
  emit('update:modelValue', value || '');
  emit('change', value || '');
  query.value = '';
}

function openCreateDialog(name: string): void {
  const trimmed = name.trim();
  form.unitName = trimmed;
  form.unitCode = defaultUnitCode(trimmed);
  form.unitSymbol = trimmed;
  form.category = 'COUNT';
  dialogVisible.value = true;
}

function selectExisting(unit: SystemUnit): void {
  const value = unit.unitName.trim();
  emit('update:modelValue', value);
  emit('change', value);
}

async function submitUnit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !props.factoryId) return;
  const duplicate = findDuplicateUnit(units.value, [form.unitCode, form.unitName, form.unitSymbol]);
  if (duplicate) {
    selectExisting(duplicate);
    ElMessage.warning(`单位已存在，已选择「${duplicate.unitName}」`);
    dialogVisible.value = false;
    return;
  }

  submitting.value = true;
  try {
    const response = await createSystemUnit(props.factoryId, {
      unitCode: form.unitCode.trim(),
      unitName: form.unitName.trim(),
      unitSymbol: form.unitSymbol.trim() || form.unitName.trim(),
      category: form.category,
      baseUnit: form.unitCode.trim(),
      conversionFactor: 1,
      decimalPlaces: form.category === 'COUNT' ? 0 : 3,
      isBaseUnit: true,
      isActive: true,
      isSystem: false,
      sortOrder: 100,
    });
    if (!response.success || !response.data) return;
    await loadUnits();
    const created = findDuplicateUnit(units.value, [response.data.unitCode, response.data.unitName]) || response.data;
    selectExisting(created);
    emit('created', created);
    dialogVisible.value = false;
    ElMessage.success(`单位「${created.unitName}」已创建`);
  } catch (error) {
    handleCatchError(error, '创建计量单位失败');
  } finally {
    submitting.value = false;
  }
}

watch(() => props.factoryId, () => void loadUnits());
onMounted(() => void loadUnits());
</script>

<template>
  <div class="unit-select">
    <el-select
      :model-value="modelValue || ''"
      :placeholder="placeholder"
      :clearable="clearable"
      :disabled="disabled"
      :loading="loading"
      filterable
      :filter-method="filterUnits"
      style="width: 100%"
      @change="selectValue"
    >
      <el-option
        v-if="canCreate"
        :key="CREATE_VALUE"
        class="create-unit-option"
        :label="`＋ 新增单位「${query.trim()}」`"
        :value="CREATE_VALUE"
      />
      <el-option
        v-for="unit in filteredUnits"
        :key="unit.unitCode"
        :label="unit.unitSymbol && unit.unitSymbol !== unit.unitName ? `${unit.unitName}（${unit.unitSymbol}）` : unit.unitName"
        :value="unit.unitName"
      />
    </el-select>

    <el-dialog v-model="dialogVisible" title="新增计量单位" width="440px" append-to-body :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="86px">
        <el-form-item label="单位名称" prop="unitName">
          <el-input v-model="form.unitName" maxlength="100" />
        </el-form-item>
        <el-form-item label="单位代码" prop="unitCode">
          <el-input v-model="form.unitCode" maxlength="20" placeholder="唯一代码，如 pcs" />
        </el-form-item>
        <el-form-item label="单位符号">
          <el-input v-model="form.unitSymbol" maxlength="20" placeholder="如 只、kg" />
        </el-form-item>
        <el-form-item label="单位分类" prop="category">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <div class="unit-dialog-tip">代码、名称、符号或别名重复时，将直接选择已有单位。</div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitUnit">创建并选中</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.unit-select { width: 100%; }
.unit-dialog-tip { margin-left: 86px; color: #909399; font-size: 12px; line-height: 1.5; }
:deep(.create-unit-option) { color: #409eff; font-weight: 600; }
</style>
