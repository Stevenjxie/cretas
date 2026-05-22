<!--
  PlaceholderTab — used by the 3 sub-tabs whose entities live in Sprint 9
  branches (not yet merged to main as of 2026-05-21):
    - 留样追踪 (FoodSample, sprint9/p2a-sample-retention-gb31654)
    - 营养标签合规 (NutritionLabel, sprint9/p2b-nutrition-label-gb28050)
    - 供应商食品资质 / 冷链 / SSOP (sprint9/p2c/p2d/p2e)

  fool-proof Rule 5: dead-end avoided — point users to indicator-center for
  data-completeness tracking + offer to subscribe to the entity's ETA.
-->
<template>
  <div class="placeholder-tab">
    <el-empty :description="title">
      <template #image>
        <el-icon size="64" color="#909399">
          <!-- text-only fallback rendered by Element Plus default Empty icon -->
        </el-icon>
      </template>
      <template #default>
        <el-result
          icon="info"
          :title="title"
          :sub-title="subtitle"
        >
          <template #extra>
            <el-text type="info" size="small" style="display: block; margin-bottom: 12px;">
              法规依据: {{ regulation }}
            </el-text>
            <el-text type="info" size="small" style="display: block; margin-bottom: 12px;">
              对应实体所在分支: {{ branch }}
            </el-text>
            <el-button type="primary" plain @click="goIndicatorCenter">
              前往指标中心查看完整度
            </el-button>
          </template>
        </el-result>
      </template>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

interface Props {
  title: string
  subtitle: string
  regulation: string
  branch: string
}
defineProps<Props>()

const router = useRouter()

function goIndicatorCenter() {
  // fool-proof Rule 5: dead-end avoided — direct to companion Canvas Phase A tab
  void router.push({ name: 'CanvasEditor', query: { phase: 'B', tab: 'indicator-center' } })
}
</script>

<style scoped>
.placeholder-tab {
  padding: 24px 0;
}
</style>
