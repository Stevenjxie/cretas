package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductProcessWorkflowValidatorTest {

    private final ProductProcessWorkflowValidator validator = new ProductProcessWorkflowValidator();

    @Test
    void draftAllowsAnIncompleteUnboundMaterialBoundary() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(material("raw", "RAW_MATERIAL"))));
        definition.setEdges(new ArrayList<>());

        assertDoesNotThrow(() -> validator.validateForDraft(definition));
    }

    @Test
    void draftAcceptsAClosedMultiInputProcess() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();

        assertDoesNotThrow(() -> validator.validateForDraft(definition));
    }

    @Test
    void draftRejectsFractionalNegativeAndDuplicateOrdinals() {
        for (Object ordinal : List.of(-1, 0.5D)) {
            ProductProcessWorkflowDTO definition = validMultiInputDefinition();
            inputPort(definition, "in-a").put("ordinal", ordinal);

            BusinessException error = assertThrows(
                    BusinessException.class,
                    () -> validator.validateForDraft(definition));

            assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
        }

        ProductProcessWorkflowDTO duplicate = validMultiInputDefinition();
        inputPort(duplicate, "in-b").put("ordinal", 0);
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(duplicate));
        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
    }

    @Test
    void draftRejectsDuplicateEdgesForOneDeclaredInputPort() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        definition.getEdges().add(edge(
                "duplicate", "raw-b", "output", "process", "in-a"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
    }

    @Test
    void draftRejectsCollidingProcessAndPortIdentifiers() {
        ProductProcessWorkflowDTO definition = collidingPortKeyDefinition();

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
    }

    private ProductProcessWorkflowDTO validMultiInputDefinition() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL"),
                material("raw-b", "RAW_MATERIAL"),
                process("process", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", 0),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", 1),
                        port("out", "OUTPUT", "semi", "SEMI_FINISHED", 0))),
                material("semi", "SEMI_FINISHED"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("e1", "raw-a", "output", "process", "in-a"),
                edge("e2", "raw-b", "output", "process", "in-b"),
                edge("e3", "process", "out", "semi", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO collidingPortKeyDefinition() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(new ArrayList<>(List.of(
                material("raw-1", "RAW_MATERIAL"),
                material("raw-2", "RAW_MATERIAL"),
                process("p", List.of(
                        port("x::y", "INPUT", "raw-1", "RAW_MATERIAL", 0),
                        port("out-p", "OUTPUT", "semi-1", "SEMI_FINISHED", 0))),
                material("semi-1", "SEMI_FINISHED"),
                process("p::x", List.of(
                        port("y", "INPUT", "raw-2", "RAW_MATERIAL", 0),
                        port("other", "INPUT", "raw-1", "RAW_MATERIAL", 1),
                        port("out-px", "OUTPUT", "semi-2", "SEMI_FINISHED", 0))),
                material("semi-2", "SEMI_FINISHED"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("p-output", "p", "out-p", "semi-1", "input"),
                edge("px-input", "raw-2", "output", "p::x", "y"),
                edge("px-other-input", "raw-1", "output", "p::x", "other"),
                edge("px-output", "p::x", "out-px", "semi-2", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind) {
        return new ProductProcessWorkflowDTO.Node(
                id,
                kind,
                new ProductProcessWorkflowDTO.Position(0D, 0D),
                new LinkedHashMap<>(Map.of("name", id)));
    }

    private ProductProcessWorkflowDTO.Node process(
            String id,
            List<Map<String, Object>> ports) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("processName", "mix");
        data.put("ports", new ArrayList<>(ports));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private Map<String, Object> port(
            String id,
            String direction,
            String materialNodeId,
            String materialKind,
            Object ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", materialKind);
        port.put("ordinal", ordinal);
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(
            String id,
            String source,
            String sourceHandle,
            String target,
            String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(
                id, source, sourceHandle, target, targetHandle);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputPort(
            ProductProcessWorkflowDTO definition,
            String portId) {
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst()
                .orElseThrow();
        return ((List<Map<String, Object>>) process.getData().get("ports")).stream()
                .filter(port -> portId.equals(port.get("id")))
                .findFirst()
                .orElseThrow();
    }
}
