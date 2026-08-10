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
          <!-- 「本图产出」才是这张图真正做出来的东西, 由画布研判驱动。
               归属对象(index.vue 顶部那个下拉)只是这张图的存放位置, 已降为次要信息 ——
               用户 2026-08-11 真机看到的正是这两个数在同一屏打架(研判说原料分流,
               归属对象却写着某一个成品)。 -->
          <el-tag
            v-if="terminalOutputNames.length"
            data-testid="workflow-terminal-outputs"
            type="success"
          >
            本图产出：{{ terminalOutputNames.join('、') }}
          </el-tag>
          <el-tag v-else data-testid="workflow-terminal-outputs" type="warning">
            本图产出：尚未画出终端产出
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

      <!--
        画布展示的是「可编辑版本」, 它是草稿时画布内容 ≠ 产线在跑的配方。
        画布自身不显示 BOM 版本状态, 不说这句话用户就会以为已经配好了。
      -->
      <el-alert
        v-if="bomDraftNotices.length > 0"
        class="workflow-bom-alert"
        type="warning"
        :closable="false"
        show-icon
        data-testid="bom-draft-notice"
      >
        <template #title>
          {{ bomDraftNotices.map((item) => `${item.productName} 草稿 v${item.draftVersion ?? '-'}`).join('、') }}
          尚未生效
        </template>
        <template #default>
          <span>
            画布上的辅料/包材是这份草稿的内容；生产仍使用{{
              bomDraftNotices[0]?.activeVersion == null
                ? '旧配方（该产品还没有任何生效版本）'
                : `生效版 v${bomDraftNotices[0]?.activeVersion}`
            }}。生效后才会被之后新建的生产计划采用。
          </span>
          <el-button
            link
            type="primary"
            :loading="activatingBomDraft"
            data-testid="bom-draft-activate"
            @click="activateBomDraft(bomDraftNotices[0])"
          >
            生效该草稿 →
          </el-button>

        </template>
      </el-alert>

      <el-alert
        v-if="bomMissingProducts.length > 0"
        class="workflow-bom-alert"
        type="info"
        :closable="false"
        show-icon
      >
        <template #title>
          {{ bomMissingProducts.map((item) => item.name).join('、') }} 暂未读取到生效 BOM
        </template>
        <template #default>
          <!-- 冷启动已在画布内闭环: 直接点辅料/包材 cell 即会建首版草稿, 不必再把用户支去别的页面。
               ⚠️ 2026-08-07 阶段 2 起这里不再提「副产 cell」—— 副产已改成工序上的真实产出节点,
               不产生 BOM 行, 也就建不了 BOM 草稿。提它等于把用户指向一扇不存在的门。 -->
          <span>
            直接在下方的辅料 / 包材 cell 上配置即可，系统会自动建立首版草稿；
            点击“自动同步并发布”后会重新执行权威检查。
          </span>
        </template>
      </el-alert>

      <el-alert
        v-else-if="bomProductionMismatchProducts.length > 0"
        class="workflow-bom-alert"
        data-testid="workflow-bom-revision-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          {{ bomProductionMismatchProducts.map((item) => item.name).join('、') }} 的生效 BOM 与当前已启用 Workflow 不一致
        </template>
        <template #default>
          <!--
            2026-08-07: 去掉「查看 BOM →」。BOM 就在本画布的辅料 / 包材 cell 上,
            把用户支去另一个页面正是这次要消灭的心智(旧 BOM 菜单入口已于 8-05 摘除)。
            横幅只说明状况, 不再提供出口。
          -->
          <span>发布当前草稿时系统会重新检查并自动同步；已有生产计划继续使用原快照。
            用量与锅序直接在下方工序的辅料 / 包材 cell 上改。</span>
        </template>
      </el-alert>

      <el-alert
        v-if="definition?.status === 'DRAFT' && activation?.enabled"
        class="workflow-bom-alert"
        data-testid="workflow-draft-production-context"
        type="info"
        :closable="false"
        show-icon
        :title="`当前编辑草稿 v${definition.version}；生产继续使用已启用 Workflow v${activation.activeDefinitionVersion}`"
      />

      <el-alert
        v-if="workflowBomSyncPreflight"
        class="workflow-bom-alert"
        data-testid="workflow-bom-sync-preflight"
        :type="workflowBomSyncBlocked ? 'error' : 'success'"
        :closable="false"
        show-icon
        :title="workflowBomSyncStatusTitle(workflowBomSyncPreflight)"
      >
        <template v-if="workflowBomSyncBlocked" #default>
          <ul>
            <li
              v-for="issue in workflowBomSyncIssues"
              :key="[issue.code, issue.materialTypeId, issue.processNodeId, issue.field].join(':')"
            >
              {{ issue.materialName ? `${issue.materialName}：` : '' }}{{ issue.message }}
              <span v-if="issue.action">（{{ issue.action }}）</span>
            </li>
          </ul>
        </template>
        <template v-else-if="workflowBomSyncPreflight.classification === 'AUTO_MIGRATABLE'" #default>
          系统将保留已有 BOM 内容，并把可唯一确定的原料入口迁移到当前 Workflow。
        </template>
        <template v-else-if="definition?.status === 'DRAFT' && activation?.enabled" #default>
          本次检查对象为草稿 v{{ definition.version }}；发布前生产仍使用 Workflow v{{ activation.activeDefinitionVersion }}。
        </template>
      </el-alert>

      <div ref="canvasRef" class="canvas-shell" :class="{ 'is-connecting': !!connectingFromKind }" v-loading="loading">
        <!-- #12b: 历史版本预览横幅 (只读, 不会自动保存覆盖草稿) -->
        <div v-if="previewingVersion !== null" class="version-preview-bar" data-testid="version-preview-bar">
          <span>正在预览历史版本 <strong>v{{ previewingVersion }}</strong>（只读，不会覆盖当前草稿）</span>
          <el-button size="small" type="primary" @click="restorePreviewAsDraft">恢复为当前草稿</el-button>
          <el-button size="small" @click="exitVersionPreview">退出预览</el-button>
        </div>
        <div
          v-if="!productTypeId && catalogLoading"
          data-testid="workflow-product-loading"
          aria-busy="true"
          aria-live="polite"
        >
          <el-skeleton :rows="5" animated />
          <span>正在加载目标产品与 Workflow…</span>
        </div>
        <el-empty v-else-if="!productTypeId" description="请先选择产品" :image-size="90" />
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
              :byproduct-options="byproductMaterialOptions"
              :unit-error="unitIssueForNode(slotProps.id)"
              :validation-error="publishBindingErrorForNode(slotProps.id)"
              :validation-attention="publishBindingAttentionNodeIds.has(slotProps.id)"
              @add-next="openAddProcess(slotProps.id)"
              @select-raw-sku="(skuId) => selectRawSku(slotProps.id, skuId)"
              @select-sku="(skuId) => selectMaterialSku(slotProps.id, skuId)"
              @select-byproduct-sku="(materialTypeId) => selectByproductSku(slotProps.id, materialTypeId)"
              @edit-sku="openQuickEditSku(slotProps.id)"
              @delete="removeNode(slotProps.id)"
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
              @add-byproduct="addOutputToProcess(slotProps.id, { byproduct: true })"
              @select-output="(portId, skuId) => selectOutputSku(slotProps.id, portId, skuId)"
              @edit-process="openQuickEditProcess(slotProps.id)"
              @delete="removeNode(slotProps.id)"
            />
          </template>

          <!-- BOM 浮层 cell(辅料/包材) —— 只读投影, 不属于工艺定义(见 bomOverlay.ts 顶部注释) -->
          <template #node-bomAuxiliary="slotProps">
            <WorkflowAuxiliaryNode
              :id="slotProps.id"
              :data="slotProps.data"
              :can-write="canEdit"
              @add-row="openAuxiliaryEditor(slotProps.data.processNodeId)"
              @edit-row="(rowId) => openAuxiliaryEditor(slotProps.data.processNodeId, rowId)"
              @open-detail="openAuxiliaryEditor(slotProps.data.processNodeId)"
            />
          </template>
          <template #node-bomPackaging="slotProps">
            <WorkflowPackagingNode
              :id="slotProps.id"
              :data="slotProps.data"
              :can-write="canEdit"
              @add-row="openPackagingEditor(slotProps.data.outputNodeId)"
              @edit-row="(rowId) => openPackagingEditor(slotProps.data.outputNodeId, rowId)"
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
        <el-form-item label="当前产出类型" required>
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
        <el-button type="primary" :loading="processEditSaving" data-testid="save-process-edit" @click="saveQuickEditProcess">保存</el-button>
      </template>
    </el-dialog>

    <!-- #Task6: 辅料编辑弹窗 —— 直接复用既有 SeasoningBindingDialog, 不新建编辑面 -->
    <SeasoningBindingDialog
      v-model="auxDialogVisible"
      :factory-id="auxDialogFactoryId"
      :recipe-id="auxDialogRecipeId"
      :process="auxDialogProcess"
      :binding="auxDialogBinding"
      :materials="bomOverlayMaterials"
      :substitute-relations="auxDialogSubstituteRelations"
      :revision="auxDialogRevision"
      @saved="onAuxDialogSaved"
    />

    <!-- #Task1(2026-08-05 bom-canvas-phase3-2): 画布上的包材编辑弹窗, 不再依赖 BOM 抽屉 -->
    <PackagingBindingDialog
      v-model="packagingDialogVisible"
      :factory-id="packagingDialogFactoryId"
      :recipe-id="packagingDialogRecipeId"
      :output-name="packagingDialogOutputName"
      :base-unit="packagingDialogBaseUnit"
      :row="packagingDialogRow"
      :materials="bomOverlayPackagingMaterials"
      :substitute-relations="packagingDialogSubstituteRelations"
      @saved="onPackagingDialogSaved"
    />

    <!--
      新增原料 Cell 的关系弹窗 (2026-08-10)。

      ⚠️ 刻意用**原生 div + v-if**, 不用 el-dialog:
      ① el-dialog 在 shallowMount 下是 stub, 槽内容压根不渲染 —— 测试就只能断言状态变量,
         而本轮已经栽过一次「CSS display 盖过 hidden 属性: 状态改了、弹窗关不掉,
         只看状态的测试全绿」。v-if 让「不存在」就是真的不在 DOM 里, 断言得到的是渲染结果。
      ② 确定/取消用原生 <button class="el-button">: 未解析组件上的 :disabled="false"
         会渲染成 disabled="false"(字符串, JS 里是真值), 原生按钮才会真的把属性摘掉。
      样式类沿用 el-button, 视觉与其余弹窗一致。

      ⛔ 不问换算系数 —— 生产用原料统一 kg, 几只一箱属于原料字典建档时的基本规格。
    -->
    <div
      v-if="rawInputDialog.visible"
      class="raw-input-mask"
      data-testid="raw-input-modal"
      @click.self="closeRawInputDialog"
    >
      <div class="raw-input-panel">
        <h3 class="raw-input-title">新增原料 Cell · {{ rawInputDialog.processName }}</h3>

        <div class="raw-input-field">
          <label class="raw-input-label">选择原料 SKU</label>
          <el-select
            v-model="rawInputDialog.skuId"
            filterable
            placeholder="从工厂原料档案选择"
            style="width: 100%"
            data-testid="raw-input-sku"
          >
            <el-option
              v-for="option in rawMaterialOptions"
              :key="option.id"
              :label="option.name"
              :value="option.id"
            />
          </el-select>
        </div>

        <div class="raw-input-field">
          <label class="raw-input-label">
            它和「{{ rawInputDialog.mainMaterialName }}」是什么关系
          </label>
          <div class="raw-input-relations">
            <label
              v-for="option in RAW_INPUT_RELATIONS"
              :key="option.value"
              class="raw-input-relation"
              :class="{ 'is-active': rawInputDialog.relation === option.value }"
            >
              <input
                v-model="rawInputDialog.relation"
                type="radio"
                name="raw-input-relation"
                :value="option.value"
                :data-testid="`raw-input-relation-${option.value}`"
              />
              <span class="raw-input-relation-title">{{ option.title }}</span>
              <span class="raw-input-relation-hint">{{ option.hint }}</span>
            </label>
          </div>
        </div>

        <div class="raw-input-actions">
          <button
            type="button"
            class="el-button"
            data-testid="raw-input-cancel"
            @click="closeRawInputDialog"
          >取消</button>
          <button
            type="button"
            class="el-button el-button--primary"
            :disabled="!rawInputDialogReady"
            data-testid="raw-input-confirm"
            @click="confirmAddRawInput"
          >确定</button>
        </div>
      </div>
    </div>

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
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
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
import { useRouter } from 'vue-router';
import { get, post, put } from '@/api/request';
import { getUnitCatalog, type UnitCatalogItem } from '@/api/unitContract';
import {
  createWorkProcess,
  getActiveWorkProcesses,
  getWorkProcessCategories,
  getProductWorkProcesses,
  updateWorkProcess,
  type WorkProcessItem,
  type WorkProcessOutputMaterialKind,
} from '@/api/processProduction';
import UnitSelect from '@/components/common/UnitSelect.vue';
import WorkProcessAIChatPanel from '@/views/system/components/WorkProcessAIChatPanel.vue';
import WorkflowMaterialNode from './WorkflowMaterialNode.vue';
import WorkflowProcessNode from './WorkflowProcessNode.vue';
import WorkflowAuxiliaryNode from './WorkflowAuxiliaryNode.vue';
import WorkflowPackagingNode from './WorkflowPackagingNode.vue';
import {
  buildRawMaterialSegmentTree,
  isRawMaterialOption,
  resolveRawMaterialByExactName,
  type MaterialSegmentNode,
  type RawMaterialPickerOption,
} from './rawMaterialCatalog';
import { classifyOutputSkuCategory, matchOutputSkuByName } from './outputSkuClassification';
import { needsPrimaryOutputKindUpdate } from './processOutputKindCompatibility';
import { usePinyinFilter } from './pinyinInitials';
import { classifyCanvasTopology, terminalOutputLabels } from './workflowClassification';
import {
  deriveBomOverlay,
  isDerivedBomOverlayConnection,
  isBomOverlayEdge,
  isBomOverlayNode,
  stripBomOverlay,
  stripBomOverlayEdges,
  type BomOverlayAuxiliaryInput,
  type BomOverlayPackagingInput,
  type BomOverlaySourceNode,
  type BomOverlaySourceNodeData,
} from './bomOverlay';
import {
  buildDraftBomNotice,
  isWritableRecipe,
  pickEditableRecipe,
  pickOwningProductId,
} from './bomEditableRecipe';
import type { DraftBomNotice } from './bomEditableRecipe';
import type { WorkflowMaterialBinding, WorkflowPackagingBinding } from './types';
import {
  allowsInjection,
  allowsPotRatio,
  isValidDosagePerKgG,
  isValidInjectionAmount,
  isValidSubsequentPotRatio,
} from './seasoningProcessCategory';
import { createBomDraftEnsurer } from './bom/bomDraftLifecycle';
import { markersForAuxiliaryRow, markersForPackagingRow } from './bomOverlayMarkers';
import type { AuxiliaryCellRow, PackagingCellRow } from './bomOverlayTypes';
import type { ByproductMaterialOption } from './WorkflowMaterialNode.vue';
import type { BomRowMarker } from './bomOverlayMarkers';
import {
  formatAuxiliaryDosageText,
  formatPackagingDosageText,
  formatPackagingNaturalHint,
} from './bomOverlayRowFormat';
import {
  bomRecipeApi,
  bomSeasoningApi,
  type BomItemSubstituteView,
  type BomRecipeItemView,
  type BomRecipeSummary,
  type SeasoningBindingView,
  type SeasoningProcessView,
  type SeasoningWorkspace,
} from '@/api/bom';
import { bigCategoryOf } from '@/utils/materialCategory';
import {
  selectAuxiliaryMaterials,
  selectByproductMaterials,
  selectPackagingMaterials,
  type BomOverlayMaterialRow,
} from './bomOverlayMaterialSources';
import SeasoningBindingDialog, { type SeasoningMaterialOption } from './bom/SeasoningBindingDialog.vue';
import PackagingBindingDialog, { type PackagingMaterialOption, type PackagingRowPayload } from './PackagingBindingDialog.vue';
import {
  activateProductProcessWorkflow,
  deactivateProductProcessWorkflow,
  getWorkflowBomSyncPreflight,
  getProductProcessWorkflow,
  getProductProcessWorkflowActivation,
  getProductProcessWorkflowVersion,
  listProductProcessWorkflowVersions,
  publishAndActivateProductProcessWorkflow,
  saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow,
  type WorkflowVersionSummary,
} from './workflowApi';
import {
  resolveWorkflowPublishCommand,
  type WorkflowPublishCommand,
} from './workflowPublishCommand';
import {
  canPublishWorkflowWithBomSync,
  executeWorkflowPublishMutation,
  workflowBomSyncBlockingIssues,
  workflowBomSyncStatusTitle,
} from './workflowPublishGate';
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
  WorkflowBomSyncPreflight,
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
  workflowId?: number | null;
  workflowDefinitionVersion?: number | null;
  workflowRevisionId?: number | null;
  workflowRevisionHash?: string | null;
}

interface BomProductTarget {
  id: string;
  name: string;
}

interface ActiveBomRevision {
  version?: number | null;
  workflowId?: number | null;
  workflowDefinitionVersion?: number | null;
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

const router = useRouter();
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
const workflowBomSyncPreflight = ref<WorkflowBomSyncPreflight | null>(null);
const workflowBomSyncIssues = computed(() => (
  workflowBomSyncBlockingIssues(workflowBomSyncPreflight.value)
));
const workflowBomSyncBlocked = computed(() => workflowBomSyncIssues.value.length > 0);
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
let autoSaveGeneration = 0;
let autoSaveBarrierDepth = 0;
// #11 fix: 每次本地改动 +1; 保存时对比, 若 PUT 往返期间有新改动则不 hydrate 覆盖 (防丢在途编辑)
let editSeq = 0;
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
/**
 * 新增原料 Cell 的关系弹窗 (2026-08-10)。
 *
 * 两个选项对下游的算法完全不同, 所以必须问清:
 * - 替代料 · 投其一 → 顶替主料, 两者只投一个。合成一个逻辑投入, 不重复计入需求与成本。
 * - 另一种投入 · 都要投 → 各自是独立需求。逻辑投入 +1。
 *
 * ⛔ 不问换算系数 —— 生产用原料统一 kg (spec D8)。
 */
const RAW_INPUT_RELATIONS = [
  {
    value: 'SUBSTITUTE',
    title: '替代料 · 投其一',
    hint: '顶替主料，两者只投一个。合成一个逻辑投入，不重复计入需求与成本。',
  },
  {
    value: 'PARALLEL',
    title: '另一种投入 · 都要投',
    hint: '各自是独立需求。逻辑投入 +1。',
  },
] as const;
const rawInputDialog = reactive({
  visible: false,
  processId: '',
  processName: '',
  mainMaterialNodeId: '',
  mainMaterialName: '',
  skuId: '',
  relation: '' as '' | 'SUBSTITUTE' | 'PARALLEL',
});
/** 两项都选齐才能确定 —— 关系选项没有默认值, 默认值等于替用户做了决定。 */
const rawInputDialogReady = computed(
  () => Boolean(rawInputDialog.skuId) && Boolean(rawInputDialog.relation),
);
const unitCatalog = ref<UnitCatalogItem[]>([]);
// #3: 该产品 BOM 原辅料清单 (per-product, 随 productTypeId 变化而重新加载,
// 与 loadCatalogs 的"全厂字典"缓存粒度不同, 单独一个 ref + 单独一个 loader)。
const productBomItems = ref<BomRecipeItemOption[]>([]);
const bomMissingProducts = ref<BomProductTarget[]>([]);
const activeBomByProduct = ref<Record<string, ActiveBomRevision>>({});
const bomProductionMismatchProducts = computed<BomProductTarget[]>(() => {
  const activeWorkflowId = activation.value?.enabled
    ? activation.value.activeWorkflowId
    : null;
  const activeDefinitionVersion = activation.value?.enabled
    ? activation.value.activeDefinitionVersion
    : null;
  if (activeWorkflowId == null || activeDefinitionVersion == null) return [];

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
      const workflowIdMismatch = activeBom.workflowId != null
        && activeBom.workflowId !== activeWorkflowId;
      const definitionVersionMismatch = activeBom.workflowDefinitionVersion != null
        && activeBom.workflowDefinitionVersion !== activeDefinitionVersion;
      return workflowIdMismatch || definitionVersionMismatch;
    });
});
const bomRawMaterialIdList = computed(() => productBomItems.value.map((item) => item.materialTypeId));

// #Task6: BOM 浮层 cell(辅料/包材)数据 —— 与上面 productBomItems(扁平 id 清单,
// 只服务原料 picker)是并行的另一份数据, 保留分组(按工序/按产出)与展示字段,
// 专供 deriveBomOverlay 派生画布浮层节点, 详见 loadBomOverlayData()。
const bomOverlayAuxiliaryByProcess = ref<Record<string, BomOverlayAuxiliaryInput>>({});
const bomOverlayPackagingByOutput = ref<Record<string, BomOverlayPackagingInput>>({});
// 辅料编辑入口(直接复用 SeasoningBindingDialog)需要知道某个工序归哪个 recipe;
// 一个 workflow 可能有多个终端产出(=多个 recipe)共享同一批工序节点, 这里按
// "第一个覆盖该工序的 recipe 生效"简化 —— 联合生产场景下若不同 recipe 对同一
// 工序有不同辅料集合, 只展示/编辑第一个命中的, 已在 loadBomOverlayData 里注释。
const bomOverlayRecipeIdByProcess = ref<Record<string, string>>({});
// recipeId → 该版本能否直接写入(仅 DRAFT 可写)。生效版只能看, 落笔前必须先 ensureDraft。
const bomOverlayRecipeWritable = ref<Record<string, boolean>>({});
// recipeId → 归属产品 id, ensureDraft 需要按产品建草稿。
const bomOverlayProductIdByRecipe = ref<Record<string, string>>({});
/** 画布当前展示的是草稿的那些产品; 非空即代表「所见 ≠ 产线在跑的」。 */
const bomDraftNotices = ref<DraftBomNotice[]>([]);
const activatingBomDraft = ref(false);

/**
 * 生效是影响生产的动作(之后新建的生产计划会读新快照), 与 BOM 页一样先确认再执行。
 * 不在这里自己判「能不能生效」—— 后端做权威校验, 前端复述一遍只会两处口径打架。
 */
async function activateBomDraft(notice: DraftBomNotice | undefined): Promise<void> {
  if (!notice || activatingBomDraft.value) return;
  try {
    await ElMessageBox.confirm(
      `激活后 ${notice.productName} 草稿 v${notice.draftVersion ?? '-'} 将成为唯一生效 BOM。`
      + '仅之后新建的生产计划采用此版本；已有生产计划快照不受影响。确认生效？',
      '生效 BOM 草稿',
      { confirmButtonText: '生效', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }

  activatingBomDraft.value = true;
  try {
    const response = await bomRecipeApi.activate(props.factoryId, notice.recipeId);
    if (!response?.success) {
      ElMessage.error(response?.message || '生效失败，请到 BOM 页查看具体原因');
      return;
    }
    ElMessage.success(`${notice.productName} 草稿已生效`);
    await loadBomOverlayData();
    await loadProductBom({ force: true });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生效失败，请稍后重试');
  } finally {
    activatingBomDraft.value = false;
  }
}

/**
 * 生效版不可写, 但用户在画布上点「+ 加辅料」时不该被甩去别的页面自己克隆。
 * ensureDraft 会复用已有草稿(不是每次都克隆), 所以重复点击不会撑爆版本上限。
 * 拿到草稿后必须重载浮层 —— 否则弹窗拿到的行 id 仍属于旧版本。
 */
const ensureBomDraftRecipe = createBomDraftEnsurer(
  bomRecipeApi.ensureDraft,
  async (draft) => { await loadBomOverlayData({ preferredDraft: draft }); },
);

/**
 * 该工序的辅料记到哪个成品的 BOM 上。有 BOM 时靠 recipe 反查; 冷启动(零版本)时
 * 没有 recipe 可查, 从图上的终端产出反推 —— 见 pickOwningProductId 的取舍说明。
 */
function resolveProductForProcess(processNodeId: string): string | null {
  const viaRecipe = bomOverlayRecipeIdByProcess.value[processNodeId];
  if (viaRecipe) return bomOverlayProductIdByRecipe.value[viaRecipe] ?? null;
  return pickOwningProductId(
    flowNodes.value
      .filter((node) => node.data?.kind === 'FINISHED_GOOD')
      .map((node) => ({ skuId: node.data?.skuId == null ? null : String(node.data.skuId) })),
  );
}

/** 返回可写的 recipeId; 当前解析到的若非草稿, 先就地建/取草稿再重载浮层。 */
async function resolveWritableRecipeId(
  productTypeId: string,
  recipeId: string | undefined,
  reread: () => string | undefined,
): Promise<string | null> {
  // recipeId 为空 = 该产品一条 BOM 版本都没有(冷启动)。与「有版本但不是草稿」一样,
  // 都靠 ensureDraft 收敛成一个可写草稿 —— 不必让用户先跑去别的页面建首版。
  if (recipeId && bomOverlayRecipeWritable.value[recipeId]) return recipeId;
  try {
    // 必须钉到画布当前 revision。省略它时，后端会按默认 Workflow 建草稿；当前正在
    // 编辑另一版草稿时，返回 workspace 的 processNodeId 对不上画布，详情入口就会
    // 误报“草稿已创建但未刷新”。
    await ensureBomDraftRecipe(props.factoryId, productTypeId, definition.value?.revisionId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法创建 BOM 草稿，请稍后重试');
    return null;
  }
  // 重载后浮层已指向草稿, 重新读一次映射, 不要沿用旧 id。
  const refreshed = reread();
  if (!refreshed || !bomOverlayRecipeWritable.value[refreshed]) {
    ElMessage.error('BOM 草稿已创建，但画布未刷新到草稿版本，请手动刷新后重试');
    return null;
  }
  return refreshed;
}
const bomOverlaySeasoningWorkspaceByRecipe = ref<Record<string, SeasoningWorkspace>>({});
const bomOverlaySubstitutesByRecipe = ref<Record<string, BomItemSubstituteView[]>>({});
/**
 * 副产 cell 的可选物料。空列表**不是**「加载中」—— 见 WorkflowMaterialNode 的空态文案:
 * 档案里一个物料都没勾「这是副产」时, 必须给出去处而不是一个空下拉(防呆规则 5)。
 */
const byproductMaterialOptions = ref<ByproductMaterialOption[]>([]);
const byproductMaterialsLoaded = ref(false);
const bomOverlayMaterials = ref<SeasoningMaterialOption[]>([]);
const bomOverlayMaterialsLoaded = ref(false);
// #Task1(2026-08-05 bom-canvas-phase3-2): 包材编辑弹窗需要知道某个终端产出归哪个 recipe
// (镜像上面 bomOverlayRecipeIdByProcess 给辅料弹窗用的做法), 以及该产出当前的完整
// PACKAGING 明细原始行(不是 bomOverlayPackagingByOutput 里那份只供展示用的 PackagingCellRow
// —— 那份没有 materialTypeId/standardQuantity/替代关系等编辑必需字段)。
const bomOverlayRecipeIdByOutput = ref<Record<string, string>>({});
const bomOverlayPackagingRawByOutput = ref<Record<string, BomRecipeItemView[]>>({});
// 副产: 同样两份 —— 展示用行 + 编辑用原始行。
const bomOverlayPackagingMaterials = ref<PackagingMaterialOption[]>([]);
const bomOverlayPackagingMaterialsLoaded = ref(false);
let bomOverlayLoadGeneration = 0;
// 辅料编辑弹窗(直接挂 SeasoningBindingDialog, 不新建编辑面 —— 见 task-6-brief.md)
const auxDialogVisible = ref(false);
const auxDialogFactoryId = ref('');
const auxDialogRecipeId = ref('');
const auxDialogProcess = ref<SeasoningProcessView | null>(null);
const auxDialogBinding = ref<SeasoningBindingView | null>(null);
const auxDialogRevision = ref(0);
const auxDialogSubstituteRelations = computed<BomItemSubstituteView[]>(() => {
  if (!auxDialogBinding.value) return [];
  const all = bomOverlaySubstitutesByRecipe.value[auxDialogRecipeId.value] || [];
  return all.filter((relation) =>
    relation.parentKind === 'SEASONING_ITEM' && relation.parentSeasoningItemId === auxDialogBinding.value!.id);
});
// 包材编辑弹窗(#Task1: 画布上直接挂 PackagingBindingDialog, 不再打开 BOM 抽屉 —— 见
// task-1-brief.md「拆抽屉之前必须先有它」)。
const packagingDialogVisible = ref(false);
const packagingDialogFactoryId = ref('');
const packagingDialogRecipeId = ref('');
const packagingDialogOutputName = ref('');
const packagingDialogBaseUnit = ref('');
const packagingDialogRow = ref<PackagingRowPayload | null>(null);
const packagingDialogSubstituteRelations = computed<BomItemSubstituteView[]>(() => {
  if (!packagingDialogRow.value) return [];
  const all = bomOverlaySubstitutesByRecipe.value[packagingDialogRecipeId.value] || [];
  return all.filter((relation) =>
    relation.parentKind === 'RECIPE_ITEM' && relation.parentRecipeItemId === packagingDialogRow.value!.id);
});
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
let lastBomLoadKey = '';
let saveGeneration = 0;
let publishGeneration = 0;
let previousPublishCommand: WorkflowPublishCommand | null = null;
let activationLoadGeneration = 0;
let activationMutationGeneration = 0;

const productTypeId = computed(() => props.productTypeId);
const rawOwnerMode = computed(() => props.rawOwnerMode === true);
const derivedWorkflowClassification = computed(() => classifyCanvasTopology(
  // ⛔ 浮层节点(辅料/包材 cell)不是画布拓扑的一部分, 混进去会把
  // rootInputCount/terminalOutputCount 算错, 让「系统研判」标签失真。
  stripBomOverlay(flowNodes.value),
  // 边也要剥。包材浮层边是「真实产出 → bom-overlay:pack:x」, 不剥的话那个真实产出
  // 会被算进 outgoing, classifyWorkflowTopology 的 !outgoing.has(id) 就把它排除出
  // 终端产出计数 —— 结构完整的工艺会被研判成 INCOMPLETE。只剥节点不剥边等于没剥。
  // ⛔ node.data → 拓扑入参的映射搬进了 classifyCanvasTopology, 那里有吃真实 node.data
  // 的用例。留在这里的话「分类器认得字段 / 画布传不到字段」这种断层没有任何测试照得出。
  stripBomOverlayEdges(flowEdges.value),
));
/** 顶部「本图产出：A、B」—— 研判结论驱动, 归属对象(存放位置)不参与。 */
const terminalOutputNames = computed(() => terminalOutputLabels(
  stripBomOverlay(flowNodes.value),
  derivedWorkflowClassification.value,
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
  && !publishing.value
  && !publishConfirming.value
  && previewingVersion.value === null   // #12b 预览历史版本时整个画布只读 (不可拖/存/发布, 防止旧版本被当草稿保存发布)
  && loadedCatalogFactoryId.value === props.factoryId
  && loadedDefinitionIdentity.value?.factoryId === props.factoryId
  && loadedDefinitionIdentity.value?.productTypeId === props.productTypeId
));
const currentDefinitionIsEnabled = computed(() => (
  definition.value?.status === 'PUBLISHED'
  && activation.value?.enabled === true
  && activation.value.activeWorkflowId === definition.value.id
  && activation.value.activeDefinitionVersion === definition.value.version
));
const publishActionCompleted = computed(() => currentDefinitionIsEnabled.value && !dirty.value);
const publishActionLabel = computed(() => (
  publishActionCompleted.value ? '已发布并启用' : '自动同步并发布'
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
  await loadEditorWorkspace(true);
});

onUnmounted(() => {
  window.removeEventListener('keydown', onEditorKeydown);
  invalidatePendingAutoSave();
  gsapCtx?.revert();
  gsapCtx = null;
});

async function loadEditorWorkspace(loadFactoryCatalogs: boolean): Promise<void> {
  const catalogPromise = loadFactoryCatalogs || loadedCatalogFactoryId.value !== props.factoryId
    ? loadCatalogs()
    : Promise.resolve();

  // 原料 owner 的根节点名称和单位来自原料目录；此模式仍先拿到目录，避免错误回退。
  if (rawOwnerMode.value) await catalogPromise;
  await Promise.all([loadDefinition(), loadActivation()]);

  // 普通产品的画布定义先出现；完整编辑能力在目录就绪后自动开放。
  if (!rawOwnerMode.value) await catalogPromise;
  await loadProductBom();
  if (projectUniqueRawMaterialBindings()) refreshPortMaterialMetadata();
  await loadBomOverlayData();
}

/**
 * Fill the real identity of legacy/free-text raw Cells after the catalog and
 * current product BOM have loaded. This is an in-memory hydration projection:
 * it intentionally does not call mutate(), set dirty, or schedule autosave.
 * A later explicit save/publish persists the already-visible identity.
 */
function projectUniqueRawMaterialBindings(): boolean {
  let changed = false;
  flowNodes.value.forEach((node) => {
    if (node.data?.kind !== 'RAW_MATERIAL' || String(node.data?.skuId || '')) return;
    const option = resolveRawMaterialByExactName(
      String(node.data?.name || ''),
      rawMaterialOptions.value,
      bomRawMaterialIdList.value,
    );
    if (!option) return;
    Object.assign(node.data, {
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      baseUnit: workflowReportingUnit('RAW_MATERIAL', option.unit || String(node.data?.baseUnit || 'kg')),
      bound: true,
    });
    changed = true;
  });
  return changed;
}

// #11: 每次操作后防抖 ~2.5s 自动保存 (静默 + 保留撤销栈)。用户停手 2.5s 即存;
// 连续操作只会在最后一次后触发。服务端失败后不自动重试同一编辑，避免 500 通知风暴；
// 用户再次编辑会重新排期，手动“保存草稿”也可立即重试。
const AUTO_SAVE_DELAY = 2500;
function invalidatePendingAutoSave(): void {
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  autoSaveTimer = null;
  autoSaveGeneration += 1;
}

async function withAutoSaveBarrier(operation: () => Promise<void>): Promise<void> {
  autoSaveBarrierDepth += 1;
  invalidatePendingAutoSave();
  try {
    await operation();
  } finally {
    invalidatePendingAutoSave();
    autoSaveBarrierDepth -= 1;
    if (autoSaveBarrierDepth === 0
      && dirty.value
      && previewingVersion.value === null
      && canEdit.value) {
      scheduleAutoSave();
    }
  }
}

function scheduleAutoSave(): void {
  if (autoSaveBarrierDepth > 0) return;
  if (autoSaveTimer) clearTimeout(autoSaveTimer);
  const scheduledGeneration = autoSaveGeneration;
  autoSaveTimer = setTimeout(async () => {
    autoSaveTimer = null;
    if (scheduledGeneration !== autoSaveGeneration || autoSaveBarrierDepth > 0) return;
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
  () => { void loadProductBom(); void loadBomOverlayData(); },
);

async function waitForWorkflowSave(timeoutMs = 5000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (saving.value && Date.now() < deadline) {
    await new Promise<void>((resolve) => window.setTimeout(resolve, 50));
  }
  return !saving.value;
}

// 2026-08-07: goToBomManagement 已删除。BOM 就在本画布的辅料 / 包材 cell 上,
// 旧 BOM 菜单入口 8-05 已摘, 画布内也不再保留任何跳去那个页面的通道。
// 反向断言见 __tests__/legacyBomEntryRetired.source.spec.ts。

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
  workflowBomSyncPreflight.value = null;
  previewingVersion.value = null;   // #12b fix: 切产品必退出历史版本预览 (否则横幅粘住 + 新产品自动保存被抑制)
  await loadEditorWorkspace(next[0] !== previous[0]);
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
  workflowBomSyncPreflight.value = null;
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

function canonicalizeWorkflowValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => canonicalizeWorkflowValue(item));
  }
  if (value !== null && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((result, key) => {
        result[key] = canonicalizeWorkflowValue(
          (value as Record<string, unknown>)[key],
        );
        return result;
      }, {});
  }
  return value;
}

function samePersistedWorkflowGraph(
  left: ProductProcessWorkflowDefinition,
  right: ProductProcessWorkflowDefinition,
): boolean {
  return JSON.stringify(canonicalizeWorkflowValue({
    schemaVersion: left.schemaVersion,
    nodes: left.nodes,
    edges: left.edges,
    viewport: left.viewport,
  })) === JSON.stringify(canonicalizeWorkflowValue({
    schemaVersion: right.schemaVersion,
    nodes: right.nodes,
    edges: right.edges,
    viewport: right.viewport,
  }));
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
async function loadProductBom(options: { force?: boolean } = {}): Promise<void> {
  const factoryId = props.factoryId;
  const ownerId = props.productTypeId;
  const graphOutputs = flowNodes.value
    .filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId)
    .map((node) => ({ id: String(node.data.skuId), name: String(node.data.name || node.data.skuId) }));
  // BOM 目标由画布产出决定；只有旧图尚无产出时才用非原料锚点兼容加载。
  const targets = graphOutputs.length > 0
    ? graphOutputs
    : rawOwnerMode.value ? [] : [{ id: ownerId, name: props.productName || ownerId }];
  const uniqueTargets = targets.filter((target, index, list) =>
    list.findIndex((candidate) => candidate.id === target.id) === index);
  const loadKey = `${factoryId}:${ownerId}:${uniqueTargets.map((target) => target.id).sort().join(',')}`;
  if (!options.force && loadKey === lastBomLoadKey) return;
  lastBomLoadKey = loadKey;
  const generation = ++bomLoadGeneration;
  productBomItems.value = [];
  bomMissingProducts.value = [];
  activeBomByProduct.value = {};
  if (!factoryId || !ownerId) return;
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
          workflowId: response.data.workflowId,
          workflowDefinitionVersion: response.data.workflowDefinitionVersion,
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

/**
 * 加载画布上辅料/包材 cell 需要的 BOM 数据 —— 按终端产出(FINISHED_GOOD 节点的
 * skuId)逐个拿当前生效配方(bomRecipeApi.getCurrentByProduct), 从其中筛出
 * PACKAGING 类目行做包材 cell; 再按拿到的 recipeId 逐个拉工序调料工作台
 * (bomSeasoningApi.getWorkspace)与替代物料关系(bomRecipeApi.listSubstitutes),
 * 供辅料 cell 与「打开辅料详情」编辑弹窗共用同一份数据, 避免打开弹窗时二次请求。
 *
 * 完成后调用 refreshBomOverlay() 把结果注入画布 —— hydrate() 内部也会调用它
 * (用当时已缓存的数据), 两处配合: hydrate 保证"浮层结构一定在", 这里保证
 * "浮层内容随 BOM 数据变化刷新"。
 */
/**
 * @param options.afterUserEdit 这次重载是不是**用户刚改完辅料/包材**触发的。
 *   ⛔ 这个参数不是可有可无的开关, 它决定「改克数会不会产生新工艺版本」:
 *   加载期 hydrate 不能置 dirty(否则打开一张图就变成有未保存改动),
 *   但用户改完之后必须置 —— 否则数据进了定义、hash 也覆盖它, 却因为没人
 *   把图标记成 dirty 而永远不会被保存成新版本。两头都对、中间断掉。
 */
async function loadBomOverlayData(options: {
  afterUserEdit?: boolean;
  /** ensure-draft 刚返回的精确草稿。版本列表 GET 可能仍命中旧响应，不能因此丢掉它。 */
  preferredDraft?: BomRecipeSummary;
} = {}): Promise<void> {
  const factoryId = props.factoryId;
  const finishedNodes = flowNodes.value.filter((node) => node.data?.kind === 'FINISHED_GOOD' && node.data?.skuId);
  const targets = finishedNodes.map((node) => ({ nodeId: node.id, skuId: String(node.data.skuId) }));
  const uniqueSkuIds = [...new Set(targets.map((target) => target.skuId))];
  const generation = ++bomOverlayLoadGeneration;
  const stillCurrent = () => generation === bomOverlayLoadGeneration && props.factoryId === factoryId;

  if (!factoryId || uniqueSkuIds.length === 0) {
    bomOverlayAuxiliaryByProcess.value = {};
    bomOverlayPackagingByOutput.value = {};
    bomOverlayRecipeIdByProcess.value = {};
    bomOverlayProductIdByRecipe.value = {};
    bomDraftNotices.value = [];
    bomOverlayRecipeWritable.value = {};
    bomOverlaySeasoningWorkspaceByRecipe.value = {};
    bomOverlaySubstitutesByRecipe.value = {};
    bomOverlayRecipeIdByOutput.value = {};
    bomOverlayPackagingRawByOutput.value = {};
    refreshBomOverlay();
    return;
  }

  try {
    // ⛔ 不能用 getCurrentByProduct: 它按后端契约只返回 ACTIVE+is_current, 而
    // 调料/包材写入只接受 DRAFT —— 格子照着生效版渲染再往生效版写, prod 实测恒 409。
    // 详见 bomEditableRecipe.ts。这里取全部版本自行择一, 保证「显示的」与「写入的」
    // 是同一条记录(否则编辑既有行时, 行 id 属于另一个版本)。
    const recipeResponses = await Promise.all(uniqueSkuIds.map(async (skuId) => {
      try {
        const response = await bomRecipeApi.getVersionsByProduct(factoryId, skuId);
        const versions = response?.success ? [...(response.data ?? [])] : [];
        if (options.preferredDraft?.productTypeId === skuId
          && !versions.some((version) => version.id === options.preferredDraft?.id)) {
          versions.unshift(options.preferredDraft);
        }
        return { skuId, recipe: pickEditableRecipe(versions), versions };
      } catch {
        const preferred = options.preferredDraft?.productTypeId === skuId
          ? options.preferredDraft
          : null;
        return { skuId, recipe: preferred, versions: preferred ? [preferred] : [] };
      }
    }));
    if (!stillCurrent()) return;

    const packagingByOutput: Record<string, BomOverlayPackagingInput> = {};
    const recipeIdBySku: Record<string, string> = {};
    const recipeIdByOutput: Record<string, string> = {};
    const productIdByRecipe: Record<string, string> = {};
    const packagingRawByOutput: Record<string, BomRecipeItemView[]> = {};
    // ⛔ 这两个必须声明在下面那个 forEach **之前**。
    // 2026-08-08 事故: `packagingBindingsByOutput` 原本声明在 auxiliaryByProcess 旁边
    // (即这个 forEach 之后), 而 forEach 里第 1 个包材赋值就引用它 —— const 的 TDZ
    // 让它抛 `ReferenceError: Cannot access ... before initialization`, 被本函数末尾的
    // catch 吞成一行 console.error。后果是**整个 BOM 浮层加载全灭**: 辅料/包材 cell 全空、
    // hydrate 从不执行, 于是「改克数产生新工艺版本」在真机上恒定是断的。
    // TypeScript 抓不到这一类: 引用写在闭包里, 编译器假定它延后执行, 不报 TS2448。
    // 闸见 __tests__/noUseBeforeDefine.spec.ts —— 它按源码顺序判, 与执行时机无关。
    const materialBindingsByProcess: Record<string, WorkflowMaterialBinding[]> = {};
    const packagingBindingsByOutput: Record<string, WorkflowPackagingBinding[]> = {};
    // must-fix #8: 联合生产标注要报"当前实际生效的是哪个产出的配方" —— 用产出名做人话
    // 标签(配方本身没有独立的展示名), 按 recipeId 建索引, 供下面 auxiliaryByProcess 用。
    const outputNameByRecipe: Record<string, string> = {};
    const draftNotices: DraftBomNotice[] = [];
    recipeResponses.forEach(({ skuId, recipe, versions }) => {
      if (!recipe?.id) return;
      const outputNodeForNotice = finishedNodes.find((node) => String(node.data.skuId) === skuId);
      const notice = buildDraftBomNotice(
        skuId,
        String(outputNodeForNotice?.data.name ?? '未命名产出'),
        versions,
      );
      if (notice) draftNotices.push(notice);
      recipeIdBySku[skuId] = recipe.id;
      bomOverlayRecipeWritable.value[recipe.id] = isWritableRecipe(recipe);
      // ensureDraft 要按产品建草稿, 所以得能从 recipeId 反查它归哪个产品。
      productIdByRecipe[recipe.id] = skuId;
      const outputNode = finishedNodes.find((node) => String(node.data.skuId) === skuId);
      outputNameByRecipe[recipe.id] = String(outputNode?.data.name ?? '未命名产出');
      // 禁止降级处理: 基本单位缺失时占位「未配」, 不能拼出 "0.05 个/undefined" 这种半成品字符串。
      const outputBaseUnit = String(outputNode?.data.baseUnit ?? '未配');
      const packagingItems = (recipe.items || []).filter((item) => item.materialCategory === 'PACKAGING');
      const rows: PackagingCellRow[] = packagingItems.map((item) => ({
        id: String(item.id),
        materialName: item.materialName || '未命名物料',
        dosageText: formatPackagingDosageText(item.standardQuantity, item.unit, outputBaseUnit),
        naturalHint: formatPackagingNaturalHint(item.standardQuantity, item.unit, outputBaseUnit),
        markers: markersForPackagingRow({
          substituteCount: item.substituteDetails?.length ?? 0,
          isOptional: item.isOptional === true,
          perPortion: item.perPortion === true,
          packagingSpecId: item.packagingSpecId ?? null,
          packagingSpecNameSnapshot: item.packagingSpecNameSnapshot ?? null,
        }),
      }));
      targets.filter((target) => target.skuId === skuId).forEach((target) => {
        packagingByOutput[target.nodeId] = { rows };
        recipeIdByOutput[target.nodeId] = recipe.id;
        // #Task1: 包材编辑弹窗的 row prop 需要完整原始行(materialTypeId/standardQuantity/
        // 替代关系等), 不是上面 rows 里那份只供展示用的 PackagingCellRow。
        packagingRawByOutput[target.nodeId] = packagingItems;
        // 2026-08-08: 包材的**权威数值**收下来, 稍后 hydrate 进终端产出节点。
        // 同 materialBindings: rows 里那份是展示串(dosageText), 数值已经丢了, 不能当数据。
        packagingBindingsByOutput[target.nodeId] = packagingItems
          .filter((item) => item.materialTypeId != null && item.materialTypeId !== ''
            && typeof item.standardQuantity === 'number')
          .map((item) => ({
            materialTypeId: String(item.materialTypeId),
            materialName: item.materialName || null,
            standardQuantity: item.standardQuantity as number,
            ...(item.unit ? { unit: item.unit } : {}),
          }));
      });
    });

    const uniqueRecipeIds = [...new Set(Object.values(recipeIdBySku))];
    const [workspaceResponses, substitutesResponses] = await Promise.all([
      Promise.all(uniqueRecipeIds.map(async (recipeId) => {
        try {
          return { recipeId, response: await bomSeasoningApi.getWorkspace(factoryId, recipeId) };
        } catch {
          return { recipeId, response: null };
        }
      })),
      Promise.all(uniqueRecipeIds.map(async (recipeId) => {
        try {
          return { recipeId, response: await bomRecipeApi.listSubstitutes(factoryId, recipeId) };
        } catch {
          return { recipeId, response: null };
        }
      })),
    ]);
    if (!stillCurrent()) return;

    const substitutesByRecipe: Record<string, BomItemSubstituteView[]> = {};
    substitutesResponses.forEach(({ recipeId, response }) => {
      substitutesByRecipe[recipeId] = response?.success && Array.isArray(response.data) ? response.data : [];
    });

    const auxiliaryByProcess: Record<string, BomOverlayAuxiliaryInput> = {};
    // materialBindingsByProcess / packagingBindingsByOutput 已提到上面那个 forEach 之前声明 ——
    // 别搬回来, 搬回来就是 TDZ (见上面的事故注释)。
    const recipeIdByProcess: Record<string, string> = {};
    const workspaceByRecipe: Record<string, SeasoningWorkspace> = {};
    // must-fix #8: 统计每个工序节点被多少份不同 recipe 引用到 —— 独立于下面的
    // "first recipe wins" 判重, 覆盖判重发生之后仍要继续数, 否则统计会在第一份就停。
    const recipeIdsByProcessNode: Record<string, Set<string>> = {};
    workspaceResponses.forEach(({ recipeId, response }) => {
      if (!response?.success || !response.data) return;
      workspaceByRecipe[recipeId] = response.data;
      const relations = substitutesByRecipe[recipeId] || [];
      response.data.processes.forEach((process) => {
        const nodeId = process.workflowProcessNodeId;
        (recipeIdsByProcessNode[nodeId] ??= new Set<string>()).add(recipeId);
        // 联合生产(多终端产出共享同批工序节点)时, 第一个覆盖该工序的 recipe 生效 ——
        // 简化: 不同 recipe 对同一工序理应配同一批辅料, 若确有分歧只展示/编辑先命中的那份。
        if (auxiliaryByProcess[nodeId]) return;
        recipeIdByProcess[nodeId] = recipeId;
        // 阶段 3: 把**权威字段**收下来, 稍后 hydrate 进工序节点的 data.materialBindings。
        // 上面 rows 里那份是展示用的(dosageText 已经拼成字符串, 丢了数值), 不能拿来当数据。
        materialBindingsByProcess[nodeId] = process.bindings
          .filter((binding) => binding.materialTypeId != null && binding.materialTypeId !== '')
          .map((binding) => ({
            materialTypeId: String(binding.materialTypeId),
            materialName: binding.name || binding.materialName || null,
            dosagePerKgG: binding.dosagePerKgG,
            ...(binding.subsequentPotRatio != null
              ? { subsequentPotRatio: binding.subsequentPotRatio }
              : {}),
            ...(binding.unit ? { unit: binding.unit } : {}),
          }));
        auxiliaryByProcess[nodeId] = {
          usageSupported: process.standardUsageSupported === true,
          rows: process.bindings.map((binding): AuxiliaryCellRow => ({
            id: String(binding.id),
            materialName: binding.name || binding.materialName || '未命名辅料',
            dosageText: formatAuxiliaryDosageText(binding.dosagePerKgG),
            markers: markersForAuxiliaryRow({
              subsequentPotRatio: binding.subsequentPotRatio,
              countInSeasoning: binding.countInSeasoning,
              substituteCount: relations.filter((relation) =>
                relation.parentKind === 'SEASONING_ITEM' && relation.parentSeasoningItemId === binding.id).length,
              costScope: process.costScope ?? null,
            }),
          })),
        };
      });
    });
    // must-fix #8 (review ruling 3a): "first recipe wins" 本身保留(接受的取舍), 但不能让
    // 用户以为自己在编辑唯一配方 —— 被 >1 份 recipe 引用的工序, 把当前实际生效的产出名
    // 标出来, 交给 WorkflowAuxiliaryNode.vue 在 cell 副标题下方渲染一行提示。
    Object.keys(auxiliaryByProcess).forEach((nodeId) => {
      const sharedAcrossRecipes = (recipeIdsByProcessNode[nodeId]?.size ?? 0) > 1;
      if (!sharedAcrossRecipes) return;
      auxiliaryByProcess[nodeId] = {
        ...auxiliaryByProcess[nodeId],
        sharedAcrossRecipes: true,
        recipeOutputName: outputNameByRecipe[recipeIdByProcess[nodeId]] ?? null,
      };
    });

    bomOverlayPackagingByOutput.value = packagingByOutput;
    bomOverlayAuxiliaryByProcess.value = auxiliaryByProcess;
    hydrateMaterialBindingsIntoDefinition(materialBindingsByProcess, packagingBindingsByOutput, options);
    bomOverlayProductIdByRecipe.value = productIdByRecipe;
    bomDraftNotices.value = draftNotices;
    bomOverlayRecipeIdByProcess.value = recipeIdByProcess;
    bomOverlaySeasoningWorkspaceByRecipe.value = workspaceByRecipe;
    bomOverlaySubstitutesByRecipe.value = substitutesByRecipe;
    bomOverlayRecipeIdByOutput.value = recipeIdByOutput;
    bomOverlayPackagingRawByOutput.value = packagingRawByOutput;
    refreshBomOverlay();
  } catch (error) {
    if (!stillCurrent()) return;
    console.error('[ProductProcessWorkflow] BOM overlay data loading failed (辅料/包材 cell 会显示为空态)', error);
  }
}

/**
 * 物料档案原始行 —— 三个弹窗共用的**未筛来源**, 一次请求缓存下来。
 *
 * 🔴 缓存的是**没筛过**的那份。谁要用谁自己按自己的口径筛 (bomOverlayMaterialSources.ts),
 * 不允许把已筛好的列表赋给另一个用途 —— 那正是副产下拉曾经全是包材的成因。
 */
type BomOverlayArchiveRow = BomOverlayMaterialRow & SeasoningMaterialOption & PackagingMaterialOption;

const bomOverlayMaterialArchive = ref<BomOverlayArchiveRow[] | null>(null);

async function loadBomOverlayMaterialArchive(): Promise<BomOverlayArchiveRow[] | null> {
  if (bomOverlayMaterialArchive.value) return bomOverlayMaterialArchive.value;
  const factoryId = props.factoryId;
  if (!factoryId) return null;
  const response = await get<BomOverlayArchiveRow[]>(`/${factoryId}/raw-material-types/active`);
  const rows = response.success && response.data ? response.data : [];
  bomOverlayMaterialArchive.value = rows;
  return rows;
}

/**
 * 副产可选物料(2026-08-07 阶段 2)。
 *
 * 🔴 这份列表不能用 `rawMaterialOptions` 代替 —— 那份被 `isRawMaterialOption` 按
 * 「材质大类 === 原料」筛过, 而副产**按定义筛不出来**: 鸡架的材质是原料、肥油是油脂,
 * 按材质怎么筛都不对。副产只看物料上的 `isByproduct` 标记, 与材质正交。
 * (同一份档案筛出来的两个列表, 类型相同 ≠ 口径相同 —— 见 bomOverlayMaterialSources.ts
 *  顶部记的 #2313 事故: 当时把包材列表直接赋给副产, 编译全绿而功能上线即不可用。)
 *
 * 为什么是物料而不是产品 SKU: `bom_recipe_items.material_type_id` 是指向
 * `raw_material_types(id)` 的硬外键, 报工时 ByproductBatchMaterializer 也是按
 * materialTypeId 建 MaterialBatch。副产在系统里始终是**物料**。
 */
async function ensureByproductMaterialOptions(): Promise<void> {
  if (byproductMaterialsLoaded.value) return;
  try {
    const rows = await loadBomOverlayMaterialArchive();
    if (!rows) return;
    byproductMaterialOptions.value = selectByproductMaterials(rows).map((row) => ({
      id: String(row.id),
      name: row.name,
      code: row.code ?? null,
      unit: row.unit ?? null,
    }));
    byproductMaterialsLoaded.value = true;
  } catch {
    ElMessage.error('副产物料档案加载失败');
  }
}

async function ensureBomOverlayMaterials(): Promise<void> {
  if (bomOverlayMaterialsLoaded.value) return;
  try {
    const rows = await loadBomOverlayMaterialArchive();
    if (!rows) return;
    bomOverlayMaterials.value = selectAuxiliaryMaterials(rows);
    bomOverlayMaterialsLoaded.value = true;
  } catch {
    ElMessage.error('辅料档案加载失败');
  }
}

// 辅料编辑入口 —— 直接复用既有 SeasoningBindingDialog(不新建编辑面, 见 task-6-brief.md)。
async function openAuxiliaryEditor(processNodeId: string, rowId?: string): Promise<void> {
  // ⛔ fool-proof Rule 5(死胡同必须变成导航或解释): 「详情」按钮在只读态下始终渲染
  // (WorkflowAuxiliaryNode.vue 没有 v-if="canWrite" 挡它 —— 辅料行本身对只读用户也该
  // 可见), 过去这里直接静默 return, 用户点了没有任何反馈。SeasoningBindingDialog 是
  // 纯编辑表单、没有只读展示态(重写它不在本轮范围内), 所以这里不能替只读用户打开它;
  // 能做、且必须做的, 是把"什么都不做"换成一句解释。
  if (!canEdit.value) {
    ElMessage.info('当前无编辑权限，无法打开辅料编辑弹窗。Cell 上显示的是用量与标记，备注、单价、替代物料明细需有权限后在弹窗内查看。');
    return;
  }
  // ⛔ 这里不能因为「没有 recipe」就早退: 该产品可能一条 BOM 版本都没有(冷启动),
  // 那不是「数据没加载完」而是「还没建过」—— 早退会让画布在这种最常见的起点上死掉,
  // 且提示词还是错的。归属产品能定就交给 resolveWritableRecipeId 去 ensureDraft。
  const recipeId = bomOverlayRecipeIdByProcess.value[processNodeId];
  await ensureBomOverlayMaterials();

  const auxProductTypeId = resolveProductForProcess(processNodeId);
  if (!auxProductTypeId) {
    // ⛔ 防呆规则 5: 这句要指向一个**真的存在**的入口。
    // 阶段 2 之前它写的是「包材/副产入口」, 而副产入口已随浮层删除(副产改成了工序上的
    // 真实产出节点, 不再产生 BOM 行) —— 留着就是把用户指向一扇不存在的门。
    ElMessage.warning('未能确定该工序归属的成品；联合生产请先从产出侧的包材 cell 建立 BOM');
    return;
  }
  const writableRecipeId = await resolveWritableRecipeId(
    auxProductTypeId,
    recipeId,
    () => bomOverlayRecipeIdByProcess.value[processNodeId],
  );
  if (!writableRecipeId) return;
  // 重载后 workspace/process/binding 都要按草稿版重新取, 旧对象里的行 id 属于别的版本。
  const writableWorkspace = bomOverlaySeasoningWorkspaceByRecipe.value[writableRecipeId];
  const writableProcess = writableWorkspace?.processes
    .find((candidate) => candidate.workflowProcessNodeId === processNodeId) ?? null;
  if (!writableWorkspace || !writableProcess) {
    // 🔴 2026-08-10: 这里以前说「辅料数据尚未加载完成，请稍后重试」—— 那是编的。
    // 走到这一行时草稿刚刚 ensure 成功、workspace 也已重载, "没加载完"不成立,
    // 用户按提示重试一百次也是同一个结果。真实条件是**这道工序不在该配方的工序清单里**
    // (联合生产走到别的产出 / 画布工序与已生效 BOM 的工序目录不符), 说清楚才有下一步。
    ElMessage.warning(
      writableWorkspace
        ? '该工序不在当前 BOM 草稿的工序清单里，通常是画布工序与已生效 BOM 的工序目录不一致。请先保存画布草稿，让 BOM 跟着当前工艺重新对齐。'
        : '该产品的 BOM 草稿尚未就绪，请先保存画布草稿再配置辅料。',
    );
    return;
  }

  auxDialogFactoryId.value = props.factoryId;
  auxDialogRecipeId.value = writableRecipeId;
  auxDialogProcess.value = writableProcess;
  auxDialogBinding.value = rowId
    ? (writableProcess.bindings.find((binding) => String(binding.id) === rowId) ?? null)
    : null;
  auxDialogRevision.value = writableWorkspace.seasoningRevision;
  auxDialogVisible.value = true;
}

function onAuxDialogSaved(): void {
  // 用户改了辅料克数/锅序 —— 这是对工艺定义的真实改动, 必须让图变 dirty,
  // 保存后才会产生新工艺版本(方案 B 的核心)。
  void loadBomOverlayData({ afterUserEdit: true });
}

// #Task1(2026-08-05 bom-canvas-phase3-2): 复用同一个 raw-material-types/active 端点,
// 只是过滤条件从 ensureBomOverlayMaterials 的"辅料/调料"换成"包材"分类 —— 不是新端点。
async function ensureBomOverlayPackagingMaterials(): Promise<void> {
  if (bomOverlayPackagingMaterialsLoaded.value) return;
  try {
    const rows = await loadBomOverlayMaterialArchive();
    if (!rows) return;
    bomOverlayPackagingMaterials.value = selectPackagingMaterials(rows);
    bomOverlayPackagingMaterialsLoaded.value = true;
  } catch {
    ElMessage.error('包材档案加载失败');
  }
}

// 包材编辑入口 —— 画布上直接挂 PackagingBindingDialog(不再打开 BOM 抽屉, 见
// task-1-brief.md「拆抽屉之前必须先有它」); rowId 现在真正定位到那一行, 补上
// Phase 3-1 遗留的 should-fix(点某一行只能开抽屉页签, 跳不到该行)。
async function openPackagingEditor(outputNodeId: string, rowId?: string): Promise<void> {
  if (!canEdit.value) {
    ElMessage.info('当前无编辑权限，无法打开包材编辑弹窗。Cell 上显示的是用量与标记，备注、单价、替代物料明细需有权限后在弹窗内查看。');
    return;
  }
  const node = flowNodes.value.find((candidate) => candidate.id === outputNodeId);
  if (node?.data?.kind !== 'FINISHED_GOOD' || !node.data?.skuId) {
    ElMessage.warning('包材数据尚未加载完成，请稍后重试');
    return;
  }
  // 同辅料: 零版本(冷启动)不早退, 交给 ensureDraft 建首版草稿。
  const recipeId = bomOverlayRecipeIdByOutput.value[outputNodeId];
  const row = rowId
    ? bomOverlayPackagingRawByOutput.value[outputNodeId]?.find((item) => String(item.id) === rowId) ?? null
    : null;
  if (rowId && !row) {
    ElMessage.warning('未找到对应的包材行，请刷新后重试');
    return;
  }
  await ensureBomOverlayPackagingMaterials();

  // 与辅料同因: 包材 item 也只能写进 DRAFT, 生效版返回「只有 DRAFT 状态可加 item」。
  const packProductTypeId = String(node.data.skuId ?? '') || bomOverlayProductIdByRecipe.value[recipeId];
  if (!packProductTypeId) {
    ElMessage.warning('未能确定该产出对应的成品，请刷新后重试');
    return;
  }
  const writablePackRecipeId = await resolveWritableRecipeId(
    packProductTypeId,
    recipeId,
    () => bomOverlayRecipeIdByOutput.value[outputNodeId],
  );
  if (!writablePackRecipeId) return;
  // 编辑既有行时, 行 id 必须来自草稿版 —— 生效版那份的 id 在草稿里不存在。
  const writableRow = rowId
    ? bomOverlayPackagingRawByOutput.value[outputNodeId]?.find((item) => String(item.id) === rowId) ?? null
    : null;
  if (rowId && !writableRow) {
    ElMessage.warning('该包材行在草稿版本中不存在，请刷新后重试');
    return;
  }

  packagingDialogFactoryId.value = props.factoryId;
  packagingDialogRecipeId.value = writablePackRecipeId;
  packagingDialogOutputName.value = String(node.data.name ?? '未命名产出');
  // 禁止降级处理: 基本单位缺失时占位「未配」, 不能让弹窗拼出 "个/undefined" 这种半成品字符串。
  packagingDialogBaseUnit.value = String(node.data.baseUnit ?? '未配');
  packagingDialogRow.value = writableRow;
  packagingDialogVisible.value = true;
}

function onPackagingDialogSaved(): void {
  // 同 onAuxDialogSaved: 包材用量也是工艺定义的一部分, 改了要能保存成新版本。
  // (包材用量目前仍只落 BOM 表, 但 hydration 一旦覆盖到它, 这里就已经接对了。)
  void loadBomOverlayData({ afterUserEdit: true });
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

/**
 * ⛔ 重新派生 BOM 浮层(辅料/包材 cell) —— 结构性挂在 hydrate() 内部, 不是散落
 * 在各个调用点各补一次。undo() / handleAutoLayout() / reconcileLoadedUnits() 等
 * 全部经由 hydrate() 整体替换 flowNodes/flowEdges, 若浮层派生只在"加载首次"那
 * 一个点做, 这些消费方会把浮层一并清空(见 task-6-brief.md Step 1b 的事故记录)。
 * 把派生做成 hydrate 的必经步骤, 「hydrate 之后浮层一定在」就是结构性保证,
 * 不依赖调用方记得再调一次。
 */
function refreshBomOverlay(): void {
  // BOM 数据重载、普通节点编辑和自动布局都会重新派生浮层。保留用户已经拖过的
  // 位置，避免 Cell 一刷新就跳回默认位置；位置只属于当前画布会话，不写入工艺定义。
  const existingOverlayPositions = new Map(
    flowNodes.value
      .filter(isBomOverlayNode)
      .map((node) => [node.id, { x: node.position.x, y: node.position.y }] as const),
  );
  const strippedNodes = stripBomOverlay(flowNodes.value);
  const strippedEdges = stripBomOverlayEdges(flowEdges.value);
  const workflowNodes: BomOverlaySourceNode[] = strippedNodes.map((node) => ({
    id: node.id,
    kind: (node.data as { kind: ProductProcessNodeKind }).kind,
    position: { x: node.position.x, y: node.position.y },
    data: node.data as BomOverlaySourceNodeData,
  }));
  const { nodes: overlayNodes, edges: overlayEdges } = deriveBomOverlay({
    workflowNodes,
    auxiliaryByProcess: bomOverlayAuxiliaryByProcess.value,
    packagingByOutput: bomOverlayPackagingByOutput.value,
  });
  flowNodes.value = [
    ...strippedNodes,
    // 浮层业务数据仍是只读投影：不可选、不可删；但位置是画布布局，允许拖动。
    // onNodeDragStop 对浮层单独处理，不会制造 Workflow dirty 或新版本。
    ...overlayNodes.map((node) => ({
      id: node.id,
      type: node.type,
      position: existingOverlayPositions.get(node.id) ?? node.position,
      data: node.data,
      draggable: true,
      selectable: false,
      deletable: false,
    })),
  ];
  flowEdges.value = [
    ...strippedEdges,
    ...overlayEdges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      sourceHandle: edge.sourceHandle,
      target: edge.target,
      targetHandle: edge.targetHandle,
      type: edge.type,
      style: edge.style,
      markerEnd: MarkerType.ArrowClosed,
      selectable: false,
      deletable: false,
      updatable: false,
      zIndex: 1,
    })),
  ];
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
  projectUniqueRawMaterialBindings();
  refreshPortMaterialMetadata();
  refreshBomOverlay();
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
    // ⛔ 浮层节点(辅料/包材 cell)是 BOM 数据的投影, 不属于工艺定义。
    // 混进去会改 revision hash → 改一克盐就让所有 BOM 需要重新对齐。
    nodes: stripBomOverlay(flowNodes.value).map(serializeFlowNode),
    // ⛔ 浮层边(辅料 cell → 工序 / 产出 → 包材 cell 的投影连接)同样必须剥离,
    // 否则序列化出的定义会带着指向已被剥离节点的悬空引用发去后端。
    edges: stripBomOverlayEdges(flowEdges.value).map(serializeFlowEdge),
    viewport: { x: viewport.x, y: viewport.y, zoom: viewport.zoom },
  };
}

/**
 * 阶段 3(版本合一): 把投入明细从 BOM 表 hydrate 进工艺定义的工序节点。
 *
 * ## ⛔ 为什么这里【绝对不能】走 mutate()
 * mutate() 会置 dirty 并 bump editSeq —— 那意味着**用户只是打开一张图就变成"有未保存改动"**,
 * 保存后还会造出一个内容与旧版完全等价的新工艺版本。版本线会因为"看了一眼"而增长,
 * 这跟方案 B「改画布才产生新版本」正好相反。
 *
 * 所以这是一次**幂等的加载期投影**: 只在数值真的不同时才写, 写完不置 dirty。
 * 判据: 连开两次同一张图, `dirty` 必须仍是 false(见 materialBindingsHydration.spec.ts)。
 *
 * ## 为什么写在节点上而不是继续放浮层
 * 浮层是从 BOM 表派生的展示物, 不进 nodesJson 也就不进 revisionHash ——
 * 改克数因此不会产生新版本。方案 B 要的正好相反。
 */
function hydrateMaterialBindingsIntoDefinition(
  bindingsByProcess: Record<string, WorkflowMaterialBinding[]>,
  packagingByOutput: Record<string, WorkflowPackagingBinding[]>,
  options: { afterUserEdit?: boolean } = {},
): void {
  let changed = false;
  // 包材挂终端产出节点 —— 与辅料同一套 dirty 口径, 改了就该产生新工艺版本。
  flowNodes.value.forEach((node) => {
    if (node.type !== 'material') return;
    const next = packagingByOutput[node.id] ?? [];
    const data = node.data as MaterialNodeData;
    const current = Array.isArray(data.packagingBindings) ? data.packagingBindings : [];
    if (JSON.stringify(current) === JSON.stringify(next)) return;
    if (current.length === 0 && next.length === 0) return;
    data.packagingBindings = next;
    changed = true;
  });
  flowNodes.value.forEach((node) => {
    if (node.type !== 'process') return;
    const next = bindingsByProcess[node.id] ?? [];
    const data = node.data as ProcessNodeData;
    const current = Array.isArray(data.materialBindings) ? data.materialBindings : [];
    // 逐字比对(而不是"有就覆盖"): 相同就不写, 否则每次加载都在制造无意义的对象身份变化,
    // 而 vue 的响应式会把它当成真改动传播出去。
    if (JSON.stringify(current) === JSON.stringify(next)) return;
    if (current.length === 0 && next.length === 0) return;
    data.materialBindings = next;
    changed = true;
  });
  if (!changed) return;
  refreshBomOverlay();

  // 🔴 这两条分支是「改克数产生新工艺版本」的最后一环, 缺了它整条链就是断的:
  //    数据进了定义、revisionHash 也覆盖它 —— 但没人把图标记成 dirty,
  //    用户就永远不会被提示保存, 于是新版本永远不会产生。两头都对、中间断掉。
  //
  //  · 加载期(afterUserEdit=false): ⛔ 不置 dirty。否则打开一张图就变成
  //    「有未保存改动」, 版本线会因为「看了一眼」而增长。
  //  · 用户改完辅料/包材之后(afterUserEdit=true): 必须置 dirty —— 那是真实改动。
  if (options.afterUserEdit) {
    editSeq += 1;
    workflowBomSyncPreflight.value = null;
    dirty.value = true;
  }
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
  projectUniqueRawMaterialBindings();
  refreshPortMaterialMetadata();
  // ⛔ must-fix #7: hydrate() 早就会重新派生浮层(#node-bomAuxiliary/#node-bomPackaging),
  // 但大多数图编辑(加工序/加端口/连线...)走的是 mutate() 直接改 flowNodes/flowEdges,
  // 不经过 hydrate()。不在这里补一次, 新加的工序/成品节点在下一次 hydrate(undo/自动布局/
  // 重新加载)之前完全没有辅料/包材 cell —— 跟 #3 是同一种"缺席被当成已配置"的混淆,
  // 只是发生在"节点刚创建, BOM 数据里压根还没有它"这个更早的时刻。refreshBomOverlay
  // 是幂等的纯派生(见其函数注释), 每次 mutate 都跑一次代价可接受。
  refreshBomOverlay();
  editSeq += 1;
  workflowBomSyncPreflight.value = null;
  dirty.value = true;
  scheduleAutoSave();   // #11: 每次改动都重排防抖 (即使 dirty 已是 true, watch transition 不会触发)
}

function undo(): void {
  if (!canEdit.value) return;
  const previous = history.value.pop();
  if (!previous) return;
  hydrate(previous);
  editSeq += 1;
  workflowBomSyncPreflight.value = null;
  dirty.value = true;
  scheduleAutoSave();
}

function onNodeClick({ node, event }: NodeMouseEvent): void {
  // ⛔ VueFlow 的 node.selectable=false 只挡它自己内部的选中逻辑, 不挡 @node-click 事件
  // 本身(它的 onSelectNode 无条件 emit.click)—— 这里若不早退, 下面几行会直接在
  // flowNodes.value 里手动把浮层节点的 .selected 置 true, 绕过 selectable 的保护。
  if (isBomOverlayNode(node)) return;
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

function onNodeDragStart({ node }: { node: Node }): void {
  if (!canEdit.value) return;
  if (isBomOverlayNode(node)) {
    dragStartSnapshot.value = null;
    return;
  }
  dragStartSnapshot.value = currentDefinition();
}

function onNodeDragStop({ node }: { node: Node }): void {
  if (!canEdit.value) return;
  if (isBomOverlayNode(node)) {
    const overlay = flowNodes.value.find((candidate) => candidate.id === node.id);
    if (overlay) overlay.position = { x: node.position.x, y: node.position.y };
    dragStartSnapshot.value = null;
    return;
  }
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
  // Vue Flow 也用这道门校验程序派生的 v-model edges。浮层节点没有工艺 kind，
  // 必须先按精确的节点归属 + handle 组合放行，否则三条派生边会被静默丢弃。
  if (isDerivedBomOverlayConnection(connection)) return true;
  if (isBomOverlayNode(source) || isBomOverlayNode(target)) return false;
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
  if (isBomOverlayEdge(edge)) return;
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
    selected: !isBomOverlayEdge(edge) && (nodeIds.has(edge.source) || nodeIds.has(edge.target)),
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
  if (isBomOverlayEdge(edge)) return;
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
  // ⛔ 浮层 cell 不是工艺定义的一部分, 不能被这条"移除 Workflow Cell"路径删除 ——
  // 且下面的确认框会读 processName/name 当标题, 对浮层 cell 显示会是「移除「腌制」…
  // 这不会删除工序/SKU 主数据」这种字面上真但语义上误导的话(它删的根本不是工序)。
  if (isBomOverlayNode(node)) return;
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
  // ⛔ 防御性过滤: selectedCellIds 理论上不该再混进浮层 id(onNodeClick/框选都已挡住),
  // 这里再筛一次是不假设那两道门永远不会被将来的改动绕开(同 removeNode 的早退)。
  const ids = selectedCellIds.value.filter((id) => !isBomOverlayNode({ id }));
  const explicitlySelectedEdges = new Set(
    selectedEdgeIds.value.filter((edgeId) => {
      const edge = flowEdges.value.find((candidate) => candidate.id === edgeId);
      return edge != null && !isBomOverlayEdge(edge);
    }),
  );
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

/**
 * 加原料 Cell 的入口。
 *
 * 这道工序**已经有原料**时先问清关系(替代料 / 并列投入), 因为这两件事对需求与成本
 * 的算法完全不同: 替代料是二选一, 合成一个逻辑投入; 并列投入各自是独立需求。
 * 系统以前不问, 于是两种情况长得一模一样, `logicalRootCount()` 只能一律按 +1 算。
 *
 * ⚠️ 这道工序**还没有任何原料**时不弹窗 —— 没有可替代的对象, 问了也只有一个答案。
 *
 * ⛔ 弹窗只是录入体验, **不是约束**: AI 画布工具是第二个写图入口, 完全绕过这里。
 *    合法性(自引用/悬空/指向非原料/成链)由后端 ProductProcessWorkflowValidator 保证。
 */
function addInputToProcess(processId: string): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  const mainMaterial = primaryRawInputOf(process);
  if (!mainMaterial) {
    createRawInputCell(processId, { skuId: '', substituteOfNodeId: '' });
    return;
  }
  Object.assign(rawInputDialog, {
    visible: true,
    processId,
    processName: String((process.data as ProcessNodeData).processName || '工序'),
    mainMaterialNodeId: mainMaterial.id,
    mainMaterialName: String(mainMaterial.data?.name || '主料'),
    skuId: '',
    relation: '',
  });
}

/**
 * 这道工序上「可以被替代」的那个原料 —— 按投入端口顺序取第一个真原料 Cell。
 * 已经是别人替代料的 Cell 不能当主料: 替代关系只允许一层(后端同样拒绝成链)。
 */
function primaryRawInputOf(process: Node): Node | null {
  const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
  const inputs = (data.ports || [])
    .filter((port) => port.direction === 'INPUT')
    .sort((left, right) => left.ordinal - right.ordinal);
  for (const port of inputs) {
    const material = flowNodes.value.find((node) => node.id === port.materialNodeId);
    if (!material || material.data?.kind !== 'RAW_MATERIAL') continue;
    const substituteOf = String((material.data as MaterialNodeData)?.substituteOfNodeId || '');
    if (substituteOf) continue;
    return material;
  }
  return null;
}

function closeRawInputDialog(): void {
  rawInputDialog.visible = false;
}

function confirmAddRawInput(): void {
  if (!rawInputDialogReady.value) return;
  const option = rawMaterialOptions.value.find((item) => item.id === rawInputDialog.skuId);
  if (!option) {
    ElMessage.warning('请选择一个原料 SKU');
    return;
  }
  const duplicate = flowNodes.value.some((node) => (
    node.data?.kind === 'RAW_MATERIAL'
    && String(node.data?.skuId || '') === option.id
  ));
  if (duplicate) {
    ElMessage.warning('该原料已在当前 Workflow 中使用');
    return;
  }
  createRawInputCell(rawInputDialog.processId, {
    skuId: option.id,
    substituteOfNodeId: rawInputDialog.relation === 'SUBSTITUTE'
      ? rawInputDialog.mainMaterialNodeId
      : '',
  });
  closeRawInputDialog();
}

/**
 * 真正落图的那一步。弹窗与「无原料直接建」两条路都走它, 避免第二条路悄悄长出别的行为。
 */
function createRawInputCell(
  processId: string,
  options: { skuId: string; substituteOfNodeId: string },
): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  const option = options.skuId
    ? rawMaterialOptions.value.find((item) => item.id === options.skuId)
    : undefined;
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const inputCount = data.ports.filter((port) => port.direction === 'INPUT').length;
    const timestamp = nextGraphIdSeed();
    const materialId = `material:input:${timestamp}`;
    const portId = `input:${timestamp}`;
    const unit = option
      ? workflowReportingUnit('RAW_MATERIAL', option.unit || 'kg')
      : 'kg';
    const materialData: MaterialNodeData & { kind: 'RAW_MATERIAL' } = option
      ? {
        kind: 'RAW_MATERIAL', name: option.name, skuId: option.id,
        skuCode: option.code || option.id, bound: true, baseUnit: unit,
      }
      : {
        kind: 'RAW_MATERIAL', name: `追加投入 ${inputCount + 1}`, skuId: '',
        skuCode: '待绑定原料 SKU', bound: false, baseUnit: unit,
      };
    if (options.substituteOfNodeId) {
      materialData.substituteOfNodeId = options.substituteOfNodeId;
    }
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x - 240, y: process.position.y + inputCount * 160 }),
      data: materialData,
    });
    data.ports = [
      ...data.ports,
      { id: portId, direction: 'INPUT', materialNodeId: materialId, unit, ordinal: inputCount },
    ];
    flowEdges.value.push(flowEdge(materialId, 'output', processId, portId));
  });
}

/**
 * 工序「产出分流」—— 主产出与副产走**同一条路径**, 只差 data 上的一个标记。
 *
 * 2026-08-07 阶段 2: 副产从 BOM 浮层(`bom-overlay:byp:*`)改成真实产出节点。
 * 设计定稿要求「复用已有的『＋ 产出 Cell（分流）』入口, 不新造入口」——
 * 所以这里不是新写一个 addByproductToProcess, 而是给同一个函数加一个 flag:
 * 节点 id 规则、端口构造、连边、mutate 置 dirty 全部共用。副产因此天然继承
 * 主产出已有的一切(图校验、拓扑、SKU 必填、发布前检查), 不需要为它开特例。
 *
 * 为什么不是 `kind: 'BYPRODUCT'`: 见 MaterialNodeData.isByproduct 的注释 ——
 * 副产是与材质分类正交的标记, 不是第 5 种 kind。
 */
function addOutputToProcess(processId: string, options: { byproduct?: boolean } = {}): void {
  const process = flowNodes.value.find((node) => node.id === processId);
  if (!process) return;
  const isByproduct = options.byproduct === true;
  // 建了副产 cell 才去拉副产档案 —— 不建就不请求。⚠️ 不 await: 建节点必须立即可见,
  // 档案回来后下拉自然填充; 真空列表的解释文案由节点自己给(见 WorkflowMaterialNode)。
  if (isByproduct) void ensureByproductMaterialOptions();
  mutate(() => {
    const data = process.data as ProcessNodeData & { kind: 'PROCESS' };
    const outputCount = data.ports.filter((port) => port.direction === 'OUTPUT').length;
    const byproductCount = flowNodes.value.filter(
      (node) => node.type === 'material' && (node.data as MaterialNodeData)?.isByproduct === true,
    ).length;
    const timestamp = nextGraphIdSeed();
    const materialId = `material:output:${timestamp}`;
    const portId = `output:${timestamp}`;
    flowNodes.value.push({
      id: materialId,
      type: 'material',
      position: snapPosition({ x: process.position.x + 480, y: process.position.y + outputCount * 160 }),
      data: {
        kind: 'SEMI_FINISHED',
        name: isByproduct ? `副产 ${byproductCount + 1}` : `产出半成品 ${outputCount + 1}`,
        skuId: '',
        skuCode: '待选择或现场创建 SKU',
        bound: false,
        baseUnit: 'kg',
        ...(isByproduct ? { isByproduct: true } : {}),
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

/**
 * 副产 cell 选物料。**刻意不复用 selectRawSku** —— 那个函数带两件 RAW_MATERIAL 专属的事:
 * 「同一原料不能在本 Workflow 里用两次」的去重, 以及把新单位回写进所有工序的投入端口。
 * 副产是产出侧, 两件都不该发生(同一副产可以从两道工序各出一份; 它的单位不是任何工序的投入单位)。
 */
function selectByproductSku(materialNodeId: string, materialTypeId: string): void {
  const node = flowNodes.value.find((item) => item.id === materialNodeId);
  const option = byproductMaterialOptions.value.find((item) => item.id === materialTypeId);
  if (!node || !option) return;
  mutate(() => {
    Object.assign(node.data, {
      name: option.name,
      skuId: option.id,
      skuCode: option.code || option.id,
      baseUnit: option.unit || String(node.data?.baseUnit || 'kg'),
      bound: true,
    });
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
  const primaryOutput = processData.ports
    .filter((candidate) => candidate.direction === 'OUTPUT')
    .sort((left, right) => left.ordinal - right.ordinal)[0];
  const snapshotKind = primaryOutput?.materialKind;
  if (snapshotKind === 'SEMI_FINISHED' || snapshotKind === 'FINISHED_GOOD') {
    return snapshotKind;
  }
  const materialKind = flowNodes.value.find(
    (node) => node.id === primaryOutput?.materialNodeId,
  )?.data?.kind;
  if (materialKind === 'SEMI_FINISHED' || materialKind === 'FINISHED_GOOD') {
    return materialKind;
  }
  // The oldest legacy nodes may carry neither marker. Only then may the process
  // catalog provide its creation-time suggestion.
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
  const currentLabel = currentKind === 'FINISHED_GOOD' ? '成品' : '半成品';
  const nextLabel = nextKind === 'FINISHED_GOOD' ? '成品' : '半成品';
  try {
    await ElMessageBox.confirm(
      `当前 Workflow 中，工序“${data.processName}”的主产出是“${currentLabel}”。是否将这个产出 Cell 改为${nextKind === 'FINISHED_GOOD' ? '成品' : '半成品'}并绑定所选 SKU？`,
      '产出类型不一致',
      {
        type: 'warning',
        confirmButtonText: `改为${nextLabel}并绑定`,
        cancelButtonText: '取消选择',
      },
    );
    if (!isLoadedIdentityCurrent(identity) || !canEdit.value) return false;
    return true;
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      console.error('[ensurePrimaryOutputKind] confirmation failed', error);
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
    const compatible = await ensurePrimaryOutputKind(processId, portId, kind);
    if (compatible) bindOutputSku(processId, portId, option, { allowPrimaryKindChange: true });
    return;
  }
  bindOutputSku(processId, portId, option);
}

function bindOutputSku(
  processId: string,
  portId: string,
  option: SkuOption,
  bindOptions: { allowPrimaryKindChange?: boolean } = {},
): boolean {
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
  if (
    needsPrimaryOutputKindUpdate(expectedKind, kind, primaryOutputPort?.id === port.id)
    && !bindOptions.allowPrimaryKindChange
  ) {
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
    defaultOutputMaterialKind: processOutputKind(data) ?? master.defaultOutputMaterialKind,
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
  const master = workProcessOptions.value.find((option) => option.id === data.workProcessId);
  if (!master) {
    ElMessage.warning('未找到该工序主数据，请刷新后重试');
    return;
  }
  const nextProcessName = form.processName.trim();
  const nextProcessCategory = form.processCategory.trim();
  const masterFieldsChanged = nextProcessName !== master.processName
    || nextProcessCategory !== (master.processCategory || '')
    || form.needsInput !== (master.needsInput !== false);
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
    let updated = master;
    if (masterFieldsChanged) {
      const response = await updateWorkProcess(identity.factoryId, data.workProcessId, {
        processName: nextProcessName,
        processCategory: nextProcessCategory,
        needsInput: form.needsInput,
      });
      if (!response.success || !response.data) throw new Error(response.message || '工序修改失败');
      updated = response.data;
      workProcessOptions.value = workProcessOptions.value.map(
        (option) => option.id === updated.id ? updated : option,
      );
    }
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
    ElMessage.success(masterFieldsChanged
      ? '工序信息及当前 Workflow 产出类型已更新'
      : '当前 Workflow 产出类型已更新');
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

function createWorkflowPublishIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  const entropy = new Uint32Array(4);
  cryptoApi?.getRandomValues?.(entropy);
  return `workflow-${Date.now()}-${Array.from(entropy).join('-')}`;
}

async function publishWorkflow(): Promise<void> {
  const identity = currentLoadedIdentity();
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
  await withAutoSaveBarrier(() => publishWorkflowUnderBarrier(identity));
}

async function publishWorkflowUnderBarrier(initialIdentity: WorkflowIdentity): Promise<void> {
  if (!(await waitForWorkflowSave())) {
    ElMessage.warning('Workflow 自动保存仍未完成，请稍后再发布');
    return;
  }
  let identity = initialIdentity;
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
    await markPublishBindingErrors(errors);
    void ElMessageBox.alert(errors.slice(0, 8).map((error) => `• ${error.message}`).join('\n'), '发布前检查未通过', {
      type: 'warning',
    });
    return;
  }
  clearPublishBindingErrors();
  // definition.value is the last server-confirmed saved envelope. flowNodes may contain
  // catalog-only display enrichment, so it must not be used to detect an authoritative
  // server graph change.
  const definitionBeforePreflight = toPlainWorkflowValue(definition.value);
  const generation = ++publishGeneration;
  publishing.value = true;
  try {
    // 发布前检查必须在最后一次草稿保存之后重新读取；旧的 BOM 展示状态不参与写入门禁。
    const preflightResponse = await getWorkflowBomSyncPreflight(
      identity.factoryId,
      identity.productTypeId,
    );
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!preflightResponse.success || !preflightResponse.data) {
      ElMessage.error(preflightResponse.message || 'BOM 与 Workflow 发布前检查失败');
      return;
    }
    const freshPreflight = preflightResponse.data;
    workflowBomSyncPreflight.value = freshPreflight;
    if (!canPublishWorkflowWithBomSync(freshPreflight)) {
      ElMessage.warning('发布前检查发现仍需处理的项目；本次未修改 BOM，也未发布 Workflow');
      return;
    }

    // 预检允许修复未被任何 BOM 固定的旧草稿修订。修复会产生新的 revision/hash
    // 并推进 optimistic lock，因此发布命令必须重新读取权威身份。图内容若被其他人
    // 同时修改，则只加载新内容并停止，绝不能拿旧画布去发布新 revision。
    const refreshedResponse = await getProductProcessWorkflow(
      identity.factoryId,
      identity.productTypeId,
    );
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!refreshedResponse.success || !refreshedResponse.data
      || !definitionMatchesIdentity(refreshedResponse.data, identity)) {
      ElMessage.error(refreshedResponse.message || 'Workflow 修订身份刷新失败');
      return;
    }
    if (!samePersistedWorkflowGraph(
      definitionBeforePreflight,
      refreshedResponse.data,
    )) {
      hydrate(refreshedResponse.data);
      dirty.value = false;
      ElMessage.warning('预检期间 Workflow 内容已变化，已加载最新草稿，请重新检查后发布');
      return;
    }
    const inputSelectionMigrated = hydrate(refreshedResponse.data);
    dirty.value = inputSelectionMigrated;
    if (inputSelectionMigrated) {
      ElMessage.warning('最新草稿需要补存投入槽身份，请先保存后再发布');
      return;
    }
    if (freshPreflight.targetWorkflowRevisionId !== null
      && freshPreflight.targetWorkflowRevisionId !== definition.value?.revisionId) {
      ElMessage.warning('Workflow 修订在预检后再次变化，请重新检查后发布');
      return;
    }
    const lockVersion = definition.value?.lockVersion;
    if (definition.value?.status !== 'DRAFT'
      || lockVersion === undefined
      || lockVersion === null) {
      ElMessage.warning('Workflow 草稿状态已变化，请重新检查后发布');
      return;
    }

    publishConfirming.value = true;
    try {
      const confirmMessage = freshPreflight.classification === 'AUTO_MIGRATABLE'
        ? '系统将自动创建或复用 BOM 同步草稿、保留已有配方，并与当前 Workflow 一并生效。已有生产计划继续使用原快照。'
        : 'BOM 与 Workflow 已一致。发布后只影响之后新建的生产计划，已有计划继续使用原快照。';
      await ElMessageBox.confirm(
        confirmMessage,
        '自动同步并发布',
        { type: 'warning', confirmButtonText: '确认同步并发布' },
      );
    } catch {
      return;
    } finally {
      publishConfirming.value = false;
    }
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (definition.value?.lockVersion !== lockVersion || dirty.value) {
      ElMessage.warning('确认期间草稿已变化，请重新执行自动同步并发布');
      return;
    }

    previousPublishCommand = resolveWorkflowPublishCommand(
      previousPublishCommand,
      {
        factoryId: identity.factoryId,
        productTypeId: identity.productTypeId,
        lockVersion,
        revisionId: definition.value.revisionId ?? null,
        revisionHash: definition.value.revisionHash ?? null,
        definitionVersion: definition.value.version,
      },
      createWorkflowPublishIdempotencyKey,
    );
    const response = await executeWorkflowPublishMutation(
      freshPreflight,
      () => publishAndActivateProductProcessWorkflow(
        identity.factoryId,
        identity.productTypeId,
        previousPublishCommand!.request,
      ),
    );
    if (!response) return;
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (!response.success || !response.data) {
      ElMessage.error(response.message || 'Workflow 自动同步发布失败');
      return;
    }
    workflowBomSyncPreflight.value = response.data.bomSync;
    if (!canPublishWorkflowWithBomSync(response.data.bomSync)) {
      ElMessage.warning('服务端重新检查后发现仍需处理的项目；本次未完成发布');
      return;
    }
    const publishedWorkflow = response.data.workflow;
    const publishedActivation = response.data.activation;
    if (!publishedWorkflow
      || !publishedActivation
      || !definitionMatchesIdentity(publishedWorkflow, identity)
      || !activationMatchesIdentity(publishedActivation, identity)) {
      ElMessage.error('自动同步发布返回的数据不完整，页面未切换到新版本');
      return;
    }
    hydrate(publishedWorkflow);
    activation.value = publishedActivation;
    clearPublishBindingErrors();
    unitReviewPending.value = false;
    dirty.value = false;
    await loadProductBom({ force: true });
    ElMessage.success(response.data.replayed
      ? '已确认上次自动同步发布结果，Workflow 与 BOM 均已生效'
      : 'Workflow 与 BOM 已自动同步、发布并生效');
  } catch (error) {
    if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
    if (isWorkflowConflict(error)) {
      await recoverWorkflowConflict(identity);
      return;
    }
    if (isWorkflowBomSyncRace(error)) {
      try {
        const refreshed = await getWorkflowBomSyncPreflight(
          identity.factoryId,
          identity.productTypeId,
        );
        if (generation !== publishGeneration || !isLoadedIdentityCurrent(identity)) return;
        if (refreshed.success && refreshed.data) {
          workflowBomSyncPreflight.value = refreshed.data;
          // 最新逐项结果由页面常驻 alert 展示；不叠加第二条瞬时 toast，也绝不自动重试写入。
          return;
        }
      } catch (refreshError) {
        console.error('[ProductProcessWorkflow] preflight refresh after publish race failed', refreshError);
      }
    }
    console.error('[ProductProcessWorkflow] atomic publish failed', error);
    ElMessage.error('自动同步发布失败；可以直接重试，系统会复用同一幂等命令');
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

function isWorkflowBomSyncRace(error: unknown): boolean {
  const errorCode = workflowErrorCode(error);
  return Boolean(errorCode && (
    errorCode.startsWith('WORKFLOW_BOM_SYNC_')
    || errorCode.startsWith('BOM_WORKFLOW_')
    || errorCode === 'WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH'
    || errorCode === 'WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE'
    // 阶段 3-3 起后端不再产出 WORKFLOW_ACTIVE_BOM_REQUIRED, 该分支已删。
  ));
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

interface WorkflowSpecOutput { kind?: string; name?: string; unit?: string; byproduct?: boolean }
/** 一味调料/辅料投入。与补丁路的 materialBinding 同形状, 同一套约束。 */
interface WorkflowSpecSeasoning {
  name?: string;
  dosagePerKgG?: number;
  subsequentPotRatio?: number;
}
// #4 合流 (N→1): inputs = 本步除主链上游外**额外**投入的原料名 (混批/拼装). 每个建一个 RAW cell + INPUT 端口。
interface WorkflowSpecStep {
  process?: string;
  processCategory?: string;
  inputs?: string[];
  outputs?: WorkflowSpecOutput[];
  seasonings?: WorkflowSpecSeasoning[];
  injectionAmount?: number;
}
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
  // 阶段 4: 被类别闸/数值域拒掉的调味行。⛔ 不能静默丢 —— 用户会以为 AI 照做了。
  const seasoningRejections: string[] = [];
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
          // 阶段 4: 规格里标了 byproduct 的分流产出建成副产 cell —— 与手工「+ 副产」同一形状
          // (kind 仍是产出类型, 只多一个 isByproduct 标记, 见 MaterialNodeData 的注释)。
          data: extraSku
            ? { kind, name: extraSku.name, skuId: extraSku.id, skuCode: extraSku.code || extraSku.id, specification: extraSku.specification, bound: true, baseUnit: extraSku.unit || unit, ...(extra?.byproduct === true ? { isByproduct: true } : {}) }
            : { kind, name: extra?.name || `产出 ${extraIdx + 2}`, skuId: '', skuCode: '待选择或现场创建 SKU', bound: false, baseUnit: unit, ...(extra?.byproduct === true ? { isByproduct: true } : {}) },
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
      // ── 阶段 4: 规格里的调味参数 ──────────────────────────────────────
      // ⛔ 与补丁路**同一套约束**。这条路的规格不经过后端 Tool, 后端那份 sanitize
      //    在这里一行都跑不到 —— 只改一条路就会留半个洞(设计定稿约束 5)。
      //    被丢弃的行不静默: 收进 seasoningRejections, 建完图一并告诉用户。
      const stepCategory = processData.processCategory;
      const acceptedSeasonings = (Array.isArray(step.seasonings) ? step.seasonings : [])
        .map((row) => {
          const name = String(row?.name || '').trim();
          if (!name) return null;
          if (!isValidDosagePerKgG(row?.dosagePerKgG)) {
            seasoningRejections.push(`「${name}」用量必须大于 0`);
            return null;
          }
          if (row?.subsequentPotRatio !== undefined) {
            if (!isValidSubsequentPotRatio(row.subsequentPotRatio)) {
              seasoningRejections.push(`「${name}」后续锅比例必须在 0–100 之间`);
              return null;
            }
            if (!allowsPotRatio(stepCategory)) {
              seasoningRejections.push(
                `工序「${processData.processName}」不是熟制类，不能配后续锅比例（「${name}」已跳过）`,
              );
              return null;
            }
          }
          return {
            materialName: name,
            dosagePerKgG: row.dosagePerKgG,
            ...(row.subsequentPotRatio !== undefined ? { subsequentPotRatio: row.subsequentPotRatio } : {}),
          };
        })
        .filter((row): row is NonNullable<typeof row> => row !== null);
      if (acceptedSeasonings.length > 0) {
        (processData as Record<string, unknown>).materialBindings = acceptedSeasonings;
      }
      if (step.injectionAmount !== undefined) {
        if (!isValidInjectionAmount(step.injectionAmount)) {
          seasoningRejections.push(`工序「${processData.processName}」的注射量必须大于 0`);
        } else if (!allowsInjection(stepCategory)) {
          seasoningRejections.push(`工序「${processData.processName}」不是注射类，不能配注射量`);
        } else {
          (processData as Record<string, unknown>).injectionAmount = step.injectionAmount;
        }
      }

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
  // ⛔ 被拒的调味行必须说出来。静默丢弃 = 用户以为 AI 照做了, 到扣料时才发现没配 ——
  //    那时已经排产了。用 warning 且 duration 0(不自动消失), 因为这条要被读到。
  if (seasoningRejections.length > 0) {
    ElMessage({
      type: 'warning',
      duration: 0,
      showClose: true,
      message: `以下调味配置未采纳（AI 给的值不符合规则）：${seasoningRejections.join('；')}`,
    });
  }
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

/* 新增原料 Cell 的关系弹窗 (原生 div, 见模板处的注释) */
.raw-input-mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
}
.raw-input-panel {
  width: min(520px, calc(100vw - 32px));
  padding: 20px 24px 16px;
  border-radius: 8px;
  background: var(--el-bg-color, #fff);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}
.raw-input-title { margin: 0 0 16px; font-size: 16px; font-weight: 600; }
.raw-input-field { margin-bottom: 16px; }
.raw-input-label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
}
.raw-input-relations { display: grid; gap: 8px; }
.raw-input-relation {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-areas: 'radio title' '. hint';
  gap: 2px 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 6px;
  cursor: pointer;
}
.raw-input-relation.is-active {
  border-color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, #ecf5ff);
}
.raw-input-relation input { grid-area: radio; margin-top: 3px; }
.raw-input-relation-title { grid-area: title; font-size: 14px; font-weight: 500; }
.raw-input-relation-hint {
  grid-area: hint;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary, #909399);
}
.raw-input-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px; }
</style>
