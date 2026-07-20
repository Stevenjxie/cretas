<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { importRuntimeCorpus, listEvalSets } from '@/api/agent-ops';
import type { EvalSetSummary, ImportRuntimeCorpusRequest } from '@/api/agent-ops';
import { InMemoryIdempotencyAttempts, stableBusinessSignature } from './idempotency';

const auth = useAuthStore();
const items = ref<EvalSetSummary[]>([]);
const loading = ref(false);
const error = ref('');
const createOpen = ref(false);
const saving = ref(false);
const idempotency = new InMemoryIdempotencyAttempts();
const form = reactive({
  name: '', version: 1, description: '', maxCases: 20,
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

async function save() {
  if (!auth.factoryId || !form.name.trim()) {
    ElMessage.warning('请填写 Eval Set 名称');
    return;
  }
  saving.value = true;
  try {
    const businessBody: Omit<ImportRuntimeCorpusRequest, 'requestId'> = {
      schemaVersion: '1.0',
      name: form.name.trim(),
      version: form.version,
      description: form.description.trim(),
      maxCases: form.maxCases,
    };
    const action = 'import-runtime-corpus';
    const requestId = idempotency.requestId(action, stableBusinessSignature({
      factoryId: auth.factoryId,
      body: businessBody,
    }));
    await importRuntimeCorpus(auth.factoryId, { ...businessBody, requestId });
    idempotency.complete(action, requestId);
    createOpen.value = false;
    ElMessage.success('已从可信 Runtime runs 导入并冻结 Eval Set');
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
        <p>从当前餐饮租户的可信 durable runs 自动冻结输入、轨迹、数值真值与证据摘要。</p>
      </div>
      <el-button type="primary" @click="createOpen = true">导入 Runtime Corpus</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon data-testid="eval-error" />
    <el-skeleton v-else-if="loading" :rows="4" animated />
    <el-empty v-else-if="items.length === 0" description="尚无 Eval Set。先从可信 Runtime runs 导入基准 Case。" data-testid="eval-empty" />
    <el-table v-else :data="items" stripe>
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="version" label="版本" width="90"><template #default="scope">v{{ scope.row.version }}</template></el-table-column>
      <el-table-column prop="caseCount" label="Cases" width="90" />
      <el-table-column label="内容摘要" min-width="210"><template #default="scope"><code>{{ scope.row.contentDigest.slice(0, 16) }}</code></template></el-table-column>
      <el-table-column prop="createdAt" label="冻结时间" min-width="180" />
    </el-table>

    <el-dialog v-model="createOpen" title="导入不可变 Runtime Eval Set" width="620px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="名称"><el-input v-model="form.name" maxlength="96" /></el-form-item>
          <el-form-item label="版本"><el-input-number v-model="form.version" :min="1" :max="1000000" /></el-form-item>
        </div>
        <el-form-item label="说明"><el-input v-model="form.description" maxlength="500" /></el-form-item>
        <el-form-item label="最多导入 Cases（1-20）"><el-input-number data-testid="import-max-cases" v-model="form.maxCases" :min="1" :max="20" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button data-testid="import-runtime-corpus" type="primary" :loading="saving" @click="save">导入并冻结</el-button></template>
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
