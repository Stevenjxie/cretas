<template>
  <el-select
    :model-value="modelValue"
    clearable
    filterable
    remote
    :remote-method="onSearch"
    :loading="loading"
    placeholder="选择员工"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-option
      v-for="employee in filteredEmployees"
      :key="employee.id"
      :label="employeeLabel(employee)"
      :value="String(employee.id)"
    />
  </el-select>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { listPermissionEmployees } from '@/api/permissionSettings';

interface EmployeeOption {
  id: string | number;
  username?: string;
  fullName?: string;
  realName?: string;
  phone?: string;
  roleCode?: string;
}

const props = defineProps<{
  factoryId: string;
  modelValue?: string;
}>();

defineEmits<{
  (event: 'update:modelValue', value: string): void;
}>();

const loading = ref(false);
const keyword = ref('');
const employees = ref<EmployeeOption[]>([]);

const filteredEmployees = computed(() => {
  const normalized = keyword.value.trim().toLowerCase();
  if (!normalized) return employees.value;
  return employees.value.filter(employee =>
    employeeLabel(employee).toLowerCase().includes(normalized),
  );
});

function employeeLabel(employee: EmployeeOption): string {
  const name = employee.fullName || employee.realName || employee.username || employee.id;
  return `${name} · ${employee.username || '-'} · ${employee.phone || '-'}`;
}

function unwrapUsers(payload: unknown): EmployeeOption[] {
  const value = payload as { data?: unknown; records?: unknown; content?: unknown };
  const data = value?.data ?? payload;
  if (Array.isArray(data)) return data as EmployeeOption[];
  const nested = data as { records?: unknown; content?: unknown };
  if (Array.isArray(nested?.records)) return nested.records as EmployeeOption[];
  if (Array.isArray(nested?.content)) return nested.content as EmployeeOption[];
  return [];
}

async function loadEmployees() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const res = await listPermissionEmployees(props.factoryId, { page: 1, size: 500 });
    employees.value = unwrapUsers(res);
  } finally {
    loading.value = false;
  }
}

function onSearch(value: string) {
  keyword.value = value;
}

onMounted(loadEmployees);
</script>
