package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 设备维护记录实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"equipment"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "equipment_maintenance",
       indexes = {
           @Index(name = "idx_maintenance_equipment", columnList = "equipment_id"),
           @Index(name = "idx_maintenance_date", columnList = "maintenance_date")
       }
)
@Where(clause = "deleted_at IS NULL")
public class EquipmentMaintenance extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;  // 改为 Long 类型，与 FactoryEquipment.id 匹配
    @Column(name = "maintenance_type", nullable = false, length = 50)
    private String maintenanceType; // routine, repair, overhaul
    @Column(name = "maintenance_date", nullable = false)
    private LocalDate maintenanceDate;
    @Column(name = "start_time")
    private LocalDateTime startTime;
    @Column(name = "end_time")
    private LocalDateTime endTime;
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @PriceSensitive
    @Column(name = "cost", precision = 10, scale = 2)
    private BigDecimal cost;
    @Column(name = "performed_by", length = 100)
    private String performedBy;
    @Column(name = "next_maintenance_date")
    private LocalDate nextMaintenanceDate;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // 乐观锁版本号
    @Version
    @Column(name = "version")
    private Integer version;

    // 关联关系
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id", referencedColumnName = "id", insertable = false, updatable = false)
    private FactoryEquipment equipment;
}
