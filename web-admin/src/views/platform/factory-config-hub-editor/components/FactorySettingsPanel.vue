<!--
  FactorySettingsPanel — Phase B Factory Config Hub sub-tab 6.

  工厂总设置 (基本信息 + 国际化 + 功能开关).
-->
<template>
  <div class="factory-settings-panel">
    <el-skeleton v-if="loading" :rows="5" animated />
    <el-form
      v-else
      :model="form"
      label-width="200px"
      class="form"
      :disabled="saving"
    >
      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="工厂名称">
        <el-input v-model="form.factoryName" />
      </el-form-item>
      <el-form-item label="工厂地址">
        <el-input v-model="form.factoryAddress" />
      </el-form-item>
      <el-form-item label="联系电话">
        <el-input v-model="form.contactPhone" />
      </el-form-item>
      <el-form-item label="联系邮箱">
        <el-input v-model="form.contactEmail" />
      </el-form-item>
      <el-form-item label="工作小时/天">
        <el-input-number v-model="form.workingHours" :min="0" :max="24" />
      </el-form-item>

      <el-divider content-position="left">国际化 / 显示</el-divider>
      <el-form-item label="语言">
        <el-select v-model="form.language">
          <el-option label="简体中文" value="zh-CN" />
          <el-option label="English" value="en-US" />
        </el-select>
      </el-form-item>
      <el-form-item label="时区">
        <el-select v-model="form.timezone">
          <el-option label="Asia/Shanghai" value="Asia/Shanghai" />
          <el-option label="UTC" value="UTC" />
          <el-option label="America/New_York" value="America/New_York" />
        </el-select>
      </el-form-item>
      <el-form-item label="货币">
        <el-select v-model="form.currency">
          <el-option label="人民币 (CNY)" value="CNY" />
          <el-option label="美元 (USD)" value="USD" />
          <el-option label="欧元 (EUR)" value="EUR" />
        </el-select>
      </el-form-item>
      <el-form-item label="日期格式">
        <el-input v-model="form.dateFormat" placeholder="yyyy-MM-dd" />
      </el-form-item>

      <el-divider content-position="left">用户注册</el-divider>
      <el-form-item label="允许自注册">
        <el-switch v-model="form.allowSelfRegistration" />
      </el-form-item>
      <el-form-item label="需要管理员审批">
        <el-switch v-model="form.requireAdminApproval" />
      </el-form-item>
      <el-form-item label="默认用户角色">
        <el-input v-model="form.defaultUserRole" placeholder="viewer / operator" />
      </el-form-item>

      <el-divider content-position="left">功能开关</el-divider>
      <el-form-item label="启用二维码">
        <el-switch v-model="form.enableQrCode" />
      </el-form-item>
      <el-form-item label="启用批次管理">
        <el-switch v-model="form.enableBatchManagement" />
      </el-form-item>
      <el-form-item label="启用质量检测">
        <el-switch v-model="form.enableQualityCheck" />
      </el-form-item>
      <el-form-item label="启用成本核算">
        <el-switch v-model="form.enableCostCalculation" />
      </el-form-item>
      <el-form-item label="启用设备管理">
        <el-switch v-model="form.enableEquipmentManagement" />
      </el-form-item>
      <el-form-item label="启用考勤管理">
        <el-switch v-model="form.enableAttendance" />
      </el-form-item>
      <el-form-item label="新建计划默认两点报工（仅头尾报）">
        <div class="reporting-mode-setting">
          <el-switch v-model="form.skipProcessReportingDefault" />
          <el-text type="info" size="small">
            开启=新计划默认只报领料投入+末道产出两点（适合六扇门类）；关闭=逐道报工（每工序都报）。单计划仍可单独覆盖。
          </el-text>
        </div>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存设置</el-button>
        <el-button @click="loadData">刷新</el-button>
        <el-text type="info" size="small" style="margin-left: 12px">
          v{{ form.version ?? 0 }} (并发控制)
        </el-text>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSettings, updateSettings, type FactorySettings } from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const form = reactive<FactorySettings>({
  factoryId: props.factoryId,
  language: 'zh-CN',
  timezone: 'Asia/Shanghai',
  currency: 'CNY',
  dateFormat: 'yyyy-MM-dd',
  workingHours: 8,
  enableQrCode: true,
  enableBatchManagement: true,
  enableQualityCheck: true,
  enableCostCalculation: true,
  enableEquipmentManagement: true,
  enableAttendance: true,
  skipProcessReportingDefault: false,
  allowSelfRegistration: false,
  requireAdminApproval: true,
  defaultUserRole: 'viewer',
  version: 0,
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await getSettings(props.factoryId)
    if (res.success && res.data) {
      Object.assign(form, res.data)
    }
  } finally {
    loading.value = false
  }
}

async function onSave() {
  saving.value = true
  try {
    const res = await updateSettings(props.factoryId, { ...form })
    if (res.success && res.data) {
      Object.assign(form, res.data)
      ElMessage.success('工厂设置已保存，新建计划默认报工模式已更新')
    }
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      await ElMessageBox.confirm(
        '设置已被他人修改, 是否加载最新版本?',
        '并发冲突',
        { type: 'warning', confirmButtonText: '加载最新', cancelButtonText: '保留我的编辑' },
      ).then(loadData).catch(() => { /* noop */ })
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.factory-settings-panel {
  padding: 8px 0;
}
.form {
  max-width: 700px;
}
.reporting-mode-setting {
  display: flex;
  align-items: center;
  gap: 12px;
  line-height: 1.5;
}
</style>
