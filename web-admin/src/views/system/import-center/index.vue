<template>
  <div class="import-center-page">
    <div class="page-header">
      <h2>导入中心</h2>
      <div class="header-actions">
        <el-button :icon="Plus" @click="openCreate">新建规则</el-button>
        <el-button type="primary" :icon="Upload" @click="openUniversalUpload">通用上传</el-button>
      </div>
    </div>

    <!-- Rules table -->
    <el-card shadow="never" class="filter-card">
      <template #header><strong>导入规则列表</strong></template>
      <el-table :data="rules" stripe v-loading="loadingRules" border>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="ruleName" label="规则名" min-width="180" />
        <el-table-column prop="moduleCode" label="模块" width="140" />
        <el-table-column prop="targetEntity" label="目标 Entity" min-width="220">
          <template #default="{ row }">
            <code class="muted">{{ shortClass(row.targetEntity) }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="dedupStrategy" label="去重" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.dedupStrategy === 'ERROR' ? 'danger' : ''">{{ row.dedupStrategy }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" type="primary" @click="openImportWizard(row)">导入</el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Recent jobs -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <strong>最近任务</strong>
          <el-button :icon="Refresh" link @click="loadJobs">刷新</el-button>
        </div>
      </template>
      <el-table :data="jobs" stripe v-loading="loadingJobs" border size="small">
        <el-table-column prop="sourceFilename" label="文件" min-width="200" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="jobStatusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalRows" label="总" width="70" />
        <el-table-column prop="validRows" label="通过" width="70" />
        <el-table-column prop="errorRows" label="错误" width="70" />
        <el-table-column prop="committedRows" label="已提交" width="80" />
        <el-table-column prop="completedAt" label="完成时间" width="170">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DRYRUN_DONE' && (row.errorRows ?? 0) === 0"
              size="small"
              type="primary"
              @click="onCommit(row)"
            >提交</el-button>
            <el-button
              v-if="row.errors && row.errors.length > 0"
              size="small"
              link
              @click="showErrors(row)"
            >查看错误</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Universal upload classifier -->
    <el-dialog v-model="uploadClassifierOpen" title="选择导入文件类型" width="560px" destroy-on-close>
      <el-radio-group v-model="uploadTarget" class="upload-type-list">
        <label
          class="upload-type-option"
          :class="{ active: uploadTarget === 'restaurantSupplierDelivery' }"
        >
          <el-radio value="restaurantSupplierDelivery">
            供应商送货 / 采购进货 Excel、CSV
          </el-radio>
          <span>进入供应商进货导入流程，解析后先生成草稿送货单，不自动入库。</span>
        </label>
        <label
          class="upload-type-option"
          :class="{ active: uploadTarget === 'genericImportRule' }"
        >
          <el-radio value="genericImportRule">
            按系统导入规则处理
          </el-radio>
          <span>选择已配置的导入规则，继续执行 dryrun 校验和提交。</span>
        </label>
      </el-radio-group>

      <el-form v-if="uploadTarget === 'genericImportRule'" label-width="90px" style="margin-top: 16px">
        <el-form-item label="导入规则">
          <el-select
            v-model="genericRuleId"
            placeholder="请选择导入规则"
            filterable
            style="width: 100%"
            :disabled="selectableRules.length === 0"
          >
            <el-option
              v-for="rule in selectableRules"
              :key="rule.id"
              :label="`${rule.ruleName} (${rule.moduleCode})`"
              :value="rule.id"
            />
          </el-select>
          <div v-if="selectableRules.length === 0" class="select-hint">
            当前没有可用导入规则，请先新建规则。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="uploadClassifierOpen = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="uploadTarget === 'genericImportRule' && !selectedGenericRule"
          @click="continueUniversalUpload"
        >
          下一步
        </el-button>
      </template>
    </el-dialog>

    <!-- Wizard -->
    <el-dialog v-model="wizardOpen" :title="'导入: ' + (wizardRule?.ruleName || '')" width="780px" destroy-on-close>
      <el-steps :active="wizardStep" finish-status="success" simple style="margin-bottom: 18px">
        <el-step title="上传 Excel" />
        <el-step title="dryrun 校验" />
        <el-step title="确认提交" />
      </el-steps>

      <div v-if="wizardStep === 0">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          <p>请上传 .xlsx Excel 文件. 表头将按规则 mapping 匹配 entity 字段:</p>
          <ul style="margin: 8px 0; padding-left: 18px">
            <li v-for="(m, i) in wizardRule?.mapping || []" :key="i">
              <code>{{ m.excelCol }}</code> → <code>{{ m.entityField }}</code>
              <span v-if="m.validator" class="muted">({{ m.validator }})</span>
            </li>
          </ul>
        </el-alert>
        <el-upload
          drag
          :auto-upload="false"
          :on-change="onFileSelected"
          :show-file-list="true"
          :limit="1"
          accept=".xlsx,.xls"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽 Excel 或 <em>点击上传</em></div>
        </el-upload>
      </div>

      <div v-else-if="wizardStep === 1">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="总行数">{{ wizardJob?.totalRows }}</el-descriptions-item>
          <el-descriptions-item label="通过校验">
            <el-tag type="success">{{ wizardJob?.validRows }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="错误行数">
            <el-tag :type="(wizardJob?.errorRows ?? 0) > 0 ? 'danger' : 'success'">
              {{ wizardJob?.errorRows }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="wizardJob?.errors && wizardJob.errors.length" style="margin-top: 12px">
          <h4>校验错误明细</h4>
          <el-table :data="wizardJob.errors" border size="small" max-height="280">
            <el-table-column prop="row" label="行号" width="80" />
            <el-table-column prop="col" label="列" width="150" />
            <el-table-column prop="msg" label="错误信息" />
          </el-table>
        </div>
      </div>

      <div v-else-if="wizardStep === 2">
        <el-alert
          v-if="wizardJob?.status === 'COMMITTED'"
          type="success"
          :title="`成功导入 ${wizardJob.committedRows} 行`"
          :closable="false"
        />
        <el-alert
          v-else-if="wizardJob?.status === 'FAILED'"
          type="error"
          :title="`部分失败 — ${wizardJob.committedRows} 成功 / ${wizardJob.errorRows ?? 0} 失败`"
          :closable="false"
        />
        <el-alert
          v-else
          type="info"
          title="处理中..."
          :closable="false"
        />
      </div>

      <template #footer>
        <el-button @click="wizardOpen = false">关闭</el-button>
        <el-button
          v-if="wizardStep === 0"
          type="primary"
          :disabled="!selectedFile"
          :loading="dryrunning"
          @click="onDryrun"
        >下一步: 校验</el-button>
        <el-button
          v-if="wizardStep === 1"
          type="primary"
          :disabled="(wizardJob?.errorRows ?? 0) > 0"
          :loading="committing"
          @click="onCommitWizard"
        >提交</el-button>
      </template>
    </el-dialog>

    <!-- Rule editor (simplified) -->
    <el-dialog v-model="editorOpen" :title="editorTitle" width="720px" destroy-on-close>
      <el-form :model="form" label-width="120px" :rules="formRules" ref="formRef">
        <el-form-item label="规则名" prop="ruleName">
          <el-input v-model="form.ruleName" />
        </el-form-item>
        <el-form-item label="模块代码" prop="moduleCode">
          <el-input v-model="form.moduleCode" />
        </el-form-item>
        <el-form-item label="目标 Entity" prop="targetEntity">
          <el-input v-model="form.targetEntity" placeholder="com.cretas.aims.entity.Customer" />
        </el-form-item>
        <el-form-item label="去重策略">
          <el-radio-group v-model="form.dedupStrategy">
            <el-radio value="ERROR">报错</el-radio>
            <el-radio value="SKIP">跳过</el-radio>
            <el-radio value="UPDATE">更新</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="去重字段">
          <el-input v-model="form.dedupKeyField" placeholder="entityField 名称 (可空)" />
        </el-form-item>
        <el-form-item label="字段映射 (JSON)" prop="mappingJson">
          <el-input
            v-model="form.mappingJson"
            type="textarea"
            :rows="8"
            placeholder='[{"excelCol":"客户名","entityField":"name","validator":"required|maxLength:100"}]'
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">{{ form.id ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type UploadFile } from 'element-plus';
import { Plus, Refresh, Upload, UploadFilled } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { importRuleApi, type ImportRule, type ImportJob } from '@/api/dataCenter';

const authStore = useAuthStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);

const rules = ref<ImportRule[]>([]);
const loadingRules = ref(false);
const jobs = ref<ImportJob[]>([]);
const loadingJobs = ref(false);

type UniversalUploadTarget = 'restaurantSupplierDelivery' | 'genericImportRule';

const uploadClassifierOpen = ref(false);
const uploadTarget = ref<UniversalUploadTarget>('restaurantSupplierDelivery');
const genericRuleId = ref<number | null>(null);
const selectableRules = computed(() =>
  rules.value.filter((rule): rule is ImportRule & { id: number } => typeof rule.id === 'number'),
);
const selectedGenericRule = computed<ImportRule | null>(
  () => selectableRules.value.find((rule) => rule.id === genericRuleId.value) || null,
);

// Editor
const editorOpen = ref(false);
const editorTitle = ref('');
const saving = ref(false);
const formRef = ref<FormInstance>();
const form = ref({
  id: undefined as number | undefined,
  ruleName: '',
  moduleCode: '',
  targetEntity: '',
  dedupStrategy: 'ERROR' as 'SKIP' | 'UPDATE' | 'ERROR',
  dedupKeyField: '',
  mappingJson: '',
  description: '',
});
const formRules = {
  ruleName: [{ required: true, message: '请填写规则名', trigger: 'blur' }],
  moduleCode: [{ required: true, message: '请填写模块代码', trigger: 'blur' }],
  targetEntity: [{ required: true, message: '请填写 Entity 类名', trigger: 'blur' }],
  mappingJson: [{ required: true, message: '请填写字段映射', trigger: 'blur' }],
};

// Wizard
const wizardOpen = ref(false);
const wizardStep = ref(0);
const wizardRule = ref<ImportRule | null>(null);
const wizardJob = ref<ImportJob | null>(null);
const selectedFile = ref<File | null>(null);
const dryrunning = ref(false);
const committing = ref(false);

async function loadRules() {
  if (!factoryId.value) return;
  loadingRules.value = true;
  try {
    const resp = await importRuleApi.list(factoryId.value);
    rules.value = resp.data?.content || [];
  } catch (e) {
    console.error('[ImportCenter] loadRules', e);
    ElMessage.error('加载规则失败');
  } finally {
    loadingRules.value = false;
  }
}

async function loadJobs() {
  if (!factoryId.value) return;
  loadingJobs.value = true;
  try {
    const resp = await importRuleApi.listJobs(factoryId.value);
    jobs.value = resp.data?.content || [];
  } catch (e) {
    console.error('[ImportCenter] loadJobs', e);
  } finally {
    loadingJobs.value = false;
  }
}

function openCreate() {
  form.value = {
    id: undefined,
    ruleName: '',
    moduleCode: '',
    targetEntity: '',
    dedupStrategy: 'ERROR',
    dedupKeyField: '',
    mappingJson: '',
    description: '',
  };
  editorTitle.value = '新建导入规则';
  editorOpen.value = true;
}

function openUniversalUpload() {
  uploadTarget.value = 'restaurantSupplierDelivery';
  genericRuleId.value = null;
  uploadClassifierOpen.value = true;
}

function continueUniversalUpload() {
  if (uploadTarget.value === 'restaurantSupplierDelivery') {
    uploadClassifierOpen.value = false;
    router.push({
      name: 'SupplierDeliveryNoteList',
      query: { openImport: 'supplier-delivery' },
    });
    return;
  }

  if (!selectedGenericRule.value) {
    ElMessage.warning('请先选择一个导入规则');
    return;
  }

  uploadClassifierOpen.value = false;
  openImportWizard(selectedGenericRule.value);
}

function openEdit(rule: ImportRule) {
  form.value = {
    id: rule.id,
    ruleName: rule.ruleName,
    moduleCode: rule.moduleCode,
    targetEntity: rule.targetEntity,
    dedupStrategy: rule.dedupStrategy,
    dedupKeyField: rule.dedupKeyField || '',
    mappingJson: JSON.stringify(rule.mapping, null, 2),
    description: rule.description || '',
  };
  editorTitle.value = `编辑规则 #${rule.id}`;
  editorOpen.value = true;
}

async function onSave() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  let mapping: Array<{ excelCol: string; entityField: string; validator?: string }>;
  try {
    mapping = JSON.parse(form.value.mappingJson);
    if (!Array.isArray(mapping) || mapping.length === 0) {
      throw new Error('mapping 必须是非空数组');
    }
  } catch (e) {
    ElMessage.error('mapping JSON 解析失败: ' + (e as Error).message);
    return;
  }
  saving.value = true;
  try {
    const payload = {
      factoryId: factoryId.value,
      ruleName: form.value.ruleName,
      moduleCode: form.value.moduleCode,
      targetEntity: form.value.targetEntity,
      dedupStrategy: form.value.dedupStrategy,
      dedupKeyField: form.value.dedupKeyField || undefined,
      mapping,
      description: form.value.description || undefined,
    };
    if (form.value.id) {
      await importRuleApi.update(factoryId.value, form.value.id, payload);
      ElMessage.success('规则已更新');
    } else {
      await importRuleApi.create(factoryId.value, payload);
      ElMessage.success('规则已创建');
    }
    editorOpen.value = false;
    loadRules();
  } catch (e) {
    console.error('[ImportCenter] save', e);
  } finally {
    saving.value = false;
  }
}

async function onDelete(rule: ImportRule) {
  try {
    await ElMessageBox.confirm(`确定删除规则 "${rule.ruleName}"?`, '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await importRuleApi.delete(factoryId.value, rule.id!);
    ElMessage.success('已删除');
    loadRules();
  } catch (e) {
    console.error('[ImportCenter] delete', e);
  }
}

function openImportWizard(rule: ImportRule) {
  wizardRule.value = rule;
  wizardStep.value = 0;
  wizardJob.value = null;
  selectedFile.value = null;
  wizardOpen.value = true;
}

function onFileSelected(file: UploadFile) {
  selectedFile.value = file.raw || null;
}

async function onDryrun() {
  if (!wizardRule.value || !selectedFile.value) return;
  dryrunning.value = true;
  try {
    const resp = await importRuleApi.dryrun(factoryId.value, wizardRule.value.id!, selectedFile.value);
    wizardJob.value = resp.data || null;
    wizardStep.value = 1;
    loadJobs();
  } catch (e) {
    console.error('[ImportCenter] dryrun', e);
  } finally {
    dryrunning.value = false;
  }
}

async function onCommitWizard() {
  if (!wizardJob.value) return;
  committing.value = true;
  try {
    const resp = await importRuleApi.commit(factoryId.value, wizardJob.value.id);
    wizardJob.value = resp.data || null;
    wizardStep.value = 2;
    loadJobs();
  } catch (e) {
    console.error('[ImportCenter] commit', e);
  } finally {
    committing.value = false;
  }
}

async function onCommit(job: ImportJob) {
  try {
    await ElMessageBox.confirm(`确认提交 ${job.validRows} 行数据?`, '提交确认', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await importRuleApi.commit(factoryId.value, job.id);
    ElMessage.success('提交成功');
    loadJobs();
  } catch (e) {
    console.error('[ImportCenter] commit', e);
  }
}

function showErrors(job: ImportJob) {
  ElMessageBox.alert(
    job.errors?.map((e) => `行 ${e.row} - ${e.col}: ${e.msg}`).join('\n') || '无错误明细',
    `Job ${job.id} 错误明细`,
    { customStyle: { whiteSpace: 'pre-wrap', maxHeight: '500px', overflow: 'auto' } }
  );
}

function jobStatusType(s: string): 'success' | 'warning' | 'danger' | 'info' | '' {
  switch (s) {
    case 'COMMITTED': return 'success';
    case 'DRYRUN_DONE': return 'warning';
    case 'FAILED': return 'danger';
    default: return 'info';
  }
}

function shortClass(fqn: string): string {
  if (!fqn) return '';
  const dot = fqn.lastIndexOf('.');
  return dot < 0 ? fqn : fqn.substring(dot + 1);
}

function formatTime(s: string | null): string {
  if (!s) return '—';
  return s.replace('T', ' ').replace(/\.\d+$/, '');
}

onMounted(() => {
  loadRules();
  loadJobs();
});
</script>

<style scoped>
.import-center-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.filter-card :deep(.el-form-item) { margin-bottom: 0; }
.muted { color: #909399; font-size: 12px; }
.upload-type-list { display: flex; flex-direction: column; gap: 10px; width: 100%; }
.upload-type-option {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  cursor: pointer;
}
.upload-type-option.active { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.upload-type-option span { padding-left: 24px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.select-hint { margin-top: 6px; color: var(--el-color-warning); font-size: 12px; }
</style>
