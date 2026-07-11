package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
            ProcessNodeData processData = objectMapper.convertValue(
                    node.getData(), ProcessNodeData.class);
            String plannedUnit = processData.outputUnit();
            if (plannedUnit == null || plannedUnit.isBlank()) {
                plannedUnit = processData.ports().stream()
                        .filter(port -> "OUTPUT".equals(port.direction()))
                        .map(DeclaredPort::unit)
                        .findFirst()
                        .orElseThrow();
            }
            boolean reportingRequired = !Boolean.FALSE.equals(processData.reportingRequired());
            tasks.add(new CompiledProductProcessWorkflow.CompiledTask(
                    nodeId,
                    processData.workProcessId(),
                    processOrder,
                    plannedUnit,
                    processData.standardTime(),
                    reportingRequired));
            appendPorts(nodeId, processData, nodesById, ports);
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
            throw new IllegalArgumentException("Workflow contains a cycle");
        }
        return ordered;
    }

    private void appendPorts(
            String workflowNodeId,
            ProcessNodeData processData,
            Map<String, ProductProcessWorkflowDTO.Node> nodesById,
            List<CompiledProductProcessWorkflow.CompiledPort> result) {
        ConversionRule conversionRule = processData.conversionRule() == null
                ? new ConversionRule(null, null)
                : processData.conversionRule();
        for (DeclaredPort declaredPort : processData.ports()) {
            ProductProcessWorkflowDTO.Node materialNode = nodesById.get(declaredPort.materialNodeId());
            MaterialNodeData materialData = objectMapper.convertValue(
                    materialNode.getData(), MaterialNodeData.class);
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
            throw new IllegalStateException("Unable to serialize workflow runtime snapshot", exception);
        }
    }

    private record RuntimeNode(
            String id,
            String kind,
            Map<String, Object> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProcessNodeData(
            String workProcessId,
            String outputUnit,
            Integer standardTime,
            Boolean reportingRequired,
            List<DeclaredPort> ports,
            ConversionRule conversionRule) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeclaredPort(
            String id,
            String direction,
            String materialNodeId,
            String materialKind,
            String unit,
            Integer ordinal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MaterialNodeData(
            String skuId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConversionRule(
            String mode,
            String expression) {
    }
}
