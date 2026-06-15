<template>
  <section class="permission-shell">
    <el-tabs :model-value="$route.path" @tab-change="go">
      <el-tab-pane label="员工管理" name="/permissions/employees" />
      <el-tab-pane label="角色权限模板" name="/permissions/role-templates" />
      <el-tab-pane label="员工权限" name="/permissions/employee-permissions" />
      <el-tab-pane label="权限预览" name="/permissions/preview" />
    </el-tabs>
    <router-view />
  </section>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';

const router = useRouter();

function go(path: string | number) {
  router.push(String(path));
}
</script>

<style scoped>
.permission-shell {
  padding: 16px;
}
</style>
