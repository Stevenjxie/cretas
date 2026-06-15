<template>
  <section class="permission-preview-page">
    <EmployeeSelector v-model="selectedUserId" :factory-id="authStore.factoryId" />
    <PermissionPreviewSidebar
      v-if="preview"
      :visible-modules="preview.visibleModules"
      :denied-modules="preview.deniedModules"
      :editable-modules="preview.editableModules"
    />
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import EmployeeSelector from '@/components/permissions/EmployeeSelector.vue';
import PermissionPreviewSidebar from '@/components/permissions/PermissionPreviewSidebar.vue';
import { previewUserPermissions, type PermissionPreviewDto } from '@/api/permissionSettings';
import { useAuthStore } from '@/store/modules/auth';

const authStore = useAuthStore();
const selectedUserId = ref('');
const preview = ref<PermissionPreviewDto | null>(null);

watch(selectedUserId, async userId => {
  if (!authStore.factoryId || !userId) {
    preview.value = null;
    return;
  }
  preview.value = await previewUserPermissions(authStore.factoryId, userId);
});
</script>

<style scoped>
.permission-preview-page {
  display: grid;
  gap: 16px;
}
</style>
