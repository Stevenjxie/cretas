<!--
  ModulePermissionMatrix.vue — Canvas Tab 8: Layer 2 factory-level module × role override matrix.

  L1 (global default, rendered grey italic when inherited) comes from /api/admin/role-permissions.
  L2 (factory override, rendered bold) reads/writes /api/mobile/{factoryId}/canvas/role-module-override.

  PUT {level} writes override. PUT level=null clears override (falls back to L1).

  Cache invalidation for the factory is performed server-side on PUT.

  See: docs/superpowers/specs/2026-04-18-permission-matrix-ai-driven-design.md §5.2.3
-->
<template>
  <div class="module-permission-matrix">
    <div class="header-bar">
      <div class="title">
        <span>工厂级权限覆盖 (Layer 2 · 仅影响 <code>{{ factoryId }}</code>)</span>
      </div>
      <div class="actions">
        <el-tag v-if="dirty.size === 0" type="info" size="small">已同步</el-tag>
        <el-tag v-else type="warning" size="small">{{ dirty.size }} 处未保存</el-tag>
        <el-button
          :disabled="dirty.size === 0"
          :loading="saving"
          type="primary"
          size="small"
          @click="save"
          style="margin-left: 8px"
        >保存 {{ dirty.size > 0 ? '(' + dirty.size + ')' : '' }}</el-button>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin: 8px 0">
 <strong>灰斜体</strong> = 继承平台默认 (L1) · <strong>加粗</strong> = 本工厂覆盖 (L2) · 点 清除覆盖, 恢复 L1.
      全平台默认权限由平台管理员在 <a href="#/system/role-permissions" target="_blank">/system/role-permissions</a> 维护.
    </el-alert>

    <el-table v-loading="loading" :data="rows" border size="small" max-height="520" stripe>
      <el-table-column prop="role" label="角色" width="180" fixed>
        <template #default="{ row }">
          <el-tag v-if="row.role === 'platform_admin' || row.role === 'factory_super_admin'" type="danger" size="small">{{ row.role }}</el-tag>
          <el-tag v-else-if="row.role === 'unactivated'" type="info" size="small">{{ row.role }}</el-tag>
          <span v-else>{{ row.role }}</span>
        </template>
      </el-table-column>
      <el-table-column v-for="m in modules" :key="m" :label="m" align="center" width="110">
        <template #default="{ row }">
          <div class="cell">
            <el-select
              :model-value="row.effective[m]"
              size="small"
              style="width: 64px"
              :class="{ overridden: isOverridden(row.role, m), inherited: !isOverridden(row.role, m) }"
              @change="(val: string) => setOverride(row.role, m, val)"
            >
              <el-option label="rw" value="rw" />
              <el-option label="r" value="r" />
              <el-option label="w" value="w" />
              <el-option label="-" value="-" />
            </el-select>
            <el-button
              v-if="isOverridden(row.role, m)"
              link
              size="small"
              title="清除覆盖, 恢复 L1 默认"
              @click="resetOverride(row.role, m)"
 ></el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getPlatformPermissions,
  getFactoryOverride,
  updateFactoryOverride,
  type PermissionLevel,
  type RoleModuleOverride,
} from '@/api/permissionApi';

interface DirtyCell {
  role: string;
  module: string;
  level: PermissionLevel | null;
}

interface Row {
  role: string;
  l1: Record<string, PermissionLevel>;
  l2: Record<string, PermissionLevel | undefined>;
  effective: Record<string, PermissionLevel>;
}

const props = defineProps<{ factoryId: string }>();

const modules = [
  'dashboard', 'production', 'warehouse', 'quality', 'procurement', 'sales',
  'hr', 'equipment', 'finance', 'system', 'analytics', 'scheduling',
  'work_report', 'inventory', 'report', 'rd', 'restaurant',
];

const loading = ref(false);
const saving = ref(false);
const rows = ref<Row[]>([]);
const dirty = ref(new Map<string, DirtyCell>());

function key(role: string, module: string) { return `${role}:${module}`; }

function isOverridden(role: string, module: string): boolean {
  const row = rows.value.find(r => r.role === role);
  return row?.l2[module] !== undefined;
}

async function load() {
  if (!props.factoryId) return;
  loading.value = true;
  dirty.value = new Map();
  try {
    const [l1Flat, l2Map] = await Promise.all([
      getPlatformPermissions(),
      getFactoryOverride(props.factoryId),
    ]);
    // Group L1 by role
    const l1ByRole = new Map<string, Record<string, PermissionLevel>>();
    for (const p of l1Flat) {
      if (!l1ByRole.has(p.roleCode)) l1ByRole.set(p.roleCode, {});
      l1ByRole.get(p.roleCode)![p.moduleCode] = p.permissionLevel;
    }
    // Build rows
    const roleNames = Array.from(l1ByRole.keys()).sort();
    rows.value = roleNames.map(roleCode => {
      const l1 = l1ByRole.get(roleCode) || {};
      const l2 = (l2Map[roleCode] || {}) as Record<string, PermissionLevel>;
      const effective: Record<string, PermissionLevel> = {};
      for (const m of modules) {
        const l1Val = (l1[m] as PermissionLevel) || '-';
        const l2Val = l2[m];
        effective[m] = (l2Val ?? l1Val) as PermissionLevel;
      }
      return { role: roleCode, l1, l2, effective };
    });
  } catch (e: any) {
    ElMessage.error('加载权限矩阵失败: ' + (e?.message || 'unknown'));
  } finally {
    loading.value = false;
  }
}

function setOverride(role: string, module: string, level: string) {
  const row = rows.value.find(r => r.role === role);
  if (!row) return;
  const current = (row.l2[module] ?? row.l1[module] ?? '-') as PermissionLevel;
  const next = level as PermissionLevel;
  if (current === next) return;
  row.l2[module] = next;
  row.effective[module] = next;
  dirty.value.set(key(role, module), { role, module, level: next });
  dirty.value = new Map(dirty.value);
}

function resetOverride(role: string, module: string) {
  const row = rows.value.find(r => r.role === role);
  if (!row) return;
  delete row.l2[module];
  row.effective[module] = (row.l1[module] || '-') as PermissionLevel;
  dirty.value.set(key(role, module), { role, module, level: null });
  dirty.value = new Map(dirty.value);
}

async function save() {
  if (!props.factoryId || dirty.value.size === 0) return;
  saving.value = true;
  const failed: DirtyCell[] = [];
  const succeeded: DirtyCell[] = [];
  try {
    for (const cell of dirty.value.values()) {
      try {
        await updateFactoryOverride(props.factoryId, cell.role, cell.module, cell.level);
        succeeded.push(cell);
      } catch (e: any) {
        failed.push(cell);
        console.warn('[ModulePermissionMatrix] PUT failed for', cell, e);
      }
    }
    if (failed.length === 0) {
      ElMessage.success(`已保存 ${succeeded.length} 处覆盖`);
      dirty.value.clear();
      dirty.value = new Map();
    } else {
      ElMessage.warning(`部分失败: 成功 ${succeeded.length} / 失败 ${failed.length}. 失败项保留, 请查看 console.`);
      const newDirty = new Map<string, DirtyCell>();
      for (const f of failed) newDirty.set(key(f.role, f.module), f);
      dirty.value = newDirty;
    }
  } finally {
    saving.value = false;
  }
}

watch(() => props.factoryId, load);
onMounted(load);
</script>

<style scoped>
.module-permission-matrix { padding: 12px; }
.header-bar {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 8px;
}
.title { font-size: 13px; color: var(--el-text-color-regular); }
.title code { background: var(--el-fill-color-light); padding: 1px 6px; border-radius: 3px; }
.actions { display: flex; align-items: center; }
.cell { display: flex; align-items: center; gap: 4px; justify-content: center; }
:deep(.el-select.inherited .el-input__inner) {
  color: var(--el-text-color-placeholder);
  font-style: italic;
}
:deep(.el-select.overridden .el-input__inner) {
  color: var(--el-color-primary);
  font-weight: bold;
}
</style>
