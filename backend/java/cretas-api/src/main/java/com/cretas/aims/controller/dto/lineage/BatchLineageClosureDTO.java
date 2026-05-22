package com.cretas.aims.controller.dto.lineage;

import com.cretas.aims.entity.lineage.BatchLineageClosure;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 批次 lineage 闭包响应 DTO — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>从 {@link BatchLineageClosure} 实体投影. depth 字段语义:
 * <ul>
 *   <li><b>0</b> — 自引用 (用于 buildFullGraph 当前节点本身)</li>
 *   <li><b>1</b> — 直接边</li>
 *   <li><b>&gt;1</b> — 多跳传递闭包</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批次 lineage 闭包响应 DTO")
public class BatchLineageClosureDTO {

    @Schema(description = "闭包记录 ID")
    private String id;

    @Schema(description = "工厂 ID")
    private String factoryId;

    @Schema(description = "祖先节点类型")
    private String ancestorType;

    @Schema(description = "祖先节点 ID")
    private String ancestorId;

    @Schema(description = "后代节点类型")
    private String descendantType;

    @Schema(description = "后代节点 ID")
    private String descendantId;

    @Schema(description = "传递深度: 0=自引用 / 1=直接边 / >1=多跳")
    private Integer depth;

    @Schema(description = "物理落库时刻")
    private LocalDateTime createdAt;

    /**
     * 从实体投影 DTO.
     */
    public static BatchLineageClosureDTO fromEntity(BatchLineageClosure c) {
        return BatchLineageClosureDTO.builder()
                .id(c.getId())
                .factoryId(c.getFactoryId())
                .ancestorType(c.getAncestorType())
                .ancestorId(c.getAncestorId())
                .descendantType(c.getDescendantType())
                .descendantId(c.getDescendantId())
                .depth(c.getDepth())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
