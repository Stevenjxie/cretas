<!--
  Notify Template Editor — Phase 3 Canvas-Notify Tab (Step T7 web-admin).

  Mount point: Canvas Editor → Phase B → "通知模板" tab.
  Sub-tabs: 模板列表 (TemplatesList) / 发送日志 (LogsList).

  Role-gated by Canvas page header (factory_super_admin / permission_admin via @RequireRole on backend).

  @since 2026-05-19
-->
<template>
  <div class="notify-template-editor">
    <el-tabs v-model="subTab" type="card" class="sub-tabs">
      <el-tab-pane label="模板列表" name="templates">
        <TemplatesList v-if="subTab === 'templates'" :factory-id="factoryId" />
      </el-tab-pane>
      <el-tab-pane label="发送日志" name="logs">
        <LogsList v-if="subTab === 'logs'" :factory-id="factoryId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import TemplatesList from './components/TemplatesList.vue'
import LogsList from './components/LogsList.vue'

interface Props {
  /** Optional override; defaults to authStore.factoryId. */
  factoryId?: string
}
const props = defineProps<Props>()
const authStore = useAuthStore()
const factoryId = computed(() => props.factoryId || authStore.factoryId || '')

const subTab = ref<'templates' | 'logs'>('templates')
</script>

<style scoped>
.notify-template-editor {
  padding: 12px 16px;
  height: 100%;
  overflow: auto;
}

.sub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
