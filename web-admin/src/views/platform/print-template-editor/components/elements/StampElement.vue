<template>
  <div class="el-print-stamp" :style="containerStyle">
    <div class="ring" :style="ringStyle">
 <div class="star"></div>
      <div class="caption">企业印章</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { StampElement } from '../../utils/printSchemaTypes'
import { ptToPx } from '../../utils/a4Coords'

const props = defineProps<{
  element: StampElement
  zoom: number
}>()

const size = computed(() => ptToPx(props.element.size, props.zoom))

const containerStyle = computed(() => ({
  width: `${size.value}px`,
  height: `${size.value}px`,
  opacity: props.element.opacity ?? 0.8,
  pointerEvents: 'none' as const,
}))

const ringStyle = computed(() => ({
  width: '100%',
  height: '100%',
  borderRadius: '50%',
  border: `${Math.max(2, size.value / 30)}px solid #dc2626`,
  fontSize: `${size.value / 8}px`,
  color: '#dc2626',
}))
</script>

<style scoped>
.ring {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-family: 'SimHei', sans-serif;
}
.star {
  font-size: 1.5em;
  line-height: 1;
}
.caption {
  line-height: 1.2;
  margin-top: 2px;
}
</style>
