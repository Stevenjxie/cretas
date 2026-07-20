package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowRevisionSnapshotService {

    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final ProductProcessWorkflowValidator validator;
    private final ObjectMapper objectMapper;

    public ProductProcessWorkflowRevision capture(ProductProcessWorkflow workflow) {
        String hash = hash(workflow);
        Optional<ProductProcessWorkflowRevision> existing = revisionRepository
                .findByWorkflowIdAndRevisionHash(workflow.getId(), hash);
        if (existing.isPresent()) return existing.get();

        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setFactoryId(workflow.getFactoryId());
        revision.setProductTypeId(workflow.getProductTypeId());
        revision.setWorkflowId(workflow.getId());
        revision.setDefinitionVersion(workflow.getDefinitionVersion());
        revision.setRevisionNumber(revisionRepository.findMaxRevisionNumber(workflow.getId()) + 1);
        revision.setRevisionHash(hash);
        revision.setStatus(workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                ? ProductProcessWorkflowRevision.Status.PUBLISHED
                : ProductProcessWorkflowRevision.Status.DRAFT);
        revision.setSchemaVersion(workflow.getSchemaVersion());
        revision.setNodesJson(workflow.getNodesJson());
        revision.setEdgesJson(workflow.getEdgesJson());
        revision.setViewportJson(workflow.getViewportJson());
        ProductProcessWorkflowDTO definition = definition(revision);
        revision.setProcessCount((int) definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).count());
        try {
            validator.validateStructureComplete(definition);
            revision.setStructurallyComplete(true);
        } catch (BusinessException error) {
            revision.setStructurallyComplete(false);
            revision.setValidationMessage(error.getMessage());
        }
        try {
            return revisionRepository.saveAndFlush(revision);
        } catch (DataIntegrityViolationException race) {
            return revisionRepository.findByWorkflowIdAndRevisionHash(workflow.getId(), hash)
                    .orElseThrow(() -> race);
        }
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
        return hash(workflow.getFactoryId(), workflow.getProductTypeId(), workflow.getDefinitionVersion(),
                workflow.getSchemaVersion(), workflow.getNodesJson(), workflow.getEdgesJson(), workflow.getViewportJson());
    }

    public String hash(ProductProcessWorkflowRevision revision) {
        return hash(revision.getFactoryId(), revision.getProductTypeId(), revision.getDefinitionVersion(),
                revision.getSchemaVersion(), revision.getNodesJson(), revision.getEdgesJson(), revision.getViewportJson());
    }

    private String hash(String factoryId, String productTypeId, Integer definitionVersion,
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

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "Workflow revision " + field + " 数据损坏", error)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_REVISION_DATA_INVALID");
        }
    }
}
