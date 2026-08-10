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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void publishRejectsSelectionGroupBoundsThatDoNotMatchFrontendContract() {
        ProductProcessWorkflowDTO definition = validPublishDefinition();
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst().orElseThrow();
        process.getData().put("portGroups", List.of(Map.of(
                "id", "inputs",
                "direction", "INPUT",
                "label", "替代投入",
                "mode", "AT_LEAST_ONE",
                "minSelections", 1,
                "maxSelections", 1,
                "portIds", List.of("in-a", "in-b"))));

        BusinessException error = assertThrows(
                BusinessException.class, () -> validator.validateForPublish(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_INVALID", error.getErrorCode());
    }

    @Test
    void draftCanAutosaveButCompleteRevisionRejectsMissingMultiOutputContract() {
        ProductProcessWorkflowDTO definition = validMultiOutputPublishDefinition();
        outputPort(definition, "out-main").remove("outputRole");
        outputPort(definition, "out-main").remove("costAllocationRatio");

        assertDoesNotThrow(() -> validator.validateForDraft(definition));
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateStructureComplete(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_OUTPUT_CONTRACT_REQUIRED", error.getErrorCode());
    }

    @Test
    void completeRevisionAcceptsExplicitMultiOutputContract() {
        ProductProcessWorkflowDTO definition = validMultiOutputPublishDefinition();

        assertDoesNotThrow(() -> validator.validateStructureComplete(definition));
    }

    @Test
    void completeRevisionRejectsMultiOutputAllocationThatDoesNotTotalOneHundred() {
        ProductProcessWorkflowDTO definition = validMultiOutputPublishDefinition();
        outputPort(definition, "out-main").put("costAllocationRatio", 80);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateStructureComplete(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_OUTPUT_CONTRACT_INVALID", error.getErrorCode());
    }

    @Test
    void actualIoReportingDoesNotRequireStaticOutputRolesOrAllocation() {
        ProductProcessWorkflowDTO definition = validMultiOutputPublishDefinition();
        definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .forEach(node -> {
                    node.getData().put("reportingSelectionMode", "ACTUAL_IO");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ports =
                            (List<Map<String, Object>>) node.getData().get("ports");
                    ports.stream()
                            .filter(port -> "OUTPUT".equals(port.get("direction")))
                            .forEach(port -> {
                                port.remove("outputRole");
                                port.remove("costAllocationRatio");
                            });
                });

        assertDoesNotThrow(() -> validator.validateStructureComplete(definition));
    }

    @Test
    void draftAcceptsOneLevelSubstituteReference() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        node(definition, "raw-b").getData().put("substituteOfNodeId", "raw-a");

        assertDoesNotThrow(() -> validator.validateForDraft(definition));
    }

    @Test
    void rejectsSubstituteOfNodeIdPointingAtItself() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        node(definition, "raw-b").getData().put("substituteOfNodeId", "raw-b");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_SUBSTITUTE_INVALID", error.getErrorCode());
        // 🔴 必须断言这句话本身: 自引用同时也满足「成链」规则(parent 就是自己, 它当然带着
        //    substituteOfNodeId), 只断言 errorCode 的话, 把自引用规则整条删掉测试照样全绿
        //    —— 实测过, 变异不红。用户看到的诊断是哪一句, 才是这条规则唯一的可观测产物。
        assertTrue(error.getMessage().contains("不能指向自己"),
                "自引用要按自引用报, 不能报成「替代关系只能有一层」: " + error.getMessage());
    }

    @Test
    void rejectsSubstituteOfNodeIdPointingAtMissingNode() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        node(definition, "raw-b").getData().put("substituteOfNodeId", "raw-ghost");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_SUBSTITUTE_INVALID", error.getErrorCode());
    }

    @Test
    void rejectsSubstituteOfNodeIdPointingAtNonRawMaterial() {
        for (String target : List.of("process", "semi")) {
            ProductProcessWorkflowDTO definition = validMultiInputDefinition();
            node(definition, "raw-b").getData().put("substituteOfNodeId", target);

            BusinessException error = assertThrows(
                    BusinessException.class,
                    () -> validator.validateForDraft(definition));

            assertEquals("PRODUCT_PROCESS_WORKFLOW_SUBSTITUTE_INVALID", error.getErrorCode());
        }
    }

    @Test
    void rejectsSubstituteChain() {
        // A→B→C: 并查集会把三个合成一个逻辑投入, 但业务上没人这么表达。只允许一层。
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        ProductProcessWorkflowDTO.Node third = material("raw-c", "RAW_MATERIAL");
        definition.getNodes().add(third);
        node(definition, "raw-b").getData().put("substituteOfNodeId", "raw-a");
        third.getData().put("substituteOfNodeId", "raw-b");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> validator.validateForDraft(definition));

        assertEquals("PRODUCT_PROCESS_WORKFLOW_SUBSTITUTE_INVALID", error.getErrorCode());
    }

    private ProductProcessWorkflowDTO.Node node(ProductProcessWorkflowDTO definition, String id) {
        return definition.getNodes().stream()
                .filter(candidate -> id.equals(candidate.getId()))
                .findFirst().orElseThrow();
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

    @SuppressWarnings("unchecked")
    private ProductProcessWorkflowDTO validPublishDefinition() {
        ProductProcessWorkflowDTO definition = validMultiInputDefinition();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind())) {
                node.getData().put("skuId", "SKU-" + node.getId());
            }
        }
        ProductProcessWorkflowDTO.Node output = definition.getNodes().stream()
                .filter(node -> "semi".equals(node.getId()))
                .findFirst().orElseThrow();
        output.setKind("FINISHED_GOOD");
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst().orElseThrow();
        List<Map<String, Object>> ports =
                (List<Map<String, Object>>) process.getData().get("ports");
        ports.forEach(port -> port.put("unit", "kg"));
        ports.stream().filter(port -> "out".equals(port.get("id")))
                .findFirst().orElseThrow().put("materialKind", "FINISHED_GOOD");
        return definition;
    }

    private ProductProcessWorkflowDTO validMultiOutputPublishDefinition() {
        ProductProcessWorkflowDTO definition = validPublishDefinition();
        ProductProcessWorkflowDTO.Node process = definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ports =
                (List<Map<String, Object>>) process.getData().get("ports");
        Map<String, Object> main = ports.stream()
                .filter(port -> "out".equals(port.get("id")))
                .findFirst().orElseThrow();
        main.put("id", "out-main");
        main.put("outputRole", "MAIN");
        main.put("costAllocationRatio", 70);

        Map<String, Object> coProduct = port(
                "out-co", "OUTPUT", "finished-co", "FINISHED_GOOD", 1);
        coProduct.put("unit", "kg");
        coProduct.put("outputRole", "CO_PRODUCT");
        coProduct.put("costAllocationRatio", 30);
        ports.add(coProduct);

        ProductProcessWorkflowDTO.Node finishedCo = material("finished-co", "FINISHED_GOOD");
        finishedCo.getData().put("skuId", "SKU-finished-co");
        definition.getNodes().add(finishedCo);
        definition.getEdges().add(edge(
                "e4", "process", "out-co", "finished-co", "input"));
        definition.getEdges().stream()
                .filter(edge -> "e3".equals(edge.getId()))
                .forEach(edge -> edge.setSourceHandle("out-main"));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputPort(
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
