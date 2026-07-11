<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type {
  DriverInput,
  LogisticsDriver,
  LogisticsVehicle,
  LogisticsVehicleDriverBinding,
  VehicleDriverRole,
  VehicleProfileUpdate,
  VehicleSourceCode,
} from '@/api/logistics';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

type ResourceFilter = 'all' | 'owned' | 'outsourced';

const state = useLogisticsScheduling();
const filter = ref<ResourceFilter>('all');

const filteredVehicles = computed(() => state.vehicles.value.filter((vehicle) => (
  filter.value === 'all'
  || (filter.value === 'owned' && vehicle.source === 'OWNED')
  || (filter.value === 'outsourced' && vehicle.source === 'OUTSOURCED')
)));

onMounted(() => {
  void state.loadResources();
});

function sourceLabel(source: VehicleSourceCode): string {
  return source === 'OWNED' ? '自有' : '外协';
}

function primaryDriverLabel(vehicle: LogisticsVehicle): string {
  const primary = vehicle.drivers.find((binding) => binding.role === 'PRIMARY');
  return primary?.driverName || (primary ? primary.driverId : '待分配');
}

function backupDriverLabel(vehicle: LogisticsVehicle): string {
  const backups = vehicle.drivers.filter((binding) => binding.role === 'BACKUP');
  if (!backups.length) return '无';
  return backups.map((binding) => binding.driverName || binding.driverId).join('、');
}

// ==================== 编辑车辆 profile ====================

const profileDialogVisible = ref(false);
const profileForm = ref<{
  vehicleId: string;
  capacityCbm: number;
  maxWeightKg: number;
  source: VehicleSourceCode;
  bodyType: string;
  serviceAreasText: string;
  availableFrom: string;
  availableTo: string;
  active: boolean;
  version?: number;
}>({
  vehicleId: '', capacityCbm: 0, maxWeightKg: 0, source: 'OWNED', bodyType: '',
  serviceAreasText: '', availableFrom: '', availableTo: '', active: true,
});

function openProfileDialog(vehicle: LogisticsVehicle): void {
  profileForm.value = {
    vehicleId: vehicle.id,
    capacityCbm: vehicle.capacityCbm,
    maxWeightKg: vehicle.maxWeightKg,
    source: vehicle.source,
    bodyType: vehicle.bodyType ?? '',
    serviceAreasText: vehicle.serviceAreas.join('、'),
    availableFrom: vehicle.availableFrom ?? '',
    availableTo: vehicle.availableTo ?? '',
    active: vehicle.active,
    version: vehicle.version,
  };
  profileDialogVisible.value = true;
}

async function saveProfile(): Promise<void> {
  const { vehicleId, serviceAreasText, ...rest } = profileForm.value;
  const payload: VehicleProfileUpdate = {
    ...rest,
    serviceAreas: serviceAreasText.split(/[、,，]/).map((area) => area.trim()).filter(Boolean),
  };
  const ok = await state.saveVehicleProfile(vehicleId, payload);
  if (ok) {
    ElMessage.success('车辆信息已更新');
    profileDialogVisible.value = false;
  }
}

// ==================== 司机绑定 ====================

const bindingsDialogVisible = ref(false);
const bindingsVehicleId = ref('');
const bindingsVehiclePlate = ref('');
const bindingsForm = ref<LogisticsVehicleDriverBinding[]>([]);

function openBindingsDialog(vehicle: LogisticsVehicle): void {
  bindingsVehicleId.value = vehicle.id;
  bindingsVehiclePlate.value = vehicle.plateNumber;
  bindingsForm.value = vehicle.drivers.map((binding) => ({ ...binding }));
  bindingsDialogVisible.value = true;
}

function addBindingRow(): void {
  bindingsForm.value = [
    ...bindingsForm.value,
    { driverId: '', role: 'PRIMARY' as VehicleDriverRole, shiftStart: '', shiftEnd: '', priority: bindingsForm.value.length },
  ];
}

function removeBindingRow(index: number): void {
  bindingsForm.value = bindingsForm.value.filter((_, i) => i !== index);
}

async function saveBindings(): Promise<void> {
  const validBindings = bindingsForm.value.filter((binding) => binding.driverId);
  const ok = await state.saveVehicleDrivers(bindingsVehicleId.value, validBindings);
  if (ok) {
    ElMessage.success('司机绑定已更新');
    bindingsDialogVisible.value = false;
  }
}

// ==================== 司机管理 ====================

const driverDialogVisible = ref(false);
const editingDriverId = ref<string | null>(null);
const driverForm = ref<DriverInput>({ name: '', phone: '', employmentType: 'OWNED', serviceAreas: [], availableFrom: '', availableTo: '', active: true });
const driverServiceAreasText = ref('');

function openAddDriver(): void {
  editingDriverId.value = null;
  driverForm.value = { name: '', phone: '', employmentType: 'OWNED', serviceAreas: [], availableFrom: '', availableTo: '', active: true };
  driverServiceAreasText.value = '';
  driverDialogVisible.value = true;
}

function openEditDriver(driver: LogisticsDriver): void {
  editingDriverId.value = driver.id;
  driverForm.value = {
    name: driver.name,
    phone: driver.phone ?? '',
    employmentType: driver.employmentType,
    serviceAreas: driver.serviceAreas,
    availableFrom: driver.availableFrom ?? '',
    availableTo: driver.availableTo ?? '',
    active: driver.active,
  };
  driverServiceAreasText.value = driver.serviceAreas.join('、');
  driverDialogVisible.value = true;
}

async function saveDriverForm(): Promise<void> {
  if (!driverForm.value.name.trim()) {
    ElMessage.warning('请填写司机姓名');
    return;
  }
  const payload: DriverInput = {
    ...driverForm.value,
    serviceAreas: driverServiceAreasText.value.split(/[、,，]/).map((area) => area.trim()).filter(Boolean),
  };
  const ok = editingDriverId.value
    ? await state.saveDriver(editingDriverId.value, payload)
    : await state.addDriver(payload);
  if (ok) {
    ElMessage.success(editingDriverId.value ? '司机信息已更新' : '司机已新增');
    driverDialogVisible.value = false;
  }
}
</script>

<template>
  <main class="support-page">
    <header class="page-header">
      <div>
        <h1>车辆与司机</h1>
        <p>查看自有和外协车辆，维护物流专属容量、区域与司机绑定。</p>
      </div>
      <el-radio-group v-model="filter" aria-label="车辆来源筛选">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="owned">自有</el-radio-button>
        <el-radio-button value="outsourced">外协</el-radio-button>
      </el-radio-group>
    </header>

    <el-alert v-if="state.resourcesError.value" type="error" :closable="false" :title="state.resourcesError.value" show-icon />

    <el-card shadow="never">
      <el-table v-loading="state.resourcesLoading.value" :data="filteredVehicles" stripe>
        <el-table-column prop="plateNumber" label="车牌号" min-width="130" />
        <el-table-column label="容量" min-width="90"><template #default="{ row }">{{ row.capacityCbm }} m³</template></el-table-column>
        <el-table-column label="最大载重" min-width="110"><template #default="{ row }">{{ row.maxWeightKg }} kg</template></el-table-column>
        <el-table-column prop="bodyType" label="车厢" min-width="110" />
        <el-table-column label="司机" min-width="110"><template #default="{ row }">{{ primaryDriverLabel(row) }}</template></el-table-column>
        <el-table-column label="备班司机" min-width="150"><template #default="{ row }">{{ backupDriverLabel(row) }}</template></el-table-column>
        <el-table-column label="固定区域" min-width="150"><template #default="{ row }">{{ row.serviceAreas.join('、') || '未设置' }}</template></el-table-column>
        <el-table-column label="来源" min-width="90"><template #default="{ row }"><el-tag :type="row.source === 'OWNED' ? 'primary' : 'warning'" effect="plain">{{ sourceLabel(row.source) }}</el-tag></template></el-table-column>
        <el-table-column label="状态" min-width="90"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'" effect="plain">{{ row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" min-width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProfileDialog(row)">编辑</el-button>
            <el-button link type="primary" @click="openBindingsDialog(row)">司机绑定</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <header class="section-header">
        <h2>司机</h2>
        <el-button type="primary" @click="openAddDriver">新增司机</el-button>
      </header>
      <el-table :data="state.drivers.value" stripe>
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column prop="phone" label="电话" min-width="130" />
        <el-table-column label="用工类型" min-width="100"><template #default="{ row }">{{ row.employmentType === 'OWNED' ? '自有' : '外协' }}</template></el-table-column>
        <el-table-column label="固定区域" min-width="150"><template #default="{ row }">{{ row.serviceAreas.join('、') || '未设置' }}</template></el-table-column>
        <el-table-column label="状态" min-width="90"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'" effect="plain">{{ row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" min-width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openEditDriver(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="profileDialogVisible" title="编辑车辆物流信息" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="容量 (m³)"><el-input-number v-model="profileForm.capacityCbm" :min="0" :precision="1" style="width: 100%" /></el-form-item>
        <el-form-item label="最大载重 (kg)"><el-input-number v-model="profileForm.maxWeightKg" :min="0" style="width: 100%" /></el-form-item>
        <el-form-item label="来源">
          <el-radio-group v-model="profileForm.source">
            <el-radio-button value="OWNED">自有</el-radio-button>
            <el-radio-button value="OUTSOURCED">外协</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="车厢类型"><el-input v-model="profileForm.bodyType" placeholder="如：双温车" /></el-form-item>
        <el-form-item label="固定区域"><el-input v-model="profileForm.serviceAreasText" placeholder="用顿号分隔，如：姑苏、相城" /></el-form-item>
        <el-form-item label="可用时段">
          <div class="time-range">
            <el-input v-model="profileForm.availableFrom" placeholder="08:00" />
            <span>至</span>
            <el-input v-model="profileForm.availableTo" placeholder="18:00" />
          </div>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="profileForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bindingsDialogVisible" :title="`司机绑定 · ${bindingsVehiclePlate}`" width="560px">
      <p class="dialog-hint">支持一车 2-3 个司机；主班 (PRIMARY) 作为默认司机，备班 (BACKUP) 用于替班。</p>
      <div v-for="(binding, index) in bindingsForm" :key="index" class="binding-row">
        <el-select v-model="binding.driverId" placeholder="选择司机" style="width: 160px">
          <el-option v-for="driver in state.drivers.value" :key="driver.id" :label="driver.name" :value="driver.id" />
        </el-select>
        <el-select v-model="binding.role" style="width: 100px">
          <el-option label="主班" value="PRIMARY" />
          <el-option label="备班" value="BACKUP" />
        </el-select>
        <el-input v-model="binding.shiftStart" placeholder="班次起" style="width: 90px" />
        <el-input v-model="binding.shiftEnd" placeholder="班次止" style="width: 90px" />
        <el-button link type="danger" @click="removeBindingRow(index)">移除</el-button>
      </div>
      <el-button plain @click="addBindingRow">+ 添加司机</el-button>
      <template #footer>
        <el-button @click="bindingsDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveBindings">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="driverDialogVisible" :title="editingDriverId ? '编辑司机' : '新增司机'" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="姓名" required><el-input v-model="driverForm.name" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="driverForm.phone" /></el-form-item>
        <el-form-item label="用工类型">
          <el-radio-group v-model="driverForm.employmentType">
            <el-radio-button value="OWNED">自有</el-radio-button>
            <el-radio-button value="OUTSOURCED">外协</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="固定区域"><el-input v-model="driverServiceAreasText" placeholder="用顿号分隔" /></el-form-item>
        <el-form-item label="可用时段">
          <div class="time-range">
            <el-input v-model="driverForm.availableFrom" placeholder="08:00" />
            <span>至</span>
            <el-input v-model="driverForm.availableTo" placeholder="18:00" />
          </div>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="driverForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="driverDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDriverForm">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped lang="scss">
.support-page { display: grid; gap: 20px; max-width: 1440px; min-height: 100%; padding: 24px; margin: 0 auto; background: #f8fafc; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.page-header h1 { margin: 0; color: #101828; font-size: 24px; }.page-header p { margin: 8px 0 0; color: #667085; }
.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; } .section-header h2 { margin: 0; color: #101828; font-size: 18px; }
.dialog-hint { margin: 0 0 12px; color: #667085; font-size: 13px; }
.binding-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.time-range { display: flex; align-items: center; gap: 8px; }
@media (max-width: 720px) { .support-page { padding: 16px; }.page-header { flex-direction: column; } }
</style>
