<!-- factory-config-hubEditor — Phase B/C/P3 stub (2026-05-22). Subagent 填充. -->
<template>
  <div class="phase-bcp3-stub">
    <el-empty description="工厂配置中心 (排班/临时工/五险一金/工资/编码/FactorySettings) - 开发中">
      <template #default>
        <el-result icon="info" title="敬请期待" sub-title="Subagent 实现中">
          <template #extra>
            <el-text type="info" size="small">factoryId: {{ factoryId }}</el-text>
          </template>
        </el-result>
      </template>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
interface Props {
  factoryId: string
}
defineProps<Props>()
</script>

<style scoped>
.phase-bcp3-stub { padding: 24px; }
</style>
