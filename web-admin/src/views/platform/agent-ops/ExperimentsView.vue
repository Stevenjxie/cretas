<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { compareExperiments, listEvalSets, listExperiments, rerunExperiment, runRuntimeShadow } from '@/api/agent-ops';
import type { EvalSetSummary, ExperimentCompare, ExperimentSummary } from '@/api/agent-ops';
import { InMemoryIdempotencyAttempts, stableBusinessSignature } from './idempotency';

const auth = useAuthStore();
const items = ref<ExperimentSummary[]>([]);
const loading = ref(false);
const comparing = ref(false);
const error = ref('');
const currentId = ref('');
const baselineId = ref('');
const comparison = ref<ExperimentCompare | null>(null);
const evalSets = ref<EvalSetSummary[]>([]);
const running = ref(false);
const rerunningId = ref('');
const idempotency = new InMemoryIdempotencyAttempts();
const runForm = reactive({
  evalSetId: '',
  promptSnapshotDigest: '',
  modelSnapshotDigest: '',
  toolSnapshotDigest: '',
  maxCases: 20,
  maxConcurrency: 2,
  perCaseTimeoutMs: 75000,
});
const canCompare = computed(() => currentId.value && baselineId.value && currentId.value !== baselineId.value);
const rerunnableItems = computed(() => items.value.filter((item) => item.operationKind !== 'RUNTIME_SHADOW'));

async function load() {
  if (!auth.factoryId) return;
  loading.value = true;
  error.value = '';
  try {
    const [experimentResponse, evalSetResponse] = await Promise.all([
      listExperiments(auth.factoryId),
      listEvalSets(auth.factoryId),
    ]);
    items.value = experimentResponse.data.items;
    evalSets.value = evalSetResponse.data.items;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Experiments 加载失败';
  } finally { loading.value = false; }
}

async function run() {
  if (!auth.factoryId || !runForm.evalSetId) {
    ElMessage.warning('请选择 Eval Set');
    return;
  }
  const digests = [
    runForm.promptSnapshotDigest,
    runForm.modelSnapshotDigest,
    runForm.toolSnapshotDigest,
  ];
  if (digests.some((digest) => !/^[0-9a-f]{64}$/.test(digest))) {
    ElMessage.warning('三个 Snapshot Digest 都必须是 64 位小写 SHA-256');
    return;
  }
  running.value = true;
  try {
    const businessBody = {
      schemaVersion: '1.0',
      evalSetId: runForm.evalSetId,
      configSnapshot: {
        promptSnapshotDigest: runForm.promptSnapshotDigest,
        modelSnapshotDigest: runForm.modelSnapshotDigest,
        toolSnapshotDigest: runForm.toolSnapshotDigest,
      },
      bounds: {
        maxCases: runForm.maxCases,
        maxConcurrency: runForm.maxConcurrency,
        perCaseTimeoutMs: runForm.perCaseTimeoutMs,
      },
    } as const;
    const action = 'run-runtime-shadow';
    const requestId = idempotency.requestId(action, stableBusinessSignature({
      factoryId: auth.factoryId,
      body: businessBody,
    }));
    await runRuntimeShadow(auth.factoryId, { ...businessBody, requestId });
    idempotency.complete(action, requestId);
    ElMessage.success('Runtime Shadow 已完成，actual snapshots 已由服务端冻结');
    await load();
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '实验运行失败');
  } finally {
    running.value = false;
  }
}

async function rerun(sourceExperimentId: string) {
  if (!auth.factoryId || rerunningId.value) return;
  const action = `rerun-experiment:${sourceExperimentId}`;
  const businessBody = { schemaVersion: '1.0', sourceExperimentId } as const;
  const requestId = idempotency.requestId(action, stableBusinessSignature({
    factoryId: auth.factoryId,
    body: businessBody,
  }));
  rerunningId.value = sourceExperimentId;
  try {
    await rerunExperiment(auth.factoryId, sourceExperimentId, {
      schemaVersion: '1.0',
      requestId,
    });
    idempotency.complete(action, requestId);
    ElMessage.success('Experiment rerun completed');
    await load();
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : 'Experiment rerun failed');
  } finally {
    rerunningId.value = '';
  }
}

async function compare() {
  if (!auth.factoryId || !canCompare.value) return;
  comparing.value = true;
  error.value = '';
  try {
    comparison.value = (await compareExperiments(auth.factoryId, currentId.value, baselineId.value)).data;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '实验比较失败';
  } finally { comparing.value = false; }
}

function pct(value: string): string { return `${(Number(value) * 100).toFixed(1)}%`; }
function deltaClass(value: string): string { return Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : 'neutral'; }
defineExpose({ rerun });
onMounted(load);
</script>

<template>
  <div class="panel" data-testid="experiments-view">
    <div class="panel-head"><div><h2>Experiments</h2><p>比较相同租户下的 Runtime Shadow 结果；actual snapshots 只由服务端真实运行生成。</p></div></div>
    <section class="run-card" data-testid="experiment-run-form">
      <div class="run-head">
        <div><h3>运行 Runtime Shadow</h3><p>服务端直接复用 bounded read-only runtime；客户端不提交逐条 actual，正常 run/event 不会被写入。</p></div>
        <el-button data-testid="run-experiment" type="primary" :loading="running" @click="run">自动运行并冻结</el-button>
      </div>
      <el-form label-position="top">
        <el-form-item label="Eval Set">
          <el-select data-testid="run-eval-set" v-model="runForm.evalSetId" placeholder="选择不可变 Eval Set 版本" filterable>
            <el-option v-for="item in evalSets" :key="item.evalSetId" :label="`${item.name} v${item.version}`" :value="item.evalSetId" />
          </el-select>
        </el-form-item>
        <div class="digest-grid">
          <el-form-item label="Prompt Snapshot SHA-256"><el-input data-testid="prompt-digest" v-model="runForm.promptSnapshotDigest" maxlength="64" /></el-form-item>
          <el-form-item label="Model Snapshot SHA-256"><el-input data-testid="model-digest" v-model="runForm.modelSnapshotDigest" maxlength="64" /></el-form-item>
          <el-form-item label="Tool Snapshot SHA-256"><el-input data-testid="tool-digest" v-model="runForm.toolSnapshotDigest" maxlength="64" /></el-form-item>
        </div>
        <div class="bounds-grid">
          <el-form-item label="Max Cases"><el-input-number v-model="runForm.maxCases" :min="1" :max="20" /></el-form-item>
          <el-form-item label="Concurrency"><el-input-number v-model="runForm.maxConcurrency" :min="1" :max="2" /></el-form-item>
          <el-form-item label="Per-case Timeout (ms)"><el-input-number v-model="runForm.perCaseTimeoutMs" :min="1000" :max="75000" /></el-form-item>
        </div>
      </el-form>
    </section>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="experiment-error" />
    <el-skeleton v-else-if="loading" :rows="5" animated />
    <el-empty v-else-if="items.length === 0" description="尚无离线实验结果。" data-testid="experiment-empty" />
    <template v-else>
      <div class="compare-bar">
        <el-select v-model="baselineId" placeholder="基线实验" filterable><el-option v-for="item in items" :key="item.experimentId" :label="`${item.evalSetName} v${item.evalSetVersion} · ${pct(item.aggregate.passRate)}`" :value="item.experimentId" /></el-select>
        <span>→</span>
        <el-select v-model="currentId" placeholder="当前实验" filterable><el-option v-for="item in items" :key="item.experimentId" :label="`${item.evalSetName} v${item.evalSetVersion} · ${pct(item.aggregate.passRate)}`" :value="item.experimentId" /></el-select>
        <el-button type="primary" :disabled="!canCompare" :loading="comparing" @click="compare">比较</el-button>
      </div>

      <section v-if="comparison" class="diff-card" data-testid="experiment-diff">
        <div><span class="label">Pass rate Δ</span><strong :class="deltaClass(comparison.passRateDelta)">{{ Number(comparison.passRateDelta) >= 0 ? '+' : '' }}{{ pct(comparison.passRateDelta) }}</strong></div>
        <div><span class="label">Eval Set</span><strong>v{{ comparison.baselineEvalSetVersion }} → v{{ comparison.currentEvalSetVersion }}</strong></div>
        <div><span class="label">Evaluator</span><strong>{{ comparison.baselineEvaluatorVersion }} → {{ comparison.currentEvaluatorVersion }}</strong></div>
        <div class="case-diff"><span class="positive">改善 {{ comparison.improvedCaseIds.length }}</span><span class="negative">回归 {{ comparison.regressedCaseIds.length }}</span></div>
        <el-alert v-if="!comparison.sameEvalSetVersion" title="Eval Set 版本不同，差异可能来自 Case 变化。" type="warning" :closable="false" />
        <div class="digest-diff" data-testid="snapshot-digest-diff">
          <span :class="comparison.promptSnapshotChanged ? 'changed' : 'unchanged'">Prompt {{ comparison.promptSnapshotChanged ? 'changed' : 'same' }}</span>
          <span :class="comparison.modelSnapshotChanged ? 'changed' : 'unchanged'">Model {{ comparison.modelSnapshotChanged ? 'changed' : 'same' }}</span>
          <span :class="comparison.toolSnapshotChanged ? 'changed' : 'unchanged'">Tool {{ comparison.toolSnapshotChanged ? 'changed' : 'same' }}</span>
        </div>
      </section>

      <el-table :data="items" stripe>
        <el-table-column prop="evalSetName" label="Eval Set" min-width="170"><template #default="scope">{{ scope.row.evalSetName }} v{{ scope.row.evalSetVersion }}</template></el-table-column>
        <el-table-column prop="evaluatorVersion" label="Evaluator" min-width="130" />
        <el-table-column label="通过率" width="110"><template #default="scope"><strong>{{ pct(scope.row.aggregate.passRate) }}</strong></template></el-table-column>
        <el-table-column label="三轴通过" min-width="190"><template #default="scope">R {{ scope.row.aggregate.routePassCount }} · T {{ scope.row.aggregate.trajectoryPassCount }} · N {{ scope.row.aggregate.numericTruthPassCount }}</template></el-table-column>
        <el-table-column label="Snapshot" min-width="170"><template #default="scope"><code>{{ scope.row.snapshotDigest.slice(0, 12) }}</code></template></el-table-column>
      </el-table>
      <div class="rerun-list" aria-label="Experiment rerun actions">
        <el-button
          v-for="item in rerunnableItems"
          :key="item.experimentId"
          :data-testid="`rerun-${item.experimentId}`"
          :loading="rerunningId === item.experimentId"
          :disabled="Boolean(rerunningId)"
          @click="rerun(item.experimentId)"
        >Rerun {{ item.evalSetName }} v{{ item.evalSetVersion }}</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.panel { padding: 24px; border: 1px solid #dce4e7; border-radius: 16px; background: #fff; }
.panel-head { margin-bottom: 22px; } h2 { margin: 0 0 6px; font-size: 20px; } p { margin: 0; color: #718086; }
.run-card { margin-bottom: 20px; padding: 18px; border: 1px solid #dce4e7; border-radius: 12px; background: #f8faf9; }
.run-head { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 14px; } .run-head h3 { margin: 0 0 5px; }
.digest-grid, .bounds-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.compare-bar { display: grid; grid-template-columns: minmax(180px,1fr) auto minmax(180px,1fr) auto; gap: 12px; align-items: center; margin-bottom: 18px; padding: 14px; background: #f5f8f7; border-radius: 12px; }
.diff-card { display: grid; grid-template-columns: repeat(3,1fr); gap: 18px; margin-bottom: 18px; padding: 18px; border: 1px solid #dce4e7; border-radius: 12px; }
.diff-card > div { display: flex; flex-direction: column; gap: 5px; } .label { color: #718086; font-size: 12px; text-transform: uppercase; }
.case-diff { flex-direction: row !important; gap: 18px !important; } .positive { color: #247151; } .negative { color: #ba3d43; } .neutral { color: #657278; }
.digest-diff { grid-column: 1 / -1; flex-direction: row !important; gap: 10px !important; } .digest-diff span { padding: 5px 8px; border-radius: 6px; } .changed { color: #9b3b3f; background: #fff0f0; } .unchanged { color: #356f60; background: #eaf4f0; }
code { color: #356f60; } .diff-card :deep(.el-alert) { grid-column: 1 / -1; }
.rerun-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
@media (max-width: 760px) { .run-head { flex-direction: column; } .digest-grid, .bounds-grid, .compare-bar { grid-template-columns: 1fr; } .compare-bar > span { display: none; } .diff-card { grid-template-columns: 1fr; } }
</style>
