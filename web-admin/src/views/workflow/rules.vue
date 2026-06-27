<script setup lang="ts">
/**
 * 流转规则设置 — Sprint 6 W1-B (C-MENU-PERSONAL-VIEW deferred sub 3).
 *
 * Round 12 §D X4 + Round 13 §10: HJ ERP 工作流 6 sub-menu 之一. Vue editor 配置
 * AMOUNT_THRESHOLD / DEPT_MATCH / ROLE_MATCH / SPEL_CUSTOM 规则, 绑定到
 * ApprovalWorkflow condition 节点.
 *
 * Backend (✅ shipped — Round 11 §I.4 C-WF-RULE-1):
 *   - GET    /api/mobile/{factoryId}/workflow-rules?workflowId=xxx     列规则
 *   - GET    /api/mobile/{factoryId}/workflow-rules/{id}               详情
 *   - POST   /api/mobile/{factoryId}/workflow-rules                    创建
 *   - PUT    /api/mobile/{factoryId}/workflow-rules/{id}               更新
 *   - DELETE /api/mobile/{factoryId}/workflow-rules/{id}               删除
 *   - POST   /api/mobile/{factoryId}/workflow-rules/{id}/test          mock 测试
 *
 * MVP slice (此 PR):
 *   - workflow 选择器 (dropdown) → 加载该 workflow 下规则列表
 *   - 创建/编辑 dialog: 4 ruleType + JSON expression textarea
 *   - 删除 (软删) + test (mock context 简化)
 *
 * Deferred (Sprint 6 W4+):
 *   - 4-ruleType conditional UI (radio + per-type fields, 替代 textarea)
 *     AMOUNT: numeric threshold + operator
 *     DEPT: multi-select departments
 *     ROLE: multi-select roles
 *     SPEL: SpEL editor + syntax check
 *   - nodeId 自动从 workflow nodesJson 取候选 condition 节点 (现需用户手输)
 *   - test dialog 完整 mock context builder
 *
 * @since 2026-05-19 (Sprint 6 W1-B)
 */
import { ref, onMounted, computed, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get, post, put, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';

interface ApprovalWorkflow {
  id: string;
  name: string;
  decisionType?: string;
}

interface WorkflowRule {
  id: string;
  factoryId: string;
  workflowId: string;
  nodeId: string;
  edgeId?: string | null;
  ruleType: 'AMOUNT_THRESHOLD' | 'DEPT_MATCH' | 'ROLE_MATCH' | 'SPEL_CUSTOM';
  expression: string;  // JSON string
  trueTargetNodeId?: string | null;
  falseTargetNodeId?: string | null;
  priority: number;
  enabled: boolean;
  description?: string | null;
}

interface RuleFormState {
  id: string | null;
  workflowId: string;
  nodeId: string;
  edgeId: string;
  ruleType: WorkflowRule['ruleType'];
  expressionJson: string;  // pretty JSON string for textarea
  trueTargetNodeId: string;
  falseTargetNodeId: string;
  priority: number;
  enabled: boolean;
  description: string;
}

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const workflows = ref<ApprovalWorkflow[]>([]);
const selectedWorkflowId = ref<string>('');
const rules = ref<WorkflowRule[]>([]);
const loading = ref(false);
const workflowsLoading = ref(false);

const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const form = reactive<RuleFormState>({
  id: null,
  workflowId: '',
  nodeId: '',
  edgeId: '',
  ruleType: 'AMOUNT_THRESHOLD',
  expressionJson: '',
  trueTargetNodeId: '',
  falseTargetNodeId: '',
  priority: 0,
  enabled: true,
  description: '',
});

// Per-ruleType 示例 expression (Rule 5 防呆 — 占位例子)
const EXPRESSION_TEMPLATES: Record<WorkflowRule['ruleType'], string> = {
  AMOUNT_THRESHOLD: '{\n  "field": "amount",\n  "op": ">",\n  "value": 10000\n}',
  DEPT_MATCH: '{\n  "field": "department",\n  "in": ["finance", "purchasing"]\n}',
  ROLE_MATCH: '{\n  "field": "role",\n  "in": ["FACTORY_SUPER_ADMIN"]\n}',
  SPEL_CUSTOM: '{\n  "spel": "#amount > 10000 && #department == \'finance\'"\n}',
};

const ruleTypeOptions = [
  { value: 'AMOUNT_THRESHOLD', label: '金额阈值', desc: '按 amount 数值比较' },
  { value: 'DEPT_MATCH', label: '部门匹配', desc: '按 department 多选匹配' },
  { value: 'ROLE_MATCH', label: '角色匹配', desc: '按 role 多选匹配' },
  { value: 'SPEL_CUSTOM', label: 'SpEL 自定义', desc: 'SpEL 表达式 (高级用户)' },
];

function ruleTypeLabel(type: string): string {
  const opt = ruleTypeOptions.find((o) => o.value === type);
  return opt ? opt.label : type;
}

async function loadWorkflows(): Promise<void> {
  if (!factoryId.value) return;
  workflowsLoading.value = true;
  try {
    const resp = await get<ApprovalWorkflow[]>(`/${factoryId.value}/approval-workflows`);
    if (resp.success && resp.data) {
      workflows.value = resp.data || [];
      if (!selectedWorkflowId.value && workflows.value.length > 0) {
        selectedWorkflowId.value = workflows.value[0].id;
        await loadRules();
      }
    } else {
      ElMessage.error(resp.message || '加载工作流列表失败');
    }
  } catch (e: unknown) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载工作流列表失败');
  } finally {
    workflowsLoading.value = false;
  }
}

async function loadRules(): Promise<void> {
  if (!factoryId.value || !selectedWorkflowId.value) {
    rules.value = [];
    return;
  }
  loading.value = true;
  try {
    const resp = await get<WorkflowRule[]>(
      `/${factoryId.value}/workflow-rules`,
      { params: { workflowId: selectedWorkflowId.value } },
    );
    if (resp.success && resp.data) {
      rules.value = resp.data || [];
    } else {
      ElMessage.error(resp.message || '加载规则列表失败');
    }
  } catch (e: unknown) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载规则列表失败');
  } finally {
    loading.value = false;
  }
}

function handleWorkflowChange(): void {
  loadRules();
}

function openCreate(): void {
  if (!selectedWorkflowId.value) {
    ElMessage.warning('请先选择工作流');
    return;
  }
  dialogMode.value = 'create';
  form.id = null;
  form.workflowId = selectedWorkflowId.value;
  form.nodeId = '';
  form.edgeId = '';
  form.ruleType = 'AMOUNT_THRESHOLD';
  form.expressionJson = EXPRESSION_TEMPLATES.AMOUNT_THRESHOLD;
  form.trueTargetNodeId = '';
  form.falseTargetNodeId = '';
  form.priority = 0;
  form.enabled = true;
  form.description = '';
  dialogVisible.value = true;
}

function openEdit(rule: WorkflowRule): void {
  dialogMode.value = 'edit';
  form.id = rule.id;
  form.workflowId = rule.workflowId;
  form.nodeId = rule.nodeId;
  form.edgeId = rule.edgeId || '';
  form.ruleType = rule.ruleType;
  // expression 是后端持久 JSON 字符串 — 用户编辑时 pretty-print
  try {
    form.expressionJson = JSON.stringify(JSON.parse(rule.expression || '{}'), null, 2);
  } catch {
    form.expressionJson = rule.expression || '';
  }
  form.trueTargetNodeId = rule.trueTargetNodeId || '';
  form.falseTargetNodeId = rule.falseTargetNodeId || '';
  form.priority = rule.priority;
  form.enabled = rule.enabled;
  form.description = rule.description || '';
  dialogVisible.value = true;
}

function handleRuleTypeChange(): void {
  // 切换 ruleType 时, 如 expression 是默认 template 就刷新
  form.expressionJson = EXPRESSION_TEMPLATES[form.ruleType];
}

async function handleSubmit(): Promise<void> {
  if (!factoryId.value) return;
  // 校验 JSON
  let parsedExpression: Record<string, unknown>;
  try {
    parsedExpression = JSON.parse(form.expressionJson);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误';
    ElMessage.error(`expression JSON 解析失败: ${msg}`);
    return;
  }
  if (!form.nodeId) {
    ElMessage.error('nodeId 不能为空');
    return;
  }

  const payload = {
    workflowId: form.workflowId,
    nodeId: form.nodeId,
    edgeId: form.edgeId || undefined,
    ruleType: form.ruleType,
    expression: parsedExpression,
    trueTargetNodeId: form.trueTargetNodeId || undefined,
    falseTargetNodeId: form.falseTargetNodeId || undefined,
    priority: form.priority,
    enabled: form.enabled,
    description: form.description || undefined,
  };

  try {
    if (dialogMode.value === 'create') {
      const resp = await post(`/${factoryId.value}/workflow-rules`, payload);
      if (resp.success) {
        ElMessage.success(resp.message || '创建成功');
        dialogVisible.value = false;
        loadRules();
      } else {
        ElMessage.error(resp.message || '创建失败');
      }
    } else if (form.id) {
      const resp = await put(`/${factoryId.value}/workflow-rules/${form.id}`, payload);
      if (resp.success) {
        ElMessage.success(resp.message || '更新成功');
        dialogVisible.value = false;
        loadRules();
      } else {
        ElMessage.error(resp.message || '更新失败');
      }
    }
  } catch (e: unknown) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '保存失败');
  }
}

async function handleDelete(rule: WorkflowRule): Promise<void> {
  if (!factoryId.value) return;
  try {
    // Rule 2: dialog 标题带 context (规则名/类型)
    await ElMessageBox.confirm(
      `确认删除规则? 类型: ${ruleTypeLabel(rule.ruleType)}, 节点: ${rule.nodeId}, 优先级: ${rule.priority}`,
      '删除流转规则',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
    const resp = await del(`/${factoryId.value}/workflow-rules/${rule.id}`);
    if (resp.success) {
      ElMessage.success(resp.message || '已删除');
      loadRules();
    } else {
      ElMessage.error(resp.message || '删除失败');
    }
  } catch (e: unknown) {
    // 用户 cancel 是 ElMessageBox 抛 'cancel' 字符串, 不报错
    if (e === 'cancel') return;
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '删除失败');
  }
}

async function handleTest(rule: WorkflowRule): Promise<void> {
  if (!factoryId.value) return;
  // MVP: 用空 context 测试. Sprint 6 W4+ 加完整 mock context builder UI.
  const mockContextStr = await ElMessageBox.prompt(
    `测试规则 (${ruleTypeLabel(rule.ruleType)}). 请输入 mock context JSON:`,
    '测试规则',
    {
      inputType: 'textarea',
      inputValue: '{\n  "amount": 15000,\n  "department": "finance",\n  "role": "FACTORY_SUPER_ADMIN"\n}',
      confirmButtonText: '运行测试',
      cancelButtonText: '取消',
      inputValidator: (v: string) => {
        try {
          JSON.parse(v);
          return true;
        } catch {
          return 'JSON 解析失败';
        }
      },
    },
  ).catch((): null => null);

  if (!mockContextStr) return;
  try {
    const mockContext = JSON.parse(mockContextStr.value);
    const resp = await post<{ result: boolean; ruleType: string; expression: string }>(
      `/${factoryId.value}/workflow-rules/${rule.id}/test`,
      mockContext,
    );
    if (resp.success) {
      const hit = resp.data?.result;
      ElMessage({
        message: hit
 ? `规则命中 — 将路由到 trueTargetNodeId`
 : `规则未命中 — 将路由到 falseTargetNodeId 或下一规则`,
        type: hit ? 'success' : 'info',
        duration: 5000,
      });
    } else {
      ElMessage.error(resp.message || '测试失败');
    }
  } catch (e: unknown) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '测试失败');
  }
}

onMounted(() => {
  loadWorkflows();
});
</script>

<template>
  <div class="workflow-rules">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <h3>流转规则设置</h3>
            <el-tag size="small" type="info">{{ rules.length }} 条规则</el-tag>
          </div>
          <div class="header-right">
            <el-button type="primary" :loading="loading" @click="loadRules">刷新</el-button>
            <el-button type="success" :disabled="!selectedWorkflowId" @click="openCreate">
              新建规则
            </el-button>
          </div>
        </div>
      </template>

      <div class="filter-row">
        <span class="filter-label">工作流:</span>
        <el-select
          v-model="selectedWorkflowId"
          placeholder="请选择工作流"
          :loading="workflowsLoading"
          style="width: 320px;"
          @change="handleWorkflowChange"
        >
          <el-option
            v-for="wf in workflows"
            :key="wf.id"
            :value="wf.id"
            :label="wf.name + (wf.decisionType ? ` (${wf.decisionType})` : '')"
          />
        </el-select>
      </div>

      <el-table
        v-loading="loading"
        :data="rules"
        stripe
        empty-text="此工作流暂无流转规则"
        style="margin-top: 12px;"
      >
        <el-table-column label="节点 ID" width="120" prop="nodeId" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small">{{ ruleTypeLabel(row.ruleType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="表达式" min-width="280">
          <template #default="{ row }">
            <code class="expression-cell">{{ row.expression }}</code>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="80" prop="priority" align="center" />
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-muted">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="success" link @click="handleTest(row)">测试</el-button>
            <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建/编辑 Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建流转规则' : '编辑流转规则'"
      width="640px"
    >
      <el-form label-width="120px">
        <el-form-item label="规则类型" required>
          <el-select v-model="form.ruleType" style="width: 100%;" @change="handleRuleTypeChange">
            <el-option
              v-for="opt in ruleTypeOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            >
              <span>{{ opt.label }}</span>
              <span style="float: right; color: #909399; font-size: 12px;">{{ opt.desc }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="绑定节点 ID" required>
          <el-input v-model="form.nodeId" placeholder="condition 节点 ID (workflow graph)" />
        </el-form-item>
        <el-form-item label="表达式 (JSON)" required>
          <el-input
            v-model="form.expressionJson"
            type="textarea"
            :rows="6"
            placeholder="按规则类型 schema 填写 JSON"
            class="monospace-textarea"
          />
          <p class="form-help">
            schema per ruleType — 见 backend WorkflowRule.expression javadoc.
          </p>
        </el-form-item>
        <el-form-item label="命中目标节点">
          <el-input v-model="form.trueTargetNodeId" placeholder="可选 — rule eval=true 时路由到的 nodeId" />
        </el-form-item>
        <el-form-item label="未命中目标节点">
          <el-input v-model="form.falseTargetNodeId" placeholder="可选 — NULL 即继续下一规则" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :step="1" />
          <span class="form-help" style="margin-left: 12px;">数值越小越优先</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">
          {{ dialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.workflow-rules {
  padding: 16px;

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }
    }

    .header-right {
      display: flex;
      gap: 8px;
    }
  }

  .filter-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;

    .filter-label {
      color: #606266;
      font-size: 14px;
    }
  }

  .expression-cell {
    font-family: monospace;
    font-size: 12px;
    color: #303133;
    background: #f5f7fa;
    padding: 4px 8px;
    border-radius: 3px;
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .text-muted {
    color: #909399;
    font-size: 13px;
  }

  .form-help {
    margin: 4px 0 0;
    color: #909399;
    font-size: 12px;
  }

  :deep(.monospace-textarea textarea) {
    font-family: monospace;
    font-size: 13px;
  }
}
</style>
