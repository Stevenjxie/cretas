<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Setting, Bell, Lock, Monitor, Coin } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const saving = ref(false);
const activeTab = ref('basic');

// 基础设置
const basicSettings = ref({
  factoryName: '',
  timezone: 'Asia/Shanghai',
  language: 'zh-CN',
  dateFormat: 'YYYY-MM-DD',
  workStartTime: '08:00',
  workEndTime: '17:00'
});

// 通知设置
const notificationSettings = ref({
  emailNotification: true,
  smsNotification: false,
  alertNotification: true,
  maintenanceReminder: true,
  reminderDays: 3
});

// 安全设置
const securitySettings = ref({
  passwordMinLength: 8,
  passwordRequireUppercase: true,
  passwordRequireNumber: true,
  sessionTimeout: 30,
  maxLoginAttempts: 5
});

// 成本设置
const laborHourlyRate = ref<number | null>(null);

// 系统状态
const systemStatus = ref({
  version: '1.0.0',
  lastBackup: '',
  databaseSize: '',
  serverStatus: 'running'
});

onMounted(() => {
  loadSettings();
  loadSystemStatus();
  loadCostSettings();
});

async function loadSettings() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/settings`);
    if (response.success && response.data) {
      if (response.data.basic) {
        basicSettings.value = { ...basicSettings.value, ...response.data.basic };
      }
      if (response.data.notification) {
        notificationSettings.value = { ...notificationSettings.value, ...response.data.notification };
      }
      if (response.data.security) {
        securitySettings.value = { ...securitySettings.value, ...response.data.security };
      }
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载设置失败');
    }
  } catch {
    // Settings may not be initialized yet — use defaults silently
  } finally {
    loading.value = false;
  }
}

async function loadSystemStatus() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/settings/full`);
    if (response.success && response.data) {
      systemStatus.value = {
        version: '1.0.0',
        serverStatus: 'running',
        lastBackup: response.data.lastBackup || '',
        databaseSize: response.data.databaseSize || ''
      };
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载系统状态失败');
    }
  } catch {
    // Use defaults — system status is non-critical
  }
}

async function saveBasicSettings() {
  if (!factoryId.value) return;

  saving.value = true;
  try {
    const response = await put(`/${factoryId.value}/settings/basic`, basicSettings.value);
    if (response.success) {
      ElMessage.success('基础设置已保存');
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

async function saveNotificationSettings() {
  if (!factoryId.value) return;

  saving.value = true;
  try {
    const response = await put(`/${factoryId.value}/settings/notification`, notificationSettings.value);
    if (response.success) {
      ElMessage.success('通知设置已保存');
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

async function saveSecuritySettings() {
  if (!factoryId.value) return;

  saving.value = true;
  try {
    const response = await put(`/${factoryId.value}/settings/security`, securitySettings.value);
    if (response.success) {
      ElMessage.success('安全设置已保存');
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

async function loadCostSettings() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/config/cost-settings`);
    if (response.success && response.data) {
      laborHourlyRate.value = response.data.laborHourlyRate ?? null;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载成本设置失败');
    }
  } catch {
    // Use defaults — cost settings may not be initialized yet
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
          <span class="page-title">系统设置</span>
        </div>
      </template>

      <el-tabs v-model="activeTab" class="settings-tabs">
        <!-- 基础设置 -->
        <el-tab-pane name="basic">
          <template #label>
            <span class="tab-label">
              <el-icon><Setting /></el-icon>
              基础设置
            </span>
          </template>

          <div class="settings-section" v-loading="loading">
            <el-form :model="basicSettings" label-width="120px" style="max-width: 600px">
              <el-form-item label="工厂名称">
                <el-input v-model="basicSettings.factoryName" placeholder="请输入工厂名称" />
              </el-form-item>
              <el-form-item label="时区">
                <el-select v-model="basicSettings.timezone" style="width: 100%">
                  <el-option label="中国标准时间 (UTC+8)" value="Asia/Shanghai" />
                  <el-option label="日本标准时间 (UTC+9)" value="Asia/Tokyo" />
                  <el-option label="韩国标准时间 (UTC+9)" value="Asia/Seoul" />
                </el-select>
              </el-form-item>
              <el-form-item label="语言">
                <el-select v-model="basicSettings.language" style="width: 100%">
                  <el-option label="简体中文" value="zh-CN" />
                  <el-option label="English" value="en-US" />
                </el-select>
              </el-form-item>
              <el-form-item label="日期格式">
                <el-select v-model="basicSettings.dateFormat" style="width: 100%">
                  <el-option label="YYYY-MM-DD" value="YYYY-MM-DD" />
                  <el-option label="DD/MM/YYYY" value="DD/MM/YYYY" />
                  <el-option label="MM/DD/YYYY" value="MM/DD/YYYY" />
                </el-select>
              </el-form-item>
              <el-form-item label="工作开始时间">
                <el-time-select
                  v-model="basicSettings.workStartTime"
                  :max-time="basicSettings.workEndTime"
                  placeholder="选择时间"
                  start="06:00"
                  step="00:30"
                  end="12:00"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="工作结束时间">
                <el-time-select
                  v-model="basicSettings.workEndTime"
                  :min-time="basicSettings.workStartTime"
                  placeholder="选择时间"
                  start="12:00"
                  step="00:30"
                  end="23:00"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveBasicSettings" :disabled="!canWrite">
                  保存设置
                </el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- 通知设置 -->
        <el-tab-pane name="notification">
          <template #label>
            <span class="tab-label">
              <el-icon><Bell /></el-icon>
              通知设置
            </span>
          </template>

          <div class="settings-section" v-loading="loading">
            <el-form :model="notificationSettings" label-width="150px" style="max-width: 600px">
              <el-form-item label="邮件通知">
                <el-switch v-model="notificationSettings.emailNotification" />
                <span class="form-tip">接收系统邮件通知</span>
              </el-form-item>
              <el-form-item label="短信通知">
                <el-switch v-model="notificationSettings.smsNotification" />
                <span class="form-tip">接收短信提醒（可能产生费用）</span>
              </el-form-item>
              <el-form-item label="告警通知">
                <el-switch v-model="notificationSettings.alertNotification" />
                <span class="form-tip">接收设备告警通知</span>
              </el-form-item>
              <el-form-item label="维护提醒">
                <el-switch v-model="notificationSettings.maintenanceReminder" />
                <span class="form-tip">设备维护到期提醒</span>
              </el-form-item>
              <el-form-item label="提前提醒天数">
                <el-input-number v-model="notificationSettings.reminderDays" :min="1" :max="30" />
                <span class="form-tip">维护到期前几天提醒</span>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveNotificationSettings" :disabled="!canWrite">
                  保存设置
                </el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- 安全设置 -->
        <el-tab-pane name="security">
          <template #label>
            <span class="tab-label">
              <el-icon><Lock /></el-icon>
              安全设置
            </span>
          </template>

          <div class="settings-section" v-loading="loading">
            <el-form :model="securitySettings" label-width="150px" style="max-width: 600px">
              <el-form-item label="密码最小长度">
                <el-input-number v-model="securitySettings.passwordMinLength" :min="6" :max="20" />
              </el-form-item>
              <el-form-item label="要求包含大写字母">
                <el-switch v-model="securitySettings.passwordRequireUppercase" />
              </el-form-item>
              <el-form-item label="要求包含数字">
                <el-switch v-model="securitySettings.passwordRequireNumber" />
              </el-form-item>
              <el-form-item label="会话超时时间">
                <el-input-number v-model="securitySettings.sessionTimeout" :min="5" :max="120" />
                <span class="form-tip">分钟，无操作后自动登出</span>
              </el-form-item>
              <el-form-item label="最大登录尝试次数">
                <el-input-number v-model="securitySettings.maxLoginAttempts" :min="3" :max="10" />
                <span class="form-tip">超过次数后锁定账户</span>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveSecuritySettings" :disabled="!canWrite">
                  保存设置
                </el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- 系统状态 -->
        <el-tab-pane name="status">
          <template #label>
            <span class="tab-label">
              <el-icon><Monitor /></el-icon>
              系统状态
            </span>
          </template>

          <div class="settings-section">
            <el-descriptions :column="1" border style="max-width: 600px">
              <el-descriptions-item label="系统版本">
                {{ systemStatus.version }}
              </el-descriptions-item>
              <el-descriptions-item label="服务器状态">
                <el-tag :type="systemStatus.serverStatus === 'running' ? 'success' : 'danger'">
                  {{ systemStatus.serverStatus === 'running' ? '运行中' : '异常' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="上次备份">
                {{ systemStatus.lastBackup || '暂无备份记录' }}
              </el-descriptions-item>
              <el-descriptions-item label="数据库大小">
                {{ systemStatus.databaseSize || '-' }}
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </el-tab-pane>

        <!-- 成本设置 -->
        <el-tab-pane name="cost">
          <template #label>
            <span class="tab-label">
              <el-icon><Coin /></el-icon>
              成本设置
            </span>
          </template>

          <div class="settings-section">
            <el-form label-width="160px" style="max-width: 600px">
              <el-form-item label="工时单价 (¥/工时)">
                <el-input-number
                  v-model="laborHourlyRate"
                  :min="0.01"
                  :precision="2"
                  :controls="true"
                  placeholder="按默认 26"
                  style="width: 200px"
                />
                <div v-if="laborHourlyRate === null" class="form-tip" style="margin-left: 0; margin-top: 6px; display: block">
                  未配置时按默认 ¥26/工时计入人工成本
                </div>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveCostSettings" :disabled="!canWrite">
                  保存设置
                </el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
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
    padding: 0;
  }
}

.card-header {
  .page-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-color-primary, #303133);
  }
}

.settings-tabs {
  height: 100%;

  :deep(.el-tabs__header) {
    padding: 0 20px;
    margin-bottom: 0;
    background: #fafafa;
    border-bottom: 1px solid #ebeef5;
  }

  :deep(.el-tabs__content) {
    padding: 20px;
    height: calc(100% - 55px);
    overflow: auto;
  }
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 6px;
}

.settings-section {
  padding: 20px 0;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
