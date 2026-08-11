package com.cretas.aims.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.workflow.WorkflowTopologyClassifier;
import com.cretas.aims.service.workflow.WorkflowAnchorPolicy;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateResponse;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.WorkflowBomSynchronizationService;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.cretas.aims.service.workflow.WorkflowActualIoSemantics;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductProcessWorkflowServiceImpl implements ProductProcessWorkflowService {

    private final ProductProcessWorkflowRepository repository;
    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final WorkflowRevisionSnapshotService revisionSnapshotService;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final WorkflowBomSynchronizationService workflowBomSynchronizationService;
    private final ProductProcessWorkflowActivationService workflowActivationService;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ObjectMapper objectMapper;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductProcessWorkflowDTO> getEditorDefinition(
            String factoryId,
            String productTypeId) {
        Optional<ProductProcessWorkflow> draft = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (draft.isPresent()) {
            return draft.map(this::toDTOWithUnitWarnings);
        }
        return find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED).map(this::toDTOWithUnitWarnings);
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition) {
        requireWorkflowOwner(factoryId, productTypeId);
        WorkflowActualIoSemantics.normalizeDraft(definition);
        validator.validateForDraft(definition);
        var unitValidation = unitValidator.validate(factoryId, definition);
        definition.setUnitWarnings(java.util.stream.Stream.concat(
                        unitValidation.errors().stream(), unitValidation.warnings().stream())
                .toList());
        Optional<ProductProcessWorkflow> existingDraft = find(
                factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);

        ProductProcessWorkflow entity;
        if (existingDraft.isPresent()) {
            entity = existingDraft.get();
            assertCurrentVersion(definition.getLockVersion(), entity);
        } else {
            Optional<ProductProcessWorkflow> published = find(
                    factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED);
            if (published.isPresent() && sameBusinessGraph(published.get(), definition)) {
                ProductProcessWorkflowDTO unchanged = toDTO(published.get());
                unchanged.setUnitWarnings(definition.getUnitWarnings());
                return unchanged;
            }
            entity = new ProductProcessWorkflow();
            entity.setFactoryId(factoryId);
            entity.setProductTypeId(productTypeId);
            entity.setStatus(ProductProcessWorkflow.Status.DRAFT);
            // SNAPSHOT 也是正式占用的历史版本号；从全部状态取最大值，避免草稿被异常重建时
            // 与已另存版本复用同一个 definitionVersion，导致历史查看命中不确定行。
            int nextVersion = repository.findMaxDefinitionVersion(factoryId, productTypeId).orElse(0) + 1;
            entity.setDefinitionVersion(nextVersion);
        }

        entity.setSchemaVersion(definition.getSchemaVersion());
        entity.setNodesJson(writeJson(definition.getNodes()));
        entity.setEdgesJson(writeJson(definition.getEdges()));
        entity.setViewportJson(writeJson(definition.getViewport()));
        entity = repository.saveAndFlush(entity);
        ProductProcessWorkflowRevision revision = revisionSnapshotService.captureDraft(entity);
        entity.setCurrentRevisionId(revision.getId());
        entity.setCurrentRevisionHash(revision.getRevisionHash());
        ProductProcessWorkflowDTO saved = toDTO(repository.saveAndFlush(entity));
        saved.setUnitWarnings(definition.getUnitWarnings());
        return saved;
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO publish(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            Long operatorId) {
        requireWorkflowOwner(factoryId, productTypeId);
        repository.lockByFactoryId(factoryId);
        ProductProcessWorkflowDTO published =
                publishInternal(factoryId, productTypeId, lockVersion, operatorId, true, null);
        workflowActivationService.activate(factoryId, published.getId(), operatorId);
        reanchorByTopology(factoryId, productTypeId, published);
        return published;
    }

    @Override
    @Transactional
    public WorkflowBomSyncPreflightResponse bomSyncPreflight(
            String factoryId,
            String productTypeId) {
        Optional<ProductProcessWorkflow> workflow =
                find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (workflow.isEmpty()) {
            workflow = find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED);
        }
        ProductProcessWorkflowRevision target = workflow
                .map(row -> {
                    ProductProcessWorkflowRevision revision = readCurrentRevision(row);
                    return row.getStatus() == ProductProcessWorkflow.Status.DRAFT
                            ? bomWorkflowRevisionService.repairCurrentDraftRevisionIfNeeded(
                                    factoryId, revision)
                            : revision;
                })
                .orElse(null);
        return workflowBomSynchronizationService.preflight(
                factoryId, productTypeId, target);
    }

    @Override
    @Transactional
    public WorkflowPublishAndActivateResponse publishAndActivate(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            String idempotencyKey,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion,
            Long operatorId) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        requireWorkflowOwner(factoryId, productTypeId);
        // The key is factory-scoped. Reuse the existing factory serialization point
        // so two concurrent products cannot both claim the same key before the
        // partial unique index becomes visible to the second transaction.
        repository.lockByFactoryId(factoryId);

        Optional<ProductProcessWorkflow> keyedPublish =
                repository.findByFactoryIdAndLastPublishIdempotencyKey(
                        factoryId, normalizedKey);
        if (keyedPublish.isPresent()) {
            return replayCompletedPublish(
                    factoryId,
                    productTypeId,
                    normalizedKey,
                    revisionId,
                    revisionHash,
                    definitionVersion,
                    keyedPublish.get());
        }

        Optional<ProductProcessWorkflow> draft =
                find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (draft.isEmpty()) {
            ProductProcessWorkflow publishedWorkflow =
                    find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED)
                            .orElseThrow(() -> new BusinessException(
                                            409, "没有可发布或重放的 Workflow")
                                    .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                                    .withHint("请先保存 Workflow 草稿")
                                    .withSeverity("warning"));
            ProductProcessWorkflowRevision publishedRevision =
                    readCurrentRevision(publishedWorkflow);
            requireRequestedRevisionIdentity(
                    publishedRevision, revisionId, revisionHash, definitionVersion);
            throw new BusinessException(409, "该 Workflow 修订已由另一幂等键完成发布")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH")
                    .withHint(publishedWorkflow.getLastPublishIdempotencyKey() == null
                            ? "该历史发布没有可重放的请求键，请保存新草稿后再发布"
                            : "请复用首次发布返回的幂等键")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }

        ProductProcessWorkflowRevision targetRevision =
                readCurrentRevision(draft.get());
        requireRequestedRevisionIdentity(
                targetRevision, revisionId, revisionHash, definitionVersion);
        WorkflowBomSyncPreflightResponse preflight =
                workflowBomSynchronizationService.preflight(
                        factoryId, productTypeId, targetRevision);
        if (!preflight.isCanCompleteAutomatically()) {
            WorkflowBomSyncPreflightResponse.SyncIssue firstIssue =
                    !preflight.getConflicts().isEmpty()
                            ? preflight.getConflicts().getFirst()
                            : preflight.getMissingItems().isEmpty()
                            ? null
                            : preflight.getMissingItems().getFirst();
            throw new BusinessException(409,
                    firstIssue == null ? "BOM 与 Workflow 当前状态不能自动同步"
                            : firstIssue.getMessage())
                    .withCode("WORKFLOW_BOM_SYNC_" + preflight.getClassification().name())
                    .withHint(firstIssue == null ? "请刷新 BOM 与 Workflow 后重试"
                            : firstIssue.getAction())
                    .withHintTarget(firstIssue == null ? "bom" : firstIssue.getField())
                    .withSeverity("warning");
        }

        ProductProcessWorkflowDTO published =
                publishInternal(
                        factoryId, productTypeId, lockVersion, operatorId, true, normalizedKey);
        ProductProcessWorkflowActivationDTO activation =
                workflowActivationService.activate(factoryId, published.getId(), operatorId);
        reanchorByTopology(factoryId, productTypeId, published);
        return WorkflowPublishAndActivateResponse.builder()
                .workflow(published)
                .activation(activation)
                .bomSync(preflight)
                .idempotencyKey(normalizedKey)
                .replayed(false)
                .build();
    }

    private WorkflowPublishAndActivateResponse replayCompletedPublish(
            String factoryId,
            String productTypeId,
            String idempotencyKey,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion,
            ProductProcessWorkflow publishedWorkflow) {
        if (!Objects.equals(productTypeId, publishedWorkflow.getProductTypeId())
                || publishedWorkflow.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                || !Objects.equals(revisionId, publishedWorkflow.getLastPublishRevisionId())
                || !Objects.equals(revisionHash, publishedWorkflow.getLastPublishRevisionHash())
                || !Objects.equals(
                        definitionVersion,
                        publishedWorkflow.getLastPublishDefinitionVersion())) {
            throw new BusinessException(409, "该幂等键已绑定到另一条 Workflow 发布请求")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_CONFLICT")
                    .withHint("请勿复用其他产品或修订的发布幂等键")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        ProductProcessWorkflowRevision targetRevision =
                readCurrentRevision(publishedWorkflow);
        requireRequestedRevisionIdentity(
                targetRevision, revisionId, revisionHash, definitionVersion);
        WorkflowBomSyncPreflightResponse preflight =
                workflowBomSynchronizationService.preflight(
                        factoryId, productTypeId, targetRevision);
        ProductProcessWorkflowActivationDTO activation =
                workflowActivationService.get(factoryId, productTypeId);
        if (preflight.getClassification()
                        != WorkflowBomSyncPreflightResponse.Classification.READY
                || activation == null
                || !Boolean.TRUE.equals(activation.getEnabled())
                || !Objects.equals(
                        publishedWorkflow.getId(), activation.getActiveWorkflowId())
                || !Objects.equals(
                        publishedWorkflow.getDefinitionVersion(),
                        activation.getActiveDefinitionVersion())) {
            throw replayConflict();
        }
        return WorkflowPublishAndActivateResponse.builder()
                .workflow(toDTOWithUnitWarnings(publishedWorkflow))
                .activation(activation)
                .bomSync(preflight)
                .idempotencyKey(idempotencyKey)
                .replayed(true)
                .build();
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(400, "自动同步并发布必须提供幂等键")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_REQUIRED")
                    .withHint("请刷新页面后重试")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 128) {
            throw new BusinessException(400, "发布幂等键长度不能超过 128 个字符")
                    .withCode("WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_INVALID")
                    .withHint("请刷新页面生成新的发布请求")
                    .withHintTarget("idempotencyKey")
                    .withSeverity("warning");
        }
        return normalized;
    }

    private BusinessException replayConflict() {
        return new BusinessException(409,
                "当前状态不能证明这是同一次 Workflow 发布请求")
                .withCode("WORKFLOW_PUBLISH_REPLAY_CONFLICT")
                .withHint("请刷新页面并基于当前草稿重新发起发布")
                .withHintTarget("workflow")
                .withSeverity("warning");
    }

    private void requireRequestedRevisionIdentity(
            ProductProcessWorkflowRevision actual,
            Long revisionId,
            String revisionHash,
            Integer definitionVersion) {
        if (revisionId == null
                || revisionHash == null
                || revisionHash.isBlank()
                || definitionVersion == null
                || !Objects.equals(actual.getId(), revisionId)
                || !Objects.equals(actual.getRevisionHash(), revisionHash)
                || !Objects.equals(actual.getDefinitionVersion(), definitionVersion)) {
            throw new BusinessException(409, "Workflow 保存修订已变化，不能重放旧发布请求")
                    .withCode("WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT")
                    .withHint("请刷新页面并使用当前保存修订重新发布")
                    .withHintTarget("workflow")
                    .withSeverity("warning");
        }
    }

    private ProductProcessWorkflowDTO publishInternal(
            String factoryId,
            String productTypeId,
            Long lockVersion,
            Long operatorId,
            boolean synchronizeBom,
            String requestedIdempotencyKey) {
        ProductProcessWorkflow candidate = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "没有可发布的 Workflow 草稿")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                        .withHint("请先保存草稿，再发布版本")
                        .withSeverity("warning"));
        ProductProcessWorkflow draft = repository.lockByIdAndFactoryId(candidate.getId(), factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "Workflow 草稿状态已变化，请刷新后重试")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                        .withSeverity("warning"));
        assertCurrentVersion(lockVersion, draft);
        ProductProcessWorkflowDTO definition = toDTO(draft);
        validator.validateForPublish(definition);
        // 🔴 2026-08-08: 这里【必须】按 synchronizeBom 分叉, 否则 3-3 的 BOM 投影是空转的。
        //
        // catalogValidator.validateForPublish 内含 validateFinishedOutputBoms ——
        // 要求每个成品 Cell 都已有 ACTIVE BOM。而 BOM 同步/投影发生在下面几行之后。
        // 闸在投影前面 ⇒ 投影永远跑不到 ⇒ 「没有生效 BOM 也能发布」根本不成立。
        // (2026-08-08 真机实测发现: 3-3 部署后仍被这道闸拦下, 报的是另一个错误码,
        //  所以第一眼看不出是同一条规则的第三个承载点。)
        //
        // synchronizeBom=true 时先只做结构/目录校验, BOM 那半交给同步; 同步完成后
        // 再跑一次完整校验兜底 —— 见下方。
        if (synchronizeBom) {
            catalogValidator.validateForBomConfiguration(factoryId, productTypeId, definition);
        } else {
            catalogValidator.validateForPublish(factoryId, productTypeId, definition);
        }
        unitValidator.validateForPublish(factoryId, definition);
        ProductProcessWorkflowRevision revision = requireCurrentRevision(draft);
        if (!Boolean.TRUE.equals(revision.getStructurallyComplete())) {
            throw new BusinessException(409, "当前 Workflow 保存修订结构不完整")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_INCOMPLETE")
                    .withHint(revision.getValidationMessage());
        }
        if (synchronizeBom) {
            workflowBomSynchronizationService.synchronizeForPublish(
                    factoryId, productTypeId, revision, operatorId);
            // ⛔ 兜底, 不能省: 同步/投影之后 BOM 必须真的到位。少了这一句, 投影失败
            //    会被静默放过 —— 发布出一个没有 BOM 的 Workflow, 报工时无料可扣。
            catalogValidator.validateForPublish(factoryId, productTypeId, definition);
        }
        bomWorkflowRevisionService.requireActiveBomPinsRevision(factoryId, productTypeId, revision);
        draft.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        draft.setUnitReviewRequired(false);
        draft.setLastPublishIdempotencyKey(
                requestedIdempotencyKey == null
                        ? legacyPublishKey(revision)
                        : requestedIdempotencyKey);
        draft.setLastPublishRevisionId(revision.getId());
        draft.setLastPublishRevisionHash(revision.getRevisionHash());
        draft.setLastPublishDefinitionVersion(revision.getDefinitionVersion());
        revision.setStatus(ProductProcessWorkflowRevision.Status.PUBLISHED);
        revisionRepository.save(revision);
        return toDTO(repository.saveAndFlush(draft));
    }

    private String legacyPublishKey(ProductProcessWorkflowRevision revision) {
        return "legacy:" + revision.getId() + ":" + revision.getRevisionHash();
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO snapshot(String factoryId, String productTypeId, Long lockVersion) {
        requireWorkflowOwner(factoryId, productTypeId);
        ProductProcessWorkflow candidate = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "没有可另存的 Workflow 草稿")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                        .withHint("请先保存草稿，再另存为版本")
                        .withSeverity("warning"));
        ProductProcessWorkflow draft = repository.lockByIdAndFactoryId(candidate.getId(), factoryId)
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "Workflow 草稿状态已变化，请刷新后重试")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                        .withSeverity("warning"));
        assertCurrentVersion(lockVersion, draft);
        ProductProcessWorkflowDTO definition = toDTO(draft);
        validator.validateForDraft(definition);
        unitValidator.validate(factoryId, definition);

        ProductProcessWorkflow snapshot = new ProductProcessWorkflow();
        snapshot.setFactoryId(factoryId);
        snapshot.setProductTypeId(productTypeId);
        snapshot.setSchemaVersion(draft.getSchemaVersion());
        snapshot.setStatus(ProductProcessWorkflow.Status.SNAPSHOT);
        snapshot.setDefinitionVersion(draft.getDefinitionVersion());
        snapshot.setNodesJson(draft.getNodesJson());
        snapshot.setEdgesJson(draft.getEdgesJson());
        snapshot.setViewportJson(draft.getViewportJson());
        snapshot.setUnitReviewRequired(draft.getUnitReviewRequired());
        snapshot = repository.saveAndFlush(snapshot);
        ProductProcessWorkflowRevision snapshotRevision = revisionSnapshotService.capture(snapshot);
        snapshot.setCurrentRevisionId(snapshotRevision.getId());
        snapshot.setCurrentRevisionHash(snapshotRevision.getRevisionHash());
        repository.saveAndFlush(snapshot);

        draft.setDefinitionVersion(draft.getDefinitionVersion() + 1);
        // The draft now represents a new mutable definition version. It must not
        // continue advertising the immutable revision captured for the snapshot.
        draft.setCurrentRevisionId(null);
        draft.setCurrentRevisionHash(null);
        return toDTO(repository.saveAndFlush(draft));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductProcessWorkflowVersionSummaryDTO> listVersions(String factoryId, String productTypeId) {
        requireWorkflowOwner(factoryId, productTypeId);
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        List<ProductProcessWorkflowRepository.VersionSummaryProjection> rows = repository
                .findVersionSummaries(factoryId, productTypeId);
        return rows.stream()
                .map(row -> toVersionSummaryDTO(row, activation))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductProcessWorkflowDTO getVersion(String factoryId, String productTypeId, Integer definitionVersion) {
        requireWorkflowOwner(factoryId, productTypeId);
        ProductProcessWorkflow row = repository
                .findFirstByFactoryIdAndProductTypeIdAndDefinitionVersion(factoryId, productTypeId, definitionVersion)
                .orElseThrow(() -> new BusinessException(
                                404, "该产品不存在 Workflow 版本: " + definitionVersion)
                        .withCode("PRODUCT_PROCESS_WORKFLOW_VERSION_NOT_FOUND")
                        .withHint("请从版本历史列表中选择一个存在的版本号")
                        .withSeverity("warning"));
        return toDTO(row);
    }

    private ProductProcessWorkflowVersionSummaryDTO toVersionSummaryDTO(
            ProductProcessWorkflowRepository.VersionSummaryProjection row,
            Optional<ProductProcessWorkflowActivation> activation) {
        boolean active = activation
                .filter(a -> Boolean.TRUE.equals(a.getEnabled()))
                .map(a -> row.getId().equals(a.getActiveWorkflowId()))
                .orElse(false);
        return new ProductProcessWorkflowVersionSummaryDTO(
                row.getDefinitionVersion(),
                row.getStatus().name(),
                row.getUpdatedAt(),
                active);
    }

    private Optional<ProductProcessWorkflow> find(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status) {
        return repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                factoryId, productTypeId, status);
    }


    /**
     * 发布后按研判结论把整条谱系搬到正确的归属对象下 —— 「归属对象自动更改」的落地点。
     *
     * <p>研判早就能认出「原料分流」并显示在顶部, 但此前**没有任何代码拿这个结论去改归属对象**:
     * 一张单原料分流成 C、D 的图, 归属对象仍钉在成品 C 上, 让人以为图属于 C。
     * 规则见 {@link WorkflowAnchorPolicy} (联产不动 —— 任选一个当锚都是编的)。
     *
     * <p><b>为什么整条谱系一起搬</b>: {@code (factoryId, productTypeId)} 就是谱系键,
     * 只搬新发布那一版会把版本历史劈成两半。三张带 product_type_id 的表
     * (workflows / revisions / activations) 必须同时搬, 漏一张就对不上。
     *
     * <p><b>为什么现在敢搬</b>: V20261029_81 已把 product_type_id 从
     * fk_ppwa_active_workflow_owner / fk_production_plan_selected_workflow / fk_pwi_workflow_owner
     * 三条复合外键里摘掉, 归属对象变了不会打断已启用记录、在跑的生产计划和运行实例。
     *
     * @return 真的搬了就返回新归属对象 id, 没搬返回 empty
     */
    private Optional<String> reanchorByTopology(
            String factoryId, String currentOwnerId, ProductProcessWorkflowDTO published) {
        Optional<String> desired = WorkflowAnchorPolicy.desiredOwner(
                published, WorkflowTopologyClassifier.classify(published));
        if (desired.isEmpty() || desired.get().equals(currentOwnerId)) return Optional.empty();
        String newOwnerId = desired.get();

        // ⛔ 目标已经有自己的谱系 → 不搬。activations 上有 UNIQUE(factory_id, product_type_id),
        //    硬搬会撞唯一键; 而且两条谱系合并不是「换个归属」能表达的事, 需要人来决定留哪条。
        //    这里选择静默留在原地 + 打日志: 发布本身是成功的, 不该因为归属没搬成而失败。
        if (repository.existsByFactoryIdAndProductTypeId(factoryId, newOwnerId)) {
            log.warn("[Workflow] 归属对象未自动搬迁: factory={} {} → {} (目标已有自己的工艺图谱系)",
                    factoryId, currentOwnerId, newOwnerId);
            return Optional.empty();
        }

        // ⛔ 目标必须真实存在于本厂主数据。不先判就直接 requireWorkflowOwner 的话, 它会抛
        //    PRODUCT_PROCESS_WORKFLOW_OWNER_INVALID —— **把整个发布带挂**。
        //    搬归属是发布的附带动作, 附带动作永远不该弄挂主动作 (单测 publishValidDraft 当场抓到)。
        boolean ownerResolvable =
                productTypeRepository.findByIdAndFactoryId(newOwnerId, factoryId).isPresent()
                        || rawMaterialTypeRepository.findById(newOwnerId)
                                .filter(raw -> factoryId.equals(raw.getFactoryId()))
                                .isPresent();
        if (!ownerResolvable) {
            log.warn("[Workflow] 归属对象未自动搬迁: factory={} {} → {} (目标不在本厂主数据里)",
                    factoryId, currentOwnerId, newOwnerId);
            return Optional.empty();
        }

        try {
            // 归属对象可以是原料 —— requireWorkflowOwner 会为 RawMaterialType 懒建 inactive 内部锚点
            // (__RAW_WORKFLOW_OWNER__*), 前端产品目录会过滤掉, 不把原料伪装成 SKU。
            requireWorkflowOwner(factoryId, newOwnerId);

            int movedWorkflows = repository.reanchorLineage(factoryId, currentOwnerId, newOwnerId);
            int movedRevisions = revisionRepository.reanchorOwner(factoryId, currentOwnerId, newOwnerId);
            int movedActivations =
                    activationRepository.reanchorOwner(factoryId, currentOwnerId, newOwnerId);
            log.info("[Workflow] 归属对象按研判自动搬迁: factory={} {} → {} "
                            + "(workflows={} revisions={} activations={})",
                    factoryId, currentOwnerId, newOwnerId,
                    movedWorkflows, movedRevisions, movedActivations);
            return Optional.of(newOwnerId);
        } catch (RuntimeException reanchorFailed) {
            // 兜底: 搬不动就留在原地, 顶部的「系统研判」照旧如实显示拓扑。
            // 宁可归属对象暂时不准, 也不能让用户的发布失败。
            log.warn("[Workflow] 归属对象搬迁失败, 保持原归属: factory={} {} → {}",
                    factoryId, currentOwnerId, newOwnerId, reanchorFailed);
            return Optional.empty();
        }
    }

    private void requireWorkflowOwner(String factoryId, String ownerId) {
        if (productTypeRepository.findByIdAndFactoryId(ownerId, factoryId).isPresent()) return;

        RawMaterialType rawOwner = rawMaterialTypeRepository.findById(ownerId)
                .filter(raw -> factoryId.equals(raw.getFactoryId()))
                .orElseThrow(() -> new BusinessException(400, "Workflow 所属成品或原料不存在于当前工厂")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_OWNER_INVALID")
                        .withHint("请重新选择当前工厂内的有效成品或入口原料")
                        .withSeverity("warning"));

        // 运行时与历史外键仍以 product_type_id 作为通用 owner key。为真正的 RawMaterialType
        // 创建一个 inactive 内部锚点，前端产品目录会过滤 RAW_MATERIAL，不再把原料伪装成 SKU。
        ProductType anchor = new ProductType();
        anchor.setId(rawOwner.getId());
        anchor.setFactoryId(factoryId);
        anchor.setCode(rawOwnerAnchorCode(rawOwner));
        anchor.setName("__RAW_WORKFLOW_OWNER__" + rawOwner.getId());
        anchor.setCategory(ProductCategory.RAW_MATERIAL);
        anchor.setProductCategory(ProductCategory.RAW_MATERIAL);
        anchor.setUnit(rawOwner.getUnit() == null || rawOwner.getUnit().isBlank() ? "kg" : rawOwner.getUnit());
        anchor.setIsActive(false);
        // product_types.created_by is NOT NULL. The internal anchor belongs to the
        // same master-data lineage as its raw material, so inherit its creator;
        // legacy raw rows without creator use the repository's established system user.
        anchor.setCreatedBy(rawOwner.getCreatedBy() == null ? 1L : rawOwner.getCreatedBy());
        try {
            productTypeRepository.saveAndFlush(anchor);
        } catch (DataIntegrityViolationException race) {
            if (productTypeRepository.findByIdAndFactoryId(ownerId, factoryId).isEmpty()) throw race;
        }
    }

    private String rawOwnerAnchorCode(RawMaterialType rawOwner) {
        String source = rawOwner.getCode() == null || rawOwner.getCode().isBlank()
                ? rawOwner.getId() : rawOwner.getCode();
        String normalized = source.replaceAll("[^A-Za-z0-9_-]", "");
        String code = "WFRAW-" + (normalized.isBlank() ? Integer.toUnsignedString(source.hashCode()) : normalized);
        return code.length() <= 50 ? code : code.substring(0, 50);
    }

    private void assertCurrentVersion(Long requestVersion, ProductProcessWorkflow entity) {
        if (requestVersion == null || !requestVersion.equals(entity.getLockVersion())) {
            throw new BusinessException(409, "该 Workflow 已被其他人更新")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                    .withHint("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON")
                    .withSeverity("warning");
        }
    }

    private ProductProcessWorkflowDTO toDTO(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(entity.getId());
        dto.setFactoryId(entity.getFactoryId());
        dto.setProductTypeId(entity.getProductTypeId());
        dto.setSchemaVersion(entity.getSchemaVersion());
        dto.setStatus(entity.getStatus().name());
        dto.setVersion(entity.getDefinitionVersion());
        dto.setLockVersion(entity.getLockVersion());
        dto.setRevisionId(entity.getCurrentRevisionId());
        dto.setRevisionHash(entity.getCurrentRevisionHash());
        dto.setUnitReviewRequired(entity.getUnitReviewRequired());
        dto.setNodes(readJson(
                entity.getNodesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {},
                "nodes"));
        dto.setEdges(readJson(
                entity.getEdgesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() {},
                "edges"));
        dto.setViewport(readJson(
                entity.getViewportJson(),
                new TypeReference<ProductProcessWorkflowDTO.Viewport>() {},
                "viewport"));
        return dto;
    }

    private ProductProcessWorkflowRevision requireCurrentRevision(ProductProcessWorkflow workflow) {
        ProductProcessWorkflowRevision revision = revisionSnapshotService.captureDraft(workflow);
        if (!revision.getId().equals(workflow.getCurrentRevisionId())
                || !revision.getRevisionHash().equals(workflow.getCurrentRevisionHash())) {
            workflow.setCurrentRevisionId(revision.getId());
            workflow.setCurrentRevisionHash(revision.getRevisionHash());
        }
        return revision;
    }

    private ProductProcessWorkflowRevision readCurrentRevision(ProductProcessWorkflow workflow) {
        if (workflow.getCurrentRevisionId() == null
                || workflow.getCurrentRevisionHash() == null) {
            throw new BusinessException(409, "Workflow 缺少当前保存修订")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_MISSING")
                    .withHint("请先保存 Workflow 草稿后重试")
                    .withSeverity("warning");
        }
        return revisionRepository
                .findByIdAndFactoryId(workflow.getCurrentRevisionId(), workflow.getFactoryId())
                .filter(revision -> workflow.getId().equals(revision.getWorkflowId()))
                .filter(revision -> workflow.getCurrentRevisionHash()
                        .equals(revision.getRevisionHash()))
                .orElseThrow(() -> new BusinessException(409, "Workflow 当前修订身份不一致")
                        .withCode("WORKFLOW_REVISION_IDENTITY_MISMATCH")
                        .withHint("请刷新并重新保存 Workflow")
                        .withSeverity("warning"));
    }

    private ProductProcessWorkflowDTO toDTOWithUnitWarnings(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = toDTO(entity);
        dto.setUnitWarnings(unitValidator.validate(entity.getFactoryId(), dto).errors());
        return dto;
    }

    /**
     * A delayed autosave may arrive after the only draft has already been published. Compare the
     * incoming business graph against that published row using the revision service's canonical
     * hash. Definition version and viewport are pinned to the published row: version allocation
     * and a late camera pan/zoom are not Workflow business changes, while schema, nodes and edges
     * remain authoritative. Keep this server-side guard after the Web autosave barrier until every
     * supported client has an equivalent publish generation barrier and late-save telemetry stays
     * at zero; only then is the no-op compatibility path removable.
     */
    private boolean sameBusinessGraph(
            ProductProcessWorkflow published,
            ProductProcessWorkflowDTO definition) {
        ProductProcessWorkflow candidate = new ProductProcessWorkflow();
        candidate.setFactoryId(published.getFactoryId());
        candidate.setProductTypeId(published.getProductTypeId());
        candidate.setDefinitionVersion(published.getDefinitionVersion());
        candidate.setSchemaVersion(definition.getSchemaVersion());
        candidate.setNodesJson(writeJson(definition.getNodes()));
        candidate.setEdgesJson(writeJson(definition.getEdges()));
        candidate.setViewportJson(published.getViewportJson());
        return Objects.equals(
                revisionSnapshotService.hash(published),
                revisionSnapshotService.hash(candidate));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow 数据无法序列化", error);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow " + field + " 数据损坏", error);
        }
    }

    private BusinessException invalidStoredJson(String message, JsonProcessingException cause) {
        return (BusinessException) new BusinessException(500, message, cause)
                .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID")
                .withHint("请联系管理员检查该产品的 Workflow 图定义")
                .withSeverity("error");
    }
}
