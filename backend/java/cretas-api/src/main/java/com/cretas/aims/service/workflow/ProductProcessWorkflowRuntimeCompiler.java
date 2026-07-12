package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Component
public class ProductProcessWorkflowRuntimeCompiler {

    private final ObjectMapper objectMapper;
    private final ProductProcessWorkflowValidator validator;

    public ProductProcessWorkflowRuntimeCompiler(
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public CompiledProductProcessWorkflow compile(ProductProcessWorkflowDTO definition) {
        ParsedExecutionData parsedExecutionData = parseExecutionData(definition);
        validator.validateForPublish(definition);

        Map<String, ProductProcessWorkflowDTO.Node> nodesById = indexNodes(definition);
        List<String> topologicalNodeIds = topologicalNodeIds(definition);
        List<CompiledProductProcessWorkflow.CompiledTask> tasks = new ArrayList<>();
        List<CompiledProductProcessWorkflow.CompiledPort> ports = new ArrayList<>();
        int processOrder = 0;

        for (String nodeId : topologicalNodeIds) {
            ProductProcessWorkflowDTO.Node node = nodesById.get(nodeId);
            if (!"PROCESS".equals(node.getKind())) {
                continue;
            }
            processOrder++;
            ProcessNodeData processData = parsedExecutionData.processNodes().get(nodeId);
            String plannedUnit = processData.outputUnit();
            if (plannedUnit == null || plannedUnit.isBlank()) {
                plannedUnit = processData.ports().stream()
                        .filter(port -> "OUTPUT".equals(port.direction()))
                        .map(DeclaredPort::unit)
                        .findFirst()
                        .orElseThrow(() -> runtimeInvalid(
                                nodeId,
                                "data.outputUnit",
                                "no output unit is available"));
            }
            boolean reportingRequired = !Boolean.FALSE.equals(processData.reportingRequired());
            tasks.add(new CompiledProductProcessWorkflow.CompiledTask(
                    nodeId,
                    processData.workProcessId(),
                    processOrder,
                    plannedUnit,
                    processData.standardTime(),
                    reportingRequired));
            appendPorts(
                    nodeId,
                    processData,
                    nodesById,
                    parsedExecutionData.materialNodes(),
                    ports);
        }

        return new CompiledProductProcessWorkflow(
                serializeRuntimeNodes(topologicalNodeIds, nodesById),
                serialize(definition.getEdges()),
                tasks,
                ports);
    }

    private Map<String, ProductProcessWorkflowDTO.Node> indexNodes(
            ProductProcessWorkflowDTO definition) {
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = new HashMap<>();
        definition.getNodes().forEach(node -> nodesById.put(node.getId(), node));
        return nodesById;
    }

    private ParsedExecutionData parseExecutionData(ProductProcessWorkflowDTO definition) {
        Map<String, ProcessNodeData> processNodes = new HashMap<>();
        Map<String, MaterialNodeData> materialNodes = new HashMap<>();
        if (definition == null || definition.getNodes() == null) {
            return new ParsedExecutionData(processNodes, materialNodes);
        }
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getId() == null || node.getKind() == null) {
                continue;
            }
            if ("PROCESS".equals(node.getKind())) {
                processNodes.put(node.getId(), parseProcessNode(node));
            } else if (List.of("RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD")
                    .contains(node.getKind())) {
                materialNodes.put(node.getId(), parseMaterialNode(node));
            }
        }
        return new ParsedExecutionData(processNodes, materialNodes);
    }

    private ProcessNodeData parseProcessNode(ProductProcessWorkflowDTO.Node node) {
        String nodeId = node.getId();
        Map<String, Object> data = node.getData();
        if (data == null) {
            throw runtimeInvalid(nodeId, "data", "must be an object");
        }
        String workProcessId = requiredString(data.get("workProcessId"), nodeId,
                "data.workProcessId");
        String outputUnit = optionalString(data.get("outputUnit"), nodeId,
                "data.outputUnit");
        Integer standardTime = optionalInteger(data.get("standardTime"), nodeId,
                "data.standardTime");
        Boolean reportingRequired = optionalBoolean(data.get("reportingRequired"), nodeId,
                "data.reportingRequired");
        ConversionRule conversionRule = parseConversionRule(data.get("conversionRule"), nodeId);
        List<DeclaredPort> ports = parsePorts(data.get("ports"), nodeId);
        return new ProcessNodeData(
                workProcessId,
                outputUnit,
                standardTime,
                reportingRequired,
                ports,
                conversionRule);
    }

    private MaterialNodeData parseMaterialNode(ProductProcessWorkflowDTO.Node node) {
        Map<String, Object> data = node.getData();
        if (data == null) {
            throw runtimeInvalid(node.getId(), "data", "must be an object");
        }
        return new MaterialNodeData(requiredString(
                data.get("skuId"), node.getId(), "data.skuId"));
    }

    private ConversionRule parseConversionRule(Object rawValue, String nodeId) {
        if (rawValue == null) {
            return new ConversionRule(null, null);
        }
        if (!(rawValue instanceof Map<?, ?> rule)) {
            throw runtimeInvalid(nodeId, "data.conversionRule", "must be an object");
        }
        return new ConversionRule(
                optionalString(rule.get("mode"), nodeId, "data.conversionRule.mode"),
                optionalString(
                        rule.get("expression"), nodeId, "data.conversionRule.expression"));
    }

    private List<DeclaredPort> parsePorts(Object rawValue, String nodeId) {
        if (!(rawValue instanceof List<?> rawPorts)) {
            throw runtimeInvalid(nodeId, "data.ports", "must be an array");
        }
        List<DeclaredPort> ports = new ArrayList<>(rawPorts.size());
        for (int index = 0; index < rawPorts.size(); index++) {
            String path = "data.ports[" + index + "]";
            Object rawPort = rawPorts.get(index);
            if (!(rawPort instanceof Map<?, ?> port)) {
                throw runtimeInvalid(nodeId, path, "must be an object");
            }
            ports.add(new DeclaredPort(
                    requiredString(port.get("id"), nodeId, path + ".id"),
                    requiredString(port.get("direction"), nodeId, path + ".direction"),
                    requiredString(
                            port.get("materialNodeId"), nodeId, path + ".materialNodeId"),
                    requiredString(port.get("unit"), nodeId, path + ".unit"),
                    requiredInteger(port.get("ordinal"), nodeId, path + ".ordinal")));
        }
        return List.copyOf(ports);
    }

    private String requiredString(Object value, String nodeId, String fieldPath) {
        String parsed = optionalString(value, nodeId, fieldPath);
        if (parsed == null || parsed.isBlank()) {
            throw runtimeInvalid(nodeId, fieldPath, "must be a non-blank string");
        }
        return parsed;
    }

    private String optionalString(Object value, String nodeId, String fieldPath) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw runtimeInvalid(nodeId, fieldPath, "must be a string");
        }
        return string;
    }

    private Boolean optionalBoolean(Object value, String nodeId, String fieldPath) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw runtimeInvalid(nodeId, fieldPath, "must be a boolean");
        }
        return booleanValue;
    }

    private Integer optionalInteger(Object value, String nodeId, String fieldPath) {
        if (value == null) {
            return null;
        }
        return strictInteger(value, nodeId, fieldPath);
    }

    private Integer requiredInteger(Object value, String nodeId, String fieldPath) {
        if (value == null) {
            throw runtimeInvalid(nodeId, fieldPath, "must be an integer");
        }
        return strictInteger(value, nodeId, fieldPath);
    }

    private Integer strictInteger(Object value, String nodeId, String fieldPath) {
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else {
            throw runtimeInvalid(nodeId, fieldPath, "must be an integral number");
        }
        if (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw runtimeInvalid(nodeId, fieldPath, "is outside the 32-bit integer range");
        }
        return integer.intValue();
    }

    private List<String> topologicalNodeIds(ProductProcessWorkflowDTO definition) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        definition.getNodes().forEach(node -> indegree.put(node.getId(), 0));
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            indegree.merge(edge.getTarget(), 1, Integer::sum);
            outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>())
                    .add(edge.getTarget());
        }
        outgoing.values().forEach(targets -> targets.sort(Comparator.naturalOrder()));

        TreeSet<String> currentLayer = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                currentLayer.add(entry.getKey());
            }
        }

        List<String> ordered = new ArrayList<>(definition.getNodes().size());
        while (!currentLayer.isEmpty()) {
            TreeSet<String> nextLayer = new TreeSet<>();
            for (String current : currentLayer) {
                ordered.add(current);
                for (String target : outgoing.getOrDefault(current, List.of())) {
                    int remaining = indegree.merge(target, -1, Integer::sum);
                    if (remaining == 0) {
                        nextLayer.add(target);
                    }
                }
            }
            currentLayer = nextLayer;
        }

        if (ordered.size() != definition.getNodes().size()) {
            throw runtimeInvalid("<workflow>", "graph", "contains a cycle");
        }
        return ordered;
    }

    private void appendPorts(
            String workflowNodeId,
            ProcessNodeData processData,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById,
            Map<String, MaterialNodeData> materialNodes,
            List<CompiledProductProcessWorkflow.CompiledPort> result) {
        ConversionRule conversionRule = processData.conversionRule() == null
                ? new ConversionRule(null, null)
                : processData.conversionRule();
        for (int index = 0; index < processData.ports().size(); index++) {
            DeclaredPort declaredPort = processData.ports().get(index);
            ProductProcessWorkflowDTO.Node materialNode = nodesById.get(declaredPort.materialNodeId());
            if (materialNode == null) {
                throw runtimeInvalid(
                        workflowNodeId,
                        "data.ports[" + index + "].materialNodeId",
                        "references missing material node " + declaredPort.materialNodeId());
            }
            MaterialNodeData materialData = materialNodes.get(materialNode.getId());
            if (materialData == null) {
                throw runtimeInvalid(
                        materialNode.getId(), "data.skuId", "material data was not parsed");
            }
            result.add(new CompiledProductProcessWorkflow.CompiledPort(
                    workflowNodeId,
                    declaredPort.id(),
                    declaredPort.direction(),
                    declaredPort.ordinal(),
                    declaredPort.materialNodeId(),
                    materialNode.getKind(),
                    materialData.skuId(),
                    declaredPort.unit(),
                    true,
                    conversionRule.mode(),
                    conversionRule.expression()));
        }
    }

    private String serializeRuntimeNodes(
            List<String> topologicalNodeIds,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById) {
        List<RuntimeNode> runtimeNodes = topologicalNodeIds.stream()
                .map(nodesById::get)
                .map(node -> new RuntimeNode(node.getId(), node.getKind(), node.getData()))
                .toList();
        return serialize(runtimeNodes);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw runtimeInvalid(
                    "<workflow>", "snapshot", "could not be serialized", exception);
        }
    }

    private BusinessException runtimeInvalid(
            String nodeId,
            String fieldPath,
            String reason) {
        return runtimeInvalid(nodeId, fieldPath, reason, null);
    }

    private BusinessException runtimeInvalid(
            String nodeId,
            String fieldPath,
            String reason,
            Throwable cause) {
        String message = "Workflow runtime data invalid at node " + nodeId
                + ", field " + fieldPath + ": " + reason;
        BusinessException exception = cause == null
                ? new BusinessException(400, message)
                : new BusinessException(400, message, cause);
        return exception
                .withCode("PRODUCT_PROCESS_WORKFLOW_RUNTIME_INVALID")
                .withHint("Return to Workflow configuration and fix node " + nodeId
                        + ", field " + fieldPath + " before activation")
                .withSeverity("warning");
    }

    private record RuntimeNode(
            String id,
            String kind,
            Map<String, Object> data) {
    }

    private record ProcessNodeData(
            String workProcessId,
            String outputUnit,
            Integer standardTime,
            Boolean reportingRequired,
            List<DeclaredPort> ports,
            ConversionRule conversionRule) {
    }

    private record DeclaredPort(
            String id,
            String direction,
            String materialNodeId,
            String unit,
            Integer ordinal) {
    }

    private record MaterialNodeData(
            String skuId) {
    }

    private record ConversionRule(
            String mode,
            String expression) {
    }

    private record ParsedExecutionData(
            Map<String, ProcessNodeData> processNodes,
            Map<String, MaterialNodeData> materialNodes) {
    }
}
