<template>
  <div class="workflow-editor" data-testid="workflow-viewport-editor">
    <div class="workflow-main">
      <div class="workflow-toolbar" aria-label="Workflow 编辑工具栏">
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
          <el-tag data-testid="workflow-system-classification" type="info">
            系统研判：{{ workflowClassificationLabel }}
          </el-tag>
          <span v-if="dirty" class="dirty-status">● 有未保存改动</span>
          <span v-else-if="definition" class="saved-status">✓ 已保存</span>
          <span class="stage-note">图定义独立保存，暂不改写现有报工运行时</span>
        </div>
        <div class="toolbar-actions">
          <el-button :disabled="!canEdit" @click="addStandaloneRaw">+ 原料 Cell</el-button>
          <el-button :disabled="!canEdit || history.length === 0" @click="undo">撤销</el-button>
          <el-button :disabled="!productTypeId || flowNodes.length === 0" @click="fitCanvas">适应画布</el-button>
          <el-button
            type="primary"
            :disabled="!canEdit || !dirty"
            :loading="saving"
            @click="() => saveDraft()"
          >保存草稿</el-button>
          <el-button
            data-testid="snapshot-workflow"
            :disabled="!canEdit || flowNodes.length === 0"
            :loading="snapshotting"
            @click="snapshotWorkflow"
          >另存为版本</el-button>
          <!-- #12a: 发布版本 = 发布并启用当前版本 (一步); 不再有单独「启用版本」按钮 -->
          <el-tooltip :content="publishDisabledReason" :disabled="!publishDisabledReason" placement="bottom">
            <span class="disabled-action-tooltip" data-testid="publish-workflow-tooltip">
              <el-button
                data-testid="publish-workflow"
                type="success"
                :plain="publishActionCompleted"
                :disabled="!!publishDisabledReason"
                :loading="publishing"
                @click="publishWorkflow"
              >{{ publishActionLabel }}</el-button>
            </span>
          </el-tooltip>
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
        title="SKU 单位契约需要复核：请创建新草稿并重新发布；已有计划不受影响。"
      />

      <el-alert
        v-if="publishBindingErrors.length > 0"
        class="workflow-validation-alert"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>
          发布前还有 {{ publishBindingErrors.length }} 个 Cell 未绑定 SKU
        </template>
        <template #default>
          <span>画布中已用红框标出；请逐个选择正确的 SKU。</span>
          <el-button link type="danger" @click="focusFirstPublishBindingError">定位第一个 →</el-button>
        </template>
      </el-alert>

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
          <el-button
            link
            type="primary"
            @click="openBomDrawer(bomMissingProducts[0]?.id)"
          >
            在右侧配置 BOM →
          </el-button>
        </template>
      </el-alert>

      <el-alert
        v-else-if="bomRevisionMismatchProducts.length > 0"
        class="workflow-bom-alert"
        data-testid="workflow-bom-revision-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          当前工艺已更新，{{ bomRevisionMismatchProducts.map((item) => item.name).join('、') }} 的生效 BOM 仍使用旧工艺
        </template>
        <template #default>
          <span>请升级 BOM、补齐新增原料并激活新版本，再发布并启用 Workflow。</span>
          <el-button
            link
            type="primary"
            @click="openBomDrawer(bomRevisionMismatchProducts[0]?.id)"
          >
            在右侧升级 BOM →
          </el-button>
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
          :class="['workflow-canvas', { 'is-batch-selecting': interactionMode === 'SELECT' }]"
          :min-zoom="0.35"
          :max-zoom="1.8"
          :pan-on-drag="interactionMode === 'PAN' ? true : [1, 2]"
          :selection-key-code="canEdit && interactionMode === 'SELECT' ? true : false"
          multi-selection-key-code="Control"
          :selection-mode="SelectionMode.Partial"
          :zoom-on-scroll="true"
          :prevent-scrolling="true"
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
          @pane-click="onPaneClick"
          @node-drag-start="onNodeDragStart"
          @node-drag-stop="onNodeDragStop"
          @selection-end="onSelectionEnd"
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
              :raw-material-segments="rawMaterialSegments"
              :excluded-raw-material-ids="usedRawMaterialIdsExcept(slotProps.id)"
              :bom-raw-material-ids="bomRawMaterialIdList"
              :semi-options="semiFinishedSkuOptions"
              :finished-options="finishedGoodSkuOptions"
              :unit-error="unitIssueForNode(slotProps.id)"
              :validation-error="publishBindingErrorForNode(slotProps.id)"
              :validation-attention="publishBindingAttentionNodeIds.has(slotProps.id)"
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
              :sku-specifications="skuSpecifications"
              :allow-add-input="true"
              @update="(patch) => updateProcessData(slotProps.id, patch)"
              @add-input="addInputToProcess(slotProps.id)"
              @add-output="addOutputToProcess(slotProps.id)"
              @select-output="(portId, skuId) => selectOutputSku(slotProps.id, portId, skuId)"
              @edit-process="openQuickEditProcess(slotProps.id)"
              @delete="removeNode(slotProps.id)"
            />
          </template>
        </VueFlow>

        <div
          :class="['canvas-floating-tools', { 'is-batch-selecting': interactionMode === 'SELECT' }]"
          data-testid="canvas-floating-tools"
        >
          <div class="canvas-tool-actions">
            <el-button-group>
            <el-button
              :type="interactionMode === 'PAN' ? 'primary' : 'default'"
              @click="interactionMode = 'PAN'"
            >拖动画布</el-button>
            <el-button
              :type="interactionMode === 'SELECT' ? 'primary' : 'default'"
              :disabled="!canEdit"
              @click="interactionMode = 'SELECT'"
            >批量选择</el-button>
            </el-button-group>
            <el-tooltip
              content="自动整理 Cell 位置"
              placement="bottom"
              :show-after="500"
              :hide-after="0"
              :enterable="false"
            >
              <el-button
                :disabled="!canEdit || flowNodes.length === 0"
                @click="handleAutoLayout"
              >自动布局</el-button>
            </el-tooltip>
          </div>
          <div v-if="interactionMode === 'SELECT'" class="batch-selection-guide" data-testid="batch-selection-guide">
            <strong>批量选择模式</strong>
            <span>空白处按住左键拖框，碰到 Cell 即选中</span>
            <span class="batch-selection-shortcut">按住 Ctrl 再框选，可追加选择</span>
            <div v-if="selectedSelectionCount > 0" class="batch-selection-result">
              <span class="batch-selection-count">
                已选 {{ selectedCellCount }} 个 Cell<span v-if="selectedEdgeCount > 0">、{{ selectedEdgeCount }} 条线</span>
              </span>
              <el-button
                size="small"
                type="danger"
                plain
                data-testid="delete-selected-cells"
                @click="removeSelectedElements"
              >删除已选内容</el-button>
            </div>
          </div>
        </div>

        <!-- #9: 选中连线时浮出可见删除入口 (不只靠 Delete 键; 防呆) -->
        <div v-if="selectedEdgeId && canEdit" class="edge-delete-bar">
          <span>已选中一条连线</span>
          <el-button size="small" type="danger" @click="removeEdgeById(selectedEdgeId)">删除连线</el-button>
          <el-button size="small" text @click="clearEdgeSelection">取消</el-button>
        </div>

        <div v-if="productTypeId && flowNodes.length === 0 && !loading" class="empty-canvas-action">
          <el-empty description="该产品还没有工序图">
            <el-button v-if="canEdit" type="primary" @click="addStandaloneRaw">添加第一个原料 Cell</el-button>
          </el-empty>
        </div>

        <section
          class="workflow-ai-dock"
          :class="{ 'is-collapsed': aiCollapsed }"
          aria-label="Workflow AI 助手"
          data-testid="workflow-ai-canvas-composer"
        >
          <div class="workflow-ai-dock__header">
            <div class="workflow-ai-dock__identity">
              <span class="workflow-ai-dock__spark" aria-hidden="true">✦</span>
              <strong>Workflow AI</strong>
              <span>{{ aiContextLabel }}</span>
            </div>
            <el-button
              text
              size="small"
              :aria-expanded="!aiCollapsed"
              aria-controls="workflow-ai-composer"
              @click="aiCollapsed = !aiCollapsed"
            >{{ aiCollapsed ? '展开' : '收起' }}</el-button>
          </div>
          <div id="workflow-ai-composer" v-show="!aiCollapsed" class="workflow-ai-dock__composer">
            <WorkProcessAIChatPanel
              v-if="factoryId"
              :key="`${factoryId}:${productTypeId}`"
              :factory-id="factoryId"
              :product-type-id="productTypeId"
              :endpoint="`/${factoryId}/config/v2/ai/chat`"
              module-code="product_process_workflow_config"
              title="Workflow AI 助手"
              :disabled="!canEdit"
              :context="selectedNodeContext"
              :context-label="aiContextLabel"
              :quick-prompts="aiQuickPrompts"
              @apply-draft="applyWorkflowAIDraft"
            />
          </div>
        </section>
      </div>
    </div>

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
          <el-form-item label="工序类别" required>
            <el-select
              v-model="newProcessForm.processCategory"
              filterable
              placeholder="从工序类别字典选择"
              style="width: 100%"
              data-testid="new-process-category"
            >
              <el-option v-for="category in workProcessCategories" :key="category" :label="category" :value="category" />
            </el-select>
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
          :disabled="!newProcessForm.name.trim() || !newProcessForm.processCategory"
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

    <el-dialog v-model="processEditVisible" title="快捷编辑工序" width="560px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        title="这里修改的是工序主数据；投入与产出单位由各 Cell 绑定的 SKU 决定，不属于工序。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="120px" data-testid="process-edit-form">
        <el-form-item label="工序名称" required>
          <el-input v-model="processEditForm.processName" maxlength="100" />
        </el-form-item>
        <el-form-item label="工序类别" required>
          <el-select
            v-model="processEditForm.processCategory"
            filterable
            placeholder="从工序类别字典选择"
            style="width: 100%"
            data-testid="edit-process-category"
          >
            <el-option v-for="category in workProcessCategories" :key="category" :label="category" :value="category" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认产出类型" required>
          <el-radio-group v-model="processEditForm.defaultOutputMaterialKind">
            <el-radio-button label="SEMI_FINISHED">半成品</el-radio-button>
            <el-radio-button label="FINISHED_GOOD">成品</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="是否需要投入">
          <el-switch v-model="processEditForm.needsInput" active-text="需要" inactive-text="不需要" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="processEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="processEditSaving" data-testid="save-process-edit" @click="saveQuickEditProcess">保存工序主数据</el-button>
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
      <BomUnifiedPanel
        v-if="bomDrawerVisible"
        :initial-product-type-id="bomDrawerProductTypeId"
      />
    </el-drawer>

    <!-- #12b: 版本记录抽屉 (只读浏览之前发布过的版本) -->
    <el-drawer v-model="versionDrawerVisible" title="版本记录" direction="rtl" size="440px">
      <div v-loading="versionLoading" class="version-list">
        <el-empty v-if="!versionList.length && !versionLoading" description="暂无版本记录" :image-size="80" />
        <div v-for="v in versionList" :key="v.definitionVersion" class="version-row" data-testid="version-row">
          <div class="version-meta">
            <span class="version-num">v{{ v.definitionVersion }}</span>
            <el-tag size="small" :type="v.status === 'PUBLISHED' ? 'success' : v.status === 'SNAPSHOT' ? 'warning' : 'info'">
              {{ v.status === 'PUBLISHED' ? '已发布' : v.status === 'SNAPSHOT' ? '独立版本' : '草稿' }}
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
  SelectionMode,
  VueFlow,
  useVueFlow,
  type Connection,
  type Edge,
  type EdgeMouseEvent,
  type Node,
  type NodeMouseEvent,
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
  getWorkProcessCategories,
  getProductWorkProcesses,
  updateWorkProcess,
  updateWorkProcessOutputKind,
  type WorkProcessItem,
  type WorkProcessOutputMaterialKind,
} from '@/api/processProduction';
import UnitSelect from '@/components/common/UnitSelect.vue';
import WorkProcessAIChatPanel from '@/views/system/components/WorkProcessAIChatPanel.vue';
import WorkflowMaterialNode from './WorkflowMaterialNode.vue';
import WorkflowProcessNode from './WorkflowProcessNode.vue';
import {
  buildRawMaterialSegmentTree,
  isRawMaterialOption,
  type MaterialSegmentNode,
  type RawMaterialPickerOption,
} from './rawMaterialCatalog';
import { classifyOutputSkuCategory, matchOutputSkuByName } from './outputSkuClassification';
import { needsPrimaryOutputKindUpdate } from './processOutputKindCompatibility';
import { usePinyinFilter } from './pinyinInitials';
import { classifyWorkflowTopology } from './workflowClassification';
import {
  activateProductProcessWorkflow,
  deactivateProductProcessWorkflow,
  getProductProcessWorkflow,
  getProductProcessWorkflowActivation,
  getProductProcessWorkflowVersion,
  listProductProcessWorkflowVersions,
  publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow,
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
  WorkflowValidationError,
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
  gramsPerUnit?: number | null;
  detailLoaded?: boolean;
}

/** Current ACTIVE recipe item shape used by the workflow material picker. */
interface BomRecipeItemOption {
  materialTypeId: string;
  materialName?: string;
  unit?: string;
  materialCategory?: string;
}

interface BomRecipeDetailOption {
  items?: BomRecipeItemOption[];
  version?: number | null;
  status?: string | null;
  workflowRevisionId?: number | null;
  workflowRevisionHash?: string | null;
}

interface BomProductTarget {
  id: string;
  name: string;
}

interface ActiveBomRevision {
  version?: number | null;
  workflowRevisionId?: number | null;
  workflowRevisionHash?: string | null;
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
  // 仅用于读取旧 owner-centric 数据时的初始锚点兼容，不得限制可编辑的画布拓扑。
  rawOwnerMode?: boolean;
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
const snapshotting = ref(false);
const publishing = ref(false);
// 发布确认框显示期间也要锁住动作，避免连续点击叠加多个确认框/发布请求。
const publishConfirming = ref(false);
const activationChanging = ref(false);
const dirty = ref(false);
const selectedNodeId = ref('');
const interactionMode = ref<'PAN' | 'SELECT'>('PAN');
function isCanvasElementSelected(element: Node | Edge): boolean {
  return Boolean((element as { selected?: boolean }).selected);
}

const selectedCellIds = computed(() => flowNodes.value.filter(isCanvasElementSelected).map((node) => node.id));
const selectedCellCount = computed(() => selectedCellIds.value.length);
const selectedEdgeIds = computed(() => flowEdges.value.filter(isCanvasElementSelected).map((edge) => edge.id));
const selectedEdgeCount = computed(() => selectedEdgeIds.value.length);
const selectedSelectionCount = computed(() => selectedCellCount.value + selectedEdgeCount.value);
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
const bomDrawerProductTypeId = ref('');
const BomUnifiedPanel = defineAsyncComponent(() => import('@/views/production/bom-unified/index.vue'));
// #12b: 版本记录浏览 (只读查看之前发布过的版本); previewingVersion 非空时 = 正在预览历史版本
const versionDrawerVisible = ref(false);
const versionList = ref<WorkflowVersionSummary[]>([]);
const versionLoading = ref(false);
const history = ref<ProductProcessWorkflowDefinition[]>([]);
const dragStartSnapshot = ref<ProductProcessWorkflowDefinition | null>(null);
const workProcessOptions = ref<WorkProcessItem[]>([]);
const workProcessCategories = ref<string[]>([]);
const skuOptions = ref<SkuOption[]>([]);
const rawMaterialOptions = ref<RawMaterialPickerOption[]>([]);
const rawMaterialSegments = ref<MaterialSegmentNode[]>([]);
const unitCatalog = ref<UnitCatalogItem[]>([]);
// #3: 该产品 BOM 原辅料清单 (per-product, 随 productTypeId 变化而重新加载,
// 与 loadCatalogs 的"全厂字典"缓存粒度不同, 单独一个 ref + 单独一个 loader)。
const productBomItems = ref<BomRecipeItemOption[]>([]);
const bomMissingProducts = ref<BomProductTarget[]>([]);
const activeBomByProduct = ref<Record<string, ActiveBomRevision>>({});
const bomRevisionMismatchProducts = computed<BomProductTarget[]>(() => {
  const revisionId = definition.value?.revisionId;
  const revisionHash = definition.value?.revisionHash;
  if (revisionId == null && !revisionHash) return [];

  const seen = new Set<string>();
  return flowNodes.value
    .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
    .map((node) => ({
      id: String(node.data.skuId),
      name: String(node.data.name || node.data.skuId),
    }))
    .filter((target) => {
      if (seen.has(target.id)) return false;
      seen.add(target.id);
      const activeBom = activeBomByProduct.value[target.id];
      if (!activeBom) return false;
      const revisionIdMismatch = revisionId != null
        && activeBom.workflowRevisionId !== revisionId;
      const revisionHashMismatch = Boolean(revisionHash)
        && activeBom.workflowRevisionHash !== revisionHash;
      return revisionIdMismatch || revisionHashMismatch;
    });
});
const bomRawMaterialIdList = computed(() => productBomItems.value.map((item) => item.materialTypeId));
function usedRawMaterialIdsExcept(nodeId: string): string[] {
  return flowNodes.value
    .filter((node) => node.id !== nodeId && node.data?.kind === 'RAW_MATERIAL')
    .map((node) => String(node.data?.skuId || ''))
    .filter(Boolean);
}
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
const skuSpecifications = computed(() => Object.fromEntries(skuOptions.value.map((option) => [
  option.id,
  { unit: option.unit || '', gramsPerUnit: option.gramsPerUnit ?? null },
])));

const processDialogVisible = ref(false);
const processSourceMaterialId = ref('');
const selectedWorkProcessId = ref('');

// #13: 现场创建工序 + 相似检测防重复建。工序本身不持有业务单位；legacy payload 继承上游端口单位。
const processCreateMode = ref<'existing' | 'create'>('existing');
const creatingProcess = ref(false);
const newProcessForm = ref<{ name: string; processCategory: string; outputKind: 'SEMI_FINISHED' | 'FINISHED_GOOD' }>(
  { name: '', processCategory: '', outputKind: 'SEMI_FINISHED' },
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
  const processCategory = newProcessForm.value.processCategory.trim();
  if (!identity || !name || !processCategory || creatingProcess.value) return;
  if (!workProcessCategories.value.includes(processCategory)) {
    ElMessage.warning('请选择有效的工序类别');
    return;
  }
  creatingProcess.value = true;
  try {
    const payload: Partial<WorkProcessItem> = {
      processName: name,
      processCategory,
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
const processEditVisible = ref(false);
const processEditSaving = ref(false);
const processEditNodeId = ref('');
const processEditForm = ref({
  processName: '',
  processCategory: '',
  defaultOutputMaterialKind: 'SEMI_FINISHED' as WorkProcessOutputMaterialKind,
  needsInput: true,
});
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
const derivedWorkflowClassification = computed(() => classifyWorkflowTopology(
  flowNodes.value.map((node) => ({
    id: node.id,
    kind: nodeKind(node),
    skuId: typeof node.data?.skuId === 'string' ? node.data.skuId : undefined,
  })),
  flowEdges.value.map((edge) => ({ source: edge.source, target: edge.target })),
));
const activationWorkflowTypeLabel = computed(() => {
  switch (activation.value?.workflowType) {
    case 'SINGLE_OUTPUT_PRODUCT': return '单产出产品';
    case 'RAW_MATERIAL_SPLIT': return '原料分流';
    case 'JOINT_PRODUCTION': return '联产';
    default: return '';
  }
});
const workflowClassificationLabel = computed(() => {
  if (derivedWorkflowClassification.value.type === 'PRODUCT') return '单产出产品';
  if (derivedWorkflowClassification.value.type === 'RAW_SPLIT') return '原料分流';
  if (derivedWorkflowClassification.value.type === 'JOINT_PRODUCTION') return '联产';
  return activationWorkflowTypeLabel.value || '待完善画布';
});
// #12b 预览历史版本标志 (非空=正在只读预览某历史版本); 提前声明供 canEdit 引用
const previewingVersion = ref<number | null>(null);
// AI 输入默认可见；用户显式收起后仍保留可键盘访问的展开按钮。
const aiCollapsed = ref(false);
const unitReviewPending = ref(false);
const unitIssues = ref<WorkflowUnitIssue[]>([]);
const publishBindingErrors = ref<WorkflowValidationError[]>([]);
const publishBindingAttentionNodeIds = ref<Set<string>>(new Set());
const canEdit = computed(() => (
  props.canWrite
  && !loading.value
  && !catalogLoading.value
  && previewingVersion.value === null   // #12b 预览历史版本时整个画布只读 (不可拖/存/发布, 防止旧版本被当草稿保存发布)
  && loadedCatalogFactoryId.value === props.factoryId
  && loadedDefinitionIdentity.value?.factoryId === props.factoryId
  && loadedDefinitionIdentity.value?.productTypeId === props.productTypeId
));
const currentDefinitionIsEnabled = computed(() => (
  definition.value?.status === 'PUBLISHED'
  && activation.value?.enabled === true
  && activation.value.activeDefinitionVersion === definition.value.version
));
const publishActionCompleted = computed(() => currentDefinitionIsEnabled.value && !dirty.value);
const publishActionLabel = computed(() => (
  publishActionCompleted.value ? '已发布并启用' : '发布并启用'
));
const publishDisabledReason = computed(() => {
  if (publishConfirming.value || publishing.value) return '正在发布 Workflow，请勿重复提交';
  if (!canEdit.value) return '当前正在加载、预览历史版本或没有编辑权限，暂不能发布';
  // 只在“当前显示的版本”已经发布并启用、且没有新的本地草稿改动时禁用。
  // 已启用 v1 但当前定义是 v2 草稿时必须仍可发布 v2。
  if (currentDefinitionIsEnabled.value && !dirty.value) {
    return `Workflow v${definition.value?.version} 已发布并启用，当前没有待发布变更`;
  }
  if (definition.value?.status === 'PUBLISHED' && !dirty.value) {
    return `Workflow v${definition.value.version} 已发布；请先修改并保存为新草稿后再发布`;
  }
  if (definition.value && definition.value.status !== 'DRAFT' && !dirty.value) {
    return '当前不是可发布的 Workflow 草稿；请先另存或修改后保存草稿';
  }
  if (flowNodes.value.length === 0) return '请先在画布中配置 Workflow Cell';
  const outputContractError = validateWorkflow(currentDefinition(), 'publish')
    .find((error) => error.code === 'OUTPUT_CONTRACT_INVALID');
  if (outputContractError) return outputContractError.message;
  if (bomMissingProducts.value.length > 0) {
    return `${bomMissingProducts.value.map((item) => item.name).join('、')} 尚未配置原辅料 BOM`;
  }
  return '';
});
const aiQuickPrompts = [
  '检查当前 Workflow 的 SKU 上下游承接',
  '检查投入与产出单位、数量换算是否完整',
  '检查分流、合流和同 SKU 多投入是否合理',
  '根据我的描述生成工序调整草稿',
];

const selectedNode = computed(() => flowNodes.value.find((node) => node.id === selectedNodeId.value));
const selectedNodeLabel = computed(() => {
  const selectedCell = selectedCellIds.value.length === 1
    ? flowNodes.value.find((node) => node.id === selectedCellIds.value[0])
    : selectedNode.value;
  const data = selectedCell?.data as Record<string, unknown> | undefined;
  return String(data?.processName || data?.name || (props.productTypeId ? '整个 Workflow' : '未选择产品'));
});
const aiContextLabel = computed(() => {
  if (selectedCellCount.value > 0 || selectedEdgeCount.value > 0) {
    const parts: string[] = [];
    if (selectedCellCount.value > 0) parts.push(`${selectedCellCount.value} 个 Cell`);
    if (selectedEdgeCount.value > 0) parts.push(`${selectedEdgeCount.value} 条线`);
    if (selectedCellCount.value === 1 && selectedEdgeCount.value <= 2) {
      return `当前范围：${selectedNodeLabel.value}`;
    }
    return `当前范围：${parts.join('、')}`;
  }
  return '当前范围：整个 Workflow';
});
const selectedNodeContext = computed<Record<string, unknown>>(() => ({
  productTypeId: props.productTypeId,
  definition: definition.value ? currentDefinition() : null,
  selectedNodeId: selectedNodeId.value || null,
  selectedNode: selectedNode.value ? serializeFlowNode(selectedNode.value) : null,
  selectedNodeIds: selectedCellIds.value,
  selectedNodes: flowNodes.value
    .filter((node) => selectedCellIds.value.includes(node.id))
    .map(serializeFlowNode),
  selectedEdgeIds: selectedEdgeIds.value,
  selectedEdges: flowEdges.value
    .filter((edge) => selectedEdgeIds.value.includes(edge.id))
    .map(serializeFlowEdge),
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
  () => { void loadProductBom(); },
);

// #10: 打开 BOM 配置抽屉; 关闭时刷新本产品 BOM (原料分组 + 提示随即更新)
function openBomDrawer(requestedProductTypeId?: string): void {
  const errors = validateWorkflow(currentDefinition(), 'publish');
  if (errors.length > 0) {
    const first = errors[0];
    if (first.nodeId) {
      selectedNodeId.value = first.nodeId;
    }
    ElMessage.error(first.message);
    return;
  }
  const finishedOutputIds = flowNodes.value
    .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
    .map((node) => String(node.data.skuId))
    .filter(Boolean);
  const targetIds = [...new Set(finishedOutputIds)].sort();
  // Workflow 的锚点 SKU 若也是终端产出，始终优先；否则稳定选择第一个终端产出，
  // 避免多产出画布每次打开随机跳到不同 BOM。
  bomDrawerProductTypeId.value = requestedProductTypeId
    && targetIds.includes(requestedProductTypeId)
    ? requestedProductTypeId
    : (targetIds.includes(productTypeId.value)
      ? productTypeId.value
      : (targetIds[0] || productTypeId.value));
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
  clearPublishBindingErrors();
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

async function loadCatalogs(): Promise<void> {
  const generation = ++catalogGeneration;
  const factoryId = props.factoryId;
  invalidateCatalogs();
  if (!factoryId) return;
  catalogLoading.value = true;
  try {
    const unitCatalogRequest = getUnitCatalog(factoryId).catch((error: unknown): null => {
      console.error('[ProductProcessWorkflow] unit catalog loading failed; using built-in scientific units', error);
      return null;
    });
    const [processResponse, categoryResponse, productResponse, rawResponse, segmentResponse, unitCatalogResponse] = await Promise.all([
      getActiveWorkProcesses(factoryId),
      getWorkProcessCategories(factoryId),
      // 精简「选项」端点 (7 字段: id/name/code/unit/specification/productCategory/isActive + @Cacheable) —
      // SkuOption 只需这些字段; 避开重 DTO 的 ~3s/422KB 全量加载 (顶部选择器已先命中缓存, 这里秒回)。
      get<{ content: SkuOption[] }>(`/${factoryId}/product-types/options`),
      get<RawMaterialPickerOption[]>(`/${factoryId}/raw-material-types/active`),
      get<MaterialSegmentNode[]>(`/${factoryId}/material-segments/tree`),
      unitCatalogRequest,
    ]);
    if (!isCurrentCatalogLoad(generation, factoryId)) return;
    if (!processResponse.success
      || !Array.isArray(processResponse.data)
      || !categoryResponse.success
      || !Array.isArray(categoryResponse.data)
      || !productResponse.success
      || !Array.isArray(productResponse.data?.content)
      || !rawResponse.success
      || !Array.isArray(rawResponse.data)
      || !segmentResponse.success
      || !Array.isArray(segmentResponse.data)) {
      throw new Error('Workflow catalog response is incomplete');
    }
    workProcessOptions.value = processResponse.data;
    workProcessCategories.value = categoryResponse.data;
    skuOptions.value = productResponse.data.content.map((option) => ({
      ...option,
      detailLoaded: Object.prototype.hasOwnProperty.call(option, 'gramsPerUnit'),
    }));
    rawMaterialOptions.value = rawResponse.data.filter(isRawMaterialOption);
    rawMaterialSegments.value = buildRawMaterialSegmentTree(segmentResponse.data);
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
  workProcessCategories.value = [];
  skuOptions.value = [];
  rawMaterialOptions.value = [];
  rawMaterialSegments.value = [];
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

async function ensureSkuDetail(skuId: string): Promise<SkuOption | undefined> {
  const option = skuOptions.value.find((candidate) => candidate.id === skuId);
  if (!option || option.detailLoaded) return option;
  const response = await get<SkuOption>(`/${props.factoryId}/product-types/${skuId}`);
  if (!response.success || !response.data) return option;
  Object.assign(option, response.data, { detailLoaded: true });
  skuOptions.value = [...skuOptions.value];
  return option;
}

async function ensureBoundSkuDetails(): Promise<void> {
  const skuIds = [...new Set(flowNodes.value
    .filter((node) => node.data?.kind === 'SEMI_FINISHED' || node.data?.kind === 'FINISHED_GOOD')
    .map((node) => String(node.data?.skuId || ''))
    .filter(Boolean))];
  await Promise.all(skuIds.map((skuId) => ensureSkuDetail(skuId)));
}

function unitIssueForNode(nodeId: string): string | undefined {
  return unitIssues.value.find((issue) => issue.nodeId === nodeId)?.message;
}

function publishBindingErrorForNode(nodeId: string): string | undefined {
  return publishBindingErrors.value.find((error) => error.nodeId === nodeId)?.message;
}

function clearPublishBindingErrors(): void {
  publishBindingErrors.value = [];
  publishBindingAttentionNodeIds.value = new Set();
}

function clearPublishBindingError(nodeId: string): void {
  publishBindingErrors.value = publishBindingErrors.value.filter((error) => error.nodeId !== nodeId);
  if (publishBindingAttentionNodeIds.value.has(nodeId)) {
    const next = new Set(publishBindingAttentionNodeIds.value);
    next.delete(nodeId);
    publishBindingAttentionNodeIds.value = next;
  }
}

function acknowledgePublishBindingError(nodeId: string): void {
  if (!publishBindingAttentionNodeIds.value.has(nodeId)) return;
  const next = new Set(publishBindingAttentionNodeIds.value);
  next.delete(nodeId);
  publishBindingAttentionNodeIds.value = next;
}

async function markPublishBindingErrors(errors: WorkflowValidationError[]): Promise<void> {
  publishBindingErrors.value = errors.filter(
    (error): error is WorkflowValidationError & { nodeId: string } => (
      error.code === 'SKU_REQUIRED' && typeof error.nodeId === 'string'
    ),
  );
  publishBindingAttentionNodeIds.value = new Set(
    publishBindingErrors.value.map((error) => error.nodeId as string),
  );
  await focusFirstPublishBindingError();
}

async function focusFirstPublishBindingError(): Promise<void> {
  const targetId = publishBindingErrors.value.find((error) => (
    error.nodeId && flowNodes.value.some((node) => node.id === error.nodeId)
  ))?.nodeId;
  if (!targetId) return;
  selectedNodeId.value = targetId;
  const target = flowNodes.value.find((node) => node.id === targetId);
  if (!target) return;
  await nextTick();
  await fitView({ nodes: [target.id], padding: 0.48, duration: 420, maxZoom: 1.15 });
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
    // Opening a published Workflow is read-only. The backend marker is broad and may be stale;
    // current catalog reconciliation is authoritative. Never fork/save a draft merely by viewing.
    unitReviewPending.value = result.errors.length > 0 || unitsChanged;
    if (result.errors.length > 0) showUnitIssues(result.errors);
    return;
  }
  if (unitsChanged) {
    hydrate(result.definition);
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
    const inputSelectionMigrated = hydrate(nextDefinition);
    loadedDefinitionIdentity.value = identity;
    await ensureBoundSkuDetails();
    if (!isCurrentLoad(generation, identity)) return;
    dirty.value = !nextDefinition.id || inputSelectionMigrated;
    await reconcileLoadedUnits();
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
  dragStartSnapshot.value = null;
  processDialogVisible.value = false;
  skuDialogVisible.value = false;
  quickEditVisible.value = false;
  quickEditNodeId.value = '';
  quickEditSkuId.value = '';
  processEditVisible.value = false;
  processEditNodeId.value = '';
  bomMissingProducts.value = [];
  activeBomByProduct.value = {};
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
  activeBomByProduct.value = {};
  if (!factoryId || !ownerId) return;
  const graphOutputs = flowNodes.value
    .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
    .map((node) => ({ id: String(node.data.skuId), name: String(node.data.name || node.data.skuId) }));
  // BOM 目标由画布产出决定；只有旧图尚无产出时才用非原料锚点兼容加载。
  const targets = graphOutputs.length > 0
    ? graphOutputs
    : rawOwnerMode.value ? [] : [{ id: ownerId, name: props.productName || ownerId }];
  const uniqueTargets = targets.filter((target, index, list) =>
    list.findIndex((candidate) => candidate.id === target.id) === index);
  if (uniqueTargets.length === 0) return;
  try {
    const responses = await Promise.all(uniqueTargets.map(async (target) => {
      try {
        return {
          target,
          response: await get<BomRecipeDetailOption>(
            `/${factoryId}/bom/recipes/by-product/${target.id}/current`,
            { _silent: true },
          ),
        };
      } catch {
        return { target, response: null };
      }
    }));
    if (generation !== bomLoadGeneration
      || props.factoryId !== factoryId
      || props.productTypeId !== ownerId) return;
    const allItems: BomRecipeItemOption[] = [];
    const missing: BomProductTarget[] = [];
    const activeBomMap: Record<string, ActiveBomRevision> = {};
    responses.forEach(({ target, response }) => {
      const items = response?.success && Array.isArray(response.data?.items)
        ? response.data.items
        : [];
      allItems.push(...items);
      if (items.length === 0) missing.push(target);
      if (response?.success && response.data) {
        activeBomMap[target.id] = {
          version: response.data.version,
          workflowRevisionId: response.data.workflowRevisionId,
          workflowRevisionHash: response.data.workflowRevisionHash,
        };
      }
    });
    productBomItems.value = allItems;
    bomMissingProducts.value = missing;
    activeBomByProduct.value = activeBomMap;
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

function hydrate(nextDefinition: ProductProcessWorkflowDefinition): boolean {
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
  return false;
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
  hydrate(previous);
  editSeq += 1;
  dirty.value = true;
  scheduleAutoSave();
}

function onNodeClick({ node, event }: NodeMouseEvent): void {
  closeCanvasDropdowns(event);
  selectedNodeId.value = node.id;
  acknowledgePublishBindingError(node.id);
  const ids = event?.ctrlKey || event?.metaKey ? new Set(selectedCellIds.value) : new Set<string>();
  ids.add(node.id);
  flowNodes.value = flowNodes.value.map((candidate) => ({
    ...candidate,
    selected: ids.has(candidate.id),
  }));
  selectEdgesConnectedTo(ids);
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
function onEdgeClick({ edge, event }: EdgeMouseEvent): void {
  closeCanvasDropdowns(event);
  flowNodes.value = flowNodes.value.map((node) => (
    isCanvasElementSelected(node) ? { ...node, selected: false } : node
  ));
  selectedNodeId.value = '';
  selectedEdgeId.value = edge.id;
  flowEdges.value = flowEdges.value.map((candidate) => ({
    ...candidate,
    selected: candidate.id === edge.id,
  }));
}

function selectEdgesConnectedTo(nodeIds: Set<string>): void {
  flowEdges.value = flowEdges.value.map((edge) => ({
    ...edge,
    selected: nodeIds.has(edge.source) || nodeIds.has(edge.target),
  }));
  selectedEdgeId.value = '';
}

async function onSelectionEnd(): Promise<void> {
  await nextTick();
  selectEdgesConnectedTo(new Set(selectedCellIds.value));
}

function clearEdgeSelection(): void {
  selectedEdgeId.value = '';
  flowEdges.value = flowEdges.value.map((edge) => (
    isCanvasElementSelected(edge) ? { ...edge, selected: false } : edge
  ));
}

function onPaneClick(): void {
  closeCanvasDropdowns();
  selectedNodeId.value = '';
  clearEdgeSelection();
}

function closeCanvasDropdowns(event?: MouseEvent | TouchEvent): void {
  const target = event?.target;
  if (target instanceof Element && target.closest(
    '.workflow-sku-picker, .workflow-sku-picker-popper, .raw-selector, .raw-category-filter-shell',
  )) return;
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  window.dispatchEvent(new Event('workflow-close-dropdowns'));
}

/** 删除一条边, 并同步解绑对应的工序端口 (保持模型一致, 反向于 attach) */
function detachEdgePort(edge: Edge): void {
  const processNode = flowNodes.value.find((node) => (
    nodeKind(node) === 'PROCESS' && (node.id === edge.source || node.id === edge.target)
  ));
  if (!processNode) return;
  const isOutput = processNode.id === edge.source;
  const portId = isOutput ? edge.sourceHandle : edge.targetHandle;
  const data = processNode.data as ProcessNodeData;
  data.ports = data.ports.filter((port) => port.id !== portId);
}

function removeEdgeById(edgeId: string): void {
  if (!canEdit.value) return;
  const edge = flowEdges.value.find((e) => e.id === edgeId);
  if (!edge) return;
  mutate(() => {
    // 找到该边连的工序端 + 端口 id (INPUT: 边的 targetHandle=portId; OUTPUT: sourceHandle=portId)
    detachEdgePort(edge);
    flowEdges.value = flowEdges.value.filter((e) => e.id !== edgeId);
  });
  selectedEdgeId.value = '';
}

// #9 删除一个 Cell (节点): 连带删掉它的所有连线, 并解绑引用它的工序端口 (模型一致)。
function removeNode(nodeId: string): void {
  if (!canEdit.value) return;
  const node = flowNodes.value.find((n) => n.id === nodeId);
  if (!node) return;
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
    if (!flowEdges.value.some((edge) => edge.id === selectedEdgeId.value)) clearEdgeSelection();
    clearPublishBindingError(nodeId);
  };
  const impact = touching > 0 ? `并同时移除 ${touching} 条相连连线` : '（当前没有相连连线）';
  ElMessageBox.confirm(
    `确认从当前 Workflow 草稿移除「${label}」${impact}？这不会删除工序/SKU 主数据，删除后可用「撤销」恢复。`,
    '移除 Workflow Cell',
    { type: 'warning', confirmButtonText: '从草稿移除', cancelButtonText: '取消' },
  ).then(doRemove).catch(() => { /* 取消 */ });
}

function removeSelectedElements(): void {
  if (!canEdit.value) return;
  const ids = [...selectedCellIds.value];
  const explicitlySelectedEdges = new Set(selectedEdgeIds.value);
  if (ids.length === 0 && explicitlySelectedEdges.size === 0) return;
  const idSet = new Set(ids);
  const materialIds = new Set(flowNodes.value
    .filter((node) => idSet.has(node.id) && nodeKind(node) !== 'PROCESS')
    .map((node) => node.id));
  const removedEdgeIds = new Set(flowEdges.value
    .filter((edge) => explicitlySelectedEdges.has(edge.id) || idSet.has(edge.source) || idSet.has(edge.target))
    .map((edge) => edge.id));
  const doRemove = (): void => {
    mutate(() => {
      flowEdges.value.filter((edge) => removedEdgeIds.has(edge.id)).forEach(detachEdgePort);
      flowNodes.value.forEach((node) => {
        if (nodeKind(node) !== 'PROCESS' || idSet.has(node.id)) return;
        const data = node.data as ProcessNodeData;
        data.ports = data.ports.filter((port) => !materialIds.has(port.materialNodeId));
      });
      flowNodes.value = flowNodes.value.filter((node) => !idSet.has(node.id));
      flowEdges.value = flowEdges.value.filter((edge) => !removedEdgeIds.has(edge.id));
    });
    if (idSet.has(selectedNodeId.value)) selectedNodeId.value = '';
    if (!flowEdges.value.some((edge) => edge.id === selectedEdgeId.value)) clearEdgeSelection();
    ids.forEach(clearPublishBindingError);
    ElMessage.success(`已删除 ${ids.length} 个 Cell、${removedEdgeIds.size} 条线，可用“撤销”恢复`);
  };
  ElMessageBox.confirm(
    `确定删除已选的 ${ids.length} 个 Cell 和 ${removedEdgeIds.size} 条线？删除 Cell 时关联线会一并删除，可用“撤销”恢复。`,
    '批量删除所选内容',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).then(doRemove).catch(() => { /* 取消 */ });
}

function onEditorKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Delete' && event.key !== 'Backspace') return;
  const tag = (event.target as HTMLElement | null)?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') return; // 输入框里删字不误删
  if (interactionMode.value === 'SELECT' && selectedSelectionCount.value > 0) {
    event.preventDefault();
    removeSelectedElements();
  } else if (selectedEdgeId.value) {
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
  const rawCount = flowNodes.value.filter((node) => node.data?.kind === 'RAW_MATERIAL').length;
  const owner = rawOwnerMode.value && rawCount === 0
    ? rawMaterialOptions.value.find((item) => item.id === props.productTypeId)
    : undefined;
  mutate(() => {
    const count = rawCount;
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
  newProcessForm.value = { name: '', processCategory: '', outputKind: 'SEMI_FINISHED' };
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
      productUnit: finishedGoodSkuOptions.value.find((option) => option.id === props.productTypeId)?.unit || '',
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
  const duplicate = flowNodes.value.some((node) => (
    node.id !== materialNodeId
    && node.data?.kind === 'RAW_MATERIAL'
    && String(node.data?.skuId || '') === skuId
  ));
  if (duplicate) {
    ElMessage.warning('该原料已在当前 Workflow 中使用');
    return;
  }
  const nextUnit = workflowReportingUnit(
    'RAW_MATERIAL',
    option.unit || String(material.data?.baseUnit || 'kg'),
  );
  mutate(() => {
    Object.assign(material.data, {
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      baseUnit: nextUnit,
      bound: true,
    });
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
  clearPublishBindingError(materialNodeId);
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

async function selectMaterialSku(materialNodeId: string, skuId: string): Promise<void> {
  if (!canEdit.value) return;
  const owner = findOutputPortOwner(materialNodeId);
  if (!owner) {
    ElMessage.warning('未找到该产出 Cell 对应的工序，无法绑定 SKU');
    return;
  }
  await selectOutputSku(owner.processId, owner.portId, skuId);
}

function processOutputKind(processData: ProcessNodeData): WorkProcessOutputMaterialKind | null {
  return workProcessOptions.value.find(
    (option) => option.id === processData.workProcessId,
  )?.defaultOutputMaterialKind ?? null;
}

function isPrimaryOutputPort(processData: ProcessNodeData, portId: string): boolean {
  return processData.ports
    .filter((candidate) => candidate.direction === 'OUTPUT')
    .sort((left, right) => left.ordinal - right.ordinal)[0]?.id === portId;
}

async function ensurePrimaryOutputKind(
  processId: string,
  portId: string,
  nextKind: WorkProcessOutputMaterialKind,
): Promise<boolean> {
  const identity = currentLoadedIdentity();
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!identity || !process || process.data?.kind !== 'PROCESS') return false;
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  if (!isPrimaryOutputPort(data, portId)) return true;

  const currentKind = processOutputKind(data);
  if (!needsPrimaryOutputKindUpdate(currentKind, nextKind, true)) return true;
  const currentLabel = currentKind === 'FINISHED_GOOD' ? '成品出品工序' : '半成品工序';
  const nextLabel = nextKind === 'FINISHED_GOOD' ? '成品出品工序' : '半成品工序';
  try {
    await ElMessageBox.confirm(
      `工序“${data.processName}”当前配置为“${currentLabel}”，不能把主产出绑定为${nextKind === 'FINISHED_GOOD' ? '成品' : '半成品'}。是否快捷修改为“${nextLabel}”后继续？`,
      '产出类型不一致',
      {
        type: 'warning',
        confirmButtonText: `修改为${nextLabel}`,
        cancelButtonText: '取消选择',
      },
    );
    const response = await updateWorkProcessOutputKind(
      identity.factoryId,
      data.workProcessId,
      nextKind,
    );
    if (!response.success || !response.data) {
      ElMessage.error(response.message || '工序产出类型修改失败');
      return false;
    }
    const updatedProcess = response.data;
    workProcessOptions.value = workProcessOptions.value.map(
      (option) => option.id === updatedProcess.id ? updatedProcess : option,
    );
    ElMessage.success(`工序“${data.processName}”已修改为${nextLabel}`);
    return true;
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      console.error('[updateWorkProcessOutputKind] failed', error);
    }
    return false;
  }
}

async function selectOutputSku(processId: string, portId: string, skuId: string): Promise<void> {
  if (!canEdit.value) return;
  if (skuId === '__CREATE__') {
    const process = flowNodes.value.find((node) => node.id === processId);
    const data = process?.data as ProcessNodeData | undefined;
    const port = data?.ports.find((candidate) => candidate.id === portId);
    if (data && isPrimaryOutputPort(data, portId) && processOutputKind(data) === 'FINISHED_GOOD') {
      ElMessage.warning('成品出品工序不能现场创建半成品 SKU，请选择已有成品 SKU，或先到 SKU 管理创建成品');
      return;
    }
    skuBindingTarget.value = { processId, portId };
    skuForm.value = {
      name: port?.materialName || `${data?.processName || '工序'}后半成品`,
      unit: port?.unit || 'kg',
    };
    skuDialogVisible.value = true;
    return;
  }
  const option = await ensureSkuDetail(skuId);
  const kind = classifyOutputSkuCategory(option?.productCategory);
  if (!option || !kind) {
    ElMessage.error('所选 SKU 分类不能作为工序产出');
    return;
  }
  const process = flowNodes.value.find((node) => node.id === processId);
  const data = process?.data as ProcessNodeData | undefined;
  const requiresKindUpdate = Boolean(
    data
    && needsPrimaryOutputKindUpdate(
      processOutputKind(data),
      kind,
      isPrimaryOutputPort(data, portId),
    ),
  );
  if (requiresKindUpdate) {
    void ensurePrimaryOutputKind(processId, portId, kind).then((compatible) => {
      if (compatible) bindOutputSku(processId, portId, option);
    });
    return;
  }
  bindOutputSku(processId, portId, option);
}

function bindOutputSku(processId: string, portId: string, option: SkuOption): boolean {
  const kind = classifyOutputSkuCategory(option.productCategory);
  if (!kind) {
    ElMessage.error('所选 SKU 分类不能作为工序产出');
    return false;
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
  const expectedKind = processOutputKind(data);
  if (needsPrimaryOutputKindUpdate(expectedKind, kind, primaryOutputPort?.id === port.id)) {
    ElMessage.error(`工序“${data.processName}”的主产出类型与所选 SKU 不一致，请先修改工序产出类型`);
    return false;
  }
  const nextUnit = workflowReportingUnit(kind, option.unit || port.unit);
  mutate(() => {
    Object.assign(port, {
      skuId: option.id,
      materialName: option.name,
      materialKind: kind,
      unit: nextUnit,
    });
    delete port.quantityMode;
    delete port.standardQuantity;
    delete port.conversionRefId;
    delete port.conversionVersion;
    if (primaryOutputPort?.id === port.id) {
      data.outputUnit = nextUnit;
    }
    // Vue Flow keeps the node-data reference used by the rendered Cell. Mutate that
    // reference so changing an output SKU refreshes the connected Cell immediately.
    Object.assign(material.data, {
      kind,
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      specification: option.specification,
      baseUnit: nextUnit,
      bound: true,
    });
  });
  clearPublishBindingError(material.id);
  return true;
}

function dataMaterialNodeId(processId: string, portId: string): string | undefined {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process || process.data?.kind !== 'PROCESS') return undefined;
  return (process.data as ProcessNodeData).ports.find((port) => port.id === portId)?.materialNodeId;
}

function openQuickEditProcess(processNodeId: string): void {
  const node = flowNodes.value.find((candidate) => candidate.id === processNodeId);
  if (!node || node.data?.kind !== 'PROCESS') return;
  const data = node.data as ProcessNodeData;
  const master = workProcessOptions.value.find((option) => option.id === data.workProcessId);
  if (!master) {
    ElMessage.warning('未找到该工序主数据，请刷新后重试');
    return;
  }
  processEditNodeId.value = processNodeId;
  processEditForm.value = {
    processName: master.processName,
    processCategory: master.processCategory || '',
    defaultOutputMaterialKind: master.defaultOutputMaterialKind,
    needsInput: master.needsInput !== false,
  };
  processEditVisible.value = true;
}

async function saveQuickEditProcess(): Promise<void> {
  const identity = currentLoadedIdentity();
  const node = flowNodes.value.find((candidate) => candidate.id === processEditNodeId.value);
  if (!identity || !node || node.data?.kind !== 'PROCESS' || processEditSaving.value) return;
  const form = processEditForm.value;
  if (!form.processName.trim() || !form.processCategory.trim()) {
    ElMessage.warning('请完整填写工序名称和类别');
    return;
  }
  if (!workProcessCategories.value.includes(form.processCategory.trim())) {
    ElMessage.warning('请选择有效的工序类别');
    return;
  }
  const data = node.data as ProcessNodeData;
  const currentOutputKind = processOutputKind(data);
  const nextOutputKind = form.defaultOutputMaterialKind;
  const incompatibleOutputPorts = currentOutputKind && currentOutputKind !== nextOutputKind
    ? data.ports.filter((port) => {
      if (port.direction !== 'OUTPUT') return false;
      const material = flowNodes.value.find((candidate) => candidate.id === port.materialNodeId);
      return material?.data?.kind === currentOutputKind;
    })
    : [];
  if (incompatibleOutputPorts.length > 0) {
    const currentLabel = currentOutputKind === 'FINISHED_GOOD' ? '成品' : '半成品';
    const nextLabel = nextOutputKind === 'FINISHED_GOOD' ? '成品' : '半成品';
    try {
      await ElMessageBox.confirm(
        `当前 Workflow 中该工序已有 ${incompatibleOutputPorts.length} 个${currentLabel}产出 Cell。确认修改后，这些 Cell 将改为${nextLabel}并解绑现有 ${currentLabel} SKU，需要重新选择兼容的 ${nextLabel} SKU。`,
        '确认修改产出类型',
        {
          type: 'warning',
          confirmButtonText: `修改并解绑 ${currentLabel} SKU`,
          cancelButtonText: '取消',
        },
      );
    } catch {
      return;
    }
    if (!isLoadedIdentityCurrent(identity) || !canEdit.value) return;
  }
  processEditSaving.value = true;
  try {
    const response = await updateWorkProcess(identity.factoryId, data.workProcessId, {
      processName: form.processName.trim(),
      processCategory: form.processCategory.trim(),
      defaultOutputMaterialKind: form.defaultOutputMaterialKind,
      needsInput: form.needsInput,
    });
    if (!response.success || !response.data) throw new Error(response.message || '工序修改失败');
    const updated = response.data;
    workProcessOptions.value = workProcessOptions.value.map(
      (option) => option.id === updated.id ? updated : option,
    );
    mutate(() => {
      data.processName = updated.processName;
      data.processCategory = updated.processCategory;
      const incompatiblePortIds = new Set(incompatibleOutputPorts.map((port) => port.id));
      data.ports.forEach((port) => {
        const material = flowNodes.value.find((candidate) => candidate.id === port.materialNodeId);
        if (incompatiblePortIds.has(port.id) && material) {
          const nextLabel = nextOutputKind === 'FINISHED_GOOD'
            ? `${updated.processName}成品`
            : `${updated.processName}后半成品`;
          delete port.skuId;
          delete port.conversionRefId;
          delete port.conversionVersion;
          Object.assign(port, {
            materialName: nextLabel,
            materialKind: nextOutputKind,
            unit: '',
          });
          delete port.quantityMode;
          delete port.standardQuantity;
          Object.assign(material.data, {
            kind: nextOutputKind,
            name: nextLabel,
            skuId: '',
            skuCode: nextOutputKind === 'FINISHED_GOOD' ? '待绑定成品 SKU' : '待选择或现场创建 SKU',
            specification: undefined,
            baseUnit: '',
            bound: false,
          });
        }
      });
    });
    processEditVisible.value = false;
    ElMessage.success('工序主数据已更新，当前 Workflow 已刷新');
  } catch (error) {
    console.error('[ProductProcessWorkflow] quick edit process failed', error);
    ElMessage.error(error instanceof Error ? error.message : '工序修改失败');
  } finally {
    processEditSaving.value = false;
  }
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
        Object.assign(material.data, { name, baseUnit: unit });
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
    // 自动保存保留撤销历史 (否则每 2-3s 自动存就把撤销栈清空了)
    if (!options.preserveHistory) {
      history.value = [];
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

async function snapshotWorkflow(): Promise<void> {
  let identity = currentLoadedIdentity();
  if (!identity || !canEdit.value || flowNodes.value.length === 0) return;
  if (dirty.value && !(await saveDraft())) return;
  identity = currentLoadedIdentity();
  const lockVersion = definition.value?.lockVersion;
  if (!identity || lockVersion === undefined) {
    ElMessage.warning('请先保存草稿，再另存为版本');
    return;
  }
  snapshotting.value = true;
  try {
    const response = await snapshotProductProcessWorkflow(
      identity.factoryId,
      identity.productTypeId,
      lockVersion,
    );
    if (!isLoadedIdentityCurrent(identity) || !response.success || !response.data) return;
    hydrate(response.data);
    dirty.value = false;
    history.value = [];
    ElMessage.success(`已另存独立版本，当前继续编辑草稿 v${response.data.version}`);
    if (versionDrawerVisible.value) await openVersionDrawer();
  } catch (error) {
    if (isWorkflowConflict(error)) {
      await recoverWorkflowConflict(identity);
      return;
    }
    console.error('[ProductProcessWorkflow] snapshot failed', error);
    ElMessage.error('另存 Workflow 版本失败');
  } finally {
    snapshotting.value = false;
  }
}

async function publishWorkflow(): Promise<void> {
  let identity = currentLoadedIdentity();
  if (!identity || !canEdit.value || publishConfirming.value || publishing.value) return;
  if (currentDefinitionIsEnabled.value && !dirty.value) {
    ElMessage.info(`Workflow v${definition.value?.version} 已发布并启用，当前没有待发布变更`);
    return;
  }
  if (definition.value?.status === 'PUBLISHED' && !dirty.value) {
    ElMessage.info(`Workflow v${definition.value.version} 已发布；请先修改并保存为新草稿后再发布`);
    return;
  }
  if (definition.value && definition.value.status !== 'DRAFT' && !dirty.value) {
    ElMessage.info('当前不是可发布的 Workflow 草稿；请先另存或修改后保存草稿');
    return;
  }
  if (bomMissingProducts.value.length > 0) {
    ElMessage.warning('请先为所有成品产出配置原辅料 BOM，再发布并启用 Workflow');
    return;
  }
  if (!(await reconcileForPersistence(identity, true))) return;
  if (dirty.value && !(await saveDraft())) return;
  identity = currentLoadedIdentity();
  if (!identity || !canEdit.value) return;
  if (bomRevisionMismatchProducts.value.length > 0) {
    openBomDrawer(bomRevisionMismatchProducts.value[0]?.id);
    ElMessage.warning('当前生效 BOM 仍使用旧工艺；请在右侧升级并激活新版本后重试');
    return;
  }
  if (!definition.value?.lockVersion && definition.value?.lockVersion !== 0) {
    ElMessage.warning('请先保存草稿');
    return;
  }
  const errors = validateWorkflow(currentDefinition(), 'publish');
  if (errors.length > 0) {
    await markPublishBindingErrors(errors);
    void ElMessageBox.alert(errors.slice(0, 8).map((error) => `• ${error.message}`).join('\n'), '发布前检查未通过', {
      type: 'warning',
    });
    return;
  }
  clearPublishBindingErrors();
  publishConfirming.value = true;
  try {
    await ElMessageBox.confirm(
      '发布后会生成可审计的 Workflow 图版本。当前阶段不会自动改写生产任务或报工链，确认发布？',
      '发布 Workflow',
      { type: 'warning', confirmButtonText: '确认发布' },
    );
  } catch {
    return;
  } finally {
    publishConfirming.value = false;
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
    clearPublishBindingErrors();
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
    if (isWorkflowBomRevisionError(error)) {
      const target = bomRevisionMismatchProducts.value[0]?.id || identity.productTypeId;
      openBomDrawer(target);
      ElMessage.warning('当前生效 BOM 未固定这次工艺修订；请在右侧升级并激活新版本后重试');
      void loadProductBom();
      return;
    }
    console.error('[ProductProcessWorkflow] publish failed', error);
    ElMessage.error('Workflow 发布失败，请稍后重试');
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

function workflowErrorCode(error: unknown): string | null {
  if (!error || typeof error !== 'object') return null;
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
  if (Number(candidate.status ?? candidate.response?.status) !== 409) return null;
  const responseData = candidate.response?.data;
  const errorCode = candidate.errorCode
    ?? candidate.code
    ?? responseData?.errorCode
    ?? responseData?.code;
  return typeof errorCode === 'string' ? errorCode : null;
}

function isWorkflowConflict(error: unknown): boolean {
  const errorCode = workflowErrorCode(error);
  return errorCode === 'PRODUCT_PROCESS_WORKFLOW_CONFLICT'
    || errorCode === 'OPTIMISTIC_LOCK_CONFLICT';
}

function isWorkflowBomRevisionError(error: unknown): boolean {
  const errorCode = workflowErrorCode(error);
  return errorCode === 'WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH'
    || errorCode === 'WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE';
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
interface WorkflowSpecStep { process?: string; processCategory?: string; inputs?: string[]; outputs?: WorkflowSpecOutput[] }
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
      const processCategory = (step.processCategory || '').trim();
      if (!workProcessCategories.value.includes(processCategory)) {
        ElMessage.error(`工序「${name}」缺少有效类别，请先在工序管理中维护或在 AI 规格中选择已有类别`);
        return;
      }
      try {
        const resp = await createWorkProcess(identity.factoryId, {
          processName: name, processCategory,
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
        productUnit: finishedGoodSkuOptions.value.find((option) => option.id === identity.productTypeId)?.unit || '',
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

</script>

<style scoped>
.workflow-bom-alert, .workflow-validation-alert { margin: 0 12px 12px; }
.workflow-validation-alert :deep(.el-alert__content) { width: 100%; }
.workflow-validation-alert :deep(.el-alert__description) {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
/* #8: 选中的边高亮 (提示可按 Delete 删除) + 连线中光标 */
.canvas-shell :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #f56c6c !important;
  stroke-width: 4 !important;
  filter: drop-shadow(0 0 4px rgb(245 108 108 / 76%));
}
.canvas-shell :deep(.vue-flow__edge:not(.selected) .vue-flow__edge-path) {
  stroke: #1b65a8 !important;
  stroke-width: 2 !important;
  filter: none;
}
.canvas-shell :deep(.vue-flow__edge .vue-flow__edge-path) {
  transition: stroke 0.15s ease, stroke-width 0.15s ease, filter 0.15s ease;
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
.canvas-floating-tools {
  position: absolute; right: 60px; top: 12px; z-index: 24;
  display: flex; flex-direction: column; gap: 6px; padding: 6px; border: 1px solid #dce8f3;
  border-radius: 9px; background: rgb(255 255 255 / 92%);
  box-shadow: 0 4px 14px rgb(31 62 92 / 14%); backdrop-filter: blur(4px);
}
.canvas-tool-actions { display: flex; gap: 8px; }
.canvas-floating-tools.is-batch-selecting {
  border-color: #409eff;
  box-shadow: 0 5px 18px rgb(64 158 255 / 22%);
}
.batch-selection-guide {
  display: grid; grid-template-columns: auto auto; align-items: center; gap: 2px 10px;
  padding: 7px 9px; border-radius: 6px; color: #39546f; background: #ecf5ff;
  font-size: 12px; line-height: 1.35;
}
.batch-selection-guide strong { color: #1b65a8; }
.batch-selection-shortcut { grid-column: 1 / -1; color: #60758a; }
.batch-selection-result {
  grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding-top: 4px; border-top: 1px solid rgb(64 158 255 / 20%);
}
.batch-selection-count { color: #1677c8; font-weight: 700; }

/* Workflow editor owns the remaining viewport and keeps the canvas full width. */
.workflow-editor {
  --workflow-editor-height: calc(100dvh - var(--header-height, 64px) - 156px);
  position: relative;
  display: block;
  height: max(360px, var(--workflow-editor-height));
  min-height: 0;
  max-height: none;
  overflow: hidden;
}
.workflow-main {
  width: 100%;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.workflow-toolbar {
  position: sticky; top: 0; z-index: 40;
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  min-height: 48px; padding: 6px 10px; border: 1px solid #edf2f7; border-radius: 10px 10px 0 0; background: #fff;
}
.toolbar-status, .toolbar-actions { display: flex; align-items: center; gap: 8px; min-width: 0; }
.toolbar-status { overflow: hidden; white-space: nowrap; }
.toolbar-actions { flex-wrap: nowrap; justify-content: flex-end; overflow-x: auto; padding-bottom: 1px; }
.disabled-action-tooltip { display: inline-flex; }
.dirty-status { color: #e6a23c; font-size: 12px; }
.saved-status { color: #67c23a; font-size: 12px; }
.stage-note { color: #7a8599; font-size: 11px; overflow: hidden; text-overflow: ellipsis; }
.canvas-shell {
  position: relative; flex: 1; min-height: 0; height: 0; overflow: hidden;
  border: 1px solid #dce8f3; border-top: none; border-radius: 0 0 10px 10px; background: #fbfdff;
}
.workflow-ai-dock {
  position: absolute;
  z-index: 26;
  left: 50%;
  bottom: 18px;
  width: min(820px, calc(100% - 96px));
  min-width: 0;
  transform: translateX(-50%);
  filter: drop-shadow(0 16px 32px rgb(31 62 92 / 16%));
}
.workflow-ai-dock.is-collapsed {
  width: max-content;
  max-width: calc(100% - 32px);
}
.workflow-ai-dock__header {
  width: max-content;
  max-width: calc(100% - 24px);
  min-height: 34px;
  margin: 0 auto 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 3px 7px 3px 12px;
  border: 1px solid rgb(210 222 234 / 92%);
  border-radius: 999px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 4px 14px rgb(31 62 92 / 10%);
  backdrop-filter: blur(12px);
}
.workflow-ai-dock__identity {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}
.workflow-ai-dock__spark {
  flex: 0 0 auto;
  color: var(--el-color-primary);
  font-size: 15px;
  line-height: 1;
}
.workflow-ai-dock__identity strong {
  flex: 0 0 auto;
  font-size: 12px;
}
.workflow-ai-dock__identity > span:last-child {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-ai-dock__composer {
  max-height: 210px;
  overflow: auto;
  border-radius: 18px;
}
.workflow-ai-dock :deep(.work-process-ai-chat-panel) {
  width: 100%;
  border-color: rgb(196 211 226 / 92%);
  box-shadow: 0 10px 30px rgb(31 62 92 / 18%);
}
.workflow-canvas { width: 100%; height: 100%; min-height: 0; }
.workflow-canvas.is-batch-selecting :deep(.vue-flow__pane) { cursor: crosshair; }
.workflow-canvas.is-batch-selecting :deep(.vue-flow__selection) {
  border: 2px dashed #1677ff;
  border-radius: 6px;
  background: rgb(64 158 255 / 18%);
  box-shadow: 0 0 0 1px rgb(255 255 255 / 88%) inset, 0 0 18px rgb(64 158 255 / 22%);
  animation: workflow-selection-pulse 0.85s ease-in-out infinite alternate;
}
.workflow-canvas.is-batch-selecting :deep(.vue-flow__node.selected > *) {
  border-radius: 10px;
  outline: 4px solid #1677ff;
  outline-offset: 4px;
  box-shadow: 0 0 0 9px rgb(64 158 255 / 22%), 0 0 28px 8px rgb(22 119 255 / 48%);
  filter: drop-shadow(0 0 8px rgb(22 119 255 / 80%)) drop-shadow(0 0 18px rgb(64 158 255 / 56%));
  animation: workflow-selected-cell-glow 1.2s ease-in-out infinite alternate;
}
/* 松开鼠标后 Vue Flow 默认会画一整块组合选区；隐藏它，只保留各 Cell 的边缘反馈。 */
.workflow-canvas.is-batch-selecting :deep(.vue-flow__nodesselection-rect) {
  border-color: transparent;
  background: transparent;
  box-shadow: none;
  pointer-events: none;
}
@keyframes workflow-selection-pulse {
  from { border-color: #1677ff; background: rgb(64 158 255 / 12%); }
  to { border-color: #69b1ff; background: rgb(64 158 255 / 24%); }
}
@keyframes workflow-selected-cell-glow {
  from {
    outline-color: #409eff;
    box-shadow: 0 0 0 7px rgb(64 158 255 / 18%), 0 0 20px 5px rgb(22 119 255 / 38%);
    filter: drop-shadow(0 0 6px rgb(22 119 255 / 68%)) drop-shadow(0 0 13px rgb(64 158 255 / 44%));
  }
  to {
    outline-color: #006be6;
    box-shadow: 0 0 0 11px rgb(64 158 255 / 25%), 0 0 34px 10px rgb(22 119 255 / 58%);
    filter: drop-shadow(0 0 10px rgb(22 119 255 / 90%)) drop-shadow(0 0 22px rgb(64 158 255 / 66%));
  }
}
@media (prefers-reduced-motion: reduce) {
  .workflow-canvas.is-batch-selecting :deep(.vue-flow__selection),
  .workflow-canvas.is-batch-selecting :deep(.vue-flow__node.selected > *) { animation: none; }
}
.empty-canvas-action { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; }
.empty-canvas-action :deep(.el-button) { pointer-events: auto; }
@media (max-width: 1180px) {
  .toolbar-status .stage-note { display: none; }
}
@media (max-width: 900px) {
  .workflow-editor { height: auto; max-height: none; overflow: visible; }
  .workflow-main { min-height: 480px; }
  .workflow-ai-dock {
    bottom: 12px;
    width: calc(100% - 28px);
  }
  .workflow-ai-dock.is-collapsed { width: max-content; }
  .workflow-ai-dock__header { max-width: 100%; }
  .canvas-shell :deep(.vue-flow__controls) { bottom: 126px; }
}
:deep(.vue-flow__edge-path) { stroke-linecap: round; }
:deep(.vue-flow__node) { border: 0; background: transparent; }
</style>
