<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { FLYWHEEL_MOCK_ACTIVE } from '@/api/smartbi/ai-flywheel';

defineProps<{
  domain: string;
}>();
const emit = defineEmits<{ (e: 'update:domain', v: string): void }>();

const route = useRoute();
const router = useRouter();

const TABS = [
  { path: '/system/ai-flywheel/overview', label: '总览看板' },
  { path: '/system/ai-flywheel/candidates', label: '晋升审核工作台' },
  { path: '/system/ai-flywheel/misses', label: 'Miss 复盘' },
  { path: '/system/ai-flywheel/quality', label: '质量与回归' },
  { path: '/system/ai-flywheel/dataset', label: '蒸馏数据集' },
];

function go(path: string) {
  if (route.path !== path) router.push(path);
}
</script>

<template>
  <div class="flywheel-header">
    <!-- MOCK 模式醒目横幅 (阻断项修复): 只在 FLYWHEEL_MOCK_ACTIVE (显式开关+本地 dev 构建双重条件)
         下出现, 生产构建恒不出现, 防止有人把 mock 截图当真实飞轮数据汇报 / 审核。 -->
    <el-alert
      v-if="FLYWHEEL_MOCK_ACTIVE"
      type="warning"
      :closable="false"
      show-icon
      class="mock-banner"
      data-test="flywheel-mock-banner"
    >
      <template #title>演示数据 / MOCK — 非真实飞轮数据, 未连接卡5b 真实后端</template>
      <div style="font-size: 12px; line-height: 1.6">
        当前页面所有指标 / 候选 / Miss / 质量数据均为本地随机生成的演示数据, 晋升通过 / 否决 /
        数据集导出等写操作在此模式下不会真实生效。仅本地开发构建在显式打开 FORCE_MOCK 开关时出现,
        生产构建不会展示本横幅。
      </div>
    </el-alert>
    <div class="title-row">
      <div class="title-block">
        <h2>AI 飞轮运营台</h2>
        <span class="subtitle">蒸馏训练的驾驶舱 — 晋升审核 / 数据质量 / 训练集导出</span>
      </div>
      <div class="domain-picker">
        <span class="domain-label">业态</span>
        <el-select :model-value="domain" style="width: 140px" @update:model-value="(v: string) => emit('update:domain', v)">
          <el-option value="restaurant" label="餐饮" />
          <el-option value="factory" label="工厂 (待接入)" disabled />
        </el-select>
      </div>
    </div>
    <nav class="flywheel-tabs">
      <button
        v-for="tab in TABS"
        :key="tab.path"
        class="flywheel-tab"
        :class="{ active: route.path === tab.path }"
        type="button"
        @click="go(tab.path)"
      >
        {{ tab.label }}
      </button>
    </nav>
  </div>
</template>

<style scoped>
.flywheel-header {
  margin-bottom: 16px;
}
.mock-banner {
  margin-bottom: 12px;
}
.title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.title-block h2 {
  margin: 0 0 4px;
}
.subtitle {
  color: #909399;
  font-size: 13px;
}
.domain-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}
.domain-label {
  color: #606266;
  font-size: 13px;
}
.flywheel-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #e4e7ed;
  overflow-x: auto;
}
.flywheel-tab {
  appearance: none;
  border: none;
  background: transparent;
  padding: 10px 16px;
  font-size: 14px;
  color: #606266;
  cursor: pointer;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
}
.flywheel-tab:hover {
  color: #409eff;
}
.flywheel-tab.active {
  color: #409eff;
  border-bottom-color: #409eff;
  font-weight: 600;
}

@media (max-width: 768px) {
  .title-row {
    flex-direction: column;
    align-items: stretch;
  }
  .domain-picker {
    justify-content: space-between;
  }
}
</style>
