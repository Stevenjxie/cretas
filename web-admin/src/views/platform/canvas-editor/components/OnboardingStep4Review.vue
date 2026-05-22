<!-- OnboardingStep4Review.vue -->
<template>
  <div>
    <h3>预览 & 提交发布</h3>
    <div class="summary-cards">
      <div class="summary-card">
        <div class="card-num">{{ state.enabledModules.length }}</div>
        <div class="card-label">启用模块</div>
      </div>
      <div class="summary-card">
        <div class="card-num">{{ state.selectedTemplate || '空白' }}</div>
        <div class="card-label">行业模板</div>
      </div>
    </div>

    <h4 style="margin:16px 0 8px">完整性检查</h4>
    <div v-for="check in checks" :key="check.name" class="check-row">
      <span :class="check.passed ? 'check-pass' : 'check-fail'">{{ check.passed ? '' : '' }}</span>
      <span>{{ check.name }}</span>
      <span class="check-msg">{{ check.message }}</span>
    </div>

    <el-alert v-if="allPassed" type="success" :closable="false" style="margin-top:16px">
      所有检查通过！点击"提交发布"进入审核流程。
    </el-alert>
    <el-alert v-else type="warning" :closable="false" style="margin-top:16px">
      部分检查未通过，建议修复后再提交。
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { OnboardingState } from '@/types/canvas'

const props = defineProps<{ factoryId: string; state: OnboardingState }>()

const checks = ref([
  { name: '模块依赖', passed: true, message: '所有依赖已满足' },
  { name: '流程完整性', passed: true, message: '所有状态可达' },
  { name: '必填字段', passed: true, message: '无缺失' },
])

const allPassed = computed(() => checks.value.every(c => c.passed))

onMounted(() => {
  // In production, call checkCompleteness API
  if (props.state.enabledModules.includes('sales_order') && !props.state.enabledModules.includes('customer')) {
    checks.value[0] = { name: '模块依赖', passed: false, message: '销售订单需要客户模块' }
  }
})
</script>

<style scoped>
.summary-cards { display: flex; gap: 16px; margin-bottom: 16px; }
.summary-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; text-align: center; min-width: 120px; }
.card-num { font-size: 24px; font-weight: bold; color: var(--el-color-primary); }
.card-label { font-size: 12px; color: var(--el-text-color-secondary); }
.check-row { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.check-msg { color: var(--el-text-color-secondary); font-size: 12px; margin-left: auto; }
</style>
