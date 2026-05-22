<!--
  FoodSafetyHubEditor — Phase A P0 stub (2026-05-21).

  Subagent #2 替换本 stub 为完整食品安全中心 (Food Safety Hub).

  范围 (8 sub-tab, 全部 wrap 现有 entity):
    - HACCP 检查点 (Sprint 9 P2.A — entity LIVE, 缺 UI)
    - 食品添加剂限量只读表 (GB 2760)
    - 召回 (Recall) 事件
    - 留样 (Food Sample) 追踪
    - 营养标签合规 (GB 28050)
    - 供应商食品资质 (SC证/HACCP/营业执照 — Sprint 9 P2.C 已 ship)
    - 冷链温控偏离 (GB 14881 — Sprint 9 P2.D 已 ship)
    - SSOP 清洁消毒记录 (Sprint 9 P2.E 已 ship)
  source-of-truth 见 docs/audits/2026-05-21-liushanmen-canvas-process-design.md §P0-2.

  Flyway 版本号 (预占): V20260823_02__food_safety_hub.sql (主要是 UI wrap, 可能不需要新 schema)
  API 路径 (预占): /api/mobile/{factoryId}/canvas-food-safety
-->
<template>
  <div class="phase-a-stub">
    <el-empty description="食品安全中心 - 开发中 (Phase A subagent #2)">
      <template #default>
        <el-result icon="info" title="敬请期待" sub-title="Subagent 实现中: 8 sub-tab wrap HACCP/添加剂/召回/留样/营养/资质/冷链/SSOP">
          <template #extra>
            <el-text type="info" size="small">
              factoryId: {{ factoryId }} | 包装 Sprint 8/9 已 ship 的 15 食品安全 entity
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
