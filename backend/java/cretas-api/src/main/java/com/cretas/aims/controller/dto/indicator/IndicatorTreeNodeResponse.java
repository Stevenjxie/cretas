package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 指标树节点响应 DTO — 递归结构, 含 children[].
 *
 * <p>用于 {@code GET /indicators/tree}; 前端按 displayOrder 渲染树。
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标树节点响应 (递归)")
public class IndicatorTreeNodeResponse {

    @Schema(description = "节点 ID")
    private String id;

    @Schema(description = "关联的指标 ID")
    private String indicatorId;

    @Schema(description = "父节点 indicator ID (NULL = 根)")
    private String parentId;

    @Schema(description = "节点权重 (用于聚合)")
    private BigDecimal weight;

    @Schema(description = "树深度, 根为 0")
    private Integer depth;

    @Schema(description = "物化路径")
    private String materializedPath;

    @Schema(description = "同级排序顺序")
    private Integer displayOrder;

    @Schema(description = "子节点列表")
    @Builder.Default
    private List<IndicatorTreeNodeResponse> children = new ArrayList<>();

    /**
     * 从实体投影 DTO (不含 children, 由调用方递归填充).
     */
    public static IndicatorTreeNodeResponse fromEntity(IndicatorTreeNode n) {
        return IndicatorTreeNodeResponse.builder()
                .id(n.getId())
                .indicatorId(n.getIndicatorId())
                .parentId(n.getParentId())
                .weight(n.getWeight())
                .depth(n.getDepth())
                .materializedPath(n.getMaterializedPath())
                .displayOrder(n.getDisplayOrder())
                .children(new ArrayList<>())
                .build();
    }
}
