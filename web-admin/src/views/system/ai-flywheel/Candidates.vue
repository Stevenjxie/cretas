<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { Refresh, Upload, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import FlywheelHeader from './components/FlywheelHeader.vue';
import { useFlywheelDomain } from './composables/useFlywheelDomain';
import { flywheelApi, type FlywheelCandidate, type CandidateStatus } from '@/api/smartbi/ai-flywheel';

const { domain } = useFlywheelDomain();
const loading = ref(false);
const candidates = ref<FlywheelCandidate[]>([]);
const statusFilter = ref<CandidateStatus>('pending');

const STATUS_TABS: Array<{ key: CandidateStatus; label: string }> = [
  { key: 'pending', label: '待审核' },
  { key: 'approved', label: '已通过' },
  { key: 'rejected', label: '已否决' },
];

const filtered = computed(() => candidates.value.filter((c) => c.status === statusFilter.value));

async function load() {
  loading.value = true;
  try {
    candidates.value = await flywheelApi.candidates(domain.value);
  } catch (e) {
    ElMessage.error('加载晋升候选失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    loading.value = false;
  }
}

// ============ 一键通过 ============
async function handleApprove(row: FlywheelCandidate) {
  try {
    await ElMessage.closeAll();
    const { ElMessageBox } = await import('element-plus');
    await ElMessageBox.confirm(
      `确认将问法「${row.query_text}」的计划晋升入 ai_promoted_routes？晋升后该问法将走确定性直答通道 (不再消耗 LLM 调用)。`,
      '通过晋升候选',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'success' },
    );
    await flywheelApi.approveCandidate(row.id, domain.value);
    row.status = 'approved';
    ElMessage.success(`已通过「${row.query_text}」`);
  } catch {
    // 用户取消
  }
}

// ============ 否决 (Rule 3: 标准原因 dropdown, 其他才 textarea) ============
const REJECT_REASONS = [
  '问法覆盖率不足, 再观察一周',
  '契约通过率不达标',
  '计划 JSON 槽位识别有误',
  '与已有晋升路由重复/冲突',
  '业务口径尚未确认',
  '其他',
];
const rejectDialogVisible = ref(false);
const rejectTarget = ref<FlywheelCandidate | null>(null);
const rejectReason = ref(REJECT_REASONS[0]);
const rejectOther = ref('');

function openReject(row: FlywheelCandidate) {
  rejectTarget.value = row;
  rejectReason.value = REJECT_REASONS[0];
  rejectOther.value = '';
  rejectDialogVisible.value = true;
}

async function confirmReject() {
  if (!rejectTarget.value) return;
  const reason = rejectReason.value === '其他' ? rejectOther.value.trim() : rejectReason.value;
  if (!reason) {
    ElMessage.warning('请填写否决原因');
    return;
  }
  try {
    await flywheelApi.rejectCandidate(rejectTarget.value.id, domain.value, reason);
    rejectTarget.value.status = 'rejected';
    rejectTarget.value.reject_reason = reason;
    ElMessage.success(`已否决「${rejectTarget.value.query_text}」`);
    rejectDialogVisible.value = false;
  } catch (e) {
    ElMessage.error('否决失败: ' + (e instanceof Error ? e.message : String(e)));
  }
}

// ============ 计划 JSON 详情 ============
const detailDrawerVisible = ref(false);
const detailTarget = ref<FlywheelCandidate | null>(null);
function openDetail(row: FlywheelCandidate) {
  detailTarget.value = row;
  detailDrawerVisible.value = true;
}
function prettyJson(v: unknown): string {
  return JSON.stringify(v, null, 2);
}

// ============ manual_seed 批量导入 ============
const seedDialogVisible = ref(false);
const seedText = ref('');
const seedImporting = ref(false);
const seedLineCount = computed(() =>
  seedText.value.split('\n').map((l) => l.trim()).filter(Boolean).length,
);

async function submitSeedImport() {
  const queries = seedText.value.split('\n').map((l) => l.trim()).filter(Boolean);
  if (queries.length === 0) {
    ElMessage.warning('请至少粘贴一条问题');
    return;
  }
  seedImporting.value = true;
  try {
    const res = await flywheelApi.seedImportCandidates(domain.value, queries);
    ElMessage.success(`已提交 ${res.imported} 条问题离线出计划, 完成后将出现在「待审核」队列`);
    seedDialogVisible.value = false;
    seedText.value = '';
    load();
  } catch (e) {
    ElMessage.error('批量导入失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    seedImporting.value = false;
  }
}

function confidenceColor(v: number): string {
  if (v >= 0.85) return '#67c23a';
  if (v >= 0.65) return '#e6a23c';
  return '#f56c6c';
}
function sourceLabel(s: string): string {
  return s === 'manual_seed' ? 'manual_seed' : '自动捕获';
}

onMounted(load);
watch(domain, load);
</script>

<template>
  <div class="page-container">
    <FlywheelHeader v-model:domain="domain" />

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="tabs-area">
            <el-radio-group v-model="statusFilter" size="small">
              <el-radio-button v-for="t in STATUS_TABS" :key="t.key" :value="t.key">
                {{ t.label }}
                <span v-if="t.key === 'pending'" class="pending-badge">
                  ({{ candidates.filter((c) => c.status === 'pending').length }})
                </span>
              </el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
          </div>
          <el-button type="primary" :icon="Upload" @click="seedDialogVisible = true">manual_seed 批量导入</el-button>
        </div>
      </template>

      <el-table :data="filtered" v-loading="loading" stripe empty-text="暂无候选">
        <el-table-column label="问法" min-width="220">
          <template #default="{ row }">
            <div class="query-cell">
              <strong>{{ row.query_text }}</strong>
              <el-tag size="small" :type="row.source === 'manual_seed' ? 'warning' : 'info'" class="source-tag">
                {{ sourceLabel(row.source) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="频次" prop="frequency" width="90" sortable align="center" />
        <el-table-column label="置信度" width="100" align="center" sortable :sort-method="(a: FlywheelCandidate, b: FlywheelCandidate) => a.confidence - b.confidence">
          <template #default="{ row }">
            <span :style="{ color: confidenceColor(row.confidence), fontWeight: 'bold' }">
              {{ (row.confidence * 100).toFixed(0) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="契约通过率" width="110" align="center">
          <template #default="{ row }">{{ (row.contract_pass_rate * 100).toFixed(0) }}%</template>
        </el-table-column>
        <el-table-column label="最近真实答案预览" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sample_answer }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link :icon="View" @click="openDetail(row)">计划详情</el-button>
            <template v-if="row.status === 'pending'">
              <el-button type="success" link @click="handleApprove(row)">通过</el-button>
              <el-button type="danger" link @click="openReject(row)">否决</el-button>
            </template>
            <el-tag v-else-if="row.status === 'approved'" type="success" size="small">已晋升</el-tag>
            <el-tooltip v-else :content="row.reject_reason || ''" placement="top">
              <el-tag type="danger" size="small">已否决</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 计划 JSON 详情 drawer -->
    <el-drawer v-model="detailDrawerVisible" title="候选计划详情" size="480px" direction="rtl">
      <template v-if="detailTarget">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="问法">{{ detailTarget.query_text }}</el-descriptions-item>
          <el-descriptions-item label="频次">{{ detailTarget.frequency }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ (detailTarget.confidence * 100).toFixed(0) }}%</el-descriptions-item>
          <el-descriptions-item label="契约通过率">{{ (detailTarget.contract_pass_rate * 100).toFixed(0) }}%</el-descriptions-item>
          <el-descriptions-item label="来源">{{ sourceLabel(detailTarget.source) }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>计划 JSON (可读化)</el-divider>
        <pre class="plan-json">{{ prettyJson(detailTarget.plan_json) }}</pre>
        <el-divider>最近真实答案</el-divider>
        <p class="sample-answer">{{ detailTarget.sample_answer }}</p>
      </template>
    </el-drawer>

    <!-- 否决 dialog (Rule 3: 标准原因下拉) -->
    <el-dialog v-model="rejectDialogVisible" title="否决晋升候选" width="480px" :close-on-click-modal="false">
      <p v-if="rejectTarget" class="reject-context">问法: 「{{ rejectTarget.query_text }}」</p>
      <el-form label-width="80px">
        <el-form-item label="否决原因">
          <el-select v-model="rejectReason" style="width: 100%">
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectReason === '其他'" label="补充说明">
          <el-input v-model="rejectOther" type="textarea" :rows="3" placeholder="请填写具体原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认否决</el-button>
      </template>
    </el-dialog>

    <!-- manual_seed 批量导入 dialog -->
    <el-dialog v-model="seedDialogVisible" title="manual_seed 批量导入" width="560px" :close-on-click-modal="false">
      <p class="seed-hint">
        粘贴客户常问问题清单 (每行一条) → 离线批量跑 LLM 出计划 → 结果进入「待审核」队列, 与自动捕获候选一样逐条人审。
      </p>
      <el-input
        v-model="seedText"
        type="textarea"
        :rows="10"
        placeholder="例如:&#10;这个月营业额多少&#10;哪个门店卖得最好&#10;毛利率最低的菜品有哪些"
      />
      <div class="seed-count">已粘贴 {{ seedLineCount }} 条</div>
      <template #footer>
        <el-button @click="seedDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="seedImporting" @click="submitSeedImport">提交离线出计划</el-button>
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
  flex-wrap: wrap;
  gap: 10px;
}
.tabs-area {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.pending-badge {
  color: #f56c6c;
  font-weight: 600;
}
.query-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.source-tag {
  flex-shrink: 0;
}
.plan-json {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.sample-answer {
  color: #606266;
  line-height: 1.6;
}
.reject-context {
  margin: 0 0 12px;
  color: #606266;
  font-size: 13px;
}
.seed-hint {
  color: #909399;
  font-size: 13px;
  margin: 0 0 10px;
}
.seed-count {
  text-align: right;
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

@media (max-width: 768px) {
  .page-container {
    padding: 12px;
  }
}
</style>
