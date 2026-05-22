<!--
  IndicatorCenterEditor — Phase A P0 stub (2026-05-21).

  Subagent #3 替换本 stub 为完整指标中心 (Indicator Center).

  范围:
    - 指标定义 + 公式编辑 (Phase 1 Day 1 PR #114 已 ship 7 entity)
    - 指标版本管理 + closure trigger (Phase 1 Day 2 PR #116 seed 已 ship)
    - 批次溯源 Lineage 可视化 (PR #114 schema + Day 3+ service)
    - vs GuanData V8.2 差异化护城河 — 重要卖点
  source-of-truth 见 docs/audits/2026-05-21-liushanmen-canvas-process-design.md §P0-3.

  Flyway 版本号 (预占): V20260823_03__indicator_center_seed.sql (可能只需 seed, 无 schema)
  API 路径 (预占): /api/mobile/{factoryId}/canvas-indicators
-->
<template>
  <div class="phase-a-stub">
    <el-empty description="指标中心 - 开发中 (Phase A subagent #3)">
      <template #default>
        <el-result icon="info" title="敬请期待" sub-title="Subagent 实现中: 包装 Phase 1 已 ship 的指标定义+公式+lineage 后端为 Vue UI">
          <template #extra>
            <el-text type="info" size="small">
              factoryId: {{ factoryId }} | vs GuanData V8.2 差异化卖点
            </el-text>
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
.phase-a-stub {
  padding: 24px;
}
</style>
