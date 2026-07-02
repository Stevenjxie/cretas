<script setup lang="ts">
/**
 * 工时单价 (¥/工时) 配置 — 人事管理 > 人工成本设置.
 *
 * 迁移背景 (2026-07): 原「工时单价」配置只在 系统设置 > 成本设置 tab
 * (route module: 'system')。逐工序报工缺配置时的死胡同提示
 * ("工时单价未配置，按默认¥26...请在工厂成本设置中配置") 让工厂管理员
 * 找不到路 —— 藏在「系统管理」20 个子项里的第 5 个 tab, 概念上也不属于
 * "系统管理"(账号/角色/审批链/AI配额…), 而是人工成本, 挪到 人事管理
 * (module: 'hr') 下更贴切也更好找。
 *
 * 复用同一后端端点 GET/PUT /{factoryId}/config/cost-settings, 字段/提示/
 * 校验/保存行为与原 系统设置 > 成本设置 tab 完全一致 (该 tab 予以保留,
 * 两处共享同一份配置, 不冲突)。
 *
 * ⚠️ 写权限口径: canWrite('hr') 对 factory_super_admin / hr_admin 为 rw,
 * 对 production_manager / dispatcher / department_admin / workshop_supervisor
 * 等一线报工相关角色目前只有 'r' (只读, 与旧 system tab 口径一致) ——
 * 这些角色能看到本页但不能保存, 若要让他们自行改必须另开
 * canWrite('production') 分支或调整 platform_role_permissions。
 *
 * @since 2026-07-02
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Coin } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

const loading = ref(false);
const saving = ref(false);
const laborHourlyRate = ref<number | null>(null);

onMounted(() => {
  loadCostSettings();
});

async function loadCostSettings() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/config/cost-settings`);
    if (response.success && response.data) {
      laborHourlyRate.value = response.data.laborHourlyRate ?? null;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载成本设置失败');
    }
  } catch {
    // Settings may not be initialized yet — use defaults silently
  } finally {
    loading.value = false;
  }
}

async function saveCostSettings() {
  if (!factoryId.value) return;

  saving.value = true;
  try {
    const response = await put(`/${factoryId.value}/config/cost-settings`, {
      laborHourlyRate: laborHourlyRate.value
    });
    if (response.success) {
      ElMessage.success('工时单价已保存');
      await loadCostSettings();
    } else {
      ElMessage.error(response.message || '保存失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">
            <el-icon><Coin /></el-icon>
            人工成本设置
          </span>
        </div>
      </template>

      <div class="settings-section" v-loading="loading">
        <el-form label-width="160px" style="max-width: 600px">
          <el-form-item label="工时单价 (¥/工时)">
            <el-input-number
              v-model="laborHourlyRate"
              :min="0.01"
              :precision="2"
              :controls="true"
              placeholder="按默认 26"
              style="width: 200px"
              :disabled="!canWrite"
            />
            <div v-if="laborHourlyRate === null" class="form-tip" style="margin-left: 0; margin-top: 6px; display: block">
              未配置时按默认 ¥26/工时计入人工成本 —— 逐工序报工的人工成本按此单价核算
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveCostSettings" :disabled="!canWrite">
              保存设置
            </el-button>
            <span v-if="!canWrite" class="form-tip" style="margin-left: 12px">
              当前角色无人事模块写权限，无法修改，请联系工厂管理员
            </span>
          </el-form-item>
        </el-form>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-color-primary, #303133);
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
}

.settings-section {
  flex: 1;
}

.form-tip {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
  margin-left: 12px;
}
</style>
