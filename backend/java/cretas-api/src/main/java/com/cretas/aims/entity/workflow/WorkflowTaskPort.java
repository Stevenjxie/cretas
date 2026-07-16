package com.cretas.aims.entity.workflow;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "workflow_task_ports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workflow_task_port",
                columnNames = {"task_id", "workflow_port_id"}))
@Where(clause = "deleted_at IS NULL")
public class WorkflowTaskPort extends BaseEntity {

    public enum Direction {
        INPUT,
        OUTPUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "workflow_port_id", nullable = false, length = 128)
    private String workflowPortId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private Direction direction;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "material_node_id", nullable = false, length = 128)
    private String materialNodeId;

    @Column(name = "material_kind", nullable = false, length = 32)
    private String materialKind;

    @Column(name = "sku_id", nullable = false, length = 128)
    private String skuId;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "unit_code", nullable = false, length = 32)
    private String unitCode;

    /** 固定包装成品的每基本单位净重快照；运行中 SKU 变更不得改写历史任务。 */
    @Column(name = "net_weight_grams_snapshot", precision = 20, scale = 6)
    private BigDecimal netWeightGramsSnapshot;

    @Column(name = "material_primary_unit_code", length = 32)
    private String materialPrimaryUnitCode;

    @Column(name = "conversion_ref_id", length = 36)
    private String conversionRefId;

    @Column(name = "conversion_version")
    private Long conversionVersion;

    @Column(name = "conversion_from_unit_code", length = 20)
    private String conversionFromUnitCode;

    @Column(name = "conversion_to_unit_code", length = 20)
    private String conversionToUnitCode;

    @Column(name = "conversion_factor_snapshot", precision = 20, scale = 8)
    private BigDecimal conversionFactorSnapshot;

    /** Immutable factor oriented as: quantity in port unit * factor = quantity in material primary unit. */
    @Column(name = "port_to_primary_factor_snapshot", precision = 20, scale = 8)
    private BigDecimal portToPrimaryFactorSnapshot;

    @Column(name = "required", nullable = false)
    private Boolean required = true;

    @Column(name = "conversion_mode", length = 32)
    private String conversionMode;

    @Column(name = "conversion_expression", length = 500)
    private String conversionExpression;

    @Column(name = "standard_quantity", precision = 20, scale = 6)
    private BigDecimal standardQuantity;

    @Column(name = "quantity_mode", length = 24)
    private String quantityMode;
}
