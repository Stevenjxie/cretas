<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type {
  AvailabilityResourceType,
  DailyAvailability,
  DriverInput,
  LogisticsDriver,
  LogisticsVehicle,
  LogisticsVehicleDriverBinding,
  VehicleDriverRole,
  VehicleProfileUpdate,
  VehicleSourceCode,
} from '@/api/logistics';
import {
  deleteDailyAvailability,
  listDailyAvailability,
  upsertDailyAvailability,
} from '@/api/logistics';
import { useAuthStore } from '@/store/modules/auth';
import { useLogisticsScheduling } from '../useLogisticsScheduling';

type ResourceFilter = 'all' | 'owned' | 'outsourced';

const state = useLogisticsScheduling();
const filter = ref<ResourceFilter>('all');

const filteredVehicles = computed(() => state.vehicles.value.filter((vehicle) => (
  filter.value === 'all'
  || (filter.value === 'owned' && vehicle.source === 'OWNED')
  || (filter.value === 'outsourced' && vehicle.source === 'OUTSOURCED')
)));

onMounted(async () => {
  await state.loadResources();
  await loadAvailability(); // 依赖 vehicles/drivers 已加载 → 合并覆盖生成可编辑行
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

// ==================== 按天可用性录入 ====================
// 排班前，调度员在此标记「今天谁请假 / 哪辆车维修 / 谁临时换班次」。
// 排线算法会读取当天覆盖：不可用的司机/车辆不进算法，班次覆盖替换固定时段。
// 无记录 = 按固定资料默认可用（引入本功能前行为完全不变）。

const authStore = useAuthStore();

/** YYYY-MM-DD（本地时区），默认今天。 */
function todayStr(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const availDate = ref<string>(todayStr());
const availLoading = ref(false);
/** 当天的覆盖记录，按 `${resourceType}:${resourceId}` 索引。 */
const overrideMap = ref<Record<string, DailyAvailability>>({});

interface AvailRow {
  key: string;
  resourceType: AvailabilityResourceType;
  resourceId: string;
  name: string;
  sub: string; // 车牌下的车厢 / 司机的电话
  fixedShift: string; // 固定资料时段（作为默认参考）
  available: boolean;
  shiftStart: string; // 覆盖班次起（空=用固定）
  shiftEnd: string;
  overrideId: string | null; // 有覆盖记录时的记录 id（用于删除恢复默认）
}

const availRows = ref<AvailRow[]>([]);

function fmtFixedShift(from?: string | null, to?: string | null): string {
  if (from && to) return `${from}–${to}`;
  if (from) return `${from}起`;
  if (to) return `至${to}`;
  return '全天';
}

/** 合并「所有车辆 + 所有司机」与当天覆盖记录，生成一行一资源的可编辑视图。 */
function rebuildAvailRows(): void {
  const rows: AvailRow[] = [];
  for (const v of state.vehicles.value) {
    const key = `VEHICLE:${v.id}`;
    const ov = overrideMap.value[key];
    rows.push({
      key,
      resourceType: 'VEHICLE',
      resourceId: v.id,
      name: v.plateNumber,
      sub: v.bodyType || `${v.capacityCbm} m³`,
      fixedShift: fmtFixedShift(v.availableFrom, v.availableTo),
      available: ov ? ov.available : true,
      shiftStart: ov?.shiftStart ?? '',
      shiftEnd: ov?.shiftEnd ?? '',
      overrideId: ov?.id ?? null,
    });
  }
  for (const d of state.drivers.value) {
    const key = `DRIVER:${d.id}`;
    const ov = overrideMap.value[key];
    rows.push({
      key,
      resourceType: 'DRIVER',
      resourceId: d.id,
      name: d.name,
      sub: d.phone || '—',
      fixedShift: fmtFixedShift(d.availableFrom, d.availableTo),
      available: ov ? ov.available : true,
      shiftStart: ov?.shiftStart ?? '',
      shiftEnd: ov?.shiftEnd ?? '',
      overrideId: ov?.id ?? null,
    });
  }
  availRows.value = rows;
}

async function loadAvailability(): Promise<void> {
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  availLoading.value = true;
  try {
    const res = await listDailyAvailability(factoryId, availDate.value);
    const map: Record<string, DailyAvailability> = {};
    for (const r of res.data ?? []) {
      map[`${r.resourceType}:${r.resourceId}`] = r;
    }
    overrideMap.value = map;
    rebuildAvailRows();
  } catch {
    ElMessage.error('加载当天可用性失败');
  } finally {
    availLoading.value = false;
  }
}

/** 标记「请假/维修」或恢复可用 —— 立即写后端，让排班读到最新状态。 */
async function toggleAvailable(row: AvailRow, available: boolean): Promise<void> {
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  try {
    // 恢复可用 且 无班次覆盖 → 删除覆盖记录，回归默认（保持表干净）。
    if (available && !row.shiftStart && !row.shiftEnd && row.overrideId) {
      await deleteDailyAvailability(factoryId, row.overrideId);
      ElMessage.success(`${row.name} 已恢复默认可用`);
    } else {
      await upsertDailyAvailability(factoryId, {
        resourceType: row.resourceType,
        resourceId: row.resourceId,
        availDate: availDate.value,
        available,
        shiftStart: row.shiftStart || null,
        shiftEnd: row.shiftEnd || null,
      });
      ElMessage.success(available ? `${row.name} 已标记可用` : `${row.name} 当天已标记不可用`);
    }
    await loadAvailability();
  } catch {
    ElMessage.error('保存失败，请重试');
    await loadAvailability(); // 回滚 UI 到后端真实状态
  }
}

/** 保存临时班次覆盖（起止时段任意一个填了即写覆盖）。 */
async function saveShiftOverride(row: AvailRow): Promise<void> {
  const factoryId = authStore.factoryId;
  if (!factoryId) return;
  try {
    await upsertDailyAvailability(factoryId, {
      resourceType: row.resourceType,
      resourceId: row.resourceId,
      availDate: availDate.value,
      available: row.available,
      shiftStart: row.shiftStart || null,
      shiftEnd: row.shiftEnd || null,
    });
    ElMessage.success(`${row.name} 班次已更新`);
    await loadAvailability();
  } catch {
    ElMessage.error('保存失败，请重试');
    await loadAvailability();
  }
}

const unavailableCount = computed(() => availRows.value.filter((r) => !r.available).length);
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

    <el-card shadow="never" class="avail-card">
      <header class="section-header">
        <div>
          <h2>按天可用性</h2>
          <p class="section-hint">
            排班前标记「今天谁请假 / 哪辆车维修 / 谁临时换班次」。
            不可用的司机/车辆当天不进排线；未标记的按固定资料默认可用。
          </p>
        </div>
        <div class="avail-toolbar">
          <el-date-picker
            v-model="availDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            placeholder="选择日期"
            style="width: 150px"
            @change="loadAvailability"
          />
          <el-tag v-if="unavailableCount" type="warning" effect="plain">当天 {{ unavailableCount }} 个不可用</el-tag>
          <el-tag v-else type="success" effect="plain">当天全部可用</el-tag>
        </div>
      </header>
      <el-table v-loading="availLoading" :data="availRows" stripe row-key="key" size="small">
        <el-table-column label="类型" min-width="70">
          <template #default="{ row }">
            <el-tag :type="row.resourceType === 'VEHICLE' ? 'primary' : 'info'" effect="plain" size="small">
              {{ row.resourceType === 'VEHICLE' ? '车辆' : '司机' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }"><strong>{{ row.name }}</strong><span class="row-sub">{{ row.sub }}</span></template>
        </el-table-column>
        <el-table-column label="固定时段" min-width="100"><template #default="{ row }">{{ row.fixedShift }}</template></el-table-column>
        <el-table-column label="当天可用" min-width="120">
          <template #default="{ row }">
            <el-switch
              v-model="row.available"
              active-text="可用"
              inactive-text="请假/维修"
              inline-prompt
              @change="(val: boolean) => toggleAvailable(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="临时班次（可选，覆盖固定时段）" min-width="220">
          <template #default="{ row }">
            <div class="shift-cell" :class="{ disabled: !row.available }">
              <el-input v-model="row.shiftStart" placeholder="起 08:00" :disabled="!row.available" style="width: 84px" />
              <span>至</span>
              <el-input v-model="row.shiftEnd" placeholder="止 18:00" :disabled="!row.available" style="width: 84px" />
              <el-button link type="primary" :disabled="!row.available" @click="saveShiftOverride(row)">保存班次</el-button>
            </div>
          </template>
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
.section-hint { margin: 6px 0 0; max-width: 640px; color: #667085; font-size: 12.5px; line-height: 1.5; }
.avail-toolbar { display: flex; align-items: center; gap: 12px; }
.avail-card .section-header { align-items: flex-start; }
.row-sub { margin-left: 8px; color: #98a2b3; font-size: 12px; }
.shift-cell { display: flex; align-items: center; gap: 6px; }
.shift-cell.disabled { opacity: 0.5; }
.binding-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.time-range { display: flex; align-items: center; gap: 8px; }
@media (max-width: 720px) { .support-page { padding: 16px; }.page-header { flex-direction: column; } }
</style>
