<!--
  Capability audit page — Phase 3 Day 10 (Sub-Project A · 数据织网).

  Per spec §6.3 (数据织网/02-A-能力驱动渲染.md v1.5 lines 1170-1188).
  Admin views capability state for the CURRENT factory (no factory selector;
  uses authStore.factoryId via useCapability composable).

  TODO: register route in router config — admin opted to keep registration manual.
    Suggested path: /system/data-fabric/capability-audit
    Suggested permission: SYSTEM_ADMIN or platform_super_admin role.
-->
<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useCapability } from '@/composables/useCapability';
import { CARD_MANIFEST, type CardManifestEntry } from '@/capability/card-manifest';

const { fetchCapability, available, cardStatus, suggestions, loading } = useCapability();

onMounted(() => {
  fetchCapability();
});

const availableFieldsList = computed<string[]>(() => {
  if (!available.value) return [];
  return [...available.value].sort();
});

const fieldsCount = computed<number>(() => available.value?.size ?? 0);

// Card breakdown from CARD_MANIFEST
const cardSatisfied = computed<number>(() =>
  CARD_MANIFEST.filter((c) => cardStatus.value[c.id]?.satisfied).length,
);
const cardUnsatisfied = computed<number>(() =>
  CARD_MANIFEST.filter((c) => cardStatus.value[c.id] && !cardStatus.value[c.id].satisfied).length,
);

type CardRow = CardManifestEntry & { satisfied: boolean; missing: string[] };

// Card list grouped by page
const cardsByPage = computed<Record<string, CardRow[]>>(() => {
  const groups: Record<string, CardRow[]> = {};
  for (const card of CARD_MANIFEST) {
    const page = card.page;
    if (!groups[page]) groups[page] = [];
    const status = cardStatus.value[card.id] ?? { satisfied: false, missing: card.requires };
    groups[page].push({ ...card, ...status });
  }
  return groups;
});

function refresh() {
  fetchCapability(true);
}
</script>

<template>
  <div class="capability-audit-page">
    <div class="page-header">
      <div>
        <h1>能力驱动渲染审计</h1>
        <span class="subtitle">当前 factory 的 capability 字段、卡片渲染状态、解锁建议</span>
      </div>
      <el-button type="primary" :loading="loading" @click="refresh">刷新</el-button>
    </div>

    <el-row v-loading="loading" :gutter="16">
      <el-col :span="8">
        <el-card>
          <template #header>可用 canonical 字段 ({{ fieldsCount }})</template>
          <el-tag
            v-for="f in availableFieldsList"
            :key="f"
            type="info"
            size="small"
            style="margin: 2px"
          >
            {{ f }}
          </el-tag>
          <el-empty
            v-if="availableFieldsList.length === 0"
            description="暂无可用字段"
            :image-size="60"
          />
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card>
          <template #header>卡片渲染状态 (CARD_MANIFEST {{ CARD_MANIFEST.length }} 张)</template>
          <p>
            满足:
            <strong style="color: var(--el-color-success)">{{ cardSatisfied }}</strong>
          </p>
          <p>
            缺数据:
            <strong style="color: var(--el-color-warning)">{{ cardUnsatisfied }}</strong>
          </p>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card>
          <template #header>解锁建议 (top 5)</template>
          <el-empty v-if="suggestions.length === 0" description="无解锁建议" :image-size="60" />
          <div
            v-for="s in suggestions"
            :key="s.missing_field"
            class="suggestion-row"
          >
            <strong>{{ s.cta }}</strong>
            <p class="examples">{{ s.unlocks_examples.join(', ') }}</p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 16px">
      <template #header>卡片详情 (按页面分组)</template>
      <div v-for="(cards, page) in cardsByPage" :key="page" class="page-group">
        <h3>{{ page }} ({{ cards.length }})</h3>
        <el-table :data="cards" stripe>
          <el-table-column prop="id" label="card-id" width="280" />
          <el-table-column prop="title" label="标题" width="180" />
          <el-table-column label="requires" width="280">
            <template #default="{ row }">
              <el-tag
                v-for="r in row.requires"
                :key="r"
                size="small"
                type="info"
                style="margin: 2px"
              >
                {{ r }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.satisfied" type="success">满足</el-tag>
              <el-tag v-else type="warning">缺 {{ row.missing.length }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="缺失字段">
            <template #default="{ row }">
              <el-tag
                v-for="m in row.missing"
                :key="m"
                size="small"
                type="danger"
                style="margin: 2px"
              >
                {{ m }}
              </el-tag>
              <span v-if="row.missing.length === 0" class="empty-hint">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.capability-audit-page {
  padding: 24px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}
.page-header h1 {
  margin: 0 0 8px 0;
}
.subtitle {
  color: var(--el-text-color-secondary);
}
.page-group {
  margin-bottom: 24px;
}
.page-group h3 {
  margin: 16px 0 8px 0;
  color: var(--el-text-color-primary);
}
.suggestion-row {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.suggestion-row:last-child {
  border-bottom: none;
}
.examples {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 0;
}
.empty-hint {
  color: var(--el-text-color-disabled);
}
</style>
