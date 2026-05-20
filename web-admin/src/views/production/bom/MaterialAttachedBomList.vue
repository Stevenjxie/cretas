<script setup lang="ts">
/**
 * MaterialAttachedBomList — Sprint 6 W4-C 反查 page.
 *
 * 物料 → BOM 反查: 输入 materialId, 列出哪些 BomRecipe 用了此物料 + 每条 item 用量明细.
 *
 * Backend: BomReverseQueryController (Sprint 6 W4-C ship).
 * - GET /bom/reverse-query/by-material/{materialId}        — distinct BomRecipes
 * - GET /bom/reverse-query/by-material/{materialId}/usage  — per item rows
 *
 * Permissions: read 公开 (price 字段会被 strip).
 */
import { ref, computed } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { Search, Refresh } from '@element-plus/icons-vue';
import {
  bomReverseQueryApi,
  type BomRecipe,
  type BomItemUsage,
} from '@/api/bomVersion';

const route = useRoute();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const materialId = ref<string>((route.params.id as string) || '');
const loading = ref(false);
const recipes = ref<BomRecipe[]>([]);
const usages = ref<BomItemUsage[]>([]);
const tab = ref<'recipes' | 'usage'>('recipes');

async function loadData() {
  if (!factoryId.value) {
    ElMessage.warning('未识别 factoryId, 请重新登录');
    return;
  }
  if (!materialId.value) {
    ElMessage.warning('请输入物料 ID');
    return;
  }
  loading.value = true;
  try {
    const [recRes, usaRes] = await Promise.all([
      bomReverseQueryApi.findRecipesByMaterial(factoryId.value, materialId.value),
      bomReverseQueryApi.findUsageByMaterial(factoryId.value, materialId.value),
    ]);
    recipes.value = recRes.success ? recRes.data ?? [] : [];
    usages.value = usaRes.success ? usaRes.data ?? [] : [];
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('反查失败');
  } finally {
    loading.value = false;
  }
}

function gotoRecipe(row: BomRecipe) {
  // 跳转 BomRecipe 详情, 路由名按现有 production/bom 区域
  window.open(`/production/bom/recipe/${row.id}`, '_blank');
}

// Auto-load if materialId provided via route
if (materialId.value) {
  loadData();
}
</script>

<template>
  <div class="material-attached-bom-list">
    <el-card>
      <template #header>
        <div class="header">
          <span>物料反查 — BOM 关联清单</span>
          <el-tag size="small" type="info">Sprint 6 W4-C</el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="查询某物料挂哪些 BOM. 替换 / 停产前先看影响范围. 走 idx_bri_material 索引, 1000 BomRecipe ≤200ms."
        style="margin-bottom: 12px"
      />

      <el-form inline @submit.prevent="loadData">
        <el-form-item label="物料 ID">
          <el-input
            v-model="materialId"
            placeholder="RawMaterialType UUID"
            clearable
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadData">
            反查
          </el-button>
          <el-button :icon="Refresh" @click="loadData" :disabled="!materialId">
            刷新
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="materialId" style="margin-top: 12px">
      <el-tabs v-model="tab">
        <el-tab-pane label="BomRecipe (distinct)" name="recipes">
          <el-table v-loading="loading" :data="recipes" stripe>
            <el-table-column prop="recipeCode" label="编号" width="180" />
            <el-table-column prop="productName" label="成品名称" min-width="160" />
            <el-table-column prop="version" label="版本" width="80" />
            <el-table-column label="当前生效" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.isCurrent" type="success" size="small">是</el-tag>
                <el-tag v-else type="info" size="small">否</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="gotoRecipe(row)">
                  查看详情
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="该物料未挂任何 BomRecipe" />
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Item 用量明细 (per item)" name="usage">
          <el-table v-loading="loading" :data="usages" stripe>
            <el-table-column prop="recipeCode" label="BOM 编号" width="180" />
            <el-table-column prop="productName" label="成品" min-width="160" />
            <el-table-column prop="version" label="版本" width="60" />
            <el-table-column prop="standardQuantity" label="标准用量" width="100" />
            <el-table-column prop="unit" label="单位" width="60" />
            <el-table-column prop="yieldRate" label="出成率(%)" width="100" />
            <el-table-column prop="actualQuantity" label="实际用量" width="100" />
            <el-table-column prop="materialCategory" label="物料类型" width="100" />
            <el-table-column label="可选" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.isOptional" size="small">可选</el-tag>
                <span v-else>必选</span>
              </template>
            </el-table-column>
            <el-table-column prop="substituteGroup" label="替代组" width="100" />
            <el-table-column label="成本" width="100">
              <template #default="{ row }">
                <span v-if="row.itemCost != null">¥{{ row.itemCost }}</span>
                <span v-else class="text-muted">—</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="该物料无 item 引用" />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.material-attached-bom-list {
  padding: 16px;
}
.header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.text-muted {
  color: var(--el-text-color-secondary, #909399);
}
</style>
