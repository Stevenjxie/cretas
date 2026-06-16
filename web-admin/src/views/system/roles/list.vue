<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { usePermissionStore } from '@/store/modules/permission';
import { useAuthStore } from '@/store/modules/auth';
import { get, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';

const permissionStore = usePermissionStore();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// Permission modules
const allPermissionModules = [
  { key: 'production', label: '生产管理' },
  { key: 'quality', label: '质检管理' },
  { key: 'warehouse', label: '仓库管理' },
  { key: 'procurement', label: '采购管理' },
  { key: 'sales', label: '销售管理' },
  { key: 'hr', label: '人事管理' },
  { key: 'equipment', label: '设备管理' },
  { key: 'finance', label: '财务管理' },
  { key: 'system', label: '系统设置' },
  { key: 'ai', label: 'AI 分析' },
  { key: 'report', label: '报表中心' },
  { key: 'dashboard', label: '数据看板' },
];

// View permissions dialog
const viewDialogVisible = ref(false);
const viewingRole = ref<TableRow | null>(null);
const rolePermissions = ref<TableRow[]>([]);
const permissionsLoading = ref(false);

// Sprint 5 Track G: 数据权限 tab (read-only stub, edit defer Sprint 6)
const viewTab = ref<'function' | 'data'>('function');
// 默认 stub 值: 一线员工 SELF, 其他 ALL — backend V20260519_05 实际写入 role_definitions
function deriveDataScope(roleName: string): string {
  if (['operator', 'warehouse_worker', 'warehouse_operator', 'quality_inspector'].includes(roleName)) {
    return 'SELF';
  }
  return 'ALL';
}
// 用普通 ref + watch viewingRole 更新, 而非 computed (radio v-model 需 writable)
const dataScopeStub = ref<string>('ALL');

// Edit dialog
const editDialogVisible = ref(false);
const editingRole = ref<TableRow | null>(null);
const editForm = reactive({
  displayName: '',
  description: '',
  permissions: {} as Record<string, string>, // module -> 'rw' | 'r' | 'w' | '-'
});
const editSubmitting = ref(false);

async function handleViewPermissions(row: TableRow) {
  viewingRole.value = row;
  viewDialogVisible.value = true;
  viewTab.value = 'function';  // 默认显功能权限 tab
  dataScopeStub.value = deriveDataScope(row.name);  // Sprint 5 Track G stub
  permissionsLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/roles/${row.name}/permissions`);
    if (res.success && res.data) {
      rolePermissions.value = res.data;
    } else {
      // Fallback: derive from role level
      rolePermissions.value = derivePermissions(row);
    }
  } catch {
    // Fallback for missing API
    rolePermissions.value = derivePermissions(row);
  } finally {
    permissionsLoading.value = false;
  }
}

function derivePermissions(role: TableRow) {
  // Sensible defaults based on level
  return allPermissionModules.map(m => {
    let access = '-';
    if (role.level === 1) access = 'rw'; // super admin
    else if (role.name.includes(m.key.replace('ment', '').replace('管理', ''))) access = 'rw';
    else if (role.level <= 2) access = 'r';
    return { module: m.key, label: m.label, access };
  });
}

function getAccessTag(access: string) {
  const map: Record<string, { type: string; label: string }> = {
    rw: { type: 'success', label: '读写' },
    r: { type: 'warning', label: '只读' },
    w: { type: 'info', label: '只写' },
    '-': { type: 'danger', label: '无权限' },
  };
  return map[access] || map['-'];
}

function handleEdit(row: TableRow) {
  editingRole.value = row;
  editForm.displayName = row.displayName;
  editForm.description = row.description;
  // Init permissions from current view or defaults
  const perms = derivePermissions(row);
  editForm.permissions = {};
  perms.forEach(p => {
    editForm.permissions[p.module] = p.access;
  });
  editDialogVisible.value = true;
}

async function handleEditSubmit() {
  // fool-proof Rule 1: 角色名称不能为空, 字段级提示而非静默提交.
  if (!editForm.displayName || !editForm.displayName.trim()) {
    ElMessage.warning('请输入角色名称');
    return;
  }
  editSubmitting.value = true;
  try {
    const payload = {
      displayName: editForm.displayName,
      description: editForm.description,
      permissions: editForm.permissions,
    };
    const res = await put(`/${factoryId.value}/roles/${editingRole.value.name}`, payload);
    if (res && res.success === false) {
      ElMessage.error(res.message || '保存失败');
      return;
    }
    ElMessage.success('保存成功');
    // Update local data
    const role = roles.value.find(r => r.name === editingRole.value.name);
    if (role) {
      role.displayName = editForm.displayName;
      role.description = editForm.description;
    }
    editDialogVisible.value = false;
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('Edit role failed:', error);
  } finally {
    editSubmitting.value = false;
  }
}

// 14种工厂角色数据
const roles = ref([
  {
    id: 1,
    name: 'factory_super_admin',
    displayName: '工厂超管',
    description: '工厂最高权限，管理所有模块',
    userCount: 0,
    level: 1
  },
  {
    id: 2,
    name: 'production_manager',
    displayName: '生产经理',
    description: '管理生产计划和批次，质检统计查看',
    userCount: 0,
    level: 2
  },
  {
    id: 3,
    name: 'workshop_supervisor',
    displayName: '车间主任',
    description: '执行生产任务，管理车间设备',
    userCount: 0,
    level: 3
  },
  {
    id: 4,
    name: 'quality_manager',
    displayName: '质检主管',
    description: '管理质检标准和流程',
    userCount: 0,
    level: 2
  },
  {
    id: 5,
    name: 'quality_inspector',
    displayName: '质检员',
    description: '执行质检任务',
    userCount: 0,
    level: 4
  },
  {
    id: 6,
    name: 'warehouse_manager',
    displayName: '仓库主管',
    description: '管理仓库和库存',
    userCount: 0,
    level: 2
  },
  {
    id: 7,
    name: 'warehouse_operator',
    displayName: '仓库操作员',
    description: '执行出入库操作',
    userCount: 0,
    level: 4
  },
  {
    id: 8,
    name: 'procurement_manager',
    displayName: '采购主管',
    description: '管理供应商和采购订单',
    userCount: 0,
    level: 2
  },
  {
    id: 9,
    name: 'procurement_staff',
    displayName: '采购员',
    description: '执行采购任务',
    userCount: 0,
    level: 4
  },
  {
    id: 10,
    name: 'sales_manager',
    displayName: '销售主管',
    description: '管理客户和销售订单',
    userCount: 0,
    level: 2
  },
  {
    id: 11,
    name: 'sales_staff',
    displayName: '销售员',
    description: '执行销售任务',
    userCount: 0,
    level: 4
  },
  {
    id: 12,
    name: 'hr_manager',
    displayName: '人事主管',
    description: '管理员工和考勤',
    userCount: 0,
    level: 2
  },
  {
    id: 13,
    name: 'equipment_manager',
    displayName: '设备主管',
    description: '管理设备和维护',
    userCount: 0,
    level: 2
  },
  {
    id: 14,
    name: 'finance_manager',
    displayName: '财务主管',
    description: '管理成本和财务报表',
    userCount: 0,
    level: 2
  },
  // SP12: 六扇门新增角色
  {
    id: 15,
    name: 'cashier',
    displayName: '出纳',
    description: '负责资金收付、银行对账及付款审核',
    userCount: 0,
    level: 3
  },
  {
    id: 16,
    name: 'quality_controller',
    displayName: '品控',
    description: '产品品控标准管理、原料验收及成品放行',
    userCount: 0,
    level: 3
  }
]);

function getLevelTag(level: number) {
  const types: Record<number, string> = {
    1: 'danger',
    2: 'warning',
    3: 'success',
    4: 'info'
  };
  const labels: Record<number, string> = {
    1: '超级管理',
    2: '主管级',
    3: '主任级',
    4: '员工级'
  };
  return { type: types[level], label: labels[level] };
}

// Fetch real user count per role (Apr 21 2026): previously hardcoded
// 2/3/5/8 for every factory which looked like cross-tenant leak to users.
async function loadRoleUserCounts() {
  if (!factoryId.value) return;
  try {
    // Backend requires page>=1 (1-indexed); using 0 triggers 409 "页码必须大于0"
    // _silent suppresses red toast when API is unavailable for new factories.
    const resp = await get<{ content: Array<{ roleCode?: string }>; totalElements: number }>(
      `/${factoryId.value}/users`,
      { params: { page: 1, size: 500 }, _silent: true } as never
    );
    if (!resp.success || !resp.data) return;
    const list = resp.data.content || [];
    const countMap: Record<string, number> = {};
    for (const u of list) {
      const code = u.roleCode || '';
      if (code) countMap[code] = (countMap[code] || 0) + 1;
    }
    roles.value.forEach(r => {
      r.userCount = countMap[r.name] || 0;
    });
  } catch (e) {
    console.warn('loadRoleUserCounts failed', e);
  }
}

onMounted(loadRoleUserCounts);
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>角色权限</span>
          <el-tag type="info">共 14 种工厂角色</el-tag>
        </div>
      </template>

      <el-table :data="roles" stripe>
        <el-table-column prop="displayName" label="角色名称" width="120" />
        <el-table-column prop="name" label="角色标识" width="180">
          <template #default="{ row }">
            <code>{{ row.name }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" />
        <el-table-column prop="level" label="级别" width="120">
          <template #default="{ row }">
            <el-tag :type="getLevelTag(row.level).type">
              {{ getLevelTag(row.level).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userCount" label="用户数" width="100">
          <template #default="{ row }">
            <el-tag>{{ row.userCount }} 人</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleViewPermissions(row)">查看权限</el-button>
            <el-tooltip content="角色编辑功能开发中" placement="top"><el-button type="info" link disabled>编辑</el-button></el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 查看权限 Dialog -->
    <el-dialog v-model="viewDialogVisible" :title="`${viewingRole?.displayName} — 权限详情`" width="600px" destroy-on-close>
      <el-tabs v-model="viewTab" type="border-card">
        <!-- Tab 1: 功能权限 (existing) -->
        <el-tab-pane label="功能权限" name="function">
          <el-table :data="rolePermissions" v-loading="permissionsLoading" stripe border>
            <el-table-column prop="label" label="功能模块" width="140" />
            <el-table-column prop="access" label="权限" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="getAccessTag(row.access).type" size="small">
                  {{ getAccessTag(row.access).label }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab 2: 数据权限 (Sprint 5 Track G stub — backend live, frontend Sprint 6 sweep) -->
        <el-tab-pane label="数据权限" name="data">
          <el-alert
            title="Sprint 5 Track G — RBAC 数据权限维度 MVP"
            type="info"
            :closable="false"
            description="后端 framework 已上线 (5 级 scope: ALL/DEPT_AND_BELOW/SELF_AND_BELOW/SELF/CUSTOM). 当前角色 default scope 见下表; 自定义编辑功能 Sprint 6 上线 (跟 §G 全 sweep 同步)."
            style="margin-bottom: 16px"
          />
          <el-form label-width="100px" disabled>
            <el-form-item label="数据范围">
              <el-radio-group v-model="dataScopeStub">
                <el-radio value="ALL">全工厂 (默认)</el-radio>
                <el-radio value="DEPT_AND_BELOW">本部门 + 下属部门</el-radio>
                <el-radio value="SELF_AND_BELOW">自己 + 下属</el-radio>
                <el-radio value="SELF">仅自己创建</el-radio>
                <el-radio value="CUSTOM">自定义 (Sprint 6)</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="生效说明">
              <el-text type="info" size="small">
                销售单 list 已实施 POC (SELF scope 仅看自己创建的订单).
                Sprint 6 sweep 时扩 customer/PO/invoice/delivery 等 10+ 接口.
              </el-text>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="viewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 编辑角色 Dialog -->
    <el-dialog v-model="editDialogVisible" :title="`编辑角色 — ${editingRole?.displayName}`" width="600px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="角色名称" required>
          <el-input v-model="editForm.displayName" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" placeholder="请输入描述" />
        </el-form-item>
        <el-form-item label="权限配置">
          <el-table :data="allPermissionModules" stripe border size="small" style="width: 100%">
            <el-table-column prop="label" label="功能模块" width="120" />
            <el-table-column label="权限" align="center">
              <template #default="{ row }">
                <el-radio-group v-model="editForm.permissions[row.key]" size="small">
                  <el-radio-button value="rw">读写</el-radio-button>
                  <el-radio-button value="r">只读</el-radio-button>
                  <el-radio-button value="w">只写</el-radio-button>
                  <el-radio-button value="-">无</el-radio-button>
                </el-radio-group>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="handleEditSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-card style="margin-top: 20px;">
      <template #header>
        <span>权限说明</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="rw">读写权限 - 可查看和修改</el-descriptions-item>
        <el-descriptions-item label="r">只读权限 - 仅可查看</el-descriptions-item>
        <el-descriptions-item label="w">只写权限 - 仅可创建</el-descriptions-item>
        <el-descriptions-item label="-">无权限 - 不可访问</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
code {
  background-color: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}
</style>
