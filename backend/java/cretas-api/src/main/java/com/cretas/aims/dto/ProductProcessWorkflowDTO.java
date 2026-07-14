package com.cretas.aims.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.dto.workflow.WorkflowUnitIssueDTO;

/**
 * 产品工序 Workflow 图定义。
 *
 * <p>阶段一只持久化编辑器图，不投影或改写 {@link ProductWorkProcessDTO} 运行时工序链。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductProcessWorkflowDTO {

    private Long id;
    private String factoryId;
    private String productTypeId;

    @NotNull
    private Integer schemaVersion = 1;

    private String status;
    private Integer version;
    private Long lockVersion;

    private List<WorkflowUnitIssueDTO> unitWarnings = new ArrayList<>();

    @Valid
    @NotNull
    private List<Node> nodes = new ArrayList<>();

    @Valid
    @NotNull
    private List<Edge> edges = new ArrayList<>();

    @Valid
    @NotNull
    private Viewport viewport = new Viewport(0D, 0D, 1D);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {
        private String id;
        private String kind;
        private Position position;
        private Map<String, Object> data = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Position {
        private Double x;
        private Double y;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        private String id;
        private String source;
        private String sourceHandle;
        private String target;
        private String targetHandle;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Viewport {
        private Double x;
        private Double y;
        private Double zoom;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishRequest {
        @NotNull
        private Long lockVersion;
    }
}
