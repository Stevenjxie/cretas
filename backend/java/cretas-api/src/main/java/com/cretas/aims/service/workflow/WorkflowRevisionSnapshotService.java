package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowRevisionSnapshotService {

    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final BomRecipeRepository recipeRepository;
    private final ProductProcessWorkflowValidator validator;
    private final ObjectMapper objectMapper;

    public ProductProcessWorkflowRevision capture(ProductProcessWorkflow workflow) {
        String hash = hash(workflow);
        Optional<ProductProcessWorkflowRevision> existing = revisionRepository
                .findByWorkflowIdAndRevisionHash(workflow.getId(), hash);
        if (existing.isPresent()) return existing.get();

        return createRevision(workflow, hash);
    }

    /**
     * Saves the one mutable revision behind an editable Workflow draft.
     *
     * <p>Once any BOM version pins the current revision, that revision becomes immutable and the
     * next draft save creates a new revision. Until then, repeated saves update the same revision
     * instead of turning canvas autosaves into permanent history.
     */
    public ProductProcessWorkflowRevision captureDraft(ProductProcessWorkflow workflow) {
        String hash = hash(workflow);
        ProductProcessWorkflowRevision current = Optional.ofNullable(workflow.getCurrentRevisionId())
                .flatMap(id -> revisionRepository.findByIdAndFactoryId(id, workflow.getFactoryId()))
                .filter(revision -> Objects.equals(workflow.getId(), revision.getWorkflowId()))
                .filter(revision -> revision.getStatus() == ProductProcessWorkflowRevision.Status.DRAFT)
                .orElse(null);
        if (current != null
                && sameDefinition(workflow, current)
                && Objects.equals(current.getRevisionHash(), hash(current))) {
            return current;
        }

        Optional<ProductProcessWorkflowRevision> matching = revisionRepository
                .findByWorkflowIdAndRevisionHash(workflow.getId(), hash);
        if (matching.isPresent()) return matching.get();

        if (current == null || isPinnedByAnyBom(workflow.getFactoryId(), current.getId())) {
            return createRevision(workflow, hash);
        }

        applySnapshot(current, workflow, hash);
        return revisionRepository.saveAndFlush(current);
    }

    private boolean sameDefinition(
            ProductProcessWorkflow workflow, ProductProcessWorkflowRevision revision) {
        return Objects.equals(workflow.getFactoryId(), revision.getFactoryId())
                && Objects.equals(workflow.getProductTypeId(), revision.getProductTypeId())
                && Objects.equals(workflow.getDefinitionVersion(), revision.getDefinitionVersion())
                && Objects.equals(workflow.getSchemaVersion(), revision.getSchemaVersion())
                && Objects.equals(
                        canonicalJson(workflow.getNodesJson(), "nodes"),
                        canonicalJson(revision.getNodesJson(), "nodes"))
                && Objects.equals(
                        canonicalJson(workflow.getEdgesJson(), "edges"),
                        canonicalJson(revision.getEdgesJson(), "edges"))
                && Objects.equals(
                        canonicalJson(workflow.getViewportJson(), "viewport"),
                        canonicalJson(revision.getViewportJson(), "viewport"));
    }

    private ProductProcessWorkflowRevision createRevision(
            ProductProcessWorkflow workflow, String hash) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setFactoryId(workflow.getFactoryId());
        revision.setProductTypeId(workflow.getProductTypeId());
        revision.setWorkflowId(workflow.getId());
        revision.setRevisionNumber(revisionRepository.findMaxRevisionNumber(workflow.getId()) + 1);
        revision.setStatus(workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                ? ProductProcessWorkflowRevision.Status.PUBLISHED
                : ProductProcessWorkflowRevision.Status.DRAFT);
        applySnapshot(revision, workflow, hash);
        try {
            return revisionRepository.saveAndFlush(revision);
        } catch (DataIntegrityViolationException race) {
            return revisionRepository.findByWorkflowIdAndRevisionHash(workflow.getId(), hash)
                    .orElseThrow(() -> race);
        }
    }

    private void applySnapshot(
            ProductProcessWorkflowRevision revision,
            ProductProcessWorkflow workflow,
            String hash) {
        revision.setProductTypeId(workflow.getProductTypeId());
        revision.setDefinitionVersion(workflow.getDefinitionVersion());
        revision.setRevisionHash(hash);
        revision.setSchemaVersion(workflow.getSchemaVersion());
        revision.setNodesJson(canonicalJson(workflow.getNodesJson(), "nodes"));
        revision.setEdgesJson(canonicalJson(workflow.getEdgesJson(), "edges"));
        revision.setViewportJson(canonicalJson(workflow.getViewportJson(), "viewport"));
        ProductProcessWorkflowDTO definition = definition(revision);
        revision.setProcessCount((int) definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).count());
        try {
            validator.validateStructureComplete(definition);
            revision.setStructurallyComplete(true);
            revision.setValidationMessage(null);
        } catch (BusinessException error) {
            revision.setStructurallyComplete(false);
            revision.setValidationMessage(error.getMessage());
        }
    }

    private boolean isPinnedByAnyBom(String factoryId, Long revisionId) {
        return java.util.Arrays.stream(com.cretas.aims.entity.bom.BomRecipe.Status.values())
                .anyMatch(status -> !recipeRepository
                        .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                                factoryId, revisionId, status)
                        .isEmpty());
    }

    public ProductProcessWorkflowDTO definition(ProductProcessWorkflowRevision revision) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(revision.getWorkflowId());
        dto.setFactoryId(revision.getFactoryId());
        dto.setProductTypeId(revision.getProductTypeId());
        dto.setSchemaVersion(revision.getSchemaVersion());
        dto.setStatus(revision.getStatus().name());
        dto.setVersion(revision.getDefinitionVersion());
        dto.setRevisionId(revision.getId());
        dto.setRevisionHash(revision.getRevisionHash());
        dto.setNodes(read(revision.getNodesJson(), new TypeReference<>() {}, "nodes"));
        dto.setEdges(read(revision.getEdgesJson(), new TypeReference<>() {}, "edges"));
        dto.setViewport(read(revision.getViewportJson(), new TypeReference<>() {}, "viewport"));
        return dto;
    }

    public String hash(ProductProcessWorkflow workflow) {
        return canonicalHash(
                workflow.getFactoryId(),
                workflow.getProductTypeId(),
                workflow.getDefinitionVersion(),
                workflow.getSchemaVersion(),
                workflow.getNodesJson(),
                workflow.getEdgesJson(),
                workflow.getViewportJson());
    }

    public String hash(ProductProcessWorkflowRevision revision) {
        String storedOrderHash = rawHash(
                revision.getFactoryId(),
                revision.getProductTypeId(),
                revision.getDefinitionVersion(),
                revision.getSchemaVersion(),
                revision.getNodesJson(),
                revision.getEdgesJson(),
                revision.getViewportJson());
        if (Objects.equals(revision.getRevisionHash(), storedOrderHash)) {
            return storedOrderHash;
        }

        String canonicalHash = canonicalHash(
                revision.getFactoryId(),
                revision.getProductTypeId(),
                revision.getDefinitionVersion(),
                revision.getSchemaVersion(),
                revision.getNodesJson(),
                revision.getEdgesJson(),
                revision.getViewportJson());
        return canonicalHash;
    }

    private String canonicalHash(
            String factoryId,
            String productTypeId,
            Integer definitionVersion,
            Integer schemaVersion,
            String nodes,
            String edges,
            String viewport) {
        return rawHash(
                factoryId,
                productTypeId,
                definitionVersion,
                schemaVersion,
                canonicalJson(nodes, "nodes"),
                canonicalJson(edges, "edges"),
                canonicalJson(viewport, "viewport"));
    }

    private String rawHash(String factoryId, String productTypeId, Integer definitionVersion,
                           Integer schemaVersion, String nodes, String edges, String viewport) {
        String source = factoryId + "\n" + productTypeId + "\n" + definitionVersion + "\n"
                + schemaVersion + "\n" + nodes + "\n" + edges + "\n" + viewport;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String canonicalJson(String json, String field) {
        try {
            return objectMapper.writeValueAsString(sortObjectKeys(objectMapper.readTree(json)));
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "Workflow revision " + field + " 数据损坏", error)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_DATA_INVALID");
        }
    }

    private JsonNode sortObjectKeys(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            node.forEach(child -> sorted.add(sortObjectKeys(child)));
            return sorted;
        }
        ObjectNode sorted = objectMapper.createObjectNode();
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        fields.sort(Comparator.naturalOrder());
        fields.forEach(field -> sorted.set(field, sortObjectKeys(node.get(field))));
        return sorted;
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "Workflow revision " + field + " 数据损坏", error)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_DATA_INVALID");
        }
    }
}
