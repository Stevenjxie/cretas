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
const error = ref('');

const STATUS_TABS: Array<{ key: CandidateStatus; label: string }> = [
  { key: 'pending', label: '待审核' },
  { key: 'approved', label: '已通过' },
  { key: 'rejected', label: '已否决' },
];

const filtered = computed(() => candidates.value.filter((c) => c.status === statusFilter.value));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    candidates.value = await flywheelApi.candidates(domain.value);
  } catch (e) {
    // 禁止降级处理: 失败就明确失败, candidates 保持空数组 (晋升审核工作台绝不能显示编造的
    // 候选问法 — 否则人可能对着不存在的问法点"通过", 写进生产 ai_promoted_routes)。
    const msg = e instanceof Error ? e.message : String(e);
    error.value = msg;
    candidates.value = [];
    ElMessage({ message: `加载晋升候选失败: ${msg}`, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

// ============ 一键通过 ============
async function handleApprove(row: FlywheelCandidate) {
  await ElMessage.closeAll();
  const { ElMessageBox } = await import('element-plus');
  try {
    await ElMessageBox.confirm(
      `确认将问法「${row.query_text}」的计划晋升入 ai_promoted_routes？晋升后该问法将走确定性直答通道 (不再消耗 LLM 调用)。`,
      '通过晋升候选',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'success' },
    );
  } catch {
    // 用户取消 confirm dialog — 正常退出, 不算错误
    return;
  }
  // ⚠️ 之前的实现把这次 try/catch 和上面的 confirm 合并在一起, 导致 approveCandidate 真实失败
  // (例如卡5b ai_promoted_routes 表未建好时的 503) 被当成"用户取消"静默吞掉, 界面上什么都不
  // 提示, row.status 也没变 — 人审员会以为"点了但没反应", 反复点击。分离成独立 try/catch 后,
  // 真实写入失败会明确展示 (sticky toast), 不会被误判为用户主动取消。
  try {
    await flywheelApi.approveCandidate(row.id, domain.value);
    row.status = 'approved';
    ElMessage.success(`已通过「${row.query_text}」`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: `晋升写入失败, 「${row.query_text}」未生效: ${msg}`, type: 'error', duration: 0, showClose: true });
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
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: `否决失败: ${msg}`, type: 'error', duration: 0, showClose: true });
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
    // seed-import 是卡5b 契约外扩展点, 待补齐前真实模式下预期 404 — 按正常失败展示,
    // 不当成功处理 (导入 dialog 不会关闭, 用户能看到明确失败原因)。
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: `批量导入失败: ${msg}`, type: 'error', duration: 0, showClose: true });
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

    <!-- 禁止降级处理: 加载失败时下面表格是空的 (candidates=[]), 这里明确告知"接口不可用",
         防止人审员误以为"当前没有候选" (业务空态) 而不是"看不到真实候选" (接口故障)。 -->
    <el-alert
      v-if="error"
      :title="`后端接口不可用: ${error}`"
      type="error"
      :closable="false"
      show-icon
      class="load-error-alert"
      data-test="flywheel-candidates-error"
    />

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

      <el-table :data="filtered" v-loading="loading" stripe :empty-text="error ? '加载失败, 详见上方提示' : '暂无候选'">
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
.load-error-alert {
  margin-bottom: 16px;
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
