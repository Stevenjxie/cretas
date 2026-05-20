<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import { Search, Refresh, View } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import { getIntentList, getIntentCategories, toggleIntentActive, type AIIntentConfig } from '@/api/intent';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const tableData = ref<AIIntentConfig[]>([]);
const categories = ref<string[]>([]);

// 搜索表单
const searchForm = ref({
  keyword: '',
  category: '',
  sensitivityLevel: '',
  isActive: '' as '' | 'true' | 'false'
});

// 详情抽屉
const drawerVisible = ref(false);
const selectedIntent = ref<AIIntentConfig | null>(null);

// ============ 初始化 ============
onMounted(() => {
  loadCategories();
  loadData();
});

// ============ 数据加载 ============
async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const params: Record<string, unknown> = {};
    if (searchForm.value.keyword) params.keyword = searchForm.value.keyword;
    if (searchForm.value.category) params.category = searchForm.value.category;
    if (searchForm.value.sensitivityLevel) params.sensitivityLevel = searchForm.value.sensitivityLevel;
    if (searchForm.value.isActive !== '') params.isActive = searchForm.value.isActive === 'true';

    const response = await getIntentList(factoryId.value, params);
    if (response.success && response.data) {
      tableData.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadCategories() {
  if (!factoryId.value) return;
  try {
    const response = await getIntentCategories(factoryId.value);
    if (response.success && response.data) {
      categories.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载分类失败');
    }
  } catch (error) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    console.error('加载分类失败:', error);
    handleCatchError(error, '加载分类失败');
  }
}

// ============ 搜索操作 ============
function handleSearch() {
  loadData();
}

function handleReset() {
  searchForm.value = { keyword: '', category: '', sensitivityLevel: '', isActive: '' };
  handleSearch();
}

// ============ 状态切换 ============
async function handleToggleActive(row: AIIntentConfig) {
  if (!factoryId.value) return;

  try {
    const response = await toggleIntentActive(factoryId.value, row.intentCode, !row.isActive);
    if (response.success) {
      row.isActive = !row.isActive;
      ElMessage.success(`已${row.isActive ? '启用' : '禁用'} ${row.intentName}`);
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast; this is debug-only log.
    console.error('切换状态失败:', error);
  }
}

// ============ 详情抽屉 ============
function showDetail(row: AIIntentConfig) {
  selectedIntent.value = row;
  drawerVisible.value = true;
}

// ============ 辅助方法 ============
const sensitivityOptions = [
  { value: 'LOW', label: '低', type: 'success' },
  { value: 'MEDIUM', label: '中', type: 'warning' },
  { value: 'HIGH', label: '高', type: 'danger' },
  { value: 'CRITICAL', label: '关键', type: '' }
];

function getSensitivityType(level: string): string {
  const option = sensitivityOptions.find(o => o.value === level);
  return option?.type || 'info';
}

function getSensitivityLabel(level: string): string {
  const option = sensitivityOptions.find(o => o.value === level);
  return option?.label || level;
}

function getCategoryType(category: string): string {
  const map: Record<string, string> = {
    'ANALYSIS': 'primary',
    'DATA_OP': 'warning',
    'FORM': 'success',
    'SCHEDULE': 'info',
    'SYSTEM': 'danger',
    'MATERIAL': '',
    'QUALITY': 'success',
    'SHIPMENT': 'warning',
    'USER': 'primary',
    'CONFIG': 'info',
    'PROCESSING': 'warning',
    'SCALE': '',
    'META': 'danger'
  };
  return map[category] || 'info';
}

function parseJsonArray(jsonStr?: string): string[] {
  if (!jsonStr) return [];
  try {
    return JSON.parse(jsonStr);
  } catch {
    return [];
  }
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleString('zh-CN');
}
</script>

<template>
  <div class="page-container">
    <!-- 搜索栏 -->
    <el-card class="search-card">
      <el-form :model="searchForm" inline>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="意图代码/名称"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="searchForm.category" placeholder="全部分类" clearable style="width: 150px">
            <el-option
              v-for="cat in categories"
              :key="cat"
              :label="cat"
              :value="cat"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="敏感度">
          <el-select v-model="searchForm.sensitivityLevel" placeholder="全部" clearable style="width: 120px">
            <el-option
              v-for="option in sensitivityOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.isActive" placeholder="全部" clearable style="width: 100px">
            <el-option label="启用" value="true" />
            <el-option label="禁用" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 数据表格 -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span>AI意图配置</span>
          <span class="header-tip">共 {{ tableData.length }} 个意图</span>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border>
        <el-table-column prop="intentCode" label="意图代码" width="200" show-overflow-tooltip />
        <el-table-column prop="intentName" label="意图名称" width="150" />
        <el-table-column prop="intentCategory" label="分类" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getCategoryType(row.intentCategory)" size="small">
              {{ row.intentCategory }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sensitivityLevel" label="敏感度" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="getSensitivityType(row.sensitivityLevel)"
              :class="{ 'critical-tag': row.sensitivityLevel === 'CRITICAL' }"
              size="small"
            >
              {{ getSensitivityLabel(row.sensitivityLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quotaCost" label="配额消耗" width="90" align="center" />
        <el-table-column prop="priority" label="优先级" width="80" align="center" />
        <el-table-column prop="semanticPath" label="语义路径" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="semantic-path">{{ row.semanticPath || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="isActive" label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.isActive"
              :disabled="!canWrite"
              @change="handleToggleActive(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="showDetail(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      :title="selectedIntent?.intentName || '意图详情'"
      size="500px"
    >
      <template v-if="selectedIntent">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="意图代码">
            <code>{{ selectedIntent.intentCode }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="意图名称">
            {{ selectedIntent.intentName }}
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <el-tag :type="getCategoryType(selectedIntent.intentCategory)" size="small">
              {{ selectedIntent.intentCategory }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="敏感度">
            <el-tag
              :type="getSensitivityType(selectedIntent.sensitivityLevel)"
              :class="{ 'critical-tag': selectedIntent.sensitivityLevel === 'CRITICAL' }"
              size="small"
            >
              {{ getSensitivityLabel(selectedIntent.sensitivityLevel) }}
            </el-tag>
            <span v-if="selectedIntent.requiresApproval" class="approval-badge">需审批</span>
          </el-descriptions-item>
          <el-descriptions-item label="语义路径">
            <span class="semantic-path">
              {{ selectedIntent.semanticDomain }}.{{ selectedIntent.semanticAction }}.{{ selectedIntent.semanticObject }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="配额消耗">
            {{ selectedIntent.quotaCost }}
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            {{ selectedIntent.priority }}
          </el-descriptions-item>
          <el-descriptions-item label="Tool 名称">
            <code v-if="selectedIntent.toolName">{{ selectedIntent.toolName }}</code>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="描述">
            {{ selectedIntent.description || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider>关键词</el-divider>
        <div class="keywords-section">
          <el-tag
            v-for="kw in parseJsonArray(selectedIntent.keywords)"
            :key="kw"
            class="keyword-tag"
            size="small"
          >
            {{ kw }}
          </el-tag>
          <span v-if="!parseJsonArray(selectedIntent.keywords).length" class="text-muted">无</span>
        </div>

        <el-divider>负向关键词</el-divider>
        <div class="keywords-section">
          <el-tag
            v-for="kw in parseJsonArray(selectedIntent.negativeKeywords)"
            :key="kw"
            type="danger"
            class="keyword-tag"
            size="small"
          >
            {{ kw }}
          </el-tag>
          <span v-if="!parseJsonArray(selectedIntent.negativeKeywords).length" class="text-muted">无</span>
        </div>

        <el-divider>允许的角色</el-divider>
        <div class="keywords-section">
          <el-tag
            v-for="role in parseJsonArray(selectedIntent.requiredRoles)"
            :key="role"
            type="info"
            class="keyword-tag"
            size="small"
          >
            {{ role }}
          </el-tag>
          <span v-if="!parseJsonArray(selectedIntent.requiredRoles).length" class="text-muted">所有角色</span>
        </div>

        <el-divider>其他信息</el-divider>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="版本">
            v{{ selectedIntent.configVersion }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDate(selectedIntent.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatDate(selectedIntent.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.search-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-tip {
    font-size: 14px;
    color: #909399;
  }
}

.semantic-path {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #606266;
}

.critical-tag {
  background-color: #722ed1;
  border-color: #722ed1;
  color: white;
}

.approval-badge {
  margin-left: 8px;
  padding: 2px 6px;
  background-color: #fff1f0;
  color: #f5222d;
  border-radius: 4px;
  font-size: 12px;
}

.keywords-section {
  min-height: 32px;

  .keyword-tag {
    margin: 4px;
  }
}

.text-muted {
  color: #909399;
  font-size: 13px;
}

code {
  background-color: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
}
</style>
