package com.cretas.aims.controller.dto.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 批次溯源有向边响应 DTO — Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>从 {@link BatchLineageEdge} 实体投影; 略掉 BaseEntity 的 deletedAt 字段
 * (forensic edge 永不软删, 暴露给前端无意义).
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批次溯源有向边 (BatchLineageEdge) 响应 DTO")
public class BatchLineageEdgeDTO {

    @Schema(description = "边 ID")
    private String id;

    @Schema(description = "工厂 ID")
    private String factoryId;

    @Schema(description = "边类型: RAW_TO_PRODUCTION / PRODUCTION_TO_FINISHED / FINISHED_TO_SHIPMENT / REWORK / BLEND")
    private String edgeType;

    @Schema(description = "源批次类型: MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD")
    private String sourceType;

    @Schema(description = "源批次 ID (Long 类型批次以字符串形式存入)")
    private String sourceId;

    @Schema(description = "目标批次类型")
    private String targetType;

    @Schema(description = "目标批次 ID")
    private String targetId;

    @Schema(description = "该边对应的使用数量")
    private BigDecimal quantityUsed;

    @Schema(description = "数量单位")
    private String unit;

    @Schema(description = "业务发生时刻")
    private LocalDateTime eventTime;

    @Schema(description = "操作人 ID")
    private Long operatorId;

    @Schema(description = "附加元数据 (CCP 监控引用 / 回流原因等)")
    private Map<String, Object> meta;

    @Schema(description = "物理落库时刻")
    private LocalDateTime createdAt;

    /**
     * 从实体投影 DTO.
     */
    public static BatchLineageEdgeDTO fromEntity(BatchLineageEdge e) {
        return BatchLineageEdgeDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .edgeType(e.getEdgeType())
                .sourceType(e.getSourceType())
                .sourceId(e.getSourceId())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .quantityUsed(e.getQuantityUsed())
                .unit(e.getUnit())
                .eventTime(e.getEventTime())
                .operatorId(e.getOperatorId())
                .meta(e.getMeta())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
