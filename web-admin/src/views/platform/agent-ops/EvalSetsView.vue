<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { createEvalSet, listEvalSets } from '@/api/agent-ops';
import type { CreateEvalSetRequest, EvalSetSummary } from '@/api/agent-ops';
import { InMemoryIdempotencyAttempts, stableBusinessSignature } from './idempotency';

const auth = useAuthStore();
const items = ref<EvalSetSummary[]>([]);
const loading = ref(false);
const error = ref('');
const createOpen = ref(false);
const saving = ref(false);
const idempotency = new InMemoryIdempotencyAttempts();
const form = reactive({
  name: '', version: 1, description: '', caseId: '',
  requiredTools: '', numericTruthRefs: '', maxRounds: 2, maxToolCalls: 10,
});

async function load() {
  if (!auth.factoryId) return;
  loading.value = true;
  error.value = '';
  try {
    const response = await listEvalSets(auth.factoryId);
    items.value = response.data.items;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Eval Sets 加载失败';
  } finally {
    loading.value = false;
  }
}

function parseRefs(raw: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const line of raw.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)) {
    const separator = line.indexOf('=');
    if (separator < 1) throw new Error('数值真值必须使用 ref=value，每行一条');
    result[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }
  return result;
}

async function save() {
  if (!auth.factoryId || !form.name.trim() || !form.caseId.trim()) {
    ElMessage.warning('请填写名称和 Case ID');
    return;
  }
  saving.value = true;
  try {
    const businessBody: Omit<CreateEvalSetRequest, 'requestId'> = {
      schemaVersion: '1.0',
      name: form.name.trim(),
      version: form.version,
      description: form.description.trim(),
      cases: [{
        caseId: form.caseId.trim(),
        expectedRoute: 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
        requiredTools: form.requiredTools.split(',').map((item) => item.trim()).filter(Boolean),
        numericTruthRefs: parseRefs(form.numericTruthRefs),
        maxRounds: form.maxRounds,
        maxToolCalls: form.maxToolCalls,
      }],
    };
    const action = 'create-eval-set';
    const requestId = idempotency.requestId(action, stableBusinessSignature({
      factoryId: auth.factoryId,
      body: businessBody,
    }));
    await createEvalSet(auth.factoryId, { ...businessBody, requestId });
    idempotency.complete(action, requestId);
    createOpen.value = false;
    ElMessage.success('Eval Set 版本已冻结');
    await load();
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="panel" data-testid="eval-sets-view">
    <div class="panel-head">
      <div>
        <h2>版本化 Eval Sets</h2>
        <p>版本创建后不可修改；Case 固定预期路由、工具轨迹和数值证据引用。</p>
      </div>
      <el-button type="primary" @click="createOpen = true">新建冻结版本</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="eval-error" />
    <el-skeleton v-else-if="loading" :rows="4" animated />
    <el-empty v-else-if="items.length === 0" description="尚无 Eval Set。先冻结一组可重复的基准 Case。" data-testid="eval-empty" />
    <el-table v-else :data="items" stripe>
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="version" label="版本" width="90"><template #default="scope">v{{ scope.row.version }}</template></el-table-column>
      <el-table-column prop="caseCount" label="Cases" width="90" />
      <el-table-column label="内容摘要" min-width="210"><template #default="scope"><code>{{ scope.row.contentDigest.slice(0, 16) }}</code></template></el-table-column>
      <el-table-column prop="createdAt" label="冻结时间" min-width="180" />
    </el-table>

    <el-dialog v-model="createOpen" title="新建不可变 Eval Set 版本" width="620px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="名称"><el-input v-model="form.name" maxlength="96" /></el-form-item>
          <el-form-item label="版本"><el-input-number v-model="form.version" :min="1" :max="1000000" /></el-form-item>
        </div>
        <el-form-item label="说明"><el-input v-model="form.description" maxlength="500" /></el-form-item>
        <el-divider content-position="left">首个 Case</el-divider>
        <el-form-item label="Case ID"><el-input v-model="form.caseId" maxlength="128" /></el-form-item>
        <el-form-item label="必需工具（按顺序，逗号分隔）"><el-input v-model="form.requiredTools" placeholder="restaurant_margin_read, restaurant_cost_read" /></el-form-item>
        <el-form-item label="数值真值引用（每行 ref=value）"><el-input v-model="form.numericTruthRefs" type="textarea" :rows="3" placeholder="evidence-1:fact-1=12.50" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="最大轮次"><el-input-number v-model="form.maxRounds" :min="1" :max="2" /></el-form-item>
          <el-form-item label="最大 Tool 调用"><el-input-number v-model="form.maxToolCalls" :min="1" :max="10" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">冻结版本</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel { padding: 24px; border: 1px solid #dce4e7; border-radius: 16px; background: #fff; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
h2 { margin: 0 0 6px; font-size: 20px; } p { margin: 0; color: #718086; }
code { color: #356f60; background: #eef5f2; padding: 4px 7px; border-radius: 5px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 700px) { .panel-head { flex-direction: column; } .form-grid { grid-template-columns: 1fr; } }
</style>
