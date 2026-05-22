<!--
  ThresholdsHubEditor — Phase A P0 stub (2026-05-21).

  Subagent #1 替换本 stub 为完整阈值参数中心 (Thresholds Hub).

  范围:
    - 库存健康 8 个阈值 (turnover/expiry/loss/aging)
    - IoT 冷链/环境 5 个阈值 (cold-chain/normal/humidity)
    - 客户信用阈值 (warning_ratio)
    - BOM max_depth
    - 缺料分析 production_lead_days
    - AI 复杂度路由阈值
  source-of-truth 见 docs/audits/2026-05-21-liushanmen-canvas-process-design.md §P0-1.

  Flyway 版本号 (预占): V20260823_01__thresholds_hub.sql
  API 路径 (预占): /api/mobile/{factoryId}/canvas-thresholds
-->
<template>
  <div class="phase-a-stub">
    <el-empty description="阈值参数中心 - 开发中 (Phase A subagent #1)">
      <template #default>
        <el-result icon="info" title="敬请期待" sub-title="Subagent 实现中: 库存健康 8 阈值 + IoT 5 阈值 + 信用 + BOM + AI 路由">
          <template #extra>
            <el-text type="info" size="small">
              factoryId: {{ factoryId }} | 完整设计: docs/audits/2026-05-21-liushanmen-canvas-process-design.md
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
