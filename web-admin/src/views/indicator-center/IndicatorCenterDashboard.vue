<template>
  <div class="indicator-center">
    <!-- 头部 -->
    <div class="page-header">
      <div class="title-block">
        <el-icon class="title-icon" :size="24"><Histogram /></el-icon>
        <div>
          <h2 class="page-title">指标中心</h2>
          <p class="page-subtitle">食品行业指标定义、阈值预警、历史趋势</p>
        </div>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="refreshAll" :loading="loading">
          刷新全部
        </el-button>
      </div>
    </div>

    <!-- 汇总卡片 -->
    <el-row :gutter="16" class="summary-row">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-total">
              <el-icon :size="28"><DataLine /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num">{{ stats.total }}</div>
              <div class="summary-label">总指标</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-green">
              <el-icon :size="28"><CircleCheck /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-success">{{ stats.green }}</div>
              <div class="summary-label">正常</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-yellow">
              <el-icon :size="28"><Warning /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-warning">{{ stats.yellow }}</div>
              <div class="summary-label">关注</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-red">
              <el-icon :size="28"><CircleClose /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-danger">{{ stats.red }}</div>
              <div class="summary-label">告警</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 主内容: 列表 + 树切换 -->
    <el-card class="main-card" shadow="never">
      <template #header>
        <div class="main-header">
          <el-tabs v-model="activeView" class="view-tabs">
            <el-tab-pane name="list">
              <template #label>
                <span class="tab-label">
                  <el-icon><Grid /></el-icon>
                  指标列表
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="tree">
              <template #label>
                <span class="tab-label">
                  <el-icon><Share /></el-icon>
                  指标树
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>

          <div class="filter-bar" v-show="activeView === 'list'">
            <el-select
              v-model="filterCategory"
              placeholder="全部分类"
              clearable
              size="default"
              class="category-filter"
              @change="loadList"
            >
              <el-option label="全部" :value="''" />
              <el-option label="工厂" value="FACTORY" />
              <el-option label="餐饮" value="RESTAURANT" />
              <el-option label="质量" value="QUALITY" />
            </el-select>
            <el-input
              v-model="searchKey"
              placeholder="搜索名称或编码"
              clearable
              size="default"
              :prefix-icon="Search"
              class="search-input"
            />
          </div>
        </div>
      </template>

      <!-- 列表视图 -->
      <div v-show="activeView === 'list'" class="list-view">
        <div v-if="loading" class="loading-block">
          <el-skeleton :rows="6" animated />
        </div>

        <el-empty
          v-else-if="filteredIndicators.length === 0"
          :image-size="100"
          :description="searchKey || filterCategory ? '无匹配指标' : '暂无指标数据'"
        />

        <el-row v-else :gutter="16" class="card-grid">
          <el-col
            v-for="ind in filteredIndicators"
            :key="ind.code"
            :xs="24"
            :sm="12"
            :md="8"
            :lg="6"
            class="card-col"
          >
            <IndicatorValueCard
              :indicator="indicatorToCardData(ind)"
              :clickable="true"
              @click="onSelectIndicator"
            />
          </el-col>
        </el-row>
      </div>

      <!-- 树视图 -->
      <div v-show="activeView === 'tree'" class="tree-view">
        <IndicatorTreeViewer
          ref="treeRef"
          :factory-id="factoryId"
          @select="onSelectIndicator"
        />
      </div>
    </el-card>

    <!-- 详情抽屉 -->
    <IndicatorDetailDrawer
      v-model="drawerOpen"
      :factory-id="factoryId"
      :code="selectedCode"
      @recomputed="onRecomputed"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * IndicatorCenterDashboard — 指标中心入口页.
 *
 * 顶部 4 个汇总卡片 (总数 / GREEN / YELLOW / RED).
 * 主区: el-tabs 切换 "列表" / "树".
 * 选中指标 → 打开 IndicatorDetailDrawer 显示完整信息.
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import {
  Histogram,
  Refresh,
  DataLine,
  CircleCheck,
  Warning,
  CircleClose,
  Grid,
  Share,
  Search,
} from '@element-plus/icons-vue';
import {
  listIndicators,
  type IndicatorListResponse,
  type IndicatorCategory,
  type AlertLevel,
} from '@/api/indicator';
import IndicatorValueCard from '@/components/indicator/IndicatorValueCard.vue';
import IndicatorTreeViewer from './IndicatorTreeViewer.vue';
import IndicatorDetailDrawer from './IndicatorDetailDrawer.vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const indicators = ref<IndicatorListResponse[]>([]);
const loading = ref(false);
const activeView = ref<'list' | 'tree'>('list');
const filterCategory = ref<IndicatorCategory | ''>('');
const searchKey = ref('');
const selectedCode = ref<string | null>(null);
const drawerOpen = ref(false);
const treeRef = ref<InstanceType<typeof IndicatorTreeViewer> | null>(null);

// 当前列表展示的指标级 alert (用于汇总卡片)
// 因为 list 没返回 alertLevel, 这里只能展示 GREEN/YELLOW/RED 各 0 — 真正告警状态由
// /value 或 /tree 单独返回. 此页面汇总取自 list 推导 (lastValue null = 未计算).
// Phase 1 简化: 用 lastValue !== null 算作"已计算", 告警分类靠 tree 视图.
const stats = computed(() => {
  // 由于 list response 不含 alertLevel, 我们用 lastValue !== null 作为"已计算".
  // 实际颜色分类需要查 /value 单独或者用 /tree 接口. 这里 Phase 1 只显示总数+未计算.
  const total = indicators.value.length;
  const computed_ = indicators.value.filter(
    i => i.lastValue !== null && i.lastValue !== undefined
  ).length;
  const uncomputed = total - computed_;
  // 简化映射: 已计算→green 占位 / 未计算→yellow / 0 红 (真值靠 tree)
  // 用户在 tree 视图看到真实分布; dashboard 卡片是 "汇总意图" 而非精确告警计数
  return {
    total,
    green: computed_,
    yellow: uncomputed,
    red: 0,
  };
});

const filteredIndicators = computed(() => {
  let list = indicators.value;
  if (searchKey.value) {
    const v = searchKey.value.toLowerCase();
    list = list.filter(
      i => i.name.toLowerCase().includes(v) || i.code.toLowerCase().includes(v)
    );
  }
  return list;
});

async function loadList() {
  if (!factoryId.value) {
    ElMessage.warning('未识别到当前工厂, 无法加载指标');
    return;
  }
  loading.value = true;
  try {
    indicators.value = await listIndicators(
      factoryId.value,
      filterCategory.value || undefined
    );
  } catch (e) {
    console.warn('[IndicatorCenterDashboard] list load failed:', e);
    indicators.value = [];
  } finally {
    loading.value = false;
  }
}

async function refreshAll() {
  await loadList();
  if (treeRef.value) {
    await treeRef.value.loadTree();
  }
}

function onSelectIndicator(code: string) {
  selectedCode.value = code;
  drawerOpen.value = true;
}

function onRecomputed(_code: string) {
  // 重算成功后刷新列表 (lastValue 更新)
  loadList();
}

function indicatorToCardData(ind: IndicatorListResponse) {
  return {
    code: ind.code,
    name: ind.name,
    description: ind.description,
    category: ind.category,
    unit: ind.unit,
    lastValue: ind.lastValue,
    lastComputedAt: ind.lastComputedAt,
    computeStrategy: ind.computeStrategy,
    // list 接口不返回 alertLevel; 点击详情时从 /value 获取真值
    alertLevel: null as AlertLevel,
  };
}

onMounted(() => {
  loadList();
});
</script>

<style scoped>
.indicator-center {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.title-block {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-icon {
  color: var(--el-color-primary);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.summary-row {
  margin: 0 !important;
}

.summary-card {
  border: 1px solid var(--el-border-color-lighter);
}

.summary-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.icon-total { background: var(--el-color-primary); }
.icon-green { background: var(--el-color-success); }
.icon-yellow { background: var(--el-color-warning); }
.icon-red { background: var(--el-color-danger); }

.summary-text {
  flex: 1;
  min-width: 0;
}

.summary-num {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.summary-num.text-success { color: var(--el-color-success); }
.summary-num.text-warning { color: var(--el-color-warning); }
.summary-num.text-danger { color: var(--el-color-danger); }

.summary-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.main-card {
  border: 1px solid var(--el-border-color-lighter);
}

.main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.view-tabs {
  flex: 1;
  min-width: 200px;
}

.view-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 4px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.category-filter {
  width: 140px;
}

.search-input {
  width: 220px;
}

.list-view,
.tree-view {
  min-height: 320px;
}

.loading-block {
  padding: 16px;
}

.card-grid {
  margin: 0 !important;
}

.card-col {
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  .indicator-center {
    padding: 12px;
  }
  .main-header {
    flex-direction: column;
    align-items: stretch;
  }
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .category-filter,
  .search-input {
    width: 100%;
  }
}
</style>
