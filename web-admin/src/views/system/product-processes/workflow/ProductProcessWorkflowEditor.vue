<template>
  <div class="workflow-editor" :class="{ 'ai-collapsed': aiCollapsed }">
    <div class="workflow-main">
      <div class="workflow-toolbar">
        <div class="toolbar-status">
          <el-tag v-if="definition" :type="definition.status === 'PUBLISHED' ? 'success' : 'warning'">
            {{ definition.status === 'PUBLISHED' ? '已发布' : '草稿' }} v{{ definition.version }}
          </el-tag>
          <el-tag
            data-testid="activation-status"
            :type="activation?.enabled ? 'success' : 'info'"
          >
            {{ activation?.enabled ? `已启用 v${activation.activeDefinitionVersion}` : '未启用' }}
          </el-tag>
          <span v-if="dirty" class="dirty-status">● 有未保存改动</span>
          <span v-else-if="definition" class="saved-status">✓ 已保存</span>
          <span class="stage-note">图定义独立保存，暂不改写现有报工运行时</span>
        </div>
        <div class="toolbar-actions">
          <el-button :disabled="!canEdit || (rawOwnerMode && hasRawRoot)" @click="addStandaloneRaw">
            {{ rawOwnerMode ? '原料模式仅允许一个入口原料' : '+ 原料 Cell' }}
          </el-button>
          <el-button :disabled="!canEdit || history.length === 0" @click="undo">撤销</el-button>
          <el-button :disabled="!canEdit || future.length === 0" @click="redo">重做</el-button>
          <el-button :disabled="!canEdit || flowNodes.length === 0" @click="handleAutoLayout">自动布局</el-button>
          <el-button :disabled="!productTypeId || flowNodes.length === 0" @click="fitCanvas">适应画布</el-button>
          <el-button
            type="primary"
            :disabled="!canEdit || !dirty"
            :loading="saving"
            @click="() => saveDraft()"
          >保存草稿</el-button>
          <!-- #12a: 发布版本 = 发布并启用当前版本 (一步); 不再有单独「启用版本」按钮 -->
          <el-button
            data-testid="publish-workflow"
            type="success"
            :disabled="!canEdit || flowNodes.length === 0"
            :loading="publishing"
            @click="publishWorkflow"
          >发布并启用</el-button>
          <!-- #12b: 版本浏览 (查看之前发布过的版本) -->
          <el-button data-testid="browse-versions" :disabled="!productTypeId" @click="openVersionDrawer">版本记录</el-button>
          <el-button
            v-if="activation?.enabled"
            data-testid="deactivate-workflow"
            type="danger"
            plain
            :disabled="!canEdit"
            :loading="activationChanging"
            @click="deactivateWorkflow"
          >停用 Workflow</el-button>
        </div>
      </div>

      <el-alert
        v-if="unitReviewPending"
        class="unit-review-alert"
        type="warning"
        :closable="false"
        show-icon
        title="SKU 单位已变化：成品产出已同步为当前基本单位。请核对并重新发布；已有计划不受影响，新计划才采用新单位。"
      />

      <el-alert
        v-if="bomMissingProducts.length > 0"
        class="workflow-bom-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          {{ bomMissingProducts.map((item) => item.name).join('、') }} 尚未配置原辅料 BOM
        </template>
        <template #default>
          <el-button link type="primary" @click="openBomDrawer">在右侧配置 BOM →</el-button>
        </template>
      </el-alert>

      <div ref="canvasRef" class="canvas-shell" :class="{ 'is-connecting': !!connectingFromKind }" v-loading="loading">
        <!-- #12b: 历史版本预览横幅 (只读, 不会自动保存覆盖草稿) -->
        <div v-if="previewingVersion !== null" class="version-preview-bar" data-testid="version-preview-bar">
          <span>正在预览历史版本 <strong>v{{ previewingVersion }}</strong>（只读，不会覆盖当前草稿）</span>
          <el-button size="small" type="primary" @click="restorePreviewAsDraft">恢复为当前草稿</el-button>
          <el-button size="small" @click="exitVersionPreview">退出预览</el-button>
        </div>
        <el-empty v-if="!productTypeId" description="请先选择产品" :image-size="90" />
        <VueFlow
          v-else
          id="product-process-workflow"
          v-model:nodes="flowNodes"
          v-model:edges="flowEdges"
          class="workflow-canvas"
          :min-zoom="0.35"
          :max-zoom="1.8"
          :pan-on-drag="true"
          :zoom-on-scroll="true"
          :zoom-on-pinch="true"
          :nodes-draggable="canEdit"
          :nodes-connectable="canEdit"
          :snap-to-grid="true"
          :snap-grid="[16, 16]"
          :connection-radius="32"
          :is-valid-connection="isValidConnection"
          :default-viewport="definition?.viewport || { x: 0, y: 0, zoom: 1 }"
          @connect="onConnect"
          @connect-start="onConnectStart"
          @connect-end="onConnectEnd"
          @edge-click="onEdgeClick"
          @node-click="onNodeClick"
          @pane-click="selectedNodeId = ''; selectedEdgeId = ''"
          @node-drag-start="onNodeDragStart"
          @node-drag-stop="onNodeDragStop"
          @viewport-change-end="onViewportChangeEnd"
        >
          <Background :gap="16" pattern-color="#dce8f3" />
          <Controls />

          <template #node-material="slotProps">
            <WorkflowMaterialNode
              :kind="slotProps.data.kind"
              :data="slotProps.data"
              :selected="slotProps.selected"
              :can-write="canEdit"
              :connecting-from-kind="connectingFromKind"
              :raw-material-options="rawMaterialOptions"
              :bom-raw-material-ids="bomRawMaterialIdList"
              :semi-options="semiFinishedSkuOptions"
              :finished-options="finishedGoodSkuOptions"
              :unit-error="unitIssueForNode(slotProps.id)"
              @add-next="openAddProcess(slotProps.id)"
              @select-raw-sku="(skuId) => selectRawSku(slotProps.id, skuId)"
              @select-sku="(skuId) => selectMaterialSku(slotProps.id, skuId)"
              @edit-sku="openQuickEditSku(slotProps.id)"
              @delete="removeNode(slotProps.id)"
              @config-bom="openBomDrawer"
            />
          </template>

          <template #node-process="slotProps">
            <WorkflowProcessNode
              :data="slotProps.data"
              :selected="slotProps.selected"
              :can-write="canEdit"
              :connecting-from-kind="connectingFromKind"
              :semi-options="semiFinishedSkuOptions"
              :finished-options="finishedGoodSkuOptions"
              :allow-add-input="!isRawOwnerFirstProcess(slotProps.id)"
              @update="(patch) => updateProcessData(slotProps.id, patch)"
              @add-input="addInputToProcess(slotProps.id)"
              @add-output="addOutputToProcess(slotProps.id)"
              @select-output="(portId, skuId) => selectOutputSku(slotProps.id, portId, skuId)"
              @delete="removeNode(slotProps.id)"
            />
          </template>
        </VueFlow>

        <!-- #9: 选中连线时浮出可见删除入口 (不只靠 Delete 键; 防呆) -->
        <div v-if="selectedEdgeId && canEdit" class="edge-delete-bar">
          <span>已选中一条连线</span>
          <el-button size="small" type="danger" @click="removeEdgeById(selectedEdgeId)">删除连线</el-button>
          <el-button size="small" text @click="selectedEdgeId = ''">取消</el-button>
        </div>

        <div v-if="productTypeId && flowNodes.length === 0 && !loading" class="empty-canvas-action">
          <el-empty description="该产品还没有工序图">
            <el-button v-if="canEdit" type="primary" @click="addStandaloneRaw">添加第一个原料 Cell</el-button>
          </el-empty>
        </div>
      </div>
    </div>

    <aside id="workflow-ai-panel" class="ai-sidebar">
      <button
        class="ai-collapse-button"
        type="button"
        :aria-expanded="!aiCollapsed"
        aria-controls="workflow-ai-panel"
        @click="toggleAI"
      >
        {{ aiCollapsed ? 'AI' : '收起 AI' }}
      </button>
      <div v-if="!aiCollapsed" class="ai-content">
        <div class="ai-context">
          <span>当前上下文</span>
          <strong>{{ selectedNodeLabel }}</strong>
        </div>
        <WorkProcessAIChatPanel
          v-if="factoryId"
          :key="`${factoryId}:${productTypeId}`"
          :factory-id="factoryId"
          :product-type-id="productTypeId"
          :endpoint="`/${factoryId}/config/v2/ai/chat`"
          module-code="product_process_workflow_config"
          :title="`AI 助手${selectedNodeId ? ' · 当前 Cell' : ''}`"
          :disabled="!canEdit"
          :context="selectedNodeContext"
          :quick-prompts="aiQuickPrompts"
          @apply-draft="applyWorkflowAIDraft"
        />
      </div>
    </aside>

    <el-dialog v-model="processDialogVisible" title="增加后续工序" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="上游物料">
          <el-tag>{{ sourceMaterialLabel }}</el-tag>
        </el-form-item>
        <el-form-item label="工序来源">
          <el-radio-group v-model="processCreateMode">
            <el-radio-button label="existing">选择已有工序</el-radio-button>
            <el-radio-button label="create">现场创建工序</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="processCreateMode === 'existing'" label="选择工序" required>
          <el-select
            v-model="selectedWorkProcessId"
            filterable
            placeholder="选择工序（支持拼音首字母搜索）"
            style="width: 100%"
            :filter-method="handleWorkProcessFilter"
            @visible-change="handleWorkProcessVisibleChange"
          >
            <el-option
              v-for="process in filteredWorkProcessOptions"
              :key="process.id"
              :label="process.processCategory ? `${process.processName} · ${process.processCategory}` : process.processName"
              :value="process.id"
            />
          </el-select>
        </el-form-item>

        <template v-else>
          <el-form-item label="工序名称" required>
            <el-input v-model="newProcessForm.name" placeholder="例：真空封口" data-testid="new-process-name" />
          </el-form-item>
          <!-- #13 相似检测防重: 有相似工序时提示复用, 不硬禁创建 -->
          <el-alert
            v-if="similarProcesses.length"
            type="warning"
            :closable="false"
            show-icon
            data-testid="similar-process-warn"
            class="similar-proc-alert"
          >
            <div style="margin-bottom: 4px;">已有相似工序，避免重复创建：</div>
            <div class="similar-proc-list">
              <el-button
                v-for="p in similarProcesses"
                :key="p.id"
                link
                type="primary"
                size="small"
                @click="reuseSimilarProcess(p)"
              >复用「{{ p.processName }}」</el-button>
            </div>
          </el-alert>
          <el-alert type="info" :closable="false" title="投入单位继承上游物料；产出单位由所选半成品或成品 SKU 自动带入。" />
          <el-form-item label="产出类型">
            <el-radio-group v-model="newProcessForm.outputKind">
              <el-radio-button label="SEMI_FINISHED">半成品</el-radio-button>
              <el-radio-button label="FINISHED_GOOD">成品</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </template>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="确认后会根据工序的默认产出类型，自动生成工序 Cell、产出 Cell 和两条连接。"
      />
      <template #footer>
        <el-button @click="processDialogVisible = false">取消</el-button>
        <el-button
          v-if="processCreateMode === 'existing'"
          type="primary"
          :disabled="!selectedWorkProcessId"
          @click="confirmAddProcess"
        >确认增加</el-button>
        <el-button
          v-else
          type="primary"
          :loading="creatingProcess"
          :disabled="!newProcessForm.name.trim()"
          @click="confirmCreateAndAddProcess"
        >创建并增加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="skuDialogVisible" title="现场创建半成品 SKU" width="500px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="半成品名称" required>
          <el-input v-model="skuForm.name" placeholder="例：红烧熟制后猪蹄" />
        </el-form-item>
        <el-form-item label="基本单位" required>
          <UnitSelect
            v-model="skuForm.unit"
            :factory-id="factoryId"
            placeholder="选择或搜索基本单位"
            :clearable="false"
          />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="确认时先检查同名 SKU；发现重复会直接复用已有 SKU，不会重复创建。"
      />
      <template #footer>
        <el-button @click="skuDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingSku" @click="confirmCreateSku">确认并绑定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="quickEditVisible" title="快捷修改 SKU" width="480px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        title="这里修改的是 SKU 主数据，会同步影响其它使用该 SKU 的页面；历史计划仍保留原快照。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="90px">
        <el-form-item label="SKU 名称" required>
          <el-input v-model="quickEditForm.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="基本单位" required>
          <UnitSelect
            v-model="quickEditForm.unit"
            :factory-id="factoryId"
            placeholder="选择或搜索基本单位"
            :clearable="false"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quickEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="quickEditSaving" @click="saveQuickEditSku">保存主数据</el-button>
      </template>
    </el-dialog>

    <!-- #10: BOM 原辅料配置抽屉 (右侧滑出, 不跳转页面; 关闭即回工序配置, 工序草稿不丢) -->
    <el-drawer
      v-model="bomDrawerVisible"
      title="BOM / 配方配置"
      direction="rtl"
      size="72%"
      destroy-on-close
      @closed="onBomDrawerClosed"
    >
      <BomUnifiedPanel v-if="bomDrawerVisible" />
    </el-drawer>

    <!-- #12b: 版本记录抽屉 (只读浏览之前发布过的版本) -->
    <el-drawer v-model="versionDrawerVisible" title="版本记录" direction="rtl" size="440px">
      <div v-loading="versionLoading" class="version-list">
        <el-empty v-if="!versionList.length && !versionLoading" description="暂无版本记录" :image-size="80" />
        <div v-for="v in versionList" :key="v.definitionVersion" class="version-row" data-testid="version-row">
          <div class="version-meta">
            <span class="version-num">v{{ v.definitionVersion }}</span>
            <el-tag size="small" :type="v.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ v.status === 'PUBLISHED' ? '已发布' : '草稿' }}
            </el-tag>
            <el-tag v-if="v.active" size="small" type="warning">已启用</el-tag>
            <span class="version-time">{{ (v.updatedAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </div>
          <el-button size="small" @click="previewVersion(v)">查看</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import gsap from 'gsap';
import { prefersReducedMotion } from '@/utils/motion/prefersReducedMotion';
import {
  MarkerType,
  VueFlow,
  useVueFlow,
  type Connection,
  type Edge,
  type Node,
  type ViewportTransform,
} from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post, put } from '@/api/request';
import { getUnitCatalog, type UnitCatalogItem } from '@/api/unitContract';
import {
  createWorkProcess,
  getActiveWorkProcesses,
  getProductWorkProcesses,
  type WorkProcessItem,
} from '@/api/processProduction';
import WorkProcessAIChatPanel from '@/views/system/components/WorkProcessAIChatPanel.vue';
import UnitSelect from '@/components/common/UnitSelect.vue';
import WorkflowMaterialNode from './WorkflowMaterialNode.vue';
import WorkflowProcessNode from './WorkflowProcessNode.vue';
import { classifyOutputSkuCategory, matchOutputSkuByName } from './outputSkuClassification';
import { usePinyinFilter } from './pinyinInitials';
import {
  activateProductProcessWorkflow,
  deactivateProductProcessWorkflow,
  getProductProcessWorkflow,
  getProductProcessWorkflowActivation,
  getProductProcessWorkflowVersion,
  listProductProcessWorkflowVersions,
  publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft,
  type WorkflowVersionSummary,
} from './workflowApi';
import {
  applyWorkflowPatches,
  autoLayoutWorkflow,
  createProcessBranch,
  createWorkflowFromLegacy,
  evaluateWorkflowConnection,
  snapPosition,
  toPlainWorkflowValue,
  validateWorkflow,
} from './workflowModel';
import {
  forkWorkflowUnitReviewDraft,
  reconcileProcessPortQuantities,
  reconcileWorkflowUnits,
  workflowReportingUnit,
  type WorkflowUnitContext,
  type WorkflowUnitIssue,
} from './workflowUnits';
import type {
  MaterialNodeData,
  ProductProcessWorkflowActivation,
  ProcessNodeData,
  ProcessPort,
  ProductProcessNodeKind,
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowEdge,
  ProductProcessWorkflowNode,
} from './types';

import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';

interface SkuOption {
  id: string;
  name: string;
  code?: string;
  unit?: string;
  specification?: string;
  productCategory?: string;
}

interface RawMaterialOption {
  id: string;
  name: string;
  code?: string;
  unit?: string;
}

/**
 * #3: 后端 BomItem 实体的精简形状 (只取本编辑器需要的字段)。
 * 对应 GET /{factoryId}/bom/items/{productTypeId}
 * (com.cretas.aims.controller.BomController#getBomItems, 已确认存在)。
 */
interface BomItemOption {
  materialTypeId: string;
  materialName?: string;
  unit?: string;
  materialCategory?: string;
}

interface WorkflowIdentity {
  factoryId: string;
  productTypeId: string;
}

interface SkuBindingTarget {
  processId: string;
  portId: string;
}

const props = defineProps<{
  factoryId: string;
  productTypeId: string;
  productName: string;
  canWrite: boolean;
  // raw-centric 双模式: owner 是原料 → 允许多终端成品 (分支/扇出); 成品 → 单终端成品。
  // 前端提示为主, 硬约束在后端发布校验 (按 owner 分类校验终端 FINISHED_GOOD 数)。
  rawOwnerMode?: boolean;
}>();

const emit = defineEmits<{
  ownerChange: [ownerId: string];
}>();

const { fitView, getViewport, setViewport } = useVueFlow('product-process-workflow');
const definition = ref<ProductProcessWorkflowDefinition | null>(null);
const activation = ref<ProductProcessWorkflowActivation | null>(null);
const loadedDefinitionIdentity = ref<WorkflowIdentity | null>(null);
const flowNodes = ref<Node[]>([]);
const flowEdges = ref<Edge[]>([]);
const loading = ref(false);
const catalogLoading = ref(false);
const loadedCatalogFactoryId = ref<string | null>(null);
const saving = ref(false);
const publishing = ref(false);
const activationChanging = ref(false);
const dirty = ref(false);
const selectedNodeId = ref('');
// #8 拖拽连线态: connectingFromKind 驱动画布上非法目标 cell 灰化; selectedEdgeId 支持连错删边
const connectingFromKind = ref<'' | 'MATERIAL' | 'PROCESS'>('');
const selectedEdgeId = ref('');
const canvasRef = ref<HTMLElement | null>(null);
let gsapCtx: gsap.Context | null = null;
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;
// #11 fix: 每次本地改动 +1; 保存时对比, 若 PUT 往返期间有新改动则不 hydrate 覆盖 (防丢在途编辑)
let editSeq = 0;
// #10: BOM 配置抽屉 (右侧滑出, 不跳转页面, 关闭即回工序配置, 避免丢失未保存草稿)
const bomDrawerVisible = ref(false);
const BomUnifiedPanel = defineAsyncComponent(() => import('@/views/production/bom-unified/index.vue'));
// #12b: 版本记录浏览 (只读查看之前发布过的版本); previewingVersion 非空时 = 正在预览历史版本
const versionDrawerVisible = ref(false);
const versionList = ref<WorkflowVersionSummary[]>([]);
const versionLoading = ref(false);
const history = ref<ProductProcessWorkflowDefinition[]>([]);
const future = ref<ProductProcessWorkflowDefinition[]>([]);
const dragStartSnapshot = ref<ProductProcessWorkflowDefinition | null>(null);
const workProcessOptions = ref<WorkProcessItem[]>([]);
const skuOptions = ref<SkuOption[]>([]);
const rawMaterialOptions = ref<RawMaterialOption[]>([]);
const unitCatalog = ref<UnitCatalogItem[]>([]);
// #3: 该产品 BOM 原辅料清单 (per-product, 随 productTypeId 变化而重新加载,
// 与 loadCatalogs 的"全厂字典"缓存粒度不同, 单独一个 ref + 单独一个 loader)。
const productBomItems = ref<BomItemOption[]>([]);
const bomMissingProducts = ref<Array<{ id: string; name: string }>>([]);
const bomRawMaterialIdList = computed(() => productBomItems.value.map((item) => item.materialTypeId));
const outputSkuOptions = computed(() => skuOptions.value.filter(
  (option) => classifyOutputSkuCategory(option.productCategory) !== null,
));
// 两级选择器用：把可作产出的 SKU 按分类拆成「半成品」「成品」两组，供物料 Cell 和
// 工序 Cell 产出行共用同一份数据源（WorkflowSkuPicker 组件负责渲染两级分组 UI）。
const semiFinishedSkuOptions = computed(() => skuOptions.value.filter(
  (option) => classifyOutputSkuCategory(option.productCategory) === 'SEMI_FINISHED',
));
const finishedGoodSkuOptions = computed(() => skuOptions.value.filter(
  (option) => classifyOutputSkuCategory(option.productCategory) === 'FINISHED_GOOD',
));

const processDialogVisible = ref(false);
const processSourceMaterialId = ref('');
const selectedWorkProcessId = ref('');

// #13: 现场创建工序 + 相似检测防重复建。工序本身不持有业务单位；legacy payload 继承上游端口单位。
const processCreateMode = ref<'existing' | 'create'>('existing');
const creatingProcess = ref(false);
const newProcessForm = ref<{ name: string; outputKind: 'SEMI_FINISHED' | 'FINISHED_GOOD' }>(
  { name: '', outputKind: 'SEMI_FINISHED' },
);
function normProcessName(s: string): string { return s.replace(/\s+/g, ''); } // 中文不 lowercase
// 相似 = 同名 / 一个包含另一个 / 共享 >=2 连续字 → 提示复用避免重复建。
const similarProcesses = computed<WorkProcessItem[]>(() => {
  const q = normProcessName(newProcessForm.value.name);
  if (q.length < 2) return [];
  return workProcessOptions.value.filter((p) => {
    const name = normProcessName(p.processName || '');
    if (!name) return false;
    if (name === q || name.includes(q) || q.includes(name)) return true;
    for (let i = 0; i + 2 <= q.length; i += 1) {
      if (name.includes(q.slice(i, i + 2))) return true;
    }
    return false;
  }).slice(0, 6);
});
function reuseSimilarProcess(p: WorkProcessItem): void {
  processCreateMode.value = 'existing';
  selectedWorkProcessId.value = p.id;
}
async function confirmCreateAndAddProcess(): Promise<void> {
  const identity = currentLoadedIdentity();
  const name = newProcessForm.value.name.trim();
  if (!identity || !name || creatingProcess.value) return;
  const source = flowNodes.value.find((node) => node.id === processSourceMaterialId.value);
  const compatibilityUnit = String(source?.data?.baseUnit || '').trim();
  if (!compatibilityUnit) {
    ElMessage.warning('上游物料缺少单位，请先绑定物料 SKU');
    return;
  }
  creatingProcess.value = true;
  try {
    const payload: Partial<WorkProcessItem> = {
      processName: name,
      unit: compatibilityUnit,
      outputUnit: compatibilityUnit,
      defaultOutputMaterialKind: newProcessForm.value.outputKind,
      isActive: true,
    };
    const response = await createWorkProcess(identity.factoryId, payload);
    if (!response.success || !response.data) {
      ElMessage.error(response.message || '工序创建失败');
      return;
    }
    workProcessOptions.value = [response.data, ...workProcessOptions.value];
    selectedWorkProcessId.value = response.data.id;
    ElMessage.success(`工序「${name}」已创建`);
    confirmAddProcess(); // 用刚建好的工序直接增加后续工序
  } catch (error) {
    console.error('[createWorkProcess] failed', error);
  } finally {
    creatingProcess.value = false;
  }
}
// #2: 「增加后续工序」dialog 里的工序选择支持拼音首字母搜索 (复用 usePinyinFilter)。
const workProcessFilter = usePinyinFilter(
  () => workProcessOptions.value,
  (process) => [process.processName],
);
const handleWorkProcessFilter = workProcessFilter.handleFilter;
const handleWorkProcessVisibleChange = workProcessFilter.handleVisibleChange;
const filteredWorkProcessOptions = workProcessFilter.filtered;
const skuDialogVisible = ref(false);
const creatingSku = ref(false);
const skuBindingTarget = ref<SkuBindingTarget | null>(null);
const skuForm = ref({ name: '', unit: 'kg' });
const quickEditVisible = ref(false);
const quickEditSaving = ref(false);
const quickEditNodeId = ref('');
const quickEditSkuId = ref('');
const quickEditForm = ref({ name: '', unit: 'kg' });
let lastGraphIdSeed = 0;
let catalogGeneration = 0;
let createSkuGeneration = 0;
let loadGeneration = 0;
let bomLoadGeneration = 0;
let saveGeneration = 0;
let publishGeneration = 0;
let activationLoadGeneration = 0;
let activationMutationGeneration = 0;

const productTypeId = computed(() => props.productTypeId);
const rawOwnerMode = computed(() => props.rawOwnerMode === true);
const rawRootNodes = computed(() => flowNodes.value.filter((node) => node.data?.kind === 'RAW_MATERIAL'));
const hasRawRoot = computed(() => rawRootNodes.value.length > 0);
// #12b 预览历史版本标志 (非空=正在只读预览某历史版本); 提前声明供 canEdit 引用
const previewingVersion = ref<number | null>(null);
const unitReviewPending = ref(false);
const unitIssues = ref<WorkflowUnitIssue[]>([]);
const canEdit = computed(() => (
  props.canWrite
  && !loading.value
  && !catalogLoading.value
  && previewingVersion.value === null   // #12b 预览历史版本时整个画布只读 (不可拖/存/发布, 防止旧版本被当草稿保存发布)
  && loadedCatalogFactoryId.value === props.factoryId
  && loadedDefinitionIdentity.value?.factoryId === props.factoryId
  && loadedDefinitionIdentity.value?.productTypeId === props.productTypeId
));
const aiStorageKey = computed(() => `product-process-workflow:ai-collapsed:${props.factoryId}`);
const aiCollapsed = ref(true);
const aiQuickPrompts = [
  '检查当前 Workflow 的 SKU 上下游承接',
  '检查投入与产出单位、数量换算是否完整',
  '检查分流、合流和同 SKU 多投入是否合理',
  '根据我的描述生成工序调整草稿',
];

const selectedNode = computed(() => flowNodes.value.find((node) => node.id === selectedNodeId.value));
const selectedNodeLabel = computed(() => {
  const data = selectedNode.value?.data as Record<string, unknown> | undefined;
  return String(data?.processName || data?.name || (props.productTypeId ? '整个 Workflow' : '未选择产品'));
});
const selectedNodeContext = computed<Record<string, unknown>>(() => ({
  productTypeId: props.productTypeId,
  definition: definition.value ? currentDefinition() : null,
  selectedNodeId: selectedNodeId.value || null,
  selectedNode: selectedNode.value ? serializeFlowNode(selectedNode.value) : null,
  graphSummary: {
    nodes: flowNodes.value.length,
    edges: flowEdges.value.length,
  },
}));
const sourceMaterialLabel = computed(() => {
  const node = flowNodes.value.find((candidate) => candidate.id === processSourceMaterialId.value);
  return String(node?.data?.name || '未选择');
});

onMounted(async () => {
  // 画布是主任务，AI 只在用户明确展开后占用侧栏宽度；历史没有偏好时默认折叠。
  aiCollapsed.value = localStorage.getItem(aiStorageKey.value) !== 'false';
  // #8: GSAP context 作用域化 (吸附脉冲), 卸载时 revert; 键盘删边监听
  gsapCtx = gsap.context(() => {}, canvasRef.value || undefined);
  window.addEventListener('keydown', onEditorKeydown);
  await loadCatalogs();
  await Promise.all([loadDefinition(), loadActivation()]);
  await loadProductBom();
});

onUnmounted(() => {
  window.removeEventListener('keydown', onEditorKeydown);
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  gsapCtx?.revert();
  gsapCtx = null;
});

// #11: 每次操作后防抖 ~2.5s 自动保存 (静默 + 保留撤销栈)。用户停手 2.5s 即存;
// 连续操作只会在最后一次后触发。服务端失败后不自动重试同一编辑，避免 500 通知风暴；
// 用户再次编辑会重新排期，手动“保存草稿”也可立即重试。
const AUTO_SAVE_DELAY = 2500;
function scheduleAutoSave(): void {
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(async () => {
    autoSaveTimer = null;
    // 只读预览 / 不可编辑 → 不存也不重排 (退出预览/恢复编辑后由 mutate 重新排)
    if (previewingVersion.value !== null || !canEdit.value) return;
    if (!dirty.value) return;                                 // 没有未保存改动
    if (saving.value) { scheduleAutoSave(); return; }         // 正在存 → 稍后重试, 不丢
    await saveDraft({ silent: true, preserveHistory: true });
  }, AUTO_SAVE_DELAY);
}
// 每次改动 (dirty 置 true) 都重排防抖定时器 —— 定时器 2.5s 后从"最后一次改动"起算,
// 不再依赖 dirty 的 false→true 一次性 transition (那样一次跳过/失败就永久停摆)。
watch(dirty, (isDirty) => { if (isDirty) scheduleAutoSave(); });
watch(
  () => flowNodes.value
    .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
    .map((node) => String(node.data.skuId))
    .sort()
    .join(','),
  () => { if (rawOwnerMode.value) void loadProductBom(); },
);

// #10: 打开 BOM 配置抽屉; 关闭时刷新本产品 BOM (原料分组 + 提示随即更新)
function openBomDrawer(): void {
  bomDrawerVisible.value = true;
}
async function onBomDrawerClosed(): Promise<void> {
  await loadProductBom();
}

// #12b: 版本记录浏览
async function openVersionDrawer(): Promise<void> {
  const identity = currentLoadedIdentity();
  if (!identity) return;
  versionDrawerVisible.value = true;
  versionLoading.value = true;
  try {
    const resp = await listProductProcessWorkflowVersions(identity.factoryId, identity.productTypeId);
    versionList.value = resp.success && Array.isArray(resp.data) ? resp.data : [];
  } catch (error) {
    console.error('[ProductProcessWorkflow] listVersions failed', error);
    versionList.value = [];
  } finally {
    versionLoading.value = false;
  }
}
async function previewVersion(v: WorkflowVersionSummary): Promise<void> {
  const identity = currentLoadedIdentity();
  if (!identity) return;
  try {
    const resp = await getProductProcessWorkflowVersion(identity.factoryId, identity.productTypeId, v.definitionVersion);
    if (!resp.success || !resp.data) { ElMessage.error('加载该版本失败'); return; }
    hydrate(resp.data);
    previewingVersion.value = v.definitionVersion;
    dirty.value = false; // 预览历史版本不算改动, 不触发自动保存 (否则会把旧版覆盖当前草稿)
    versionDrawerVisible.value = false;
    await fitCanvas();
  } catch (error) {
    console.error('[ProductProcessWorkflow] getVersion failed', error);
  }
}
async function exitVersionPreview(): Promise<void> {
  previewingVersion.value = null;
  await loadDefinition(); // 回到当前草稿/最新版本
  await fitCanvas();
}
function restorePreviewAsDraft(): void {
  // 把正在预览的历史版本内容作为新草稿 (标 dirty → 自动保存写回 draft)
  previewingVersion.value = null;
  dirty.value = true;
  ElMessage.success('已将该历史版本恢复为当前草稿，正在自动保存');
}

watch(() => [props.factoryId, props.productTypeId] as const, async (next, previous) => {
  if (next[0] === previous[0] && next[1] === previous[1]) return;
  previewingVersion.value = null;   // #12b fix: 切产品必退出历史版本预览 (否则横幅粘住 + 新产品自动保存被抑制)
  if (next[0] !== previous[0]) {
    await loadCatalogs();
    await Promise.all([loadDefinition(), loadActivation()]);
    await loadProductBom();
    return;
  }
  await Promise.all([loadDefinition(), loadActivation()]);
  await loadProductBom();
});

watch(aiStorageKey, () => {
  aiCollapsed.value = localStorage.getItem(aiStorageKey.value) !== 'false';
});

async function loadCatalogs(): Promise<void> {
  const generation = ++catalogGeneration;
  const factoryId = props.factoryId;
  invalidateCatalogs();
  if (!factoryId) return;
  catalogLoading.value = true;
  try {
    const unitCatalogRequest = getUnitCatalog(factoryId).catch((error) => {
      console.error('[ProductProcessWorkflow] unit catalog loading failed; using built-in scientific units', error);
      return null;
    });
    const [processResponse, productResponse, rawResponse, unitCatalogResponse] = await Promise.all([
      getActiveWorkProcesses(factoryId),
      // 精简「选项」端点 (7 字段: id/name/code/unit/specification/productCategory/isActive + @Cacheable) —
      // SkuOption 只需这些字段; 避开重 DTO 的 ~3s/422KB 全量加载 (顶部选择器已先命中缓存, 这里秒回)。
      get<{ content: SkuOption[] }>(`/${factoryId}/product-types/options`),
      get<RawMaterialOption[]>(`/${factoryId}/raw-material-types/active`),
      unitCatalogRequest,
    ]);
    if (!isCurrentCatalogLoad(generation, factoryId)) return;
    if (!processResponse.success
      || !Array.isArray(processResponse.data)
      || !productResponse.success
      || !Array.isArray(productResponse.data?.content)
      || !rawResponse.success
      || !Array.isArray(rawResponse.data)) {
      throw new Error('Workflow catalog response is incomplete');
    }
    workProcessOptions.value = processResponse.data;
    skuOptions.value = productResponse.data.content;
    rawMaterialOptions.value = rawResponse.data;
    if (unitCatalogResponse?.success && Array.isArray(unitCatalogResponse.data)) {
      unitCatalog.value = unitCatalogResponse.data;
    } else {
      unitCatalog.value = [];
      ElMessage.warning('系统单位目录加载失败，当前仅使用内建质量、体积和长度换算规则');
    }
    loadedCatalogFactoryId.value = factoryId;
    void reconcileLoadedUnits();
  } catch (error) {
    if (!isCurrentCatalogLoad(generation, factoryId)) return;
    invalidateCatalogs();
    console.error('[ProductProcessWorkflow] catalog loading failed', error);
    ElMessage.error('Workflow 所需的工序或 SKU 字典加载失败');
  } finally {
    if (isCurrentCatalogLoad(generation, factoryId)) {
      catalogLoading.value = false;
    }
  }
}

function invalidateCatalogs(): void {
  catalogLoading.value = false;
  loadedCatalogFactoryId.value = null;
  workProcessOptions.value = [];
  skuOptions.value = [];
  rawMaterialOptions.value = [];
  unitCatalog.value = [];
}

function isCurrentCatalogLoad(generation: number, factoryId: string): boolean {
  return generation === catalogGeneration && factoryId === props.factoryId;
}

function unitContext(): WorkflowUnitContext {
  const products: WorkflowUnitContext['products'] = {};
  [...skuOptions.value, ...rawMaterialOptions.value].forEach((option) => {
    if (!option.id || !option.unit) return;
    products[option.id] = {
      productTypeId: option.id,
      primaryUnit: option.unit,
      conversions: [],
    };
  });
  return { products, catalog: unitCatalog.value };
}

function unitIssueForNode(nodeId: string): string | undefined {
  return unitIssues.value.find((issue) => issue.nodeId === nodeId)?.message;
}

function rememberUnitIssues(issues: WorkflowUnitIssue[]): void {
  unitIssues.value = issues;
}

async function reconcileLoadedUnits(): Promise<void> {
  const identity = currentLoadedIdentity();
  if (!identity || loadedCatalogFactoryId.value !== identity.factoryId || !definition.value) return;
  if (!isLoadedIdentityCurrent(identity)) return;
  const current = currentDefinition();
  const result = reconcileWorkflowUnits(current, unitContext());
  rememberUnitIssues(result.errors);
  const unitsChanged = JSON.stringify(result.definition) !== JSON.stringify(current);
  if (definition.value.status === 'PUBLISHED') {
    if (result.errors.length > 0) showUnitIssues(result.errors);
    if (result.errors.length > 0) return;
    // 旧版本可能早于 unitReviewRequired 标记，或 SKU 数据曾通过导入/迁移更新。
    // 只要当前 SKU 契约与发布图不一致，前端也必须主动进入待复核草稿，不能继续把 g/box
    // 当作当前报工单位展示。发布版本本身仍保持不可变，重新发布后才影响新计划。
    if (!unitReviewPending.value && !unitsChanged) return;
    unitReviewPending.value = true;
  }
  const needsReviewDraft = definition.value.status === 'PUBLISHED' && unitReviewPending.value;
  if (unitsChanged || needsReviewDraft) {
    const reconciled = needsReviewDraft
      ? forkWorkflowUnitReviewDraft(result.definition)
      : result.definition;
    hydrate(reconciled);
    dirty.value = true;
  }
}

async function reconcileForPersistence(
  identity: WorkflowIdentity,
  showErrors: boolean,
): Promise<ProductProcessWorkflowDefinition | null> {
  let current = currentDefinition();
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const seq = editSeq;
    if (!isLoadedIdentityCurrent(identity)) return null;
    current = currentDefinition();
    if (seq === editSeq) break;
    if (attempt === 2) {
      if (showErrors) ElMessage.warning('单位校验期间内容仍在变化，请停止编辑后重试');
      return null;
    }
  }
  const result = reconcileWorkflowUnits(current, unitContext());
  rememberUnitIssues(result.errors);
  if (result.errors.length > 0) {
    if (showErrors) showUnitIssues(result.errors);
    return null;
  }
  if (JSON.stringify(result.definition) !== JSON.stringify(current)) {
    hydrate(result.definition);
    dirty.value = true;
  }
  return result.definition;
}

function showUnitIssues(issues: WorkflowUnitIssue[]): void {
  void ElMessageBox.alert(
    issues.slice(0, 8).map((issue) => `• ${issue.message}`).join('\n'),
    'Workflow 单位契约未通过',
    { type: 'warning' },
  );
}

async function loadDefinition(): Promise<void> {
  const generation = ++loadGeneration;
  const identity = {
    factoryId: props.factoryId,
    productTypeId: props.productTypeId,
  };
  const productName = props.productName;
  invalidateLoadedDefinition();
  if (!identity.factoryId || !identity.productTypeId) {
    return;
  }
  loading.value = true;
  try {
    const response = await getProductProcessWorkflow(identity.factoryId, identity.productTypeId);
    if (!isCurrentLoad(generation, identity)) return;
    let nextDefinition = response.success ? response.data : null;
    if (!nextDefinition) {
      let legacyProcesses: Parameters<typeof createWorkflowFromLegacy>[0]['processes'] = [];
      if (!rawOwnerMode.value) {
        const legacyResponse = await getProductWorkProcesses(identity.factoryId, identity.productTypeId);
        if (!isCurrentLoad(generation, identity)) return;
        legacyProcesses = legacyResponse.success && Array.isArray(legacyResponse.data)
          ? legacyResponse.data
          : [];
      }
      nextDefinition = createWorkflowFromLegacy({
        productTypeId: identity.productTypeId,
        productName: productName || identity.productTypeId,
        processes: legacyProcesses,
      });
    }
    if (rawOwnerMode.value) {
      const owner = rawMaterialOptions.value.find((item) => item.id === identity.productTypeId);
      const roots = nextDefinition.nodes.filter((node) => node.kind === 'RAW_MATERIAL');
      if (roots.length === 1) {
        roots[0].data = {
          ...roots[0].data,
          name: owner?.name || productName || identity.productTypeId,
          skuId: identity.productTypeId,
          skuCode: owner?.code || identity.productTypeId,
          baseUnit: workflowReportingUnit('RAW_MATERIAL', owner?.unit || 'kg'),
          bound: true,
        };
      }
    }
    if (!definitionMatchesIdentity(nextDefinition, identity)) {
      throw new Error('Workflow definition identity does not match the requested product');
    }
    unitReviewPending.value = nextDefinition.unitReviewRequired === true;
    hydrate(nextDefinition);
    loadedDefinitionIdentity.value = identity;
    dirty.value = !nextDefinition.id;
    void reconcileLoadedUnits();
    await nextTick();
    if (!isCurrentLoad(generation, identity)) return;
    if (nextDefinition.id) {
      await setViewport(nextDefinition.viewport);
    } else if (flowNodes.value.length > 0) {
      await fitCanvas();
    }
  } catch (error) {
    if (!isCurrentLoad(generation, identity)) return;
    invalidateLoadedDefinition(false);
    console.error('[ProductProcessWorkflow] definition loading failed', error);
    ElMessage.error('Workflow 图定义加载失败');
  } finally {
    if (isCurrentLoad(generation, identity)) {
      loading.value = false;
    }
  }
}

function invalidateLoadedDefinition(invalidatePersistence = true): void {
  loading.value = false;
  loadedDefinitionIdentity.value = null;
  definition.value = null;
  unitReviewPending.value = false;
  unitIssues.value = [];
  flowNodes.value = [];
  flowEdges.value = [];
  dirty.value = false;
  selectedNodeId.value = '';
  history.value = [];
  future.value = [];
  dragStartSnapshot.value = null;
  processDialogVisible.value = false;
  skuDialogVisible.value = false;
  quickEditVisible.value = false;
  quickEditNodeId.value = '';
  quickEditSkuId.value = '';
  bomMissingProducts.value = [];
  skuBindingTarget.value = null;
  if (invalidatePersistence) {
    createSkuGeneration += 1;
    saveGeneration += 1;
    publishGeneration += 1;
    creatingSku.value = false;
    saving.value = false;
    publishing.value = false;
  }
}

function isCurrentLoad(generation: number, identity: WorkflowIdentity): boolean {
  return generation === loadGeneration
    && identity.factoryId === props.factoryId
    && identity.productTypeId === props.productTypeId;
}

function definitionMatchesIdentity(
  candidate: ProductProcessWorkflowDefinition,
  identity: WorkflowIdentity,
): boolean {
  return (!candidate.factoryId || candidate.factoryId === identity.factoryId)
    && (!candidate.productTypeId || candidate.productTypeId === identity.productTypeId);
}

async function loadActivation(): Promise<void> {
  const generation = ++activationLoadGeneration;
  ++activationMutationGeneration;
  activationChanging.value = false;
  const identity: WorkflowIdentity = {
    factoryId: props.factoryId,
    productTypeId: props.productTypeId,
  };
  activation.value = null;
  if (!identity.factoryId || !identity.productTypeId) return;
  try {
    const response = await getProductProcessWorkflowActivation(
      identity.factoryId,
      identity.productTypeId,
    );
    if (generation !== activationLoadGeneration || !propsMatchIdentity(identity)) return;
    const candidate = response.success ? response.data : null;
    activation.value = candidate && activationMatchesIdentity(candidate, identity)
      ? candidate
      : null;
  } catch (error) {
    if (generation !== activationLoadGeneration || !propsMatchIdentity(identity)) return;
    console.error('[ProductProcessWorkflow] activation loading failed', error);
    activation.value = null;
  }
}

/**
 * #3: 拉该产品的 BOM 原辅料清单，供原料 Cell picker 做「BOM 原料优先」分组。
 * Per-product (随 productTypeId 变化), 与 loadCatalogs 的全厂字典缓存粒度不同,
 * 所以是独立的 loader, 复用 loadDefinition/loadActivation 同款的 generation 防
 * race 写法。BOM 为空是正常业务状态 (产品尚未配置 BOM), 不当错误处理; 只有请求
 * 本身失败才 console.error + 保持空列表 (picker 会退化成"全部原料一组", 不阻断
 * 编辑器其它功能)。
 */
async function loadProductBom(): Promise<void> {
  const generation = ++bomLoadGeneration;
  const factoryId = props.factoryId;
  const ownerId = props.productTypeId;
  productBomItems.value = [];
  bomMissingProducts.value = [];
  if (!factoryId || !ownerId) return;
  const targets = rawOwnerMode.value
    ? flowNodes.value
      .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
      .map((node) => ({ id: String(node.data.skuId), name: String(node.data.name || node.data.skuId) }))
    : [{ id: ownerId, name: props.productName || ownerId }];
  const uniqueTargets = targets.filter((target, index, list) =>
    list.findIndex((candidate) => candidate.id === target.id) === index);
  if (uniqueTargets.length === 0) return;
  try {
    const responses = await Promise.all(uniqueTargets.map(async (target) => ({
      target,
      response: await get<BomItemOption[]>(`/${factoryId}/bom/items/${target.id}`),
    })));
    if (generation !== bomLoadGeneration
      || props.factoryId !== factoryId
      || props.productTypeId !== ownerId) return;
    const allItems: BomItemOption[] = [];
    const missing: Array<{ id: string; name: string }> = [];
    responses.forEach(({ target, response }) => {
      if (!response.success || !Array.isArray(response.data)) return;
      allItems.push(...response.data);
      if (response.data.length === 0) missing.push(target);
    });
    productBomItems.value = allItems;
    bomMissingProducts.value = missing;
  } catch (error) {
    if (generation !== bomLoadGeneration
      || props.factoryId !== factoryId
      || props.productTypeId !== ownerId) return;
    console.error('[ProductProcessWorkflow] BOM items loading failed (raw material picker falls back to a single unsorted group)', error);
  }
}

function propsMatchIdentity(identity: WorkflowIdentity): boolean {
  return props.factoryId === identity.factoryId
    && props.productTypeId === identity.productTypeId;
}

function activationMatchesIdentity(
  candidate: ProductProcessWorkflowActivation,
  identity: WorkflowIdentity,
): boolean {
  return candidate.factoryId === identity.factoryId
    && candidate.productTypeId === identity.productTypeId;
}

function currentLoadedIdentity(): WorkflowIdentity | null {
  const identity = loadedDefinitionIdentity.value;
  if (!identity
    || identity.factoryId !== props.factoryId
    || identity.productTypeId !== props.productTypeId) {
    return null;
  }
  return { ...identity };
}

function isLoadedIdentityCurrent(identity: WorkflowIdentity): boolean {
  return loadedDefinitionIdentity.value?.factoryId === identity.factoryId
    && loadedDefinitionIdentity.value?.productTypeId === identity.productTypeId
    && props.factoryId === identity.factoryId
    && props.productTypeId === identity.productTypeId;
}

function hydrate(nextDefinition: ProductProcessWorkflowDefinition): void {
  definition.value = toPlainWorkflowValue(nextDefinition);
  flowNodes.value = nextDefinition.nodes.map((node) => ({
    id: node.id,
    type: node.kind === 'PROCESS' ? 'process' : 'material',
    position: { ...node.position },
    data: { ...toPlainWorkflowValue(node.data), kind: node.kind },
  }));
  flowEdges.value = nextDefinition.edges.map((edge) => ({
    ...edge,
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#1b65a8', strokeWidth: 2 },
  }));
  refreshPortMaterialMetadata();
}

function currentDefinition(): ProductProcessWorkflowDefinition {
  const base = definition.value || {
    schemaVersion: 1 as const,
    status: 'DRAFT' as const,
    version: 1,
    nodes: [],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
  const viewport = getViewport();
  return {
    ...toPlainWorkflowValue(base),
    status: base.status,
    nodes: flowNodes.value.map(serializeFlowNode),
    edges: flowEdges.value.map(serializeFlowEdge),
    viewport: { x: viewport.x, y: viewport.y, zoom: viewport.zoom },
  };
}

function serializeFlowNode(node: Node): ProductProcessWorkflowNode {
  const clonedData = toPlainWorkflowValue(node.data || {}) as Record<string, unknown>;
  const kind = clonedData.kind as ProductProcessNodeKind;
  delete clonedData.kind;
  return {
    id: node.id,
    kind,
    position: snapPosition(node.position),
    data: clonedData as MaterialNodeData | ProcessNodeData,
  };
}

function serializeFlowEdge(edge: Edge): ProductProcessWorkflowEdge {
  return {
    id: edge.id,
    source: edge.source,
    sourceHandle: edge.sourceHandle || 'output',
    target: edge.target,
    targetHandle: edge.targetHandle || 'input',
  };
}

function remember(snapshot = currentDefinition()): void {
  history.value.push(toPlainWorkflowValue(snapshot));
  if (history.value.length > 50) history.value.shift();
  future.value = [];
}

function mutate(action: () => void): void {
  if (!canEdit.value) return;
  remember();
  action();
  refreshPortMaterialMetadata();
  editSeq += 1;
  dirty.value = true;
  scheduleAutoSave();   // #11: 每次改动都重排防抖 (即使 dirty 已是 true, watch transition 不会触发)
}

function undo(): void {
  if (!canEdit.value) return;
  const previous = history.value.pop();
  if (!previous) return;
  future.value.push(currentDefinition());
  hydrate(previous);
  dirty.value = true;
}

function redo(): void {
  if (!canEdit.value) return;
  const next = future.value.pop();
  if (!next) return;
  history.value.push(currentDefinition());
  hydrate(next);
  dirty.value = true;
}

function onNodeClick({ node }: { node: Node }): void {
  selectedNodeId.value = node.id;
}

function onNodeDragStart(): void {
  if (!canEdit.value) return;
  dragStartSnapshot.value = currentDefinition();
}

function onNodeDragStop({ node }: { node: Node }): void {
  if (!canEdit.value) return;
  if (dragStartSnapshot.value) remember(dragStartSnapshot.value);
  dragStartSnapshot.value = null;
  const target = flowNodes.value.find((candidate) => candidate.id === node.id);
  if (target) target.position = snapPosition(node.position);
  editSeq += 1;
  dirty.value = true;
  scheduleAutoSave();
}

function onViewportChangeEnd(viewport: ViewportTransform): void {
  if (!definition.value || !canEdit.value) return;
  definition.value.viewport = { x: viewport.x, y: viewport.y, zoom: viewport.zoom };
  editSeq += 1;
  dirty.value = true;
  scheduleAutoSave();
}

// ── 手动拖拽连线 (#8) ────────────────────────────────────────────────
// 拖线不是"加一条边"就完事——必须和「+来源/+产出」按钮产出一致的
// 端口↔物料模型 (process.ports 带 direction + materialNodeId, 边 anchor 到
// port.id), 否则序列化/hydrate/报工会错。物料→工序 = 新增 INPUT 端口 (合流);
// 工序→物料 = 新增 OUTPUT 端口 (产出)。均绑定"已存在"的物料 Cell, 不新建物料。

function nodeKind(node: Node | undefined): string {
  return String((node?.data as { kind?: string } | undefined)?.kind || '');
}

/** 校验一条待建连接是否合法 (Vue Flow 拖拽时实时调用, 非法则不允许落下)。
 *  类型规则委托给纯函数 evaluateWorkflowConnection (workflowModel, 已单测)。 */
function isValidConnection(connection: Connection): boolean {
  const source = flowNodes.value.find((n) => n.id === connection.source);
  const target = flowNodes.value.find((n) => n.id === connection.target);
  if (!source || !target) return false;
  return evaluateWorkflowConnection(nodeKind(source), nodeKind(target), source.id === target.id).valid;
}

function attachInputBinding(process: Node, material: Node): boolean {
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  if (data.ports.some((p) => p.direction === 'INPUT' && p.materialNodeId === material.id)) return false;
  const inputCount = data.ports.filter((p) => p.direction === 'INPUT').length;
  const portId = `input:${nextGraphIdSeed()}`;
  const unit = String((material.data as { baseUnit?: string })?.baseUnit || data.inputUnit);
  data.ports = [...data.ports, { id: portId, direction: 'INPUT', materialNodeId: material.id, unit, ordinal: inputCount }];
  flowEdges.value.push(flowEdge(material.id, 'output', process.id, portId));
  return true;
}

function attachOutputBinding(process: Node, material: Node): boolean {
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  if (data.ports.some((p) => p.direction === 'OUTPUT' && p.materialNodeId === material.id)) return false;
  const outputCount = data.ports.filter((p) => p.direction === 'OUTPUT').length;
  const portId = `output:${nextGraphIdSeed()}`;
  const materialKind = nodeKind(material) as Exclude<ProductProcessNodeKind, 'PROCESS'>;
  const unit = String((material.data as { baseUnit?: string })?.baseUnit || data.outputUnit);
  data.ports = [
    ...data.ports,
    { id: portId, direction: 'OUTPUT', materialNodeId: material.id, materialKind, unit, ordinal: outputCount },
  ];
  flowEdges.value.push(flowEdge(process.id, portId, material.id, 'input'));
  return true;
}

function onConnect(connection: Connection): void {
  if (!canEdit.value || !isValidConnection(connection)) return;
  const source = flowNodes.value.find((n) => n.id === connection.source);
  const target = flowNodes.value.find((n) => n.id === connection.target);
  if (!source || !target) return;
  const materialToProcess = nodeKind(source) !== 'PROCESS';
  const process = materialToProcess ? target : source;
  const material = materialToProcess ? source : target;
  // 先判重, 避免空 history 记录
  const dataPorts = (process.data as ProcessNodeData).ports;
  const dir = materialToProcess ? 'INPUT' : 'OUTPUT';
  if (dataPorts.some((p) => p.direction === dir && p.materialNodeId === material.id)) {
    ElMessage.info(materialToProcess ? '该物料已是本工序的投入' : '该物料已是本工序的产出');
    return;
  }
  let boundPortId = '';
  mutate(() => {
    if (materialToProcess) attachInputBinding(process, material);
    else attachOutputBinding(process, material);
    const ports = (process.data as ProcessNodeData).ports;
    boundPortId = ports[ports.length - 1]?.id || '';
  });
  connectionMadeThisDrag = true;
  pulseHandle(process.id, boundPortId);
}

let connectingFromNodeId = '';
let connectionMadeThisDrag = false;

function onConnectStart(params: { nodeId?: string | null } | undefined): void {
  const node = flowNodes.value.find((n) => n.id === params?.nodeId);
  connectingFromKind.value = nodeKind(node) === 'PROCESS' ? 'PROCESS' : node ? 'MATERIAL' : '';
  connectingFromNodeId = params?.nodeId || '';
  connectionMadeThisDrag = false;
}

// 8d: 从物料 Cell 拖到「空白画布」松手 = 想接着往下做 → 引导"增加后续工序"
// (复用现有 dialog, 用户仍要选工序, 不盲目创建)。保守触发:
//  - 只在真的落在空白 pane (不是落在某个 cell 上, 哪怕非法) 才触发;
//  - 只处理 物料→空白 这个"续链"手势; 工序→空白 不自动建 (风险高, 有 +产出按钮兜底)。
function onConnectEnd(event?: MouseEvent | TouchEvent): void {
  const fromKind = connectingFromKind.value;
  const fromId = connectingFromNodeId;
  const made = connectionMadeThisDrag;
  connectingFromKind.value = '';
  connectingFromNodeId = '';
  if (made || !canEdit.value || !fromId || fromKind !== 'MATERIAL') return;
  const target = event?.target as HTMLElement | null;
  if (target?.closest?.('.vue-flow__node')) return; // 落在 cell 上(非法目标) 不触发续链
  openAddProcess(fromId);
}

// ── 连错可删 ────────────────────────────────────────────────
function onEdgeClick({ edge }: { edge: Edge }): void {
  selectedEdgeId.value = edge.id;
}

/** 删除一条边, 并同步解绑对应的工序端口 (保持模型一致, 反向于 attach) */
function removeEdgeById(edgeId: string): void {
  if (!canEdit.value) return;
  const edge = flowEdges.value.find((e) => e.id === edgeId);
  if (!edge) return;
  mutate(() => {
    // 找到该边连的工序端 + 端口 id (INPUT: 边的 targetHandle=portId; OUTPUT: sourceHandle=portId)
    const processNode = flowNodes.value.find((n) => nodeKind(n) === 'PROCESS' && (n.id === edge.source || n.id === edge.target));
    if (processNode) {
      const isOutput = processNode.id === edge.source;
      const portId = isOutput ? edge.sourceHandle : edge.targetHandle;
      const data = processNode.data as ProcessNodeData;
      data.ports = data.ports.filter((p) => p.id !== portId);
    }
    flowEdges.value = flowEdges.value.filter((e) => e.id !== edgeId);
  });
  selectedEdgeId.value = '';
}

// #9 删除一个 Cell (节点): 连带删掉它的所有连线, 并解绑引用它的工序端口 (模型一致)。
function removeNode(nodeId: string): void {
  if (!canEdit.value) return;
  const node = flowNodes.value.find((n) => n.id === nodeId);
  if (!node) return;
  if (rawOwnerMode.value && nodeKind(node) === 'RAW_MATERIAL'
    && String(node.data?.skuId || '') === productTypeId.value) {
    ElMessage.warning('原料模式必须保留当前入口原料；如需更换，请在顶部选择其它原料');
    return;
  }
  const data = node.data as { name?: string; processName?: string } | undefined;
  const label = data?.name || data?.processName || '该 Cell';
  const touching = flowEdges.value.filter((e) => e.source === nodeId || e.target === nodeId).length;
  const doRemove = (): void => {
    mutate(() => {
      if (nodeKind(node) !== 'PROCESS') {
        // 物料 Cell: 删掉所有以它为 materialNodeId 的工序端口
        flowNodes.value.forEach((n) => {
          if (nodeKind(n) !== 'PROCESS') return;
          const d = n.data as ProcessNodeData;
          d.ports = d.ports.filter((p) => p.materialNodeId !== nodeId);
        });
      }
      flowNodes.value = flowNodes.value.filter((n) => n.id !== nodeId);
      flowEdges.value = flowEdges.value.filter((e) => e.source !== nodeId && e.target !== nodeId);
    });
    if (selectedNodeId.value === nodeId) selectedNodeId.value = '';
  };
  // 有连线才二次确认 (防呆: 别误删一整条链路); 孤立 Cell 直接删。
  if (touching > 0) {
    ElMessageBox.confirm(`删除「${label}」及其 ${touching} 条连线？删除后可用「撤销」恢复。`, '删除 Cell', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    }).then(doRemove).catch(() => { /* 取消 */ });
  } else {
    doRemove();
  }
}

function onEditorKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Delete' && event.key !== 'Backspace') return;
  const tag = (event.target as HTMLElement | null)?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') return; // 输入框里删字不误删
  if (selectedEdgeId.value) {
    event.preventDefault();
    removeEdgeById(selectedEdgeId.value);
  } else if (selectedNodeId.value) {
    event.preventDefault();
    removeNode(selectedNodeId.value);
  }
}

/** 连接成功后对新端口 handle 做一次 scale 脉冲 (咬合反馈), 尊重 reduced-motion */
function pulseHandle(processId: string, portId: string): void {
  if (!portId || prefersReducedMotion() || !canvasRef.value) return;
  nextTick(() => {
    const el = canvasRef.value?.querySelector(`[data-nodeid="${processId}"] [data-handleid="${portId}"]`)
      || canvasRef.value?.querySelector(`.vue-flow__handle[data-handleid="${portId}"]`);
    if (!el) return;
    gsapCtx?.add(() => {
      gsap.fromTo(el, { scale: 1 }, { scale: 1.35, duration: 0.15, yoyo: true, repeat: 1, ease: 'power1.out', transformOrigin: 'center' });
    });
  });
}

function addStandaloneRaw(): void {
  if (rawOwnerMode.value && hasRawRoot.value) {
    ElMessage.warning('原料模式只能有一个入口原料；请直接修改现有原料 Cell');
    return;
  }
  const owner = rawOwnerMode.value
    ? rawMaterialOptions.value.find((item) => item.id === props.productTypeId)
    : undefined;
  mutate(() => {
    const count = flowNodes.value.filter((node) => node.data?.kind === 'RAW_MATERIAL').length;
    flowNodes.value.push({
      id: `material:raw:${nextGraphIdSeed()}`,
      type: 'material',
      position: { x: 32, y: 32 + count * 160 },
      data: {
        kind: 'RAW_MATERIAL',
        name: owner?.name || `入口原料 ${count + 1}`,
        skuId: owner?.id || '',
        skuCode: owner?.code || '待绑定原料 SKU',
        bound: Boolean(owner),
        baseUnit: workflowReportingUnit('RAW_MATERIAL', owner?.unit || 'kg'),
      },
    });
  });
}

function openAddProcess(materialNodeId: string): void {
  if (!canEdit.value) return;
  processSourceMaterialId.value = materialNodeId;
  selectedWorkProcessId.value = '';
  processCreateMode.value = 'existing';
  newProcessForm.value = { name: '', outputKind: 'SEMI_FINISHED' };
  processDialogVisible.value = true;
}

function confirmAddProcess(): void {
  const source = flowNodes.value.find((node) => node.id === processSourceMaterialId.value);
  const workProcess = workProcessOptions.value.find((item) => item.id === selectedWorkProcessId.value);
  if (!source || !workProcess) return;
  mutate(() => {
    const branch = createProcessBranch({
      source: serializeFlowNode(source),
      workProcess,
      productTypeId: props.productTypeId,
      productName: props.productName || props.productTypeId,
      timestamp: nextGraphIdSeed(),
    });
    flowNodes.value.push(
      {
        id: branch.processNode.id,
        type: 'process',
        position: branch.processNode.position,
        data: {
          ...toPlainWorkflowValue(branch.processNode.data),
          kind: branch.processNode.kind,
        },
      },
      {
        id: branch.outputNode.id,
        type: 'material',
        position: branch.outputNode.position,
        data: {
          ...toPlainWorkflowValue(branch.outputNode.data),
          kind: branch.outputNode.kind,
        },
      },
    );
    flowEdges.value.push(
      ...branch.edges.map((edge) => ({
        ...edge,
        markerEnd: MarkerType.ArrowClosed,
        style: { stroke: '#1b65a8', strokeWidth: 2 },
      })),
    );
  });
  processDialogVisible.value = false;
}

function addInputToProcess(processId: string): void {
  if (isRawOwnerFirstProcess(processId)) {
    ElMessage.warning('原料模式的首道工序固定由唯一入口原料供料，不能再添加来源 Cell');
    return;
  }
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const inputCount = data.ports.filter((port) => port.direction === 'INPUT').length;
    const timestamp = nextGraphIdSeed();
    const materialId = `material:input:${timestamp}`;
    const portId = `input:${timestamp}`;
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x - 240, y: process.position.y + inputCount * 160 }),
      data: {
        kind: 'RAW_MATERIAL', name: `追加投入 ${inputCount + 1}`, skuId: '',
        skuCode: '待绑定原料 SKU', bound: false, baseUnit: 'kg',
      },
    });
    data.ports = [
      ...data.ports,
      { id: portId, direction: 'INPUT', materialNodeId: materialId, unit: 'kg', ordinal: inputCount },
    ];
    flowEdges.value.push(flowEdge(materialId, 'output', processId, portId));
  });
}

function isRawOwnerFirstProcess(processId: string): boolean {
  if (!rawOwnerMode.value) return false;
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process || process.data?.kind !== 'PROCESS') return false;
  const rawRootIds = new Set(rawRootNodes.value.map((node) => node.id));
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  return data.ports.some((port) => port.direction === 'INPUT'
    && !!port.materialNodeId
    && rawRootIds.has(port.materialNodeId));
}

function addOutputToProcess(processId: string): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const outputCount = data.ports.filter((port) => port.direction === 'OUTPUT').length;
    const timestamp = nextGraphIdSeed();
    const materialId = `material:output:${timestamp}`;
    const portId = `output:${timestamp}`;
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x + 480, y: process.position.y + outputCount * 160 }),
      data: {
        kind: 'SEMI_FINISHED', name: `产出半成品 ${outputCount + 1}`, skuId: '',
        skuCode: '待选择或现场创建 SKU', bound: false, baseUnit: 'kg',
      },
    });
    data.ports = [
      ...data.ports,
      {
        id: portId, direction: 'OUTPUT', materialNodeId: materialId, materialKind: 'SEMI_FINISHED',
        unit: 'kg', ordinal: outputCount,
      },
    ];
    flowEdges.value.push(flowEdge(processId, portId, materialId, 'input'));
  });
}

function updateProcessData(processId: string, patch: Partial<ProcessNodeData>): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  mutate(() => {
    process.data = { ...process.data, ...toPlainWorkflowValue(patch), kind: 'PROCESS' };
  });
}

function selectRawSku(materialNodeId: string, skuId: string): void {
  const material = flowNodes.value.find((node) => node.id === materialNodeId);
  const option = rawMaterialOptions.value.find((item) => item.id === skuId);
  if (!material || !option) return;
  if (rawOwnerMode.value && rawRootNodes.value.some((node) => node.id === materialNodeId)
    && skuId !== props.productTypeId) {
    emit('ownerChange', skuId);
    return;
  }
  const nextUnit = workflowReportingUnit(
    'RAW_MATERIAL',
    option.unit || String(material.data?.baseUnit || 'kg'),
  );
  mutate(() => {
    material.data = {
      ...material.data,
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      baseUnit: nextUnit,
      bound: true,
    };
    flowNodes.value.filter((node) => node.data?.kind === 'PROCESS').forEach((node) => {
      const data = node.data as ProcessNodeData & { kind: 'PROCESS' };
      data.ports.forEach((port) => {
        if (port.materialNodeId === materialNodeId) port.unit = nextUnit;
      });
      const primaryInput = data.ports.filter((port) => port.direction === 'INPUT')
        .sort((left, right) => left.ordinal - right.ordinal)[0];
      if (primaryInput) data.inputUnit = primaryInput.unit;
    });
  });
}

function findOutputPortOwner(materialNodeId: string): SkuBindingTarget | null {
  for (const node of flowNodes.value) {
    if (node.data?.kind !== 'PROCESS') continue;
    const data = node.data as ProcessNodeData & { kind: 'PROCESS' };
    const port = data.ports.find(
      (candidate) => candidate.direction === 'OUTPUT' && candidate.materialNodeId === materialNodeId,
    );
    if (port) return { processId: node.id, portId: port.id };
  }
  return null;
}

function selectMaterialSku(materialNodeId: string, skuId: string): void {
  if (!canEdit.value) return;
  const owner = findOutputPortOwner(materialNodeId);
  if (!owner) {
    ElMessage.warning('未找到该产出 Cell 对应的工序，无法绑定 SKU');
    return;
  }
  selectOutputSku(owner.processId, owner.portId, skuId);
}

function selectOutputSku(processId: string, portId: string, skuId: string): void {
  if (!canEdit.value) return;
  if (skuId === '__CREATE__') {
    const process = flowNodes.value.find((node) => node.id === processId);
    const data = process?.data as ProcessNodeData | undefined;
    const port = data?.ports.find((candidate) => candidate.id === portId);
    skuBindingTarget.value = { processId, portId };
    skuForm.value = {
      name: port?.materialName || `${data?.processName || '工序'}后半成品`,
      unit: port?.unit || 'kg',
    };
    skuDialogVisible.value = true;
    return;
  }
  const option = skuOptions.value.find((item) => item.id === skuId);
  if (option) bindOutputSku(processId, portId, option);
}

function bindOutputSku(processId: string, portId: string, option: SkuOption): boolean {
  const kind = classifyOutputSkuCategory(option.productCategory);
  if (!kind) {
    ElMessage.error('所选 SKU 分类不能作为工序产出');
    return false;
  }
  if (!rawOwnerMode.value && kind === 'FINISHED_GOOD') {
    const existingFinished = flowNodes.value.find((node) => node.data?.kind === 'FINISHED_GOOD'
      && node.data?.skuId
      && node.id !== dataMaterialNodeId(processId, portId));
    if (existingFinished) {
      ElMessage.warning('成品模式只能有一个最终成品出口；如需多成品分支，请切换到原料模式');
      return false;
    }
  }
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return false;
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  const port = data.ports.find((candidate) => candidate.id === portId);
  const material = flowNodes.value.find((node) => node.id === port?.materialNodeId);
  if (!port || !material) return false;
  const primaryOutputPort = data.ports
    .filter((candidate) => candidate.direction === 'OUTPUT')
    .sort((left, right) => left.ordinal - right.ordinal)[0];
  const nextUnit = workflowReportingUnit(kind, option.unit || port.unit);
  mutate(() => {
    Object.assign(port, {
      skuId: option.id,
      materialName: option.name,
      materialKind: kind,
      unit: nextUnit,
    });
    delete port.conversionRefId;
    delete port.conversionVersion;
    if (primaryOutputPort?.id === port.id) {
      data.outputUnit = nextUnit;
    }
    material.data = {
      ...material.data,
      kind,
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      specification: option.specification,
      baseUnit: nextUnit,
      bound: true,
    };
  });
  return true;
}

function dataMaterialNodeId(processId: string, portId: string): string | undefined {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process || process.data?.kind !== 'PROCESS') return undefined;
  return (process.data as ProcessNodeData).ports.find((port) => port.id === portId)?.materialNodeId;
}

function openQuickEditSku(materialNodeId: string): void {
  const material = flowNodes.value.find((node) => node.id === materialNodeId);
  if (!material || !material.data?.skuId) return;
  quickEditNodeId.value = materialNodeId;
  quickEditSkuId.value = String(material.data.skuId);
  quickEditForm.value = {
    name: String(material.data.name || ''),
    unit: String(material.data.baseUnit || 'kg'),
  };
  quickEditVisible.value = true;
}

async function saveQuickEditSku(): Promise<void> {
  const name = quickEditForm.value.name.trim();
  const unit = quickEditForm.value.unit.trim();
  if (!name || !unit || !props.factoryId || !quickEditSkuId.value) {
    ElMessage.warning('请填写 SKU 名称和基本单位');
    return;
  }
  quickEditSaving.value = true;
  try {
    const response = await put<SkuOption>(
      `/${props.factoryId}/product-types/${quickEditSkuId.value}`,
      { name, unit },
    );
    if (!response.success) throw new Error(response.message || 'SKU 修改失败');
    const option = skuOptions.value.find((item) => item.id === quickEditSkuId.value);
    if (option) Object.assign(option, { name, unit });
    const material = flowNodes.value.find((node) => node.id === quickEditNodeId.value);
    if (material) {
      mutate(() => {
        material.data = { ...material.data, name, baseUnit: unit };
        flowNodes.value.filter((node) => node.data?.kind === 'PROCESS').forEach((node) => {
          const data = node.data as ProcessNodeData;
          data.ports.forEach((port) => {
            if (port.materialNodeId === material.id) port.unit = unit;
          });
        });
      });
    }
    quickEditVisible.value = false;
    ElMessage.success('SKU 主数据已更新；当前 Workflow 单位已同步，请核对后重新发布');
  } catch (error) {
    console.error('[ProductProcessWorkflow] quick edit sku failed', error);
    ElMessage.error(error instanceof Error ? error.message : 'SKU 修改失败');
  } finally {
    quickEditSaving.value = false;
  }
}

async function confirmCreateSku(): Promise<void> {
  const identity = currentLoadedIdentity();
  const bindingTarget = skuBindingTarget.value ? { ...skuBindingTarget.value } : null;
  if (!identity || !bindingTarget || !canEdit.value) return;
  const name = skuForm.value.name.trim();
  const unit = skuForm.value.unit.trim();
  if (!name) {
    ElMessage.warning('请填写半成品名称');
    return;
  }
  if (!unit) {
    ElMessage.warning('请选择或输入基本单位');
    return;
  }
  const duplicate = skuOptions.value.find((item) => item.name.trim() === name);
  if (duplicate) {
    ElMessage.info(`发现同名 SKU，已复用 ${duplicate.code || duplicate.id}`);
    if (bindOutputSku(bindingTarget.processId, bindingTarget.portId, duplicate)) {
      skuDialogVisible.value = false;
    }
    return;
  }
  const generation = ++createSkuGeneration;
  creatingSku.value = true;
  try {
    const response = await post<SkuOption>(`/${identity.factoryId}/product-types`, {
      name,
      unit,
      specification: null,
      productCategory: 'SEMI_FINISHED',
      isActive: true,
      notes: '在产品工序 Workflow 中现场创建',
    });
    if (!isCreateSkuOperationCurrent(generation, identity, bindingTarget)) return;
    if (!response.success || !response.data) {
      ElMessage.error(response.message || '半成品 SKU 创建失败');
      return;
    }
    skuOptions.value.unshift(response.data);
    bindOutputSku(bindingTarget.processId, bindingTarget.portId, response.data);
    skuDialogVisible.value = false;
    ElMessage.success('半成品 SKU 已创建并绑定');
  } catch (error) {
    if (!isCreateSkuOperationCurrent(generation, identity, bindingTarget)) return;
    console.error('[ProductProcessWorkflow] create sku failed', error);
    ElMessage.error('半成品 SKU 创建失败');
  } finally {
    if (generation === createSkuGeneration) {
      creatingSku.value = false;
    }
  }
}

function isCreateSkuOperationCurrent(
  generation: number,
  identity: WorkflowIdentity,
  bindingTarget: SkuBindingTarget,
): boolean {
  return generation === createSkuGeneration
    && isLoadedIdentityCurrent(identity)
    && skuBindingTarget.value?.processId === bindingTarget.processId
    && skuBindingTarget.value?.portId === bindingTarget.portId;
}

function refreshPortMaterialMetadata(): void {
  const materialById = new Map(flowNodes.value
    .filter((node) => node.data?.kind !== 'PROCESS')
    .map((node) => [node.id, node]));
  flowNodes.value.forEach((node) => {
    if (node.data?.kind !== 'PROCESS') return;
    const data = node.data as ProcessNodeData & { kind: 'PROCESS' };
    data.ports = data.ports.map((port) => {
      const material = port.materialNodeId ? materialById.get(port.materialNodeId) : undefined;
      const materialUnit = String(material?.data?.baseUnit || '').trim();
      return {
        ...port,
        materialName: String(material?.data?.name || port.materialName || ''),
        skuId: String(material?.data?.skuId || port.skuId || ''),
        materialKind: (material?.data?.kind || port.materialKind) as ProcessPort['materialKind'],
        unit: materialUnit || port.unit,
      };
    });
    const reconciled = reconcileProcessPortQuantities(data, unitCatalog.value);
    // Vue Flow can cache nested data references. Replacing data makes all connected Cell chips refresh now.
    node.data = {
      ...reconciled,
      kind: 'PROCESS',
      ports: [...reconciled.ports],
      conversionRule: { ...reconciled.conversionRule },
    };
  });
  flowNodes.value = [...flowNodes.value];
}

function flowEdge(source: string, sourceHandle: string, target: string, targetHandle: string): Edge {
  return {
    id: `edge:${source}:${sourceHandle}:${target}:${targetHandle}`,
    source,
    sourceHandle,
    target,
    targetHandle,
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#1b65a8', strokeWidth: 2 },
  };
}

function nextGraphIdSeed(): number {
  lastGraphIdSeed = Math.max(Date.now(), lastGraphIdSeed + 1);
  return lastGraphIdSeed;
}

async function handleAutoLayout(): Promise<void> {
  if (!canEdit.value) return;
  remember();
  const laidOut = autoLayoutWorkflow(currentDefinition());
  hydrate(laidOut);
  dirty.value = true;
  await nextTick();
  await fitCanvas();
}

async function fitCanvas(): Promise<void> {
  await nextTick();
  await fitView({ padding: 0.16, duration: 300, maxZoom: 1.1 });
}

async function saveDraft(options: { silent?: boolean; preserveHistory?: boolean } = {}): Promise<boolean> {
  const identity = currentLoadedIdentity();
  if (!identity || !canEdit.value) return false;
  const nextDefinition = await reconcileForPersistence(identity, !options.silent);
  if (!nextDefinition) return false;
  const errors = validateWorkflow(nextDefinition, 'draft');
  if (errors.length > 0) {
    // 自动保存(silent)时不弹错——用户可能正编到一半暂时非法, 等下次改动再存
    if (!options.silent) ElMessage.error(errors[0].message);
    return false;
  }
  const generation = ++saveGeneration;
  const seqAtRequest = editSeq;   // 记录发请求时的本地改动序号, 用于检测 PUT 往返期间是否又改了
  saving.value = true;
  try {
    const response = await saveProductProcessWorkflowDraft(
      identity.factoryId,
      identity.productTypeId,
      nextDefinition,
    );
    if (generation !== saveGeneration || !isLoadedIdentityCurrent(identity)) return false;
    if (!response.success || !response.data) {
      ElMessage.error(response.message || 'Workflow 草稿保存失败');
      return false;
    }
    if (!definitionMatchesIdentity(response.data, identity)) return false;
    if (editSeq !== seqAtRequest) {
      // #11 fix: PUT 往返期间用户又改了 → 绝不 hydrate 覆盖 (会丢新编辑)。只把服务端 envelope
      // (lockVersion/id/version) 更新到 definition 供下次保存, 本地节点保留, dirty 保持 true, 重排补存。
      definition.value = toPlainWorkflowValue(response.data);
      if (!options.silent) ElMessage.success('Workflow 草稿已独立保存');
      scheduleAutoSave();
      return true;
    }
    hydrate(response.data);
    dirty.value = false;
    // 自动保存保留 undo/redo 历史 (否则每 2-3s 自动存就把撤销栈清空了)
    if (!options.preserveHistory) {
      history.value = [];
      future.value = [];
    }
    if (!options.silent) ElMessage.success('Workflow 草稿已独立保存');
    return true;
  } catch (error) {
    if (generation !== saveGeneration || !isLoadedIdentityCurrent(identity)) return false;
    if (isWorkflowConflict(error)) {
      await recoverWorkflowConflict(identity);
      return false;
    }
    console.error('[ProductProcessWorkflow] save failed', error);
    return false;
  } finally {
    if (generation === saveGeneration) {
      saving.value = false;
    }
  }
}

async function publishWorkflow(): Promise<void> {
  let identity = currentLoadedIdentity();
  if (!identity || !canEdit.value) return;
  if (!(await reconcileForPersistence(identity, true))) return;
  if (dirty.value && !(await saveDraft())) return;
  identity = currentLoadedIdentity();
  if (!identity || !canEdit.value) return;
  if (!definition.value?.lockVersion && definition.value?.lockVersion !== 0) {
    ElMessage.warning('请先保存草稿');
    return;
  }
  const errors = validateWorkflow(currentDefinition(), 'publish');
  if (errors.length > 0) {
    ElMessageBox.alert(errors.slice(0, 8).map((error) => `• ${error.message}`).join('\n'), '发布前检查未通过', {
      type: 'warning',
    });
    return;
  }
  try {
    await ElMessageBox.confirm(
      '发布后会生成可审计的 Workflow 图版本。当前阶段不会自动改写生产任务或报工链，确认发布？',
      '发布 Workflow',
      { type: 'warning', confirmButtonText: '确认发布' },
    );
  } catch {
    return;
  }
  if (!isLoadedIdentityCurrent(identity) || !canEdit.value) return;
  const generation = ++publishGeneration;
  publishing.value = true;
  try {
    const response = await publishProductProcessWorkflow(
      identity.factoryId,
      identity.productTypeId,
      definition.value.lockVersion,
    );
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!response.success || !response.data) {
      ElMessage.error(response.message || 'Workflow 发布失败');
      return;
    }
    if (!definitionMatchesIdentity(response.data, identity)) return;
    hydrate(response.data);
    unitReviewPending.value = false;
    dirty.value = false;
    // #12a: 发布即启用当前版本 (一步完成), 不再需要单独的「启用版本」按钮
    const publishedId = response.data.id;
    let activated = false;
    if (publishedId) {
      try {
        const actResp = await activateProductProcessWorkflow(identity.factoryId, publishedId);
        if (actResp.success && actResp.data && activationMatchesIdentity(actResp.data, identity)) {
          activation.value = actResp.data;
          activated = true;
        }
      } catch (actErr) {
        console.error('[ProductProcessWorkflow] publish→activate failed', actErr);
      }
    }
    // #12a fix: 只有真启用成功才说"已启用"; 启用失败如实提示 (别撒谎让用户以为已生效)
    if (activated) {
      ElMessage.success('Workflow 版本已发布并启用');
    } else {
      ElMessage.warning('Workflow 版本已发布，但自动启用失败——请在版本记录里手动启用');
    }
  } catch (error) {
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (isWorkflowConflict(error)) {
      await recoverWorkflowConflict(identity);
      return;
    }
    console.error('[ProductProcessWorkflow] publish failed', error);
  } finally {
    if (generation === publishGeneration) {
      publishing.value = false;
    }
  }
}

async function activateWorkflow(): Promise<void> {
  const identity = currentLoadedIdentity();
  const workflowId = definition.value?.id;
  if (!identity || !canEdit.value || definition.value?.status !== 'PUBLISHED' || !workflowId) return;
  try {
    await ElMessageBox.confirm(
      '只影响之后新建的生产批次；正在生产的批次不会变化。',
      '启用 Workflow 版本',
      { type: 'warning', confirmButtonText: '确认启用' },
    );
  } catch {
    return;
  }
  if (!isLoadedIdentityCurrent(identity)) return;
  const generation = ++activationMutationGeneration;
  activationChanging.value = true;
  try {
    const response = await activateProductProcessWorkflow(identity.factoryId, workflowId);
    if (generation !== activationMutationGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!response.success || !response.data
      || !activationMatchesIdentity(response.data, identity)) return;
    activation.value = response.data;
    ElMessage.success(`Workflow v${response.data.activeDefinitionVersion} 已启用`);
  } catch (error) {
    if (generation === activationMutationGeneration && isLoadedIdentityCurrent(identity)) {
      console.error('[ProductProcessWorkflow] activation failed', error);
    }
  } finally {
    if (generation === activationMutationGeneration) activationChanging.value = false;
  }
}

async function deactivateWorkflow(): Promise<void> {
  const identity = currentLoadedIdentity();
  const current = activation.value;
  if (!identity || !canEdit.value || !current?.enabled) return;
  try {
    await ElMessageBox.confirm(
      '停用后新批次恢复旧工序配置；已有批次继续当前 Workflow。',
      '停用 Workflow',
      { type: 'warning', confirmButtonText: '确认停用' },
    );
  } catch {
    return;
  }
  if (!isLoadedIdentityCurrent(identity)) return;
  const generation = ++activationMutationGeneration;
  activationChanging.value = true;
  try {
    const response = await deactivateProductProcessWorkflow(
      identity.factoryId,
      identity.productTypeId,
      current.lockVersion,
    );
    if (generation !== activationMutationGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!response.success || !response.data
      || !activationMatchesIdentity(response.data, identity)) return;
    activation.value = response.data;
    ElMessage.success('Workflow 已停用');
  } catch (error) {
    if (generation === activationMutationGeneration && isLoadedIdentityCurrent(identity)) {
      console.error('[ProductProcessWorkflow] deactivation failed', error);
    }
  } finally {
    if (generation === activationMutationGeneration) activationChanging.value = false;
  }
}

function isWorkflowConflict(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const candidate = error as {
    actionHint?: unknown;
    code?: unknown;
    errorCode?: unknown;
    status?: unknown;
    response?: {
      data?: { actionHint?: unknown; code?: unknown; errorCode?: unknown };
      status?: unknown;
    };
  };
  if (Number(candidate.status ?? candidate.response?.status) !== 409) return false;
  const responseData = candidate.response?.data;
  const errorCode = candidate.errorCode
    ?? candidate.code
    ?? responseData?.errorCode
    ?? responseData?.code;
  return errorCode === 'PRODUCT_PROCESS_WORKFLOW_CONFLICT'
    || errorCode === 'OPTIMISTIC_LOCK_CONFLICT';
}

async function recoverWorkflowConflict(
  identity: WorkflowIdentity,
): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '该 Workflow 已被其他人更新。重新加载会读取服务器最新版本；也可以先复制当前本地草稿 JSON 留存。',
      'Workflow 版本冲突',
      {
        type: 'warning',
        confirmButtonText: '重新加载最新版本',
        cancelButtonText: '复制当前草稿 JSON',
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
      },
    );
    if (!isLoadedIdentityCurrent(identity)) return;
    await reloadLatestDefinition(identity);
  } catch (action) {
    if (action !== 'cancel') return;
    if (!isLoadedIdentityCurrent(identity)) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(currentDefinition(), null, 2));
      ElMessage.success('当前 Workflow 草稿 JSON 已复制');
    } catch (error) {
      console.error('[ProductProcessWorkflow] copy conflict draft failed', error);
      ElMessage.error('复制草稿 JSON 失败，请保持当前页面并稍后重试');
    }
  }
}

async function reloadLatestDefinition(identity: WorkflowIdentity): Promise<void> {
  const generation = ++loadGeneration;
  loading.value = true;
  try {
    const response = await getProductProcessWorkflow(identity.factoryId, identity.productTypeId);
    if (!isCurrentLoad(generation, identity)) return;
    if (!response.success || !response.data) {
      throw new Error(response.message || 'Latest workflow definition is unavailable');
    }
    if (!definitionMatchesIdentity(response.data, identity)) {
      throw new Error('Latest workflow definition identity does not match the requested product');
    }
    hydrate(response.data);
    loadedDefinitionIdentity.value = identity;
    dirty.value = false;
    history.value = [];
    future.value = [];
    await nextTick();
    if (!isCurrentLoad(generation, identity)) return;
    await setViewport(response.data.viewport);
    ElMessage.success('已重新加载服务器最新 Workflow 版本');
  } catch (error) {
    if (!isCurrentLoad(generation, identity)) return;
    console.error('[ProductProcessWorkflow] conflict reload failed', error);
    ElMessage.error('最新 Workflow 版本加载失败，本地草稿仍保留');
  } finally {
    if (isCurrentLoad(generation, identity)) {
      loading.value = false;
    }
  }
}

async function applyWorkflowAIDraft(
  payload: Record<string, unknown>,
  sourceIdentity?: WorkflowIdentity,
): Promise<void> {
  const identity = currentLoadedIdentity();
  if (!identity
    || !sourceIdentity
    || !identitiesMatch(identity, sourceIdentity)
    || !canEdit.value) return;
  // 长久方案: AI 返回「语义规格」时走确定性编译器建图 (弃补丁)。旧的 patch 路径保留兜底。
  if (payload.spec && typeof payload.spec === 'object') {
    await buildWorkflowFromSpec(payload.spec as WorkflowSpec, identity);
    return;
  }
  if (!Array.isArray(payload.patches)) {
    ElMessage.warning('AI 没有返回合法的 Workflow 描述');
    return;
  }
  const proposed = applyWorkflowPatches(currentDefinition(), payload.patches);
  if (proposed.errors.length > 0) {
    ElMessage.warning(proposed.errors[0]);
    return;
  }
  if (proposed.summary.length === 0) {
    ElMessage.warning('AI 补丁均未通过安全校验，未修改本地草稿');
    return;
  }
  try {
    await ElMessageBox.confirm(
      proposed.summary.map((item) => `• ${item}`).join('\n'),
      '审核 Workflow AI 补丁',
      { type: 'warning', confirmButtonText: '应用到草稿' },
    );
  } catch {
    return;
  }
  if (!isLoadedIdentityCurrent(identity) || !canEdit.value) return;
  remember();
  hydrate(proposed.definition);
  dirty.value = true;
  await fitCanvas();
}

interface WorkflowSpecOutput { kind?: string; name?: string; unit?: string }
// #4 合流 (N→1): inputs = 本步除主链上游外**额外**投入的原料名 (混批/拼装). 每个建一个 RAW cell + INPUT 端口。
interface WorkflowSpecStep { process?: string; inputs?: string[]; outputs?: WorkflowSpecOutput[] }
interface WorkflowSpec { rawMaterials?: string[]; steps?: WorkflowSpecStep[] }

/**
 * 长久方案确定性编译器 (#7 弃补丁): 把 LLM 的「语义规格」建成合法图, 复用 createProcessBranch /
 * createWorkProcess —— LLM 不产任何 node/edge/id, 图 100% 自洽, 不会再"整批被拒"。
 * MVP: 线性链 + 每步首产出 (多产出/分流 后续迭代)。工序匹配不到就现场创建 (复用 #13 逻辑);
 * 产出 SKU 先不自动建, 留待用户绑定 (安全)。
 */
async function buildWorkflowFromSpec(spec: WorkflowSpec, identity: WorkflowIdentity): Promise<void> {
  const steps = Array.isArray(spec.steps)
    ? spec.steps.filter((s) => s && (s.process || '').trim())
    : [];
  if (!steps.length) { ElMessage.warning('AI 规格里没有可用的工序步骤'); return; }

  // 1) 预解析每步工序 (匹配已有 / 现场创建), async 先做完再建图
  const resolved: WorkProcessItem[] = [];
  for (const step of steps) {
    const name = (step.process || '').trim();
    let wp = workProcessOptions.value.find((p) => normProcessName(p.processName || '') === normProcessName(name));
    if (!wp) {
      wp = workProcessOptions.value.find((p) => {
        const pn = normProcessName(p.processName || '');
        const q = normProcessName(name);
        return !!pn && !!q && (pn.includes(q) || q.includes(pn));
      });
    }
    if (!wp) {
      const outKind = step.outputs?.[0]?.kind === 'FINISHED_GOOD' ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
      const unit = step.outputs?.[0]?.unit || '';
      try {
        const resp = await createWorkProcess(identity.factoryId, {
          processName: name, unit, outputUnit: unit,
          defaultOutputMaterialKind: outKind as WorkProcessItem['defaultOutputMaterialKind'],
          isActive: true,
        });
        if (resp.success && resp.data) {
          wp = resp.data;
          workProcessOptions.value = [resp.data, ...workProcessOptions.value];
        }
      } catch (error) {
        console.error('[buildWorkflowFromSpec] createWorkProcess failed', error);
      }
    }
    if (!wp) { ElMessage.error(`工序「${name}」无法匹配或创建，AI 建流程中止`); return; }
    resolved.push(wp);
  }

  // 2) 会替换当前画布 → 二次确认
  if (flowNodes.value.length > 0) {
    try {
      await ElMessageBox.confirm(
        `将按 AI 描述生成 ${steps.length} 道工序的流程图，替换当前画布内容（可撤销）。继续？`,
        'AI 生成工序流程', { type: 'warning', confirmButtonText: '生成并替换' },
      );
    } catch { return; }
  }
  if (!isLoadedIdentityCurrent(identity) || !canEdit.value) return;

  // 3) 确定性建图 (sync in mutate, 复用 createProcessBranch): 入口原料 → 逐步 工序+产出
  let autoBoundCount = 0;   // #5 自动绑定成功的产出 SKU 数
  let mergeInputCount = 0;  // #4 合流额外投入原料端口数
  mutate(() => {
    const nodes: Node[] = [];
    const edges: Edge[] = [];
    const rawName = (spec.rawMaterials?.[0] || '入口原料').trim();
    const rawNode: Node = {
      id: `material:raw:${nextGraphIdSeed()}`,
      type: 'material',
      position: { x: 32, y: 32 },
      data: { kind: 'RAW_MATERIAL', name: rawName, skuId: '', skuCode: '待绑定原料 SKU', bound: false, baseUnit: '' },
    };
    nodes.push(rawNode);
    let prevMaterial = serializeFlowNode(rawNode);
    steps.forEach((step, i) => {
      const branch = createProcessBranch({
        source: prevMaterial,
        workProcess: resolved[i],
        productTypeId: identity.productTypeId,
        productName: props.productName || identity.productTypeId,
        timestamp: nextGraphIdSeed(),
      });
      const outputs = Array.isArray(step.outputs) && step.outputs.length ? step.outputs : [{}];
      const isLastStep = i === steps.length - 1;
      // 首产出: 用规格覆盖 createProcessBranch 建的产出 Cell 名/kind
      const firstOut = outputs[0];
      const outData = { ...toPlainWorkflowValue(branch.outputNode.data) } as Record<string, unknown>;
      if (firstOut?.name) outData.name = firstOut.name;
      // 中链步骤的"续链产出"必须是半成品: 成品 Cell 无 output handle → 下游会断链, 且
      // createProcessBranch 会把成品 Cell 误绑成产品 SKU。只有末步产出才可为成品。
      if (!isLastStep) {
        outData.kind = 'SEMI_FINISHED';
        outData.skuId = '';
        outData.bound = false;
        outData.skuCode = '待选择或现场创建 SKU';
      } else if (firstOut?.kind === 'FINISHED_GOOD' || firstOut?.kind === 'SEMI_FINISHED') {
        outData.kind = firstOut.kind;
      }
      // #5 自动绑定产出 SKU: 按产出名在对应 kind 池里查唯一精确同名 (无/歧义则留待用户绑定, 防呆)。
      const firstKind = String(outData.kind || branch.outputNode.kind) === 'FINISHED_GOOD'
        ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
      const firstSku = matchOutputSkuByName(outData.name as string, firstKind, outputSkuOptions.value);
      if (firstSku) {
        outData.kind = firstKind;
        outData.skuId = firstSku.id;
        outData.skuCode = firstSku.code || firstSku.id;
        outData.specification = firstSku.specification;
        outData.baseUnit = firstSku.unit || outData.baseUnit;
        outData.bound = true;
        autoBoundCount += 1;
      }
      const outputNode: Node = {
        id: branch.outputNode.id,
        type: 'material',
        position: branch.outputNode.position,
        data: { ...outData, kind: String(outData.kind || branch.outputNode.kind) },
      };
      // 收集 process 端口 (首产出端口来自 createProcessBranch, 多产出再追加)
      const processData = {
        ...toPlainWorkflowValue(branch.processNode.data),
        kind: branch.processNode.kind,
      } as ProcessNodeData & { kind: 'PROCESS' };
      // #5 首产出若已自动绑定, 同步写工序 OUTPUT 端口 (端口↔物料一致, 同 bindOutputSku 语义)。
      if (firstSku) {
        const firstPort = processData.ports.find(
          (p) => p.direction === 'OUTPUT' && p.materialNodeId === branch.outputNode.id,
        );
        if (firstPort) {
          Object.assign(firstPort, {
            skuId: firstSku.id,
            materialName: firstSku.name,
            materialKind: firstKind,
            unit: firstSku.unit || firstPort.unit,
          });
        }
      }
      edges.push(...branch.edges.map((edge) => ({
        ...edge,
        markerEnd: MarkerType.ArrowClosed,
        style: { stroke: '#1b65a8', strokeWidth: 2 },
      })));
      // 多产出/分流: 其余产出各建一个物料 Cell + OUTPUT 端口 + 边 (复用 attachOutputBinding 端口结构)
      outputs.slice(1).forEach((extra, extraIdx) => {
        const kind = extra?.kind === 'FINISHED_GOOD' ? 'FINISHED_GOOD' : 'SEMI_FINISHED';
        const unit = extra?.unit || String(processData.outputUnit || '');
        const matId = `material:output:${nextGraphIdSeed()}`;
        const portId = `output:${nextGraphIdSeed()}`;
        // #5 分流产出同样尝试自动绑定 SKU (唯一精确同名; 否则留待用户绑定)。
        const extraSku = matchOutputSkuByName(extra?.name, kind, outputSkuOptions.value);
        if (extraSku) autoBoundCount += 1;
        nodes.push({
          id: matId,
          type: 'material',
          position: { x: outputNode.position.x, y: outputNode.position.y + (extraIdx + 1) * 160 },
          data: extraSku
            ? { kind, name: extraSku.name, skuId: extraSku.id, skuCode: extraSku.code || extraSku.id, specification: extraSku.specification, bound: true, baseUnit: extraSku.unit || unit }
            : { kind, name: extra?.name || `产出 ${extraIdx + 2}`, skuId: '', skuCode: '待选择或现场创建 SKU', bound: false, baseUnit: unit },
        });
        processData.ports = [
          ...processData.ports,
          {
            id: portId, direction: 'OUTPUT', materialNodeId: matId, materialKind: kind,
            unit: extraSku?.unit || unit,
            ...(extraSku ? { skuId: extraSku.id, materialName: extraSku.name } : {}),
            ordinal: processData.ports.filter((p) => p.direction === 'OUTPUT').length,
          },
        ];
        edges.push({
          ...flowEdge(branch.processNode.id, portId, matId, 'input'),
          markerEnd: MarkerType.ArrowClosed,
          style: { stroke: '#1b65a8', strokeWidth: 2 },
        });
      });
      // #4 合流 (N→1): 本步声明的额外投入原料 → 各建 RAW cell + INPUT 端口 + 边 (混批/拼装分装)。
      // 主链上游 (prevMaterial) 仍是首个 INPUT (createProcessBranch 已建), 这里只追加额外投入。
      const extraInputs = Array.isArray(step.inputs)
        ? step.inputs.map((s) => (s || '').trim()).filter(Boolean)
        : [];
      extraInputs.forEach((inName, inIdx) => {
        const rawId = `material:raw:${nextGraphIdSeed()}`;
        const inPortId = `input:${nextGraphIdSeed()}`;
        nodes.push({
          id: rawId,
          type: 'material',
          position: { x: branch.processNode.position.x - 220, y: branch.processNode.position.y + (inIdx + 1) * 140 },
          data: { kind: 'RAW_MATERIAL', name: inName, skuId: '', skuCode: '待绑定原料 SKU', bound: false, baseUnit: '' },
        });
        processData.ports = [
          ...processData.ports,
          {
            id: inPortId, direction: 'INPUT', materialNodeId: rawId, unit: '',
            ordinal: processData.ports.filter((p) => p.direction === 'INPUT').length,
          },
        ];
        edges.push({
          ...flowEdge(rawId, 'output', branch.processNode.id, inPortId),
          markerEnd: MarkerType.ArrowClosed,
          style: { stroke: '#1b65a8', strokeWidth: 2 },
        });
        mergeInputCount += 1;
      });
      nodes.push({ id: branch.processNode.id, type: 'process', position: branch.processNode.position, data: processData });
      nodes.push(outputNode);
      // 主链沿首产出继续 (多产出的其余分支是终端支流)
      prevMaterial = serializeFlowNode(outputNode);
    });
    flowNodes.value = nodes;
    flowEdges.value = edges;
  });
  await nextTick();
  await handleAutoLayout();
  const parts = [`已按 AI 描述生成 ${steps.length} 道工序流程`];
  if (mergeInputCount > 0) parts.push(`含 ${mergeInputCount} 处合流投入原料 (待绑定原料 SKU)`);
  if (autoBoundCount > 0) parts.push(`已自动绑定 ${autoBoundCount} 个产出 SKU`);
  parts.push('请检查并绑定剩余产出/原料 SKU 后保存');
  ElMessage.success(parts.join('，'));
}

function identitiesMatch(left: WorkflowIdentity, right: WorkflowIdentity): boolean {
  return left.factoryId === right.factoryId && left.productTypeId === right.productTypeId;
}

function toggleAI(): void {
  aiCollapsed.value = !aiCollapsed.value;
  localStorage.setItem(aiStorageKey.value, String(aiCollapsed.value));
  window.setTimeout(() => fitCanvas(), 220);
}
</script>

<style scoped>
.workflow-bom-alert { margin: 0 12px 12px; }
/* #8: 选中的边高亮 (提示可按 Delete 删除) + 连线中光标 */
.canvas-shell :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #f56c6c !important;
  stroke-width: 3 !important;
}
.canvas-shell.is-connecting { cursor: crosshair; }
/* #12b: 历史版本预览横幅 + 版本列表行 */
.version-preview-bar {
  position: absolute; top: 0; left: 0; right: 0; z-index: 30;
  display: flex; align-items: center; gap: 12px;
  padding: 8px 16px; background: #fdf6ec; border-bottom: 1px solid #f5dab1;
  font-size: 13px; color: #b88230;
}
.version-list { display: flex; flex-direction: column; gap: 8px; }
.version-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border: 1px solid #ebeef5; border-radius: 8px;
}
.version-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.version-num { font-weight: 700; color: #1a2332; }
.version-time { font-size: 12px; color: #909399; }
/* #9: 选中连线的浮动删除条 */
.edge-delete-bar {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #fff;
  border: 1px solid #f0d0d0;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  font-size: 13px;
  color: #606266;
}

/* 画布始终使用完整宽度；AI 是按需覆盖层，展开时不会重新挤压/缩放流程图。 */
.workflow-editor {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-height: calc(100vh - 200px);
}
.workflow-main {
  width: calc(100% - 44px);
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.workflow-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 52px; padding: 8px 12px; border: 1px solid #edf2f7; border-radius: 10px 10px 0 0; background: #fff;
}
.toolbar-status, .toolbar-actions { display: flex; align-items: center; gap: 8px; }
.toolbar-actions { flex-wrap: wrap; justify-content: flex-end; }
.dirty-status { color: #e6a23c; font-size: 12px; }
.saved-status { color: #67c23a; font-size: 12px; }
.stage-note { color: #7a8599; font-size: 11px; }
.canvas-shell {
  position: relative; flex: 1; min-height: calc(100vh - 256px); overflow: hidden;
  border: 1px solid #dce8f3; border-top: none; border-radius: 0 0 10px 10px; background: #fbfdff;
}
.workflow-canvas { width: 100%; height: 100%; min-height: calc(100vh - 256px); }
.empty-canvas-action { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; }
.empty-canvas-action :deep(.el-button) { pointer-events: auto; }
.ai-sidebar {
  position: absolute; top: 0; right: 0; bottom: 0; z-index: 40;
  width: 320px; min-width: 0; overflow: hidden; border: 1px solid #dbe7f3;
  border-radius: 10px; background: #fff; box-shadow: -8px 0 24px rgb(31 62 92 / 14%);
  transition: width 0.2s ease;
}
.workflow-editor.ai-collapsed .ai-sidebar { width: 44px; box-shadow: none; }
.ai-collapse-button {
  width: 100%; min-height: 44px; border: 0; border-bottom: 1px solid #edf2f7;
  color: #1b65a8; background: #f4f9ff; cursor: pointer; font-weight: 650;
}
.ai-content { padding: 12px; }
.ai-context { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; border-radius: 8px; background: #f7f9fc; }
.ai-context span { color: #7a8599; font-size: 11px; }
.ai-context strong { overflow: hidden; color: #344054; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.ai-content :deep(.work-process-ai-chat-panel) { min-height: 560px; margin-top: 8px; }
.workflow-editor.ai-collapsed .ai-collapse-button { writing-mode: vertical-rl; min-height: 120px; padding: 12px 0; }
:deep(.vue-flow__edge-path) { stroke-linecap: round; }
:deep(.vue-flow__node) { border: 0; background: transparent; }
</style>
