<script setup lang="ts">
import type { StockShortagePresentation } from './processStockShortage';

defineProps<{ presentation: StockShortagePresentation }>();
</script>

<template>
  <el-alert
    class="stock-shortage-alert"
    type="error"
    :closable="false"
    show-icon
  >
    <template #title>{{ presentation.title }}</template>
    <div class="stock-shortage-body">
      <template v-if="presentation.items.length">
        <strong>缺什么 / 缺多少</strong>
        <ul>
          <li v-for="item in presentation.items" :key="item.materialName">
            <span>{{ item.materialName }}</span>
            <b>缺 {{ item.shortageText }}</b>
            <small>需 {{ item.requiredText }}，可用 {{ item.availableText }}</small>
          </li>
        </ul>
      </template>
      <p v-else>{{ presentation.rawMessage }}</p>
      <p><strong>去哪补：</strong>{{ presentation.action }}</p>
      <details v-if="presentation.items.length">
        <summary>查看系统原始信息</summary>
        <span>{{ presentation.rawMessage }}</span>
      </details>
    </div>
  </el-alert>
</template>

<style scoped>
.stock-shortage-body { margin-top: 6px; color: var(--el-text-color-primary); }
.stock-shortage-body ul { margin: 6px 0; padding-left: 18px; }
.stock-shortage-body li { margin: 4px 0; }
.stock-shortage-body li span { margin-right: 10px; }
.stock-shortage-body li b { margin-right: 8px; }
.stock-shortage-body small { color: var(--el-text-color-secondary); }
.stock-shortage-body p { margin: 6px 0 0; }
.stock-shortage-body details { margin-top: 6px; color: var(--el-text-color-secondary); }
.stock-shortage-body summary { cursor: pointer; }
</style>
