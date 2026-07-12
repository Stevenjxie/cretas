package com.cretas.aims.dto.workflow;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionWorkflowRuntimeDTO {

    private Long workflowInstanceId;
    private String factoryId;
    private Long productionBatchId;
    private String productTypeId;
    private Long workflowId;
    private Integer definitionVersion;
    private String nodesJson;
    private String edgesJson;
    private ProductionWorkflowInstance.Status status;
    private LocalDateTime compiledAt;
    private List<TaskRuntimeDTO> tasks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskRuntimeDTO {
        private WorkProcessTaskDTO task;
        private List<PortDTO> ports;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortDTO {
        private Long id;
        private String factoryId;
        private Long workflowInstanceId;
        private Long taskId;
        private String workflowPortId;
        private WorkflowTaskPort.Direction direction;
        private Integer ordinal;
        private String materialNodeId;
        private String materialKind;
        private String skuId;
        private String unit;
        private Boolean required;
        private String conversionMode;
        private String conversionExpression;
    }
}
