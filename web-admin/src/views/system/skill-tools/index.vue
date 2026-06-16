<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Search, Refresh, Plus, Delete, View } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  listSkills, getSkillDetail, createSkill, toggleSkill, deleteSkill,
  getRecommendations, getPatterns,
  type SkillInfo, type SkillRecommendation, type CoOccurrence, type ToolSequence
} from '@/api/governance';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

// ============ Skills Tab ============
const loading = ref(false);
const skills = ref<SkillInfo[]>([]);
const searchKeyword = ref('');
const activeTab = ref('skills');

const filteredSkills = computed(() => {
  if (!searchKeyword.value) return skills.value;
  const kw = searchKeyword.value.toLowerCase();
  return skills.value.filter(s =>
    s.name.toLowerCase().includes(kw) ||
    s.displayName.toLowerCase().includes(kw) ||
    (s.description || '').toLowerCase().includes(kw)
  );
});

// ============ Detail Drawer ============
const drawerVisible = ref(false);
const selectedSkill = ref<SkillInfo | null>(null);

// ============ Create Dialog ============
const createDialogVisible = ref(false);
const createForm = ref({
  name: '',
  displayName: '',
  tools: '',
  triggers: '',
  description: '',
  category: 'operations'
});

// ============ Recommendations Tab ============
const recommendations = ref<SkillRecommendation[]>([]);
const recLoading = ref(false);
const recDays = ref(30);

// ============ Patterns Tab ============
const patterns = ref<{ co_occurrences: CoOccurrence[]; sequences: ToolSequence[] }>({
  co_occurrences: [], sequences: []
});
const patLoading = ref(false);
const patDays = ref(30);

// ============ 初始化 ============
onMounted(() => {
  loadSkills();
});

// ============ Skills ============
async function loadSkills() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listSkills(factoryId.value);
    if (res.success && res.data) {
      skills.value = res.data;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载 Skill 列表失败');
  } finally {
    loading.value = false;
  }
}

function openDetail(skill: SkillInfo) {
  selectedSkill.value = skill;
  drawerVisible.value = true;
}

async function handleToggle(skill: SkillInfo, enabled: boolean) {
  if (!factoryId.value) return;
  try {
    const res = await toggleSkill(factoryId.value, skill.name, enabled);
    if (res.success) {
      ElMessage.success(`Skill "${skill.displayName}" 已${enabled ? '启用' : '禁用'}`);
      loadSkills();
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  }
}

async function handleDelete(skill: SkillInfo) {
  if (!factoryId.value) return;
  if (skill.source !== 'database') {
    ElMessage.warning('仅数据库来源的 Skill 可删除');
    return;
  }
  try {
    await ElMessageBox.confirm(`确定删除 Skill "${skill.displayName}"？`, '确认删除', { type: 'warning' });
    const res = await deleteSkill(factoryId.value, skill.name);
    if (res.success) {
      ElMessage.success('删除成功');
      loadSkills();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch {
    // cancelled
  }
}

async function handleCreate() {
  if (!factoryId.value) return;
  const tools = createForm.value.tools.split(',').map(t => t.trim()).filter(Boolean);
  const triggers = createForm.value.triggers ? createForm.value.triggers.split(',').map(t => t.trim()).filter(Boolean) : undefined;
  if (!createForm.value.name?.trim()) {
    ElMessage.warning('请填写 Skill 名称');
    return;
  }
  if (tools.length === 0) {
    ElMessage.warning('请填写工具列表 (逗号分隔)');
    return;
  }
  try {
    const res = await createSkill(factoryId.value, {
      name: createForm.value.name,
      displayName: createForm.value.displayName || undefined,
      tools,
      triggers,
      description: createForm.value.description || undefined,
      category: createForm.value.category
    });
    if (res.success) {
      ElMessage.success('Skill 创建成功');
      createDialogVisible.value = false;
      resetCreateForm();
      loadSkills();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

function resetCreateForm() {
  createForm.value = { name: '', displayName: '', tools: '', triggers: '', description: '', category: 'operations' };
}

// ============ Recommendations ============
async function loadRecommendations() {
  if (!factoryId.value) return;
  recLoading.value = true;
  try {
    const res = await getRecommendations(factoryId.value, recDays.value);
    if (res.success && res.data) {
      recommendations.value = res.data;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载推荐失败');
  } finally {
    recLoading.value = false;
  }
}

async function adoptRecommendation(rec: SkillRecommendation) {
  if (!factoryId.value) return;
  try {
    const res = await createSkill(factoryId.value, {
      name: rec.suggestedName,
      displayName: rec.suggestedDisplayName,
      tools: rec.tools,
      triggers: rec.suggestedTriggers,
      description: rec.reason,
      category: 'auto-composed'
    });
    if (res.success) {
      ElMessage.success(`Skill "${rec.suggestedDisplayName}" 创建成功`);
      loadRecommendations();
      loadSkills();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

// ============ Patterns ============
async function loadPatterns() {
  if (!factoryId.value) return;
  patLoading.value = true;
  try {
    const res = await getPatterns(factoryId.value, patDays.value);
    if (res.success && res.data) {
      patterns.value = res.data;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载模式失败');
  } finally {
    patLoading.value = false;
  }
}

function onTabChange(tab: string) {
  if (tab === 'recommendations' && recommendations.value.length === 0) {
    loadRecommendations();
  } else if (tab === 'patterns' && patterns.value.co_occurrences.length === 0) {
    loadPatterns();
  }
}

function sourceLabel(source: string) {
  if (source?.startsWith('database')) return '数据库';
  if (source?.startsWith('file')) return '文件';
  return '内置';
}

function sourceType(source: string) {
  if (source?.startsWith('database')) return 'warning';
  if (source?.startsWith('file')) return '';
  return 'info';
}

function confidenceColor(score: number) {
  if (score >= 0.8) return '#67c23a';
  if (score >= 0.5) return '#e6a23c';
  return '#f56c6c';
}
</script>

<template>
  <div class="page-container">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ========== Tab 1: Skill 列表 ========== -->
      <el-tab-pane label="Skill 管理" name="skills">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <div class="search-area">
                <el-input
                  v-model="searchKeyword"
                  placeholder="搜索 Skill 名称/描述..."
                  :prefix-icon="Search"
                  clearable
                  style="width: 300px"
                />
                <el-button :icon="Refresh" @click="loadSkills">刷新</el-button>
              </div>
              <el-button v-if="canWrite" type="primary" :icon="Plus" @click="createDialogVisible = true">
                创建 Skill
              </el-button>
            </div>
          </template>

          <el-table :data="filteredSkills" v-loading="loading" stripe empty-text="暂无 Skill">
            <el-table-column prop="displayName" label="名称" min-width="140">
              <template #default="{ row }">
                <div>
                  <strong>{{ row.displayName }}</strong>
                  <div style="color: #909399; font-size: 12px">{{ row.name }}</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="工具" min-width="200">
              <template #default="{ row }">
                <el-tag v-for="tool in (row.tools || []).slice(0, 3)" :key="tool" size="small" style="margin: 2px">
                  {{ tool }}
                </el-tag>
                <el-tag v-if="(row.tools || []).length > 3" size="small" type="info" style="margin: 2px">
                  +{{ row.tools.length - 3 }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="sourceType(row.source)" size="small">{{ sourceLabel(row.source) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本" prop="version" width="70" align="center" />
            <el-table-column label="启用" width="70" align="center">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :disabled="!canWrite || !row.source?.startsWith('database')"
                  @change="(val: boolean) => handleToggle(row, val)"
                  size="small"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right" align="center">
              <template #default="{ row }">
                <el-button type="primary" link :icon="View" @click="openDetail(row)">详情</el-button>
                <el-button
                  v-if="canWrite && row.source?.startsWith('database')"
                  type="danger" link :icon="Delete"
                  @click="handleDelete(row)"
                >删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ========== Tab 2: 自动推荐 ========== -->
      <el-tab-pane label="自动推荐" name="recommendations">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <div class="search-area">
                <span style="margin-right: 8px">回溯天数:</span>
                <el-input-number v-model="recDays" :min="7" :max="90" :step="7" style="width: 120px" />
                <el-button :icon="Refresh" @click="loadRecommendations" style="margin-left: 12px">分析</el-button>
              </div>
              <el-tag type="info">基于 tool_call_records 共现挖掘</el-tag>
            </div>
          </template>

          <el-table :data="recommendations" v-loading="recLoading" stripe empty-text="暂无推荐">
            <el-table-column prop="suggestedDisplayName" label="推荐名称" min-width="160" />
            <el-table-column label="包含工具" min-width="220">
              <template #default="{ row }">
                <el-tag v-for="tool in row.tools" :key="tool" size="small" style="margin: 2px">{{ tool }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="证据" width="80" align="center">
              <template #default="{ row }">{{ row.evidenceCount }}次</template>
            </el-table-column>
            <el-table-column label="置信度" width="90" align="center">
              <template #default="{ row }">
                <span :style="{ color: confidenceColor(row.confidenceScore), fontWeight: 'bold' }">
                  {{ (row.confidenceScore * 100).toFixed(0) }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.alreadyCoveredBySkill" type="info" size="small">已覆盖</el-tag>
                <el-tag v-else type="success" size="small">可创建</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="canWrite && !row.alreadyCoveredBySkill"
                  type="primary" link size="small"
                  @click="adoptRecommendation(row)"
                >采纳</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ========== Tab 3: 共现模式 ========== -->
      <el-tab-pane label="共现模式" name="patterns">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <div class="search-area">
                <span style="margin-right: 8px">回溯天数:</span>
                <el-input-number v-model="patDays" :min="7" :max="90" :step="7" style="width: 120px" />
                <el-button :icon="Refresh" @click="loadPatterns" style="margin-left: 12px">分析</el-button>
              </div>
            </div>
          </template>

          <h4 style="margin: 0 0 12px">工具共现 ({{ patterns.co_occurrences.length }} 组)</h4>
          <el-table :data="patterns.co_occurrences" v-loading="patLoading" stripe empty-text="暂无数据" style="margin-bottom: 24px">
            <el-table-column prop="toolA" label="工具 A" min-width="180" />
            <el-table-column prop="toolB" label="工具 B" min-width="180" />
            <el-table-column label="共现会话数" prop="sessionCount" width="120" align="center" />
            <el-table-column label="支持率" width="100" align="center">
              <template #default="{ row }">{{ (row.supportRate * 100).toFixed(1) }}%</template>
            </el-table-column>
          </el-table>

          <h4 style="margin: 0 0 12px">有序序列 ({{ patterns.sequences.length }} 组)</h4>
          <el-table :data="patterns.sequences" v-loading="patLoading" stripe empty-text="暂无数据">
            <el-table-column label="工具序列" min-width="300">
              <template #default="{ row }">
                <span v-for="(tool, idx) in row.tools" :key="idx">
                  <el-tag size="small">{{ tool }}</el-tag>
                  <span v-if="idx < row.tools.length - 1" style="margin: 0 4px; color: #909399"> → </span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="出现次数" prop="occurrences" width="100" align="center" />
            <el-table-column label="平均耗时" width="100" align="center">
              <template #default="{ row }">{{ row.avgTotalTimeMs.toFixed(0) }}ms</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ========== Detail Drawer ========== -->
    <el-drawer v-model="drawerVisible" title="Skill 详情" size="480px" direction="rtl">
      <template v-if="selectedSkill">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="名称">{{ selectedSkill.name }}</el-descriptions-item>
          <el-descriptions-item label="显示名称">{{ selectedSkill.displayName }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ selectedSkill.description || '无' }}</el-descriptions-item>
          <el-descriptions-item label="来源">
            <el-tag :type="sourceType(selectedSkill.source)" size="small">{{ sourceLabel(selectedSkill.source) }}</el-tag>
            <span style="margin-left: 8px; color: #909399">{{ selectedSkill.source }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="版本">{{ selectedSkill.version }}</el-descriptions-item>
          <el-descriptions-item label="执行图">{{ selectedSkill.hasExecutionGraph ? '有' : '无' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider>包含工具 ({{ selectedSkill.tools?.length || 0 }})</el-divider>
        <div class="tag-group">
          <el-tag v-for="tool in selectedSkill.tools" :key="tool" style="margin: 4px">{{ tool }}</el-tag>
        </div>

        <el-divider>触发词 ({{ selectedSkill.triggers?.length || 0 }})</el-divider>
        <div class="tag-group">
          <el-tag v-for="trigger in selectedSkill.triggers" :key="trigger" type="success" style="margin: 4px">
            {{ trigger }}
          </el-tag>
        </div>
      </template>
    </el-drawer>

    <!-- ========== Create Dialog ========== -->
    <el-dialog v-model="createDialogVisible" title="创建 Skill" width="520px" :close-on-click-modal="false">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="slug 格式，如 material-quality-combo" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="createForm.displayName" placeholder="如：库存质检联查（可留空自动生成）" />
        </el-form-item>
        <el-form-item label="工具列表" required>
          <el-input v-model="createForm.tools" placeholder="逗号分隔，如 material_batch_query,quality_check_query" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="触发词">
          <el-input v-model="createForm.triggers" placeholder="逗号分隔（可留空自动生成）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" placeholder="Skill 功能描述" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="createForm.category" style="width: 100%">
            <el-option label="运营" value="operations" />
            <el-option label="分析" value="analytics" />
            <el-option label="告警" value="alerting" />
            <el-option label="报表" value="reporting" />
            <el-option label="自动组合" value="auto-composed" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.search-area {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tag-group {
  display: flex;
  flex-wrap: wrap;
}
</style>
