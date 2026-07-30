package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ProcessingStageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 加工环节记录实体
 * 用于记录生产批次各个加工环节的详细数据，支持AI分析
 *
 * 支持的加工环节:
 * - RECEIVING: 接收
 * - THAWING: 解冻
 * - TRIMMING: 去尾/修整
 * - SLICING: 切片
 * - WASHING: 清洗
 * - DRAINING: 沥干
 * - MARINATING: 腌制/上浆
 * - PACKAGING: 包装
 * - FREEZING: 速冻
 * - QUALITY_CHECK: 品控检查
 * - CLEANING: 清洗换线
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-23
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "processing_stage_records",
       indexes = {
           @Index(name = "idx_stage_factory", columnList = "factory_id"),
           @Index(name = "idx_stage_batch", columnList = "production_batch_id"),
           @Index(name = "idx_stage_type", columnList = "stage_type"),
           @Index(name = "idx_stage_time", columnList = "start_time")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ProcessingStageRecord extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 关联的生产批次ID
     */
    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    /**
     * 加工环节类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type", nullable = false, length = 30)
    private ProcessingStageType stageType;

    /**
     * 环节名称 (自定义名称，可选)
     */
    @Column(name = "stage_name", length = 100)
    private String stageName;

    /**
     * 环节序号 (在批次中的顺序)
     */
    @Column(name = "stage_order")
    private Integer stageOrder;

    // ==================== 时间数据 ====================

    /**
     * 开始时间
     */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /**
     * 持续时长（分钟）
     */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    // ==================== 重量/数量数据 ====================

    /**
     * 投入重量 (kg)
     */
    @Column(name = "input_weight", precision = 12, scale = 3)
    private BigDecimal inputWeight;

    /**
     * 产出重量 (kg)
     */
    @Column(name = "output_weight", precision = 12, scale = 3)
    private BigDecimal outputWeight;

    /**
     * 损耗量 (kg)
     */
    @Column(name = "loss_weight", precision = 12, scale = 3)
    private BigDecimal lossWeight;

    /**
     * 损耗率 (%)
     */
    @Column(name = "loss_rate", precision = 5, scale = 2)
    private BigDecimal lossRate;

    // ==================== 温度数据 ====================

    /**
     * 环境温度 (°C)
     */
    @Column(name = "ambient_temperature", precision = 5, scale = 2)
    private BigDecimal ambientTemperature;

    /**
     * 产品温度 (°C)
     */
    @Column(name = "product_temperature", precision = 5, scale = 2)
    private BigDecimal productTemperature;

    /**
     * 目标温度 (°C)
     */
    @Column(name = "target_temperature", precision = 5, scale = 2)
    private BigDecimal targetTemperature;

    // ==================== 质量数据 ====================

    /**
     * 合格数量
     */
    @Column(name = "pass_count")
    private Integer passCount;

    /**
     * 不合格数量
     */
    @Column(name = "fail_count")
    private Integer failCount;

    /**
     * 合格率 (%)
     */
    @Column(name = "pass_rate", precision = 5, scale = 2)
    private BigDecimal passRate;

    /**
     * 返工数量
     */
    @Column(name = "rework_count")
    private Integer reworkCount;

    // ==================== 设备数据 ====================

    /**
     * 使用的设备ID
     */
    @Column(name = "equipment_id")
    private Long equipmentId;

    /**
     * 设备名称
     */
    @Column(name = "equipment_name", length = 100)
    private String equipmentName;

    /**
     * 设备运行时间 (分钟)
     */
    @Column(name = "equipment_run_time")
    private Integer equipmentRunTime;

    /**
     * 设备停机时间 (分钟)
     */
    @Column(name = "equipment_downtime")
    private Integer equipmentDowntime;

    /**
     * 设备效率 OEE (%)
     */
    @Column(name = "equipment_oee", precision = 5, scale = 2)
    private BigDecimal equipmentOee;

    // ==================== 资源消耗数据 ====================

    /**
     * 用水量 (升)
     */
    @Column(name = "water_usage", precision = 10, scale = 2)
    private BigDecimal waterUsage;

    /**
     * 用电量 (kWh)
     */
    @Column(name = "power_usage", precision = 10, scale = 2)
    private BigDecimal powerUsage;

    /**
     * 辅料消耗量 (kg)
     */
    @Column(name = "auxiliary_usage", precision = 10, scale = 3)
    private BigDecimal auxiliaryUsage;

    /**
     * 辅料名称
     */
    @Column(name = "auxiliary_name", length = 100)
    private String auxiliaryName;

    // ==================== 特殊环节数据 ====================

    /**
     * 滴水损失率 (%) - 解冻环节
     */
    @Column(name = "drip_loss", precision = 5, scale = 2)
    private BigDecimal dripLoss;

    /**
     * 厚度标准差 (mm) - 切片环节
     */
    @Column(name = "thickness_sd", precision = 5, scale = 3)
    private BigDecimal thicknessSd;

    /**
     * 腌料吸收率 (%) - 腌制环节
     */
    @Column(name = "marinade_absorption", precision = 5, scale = 2)
    private BigDecimal marinadeAbsorption;

    /**
     * pH值 - 腌制/清洗环节
     */
    @Column(name = "ph_value", precision = 4, scale = 2)
    private BigDecimal phValue;

    /**
     * 盐度 (%) - 腌制环节
     */
    @Column(name = "salinity", precision = 5, scale = 2)
    private BigDecimal salinity;

    /**
     * ATP检测结果 (RLU) - 清洗环节
     */
    @Column(name = "atp_result")
    private Integer atpResult;

    /**
     * 微生物检测合格率 (%) - 品控环节
     */
    @Column(name = "micro_pass_rate", precision = 5, scale = 2)
    private BigDecimal microPassRate;

    /**
     * CCP检查合格率 (%) - 品控环节
     */
    @Column(name = "ccp_pass_rate", precision = 5, scale = 2)
    private BigDecimal ccpPassRate;

    // ==================== 操作员信息 ====================

    /**
     * 操作员ID
     */
    @Column(name = "operator_id")
    private Long operatorId;

    /**
     * 操作员姓名
     */
    @Column(name = "operator_name", length = 50)
    private String operatorName;

    /**
     * 参与工人数
     */
    @Column(name = "worker_count")
    private Integer workerCount;

    // ==================== 其他 ====================

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * 扩展数据 (JSON格式，存储其他自定义字段)
     */
    @Column(name = "extra_data", columnDefinition = "TEXT")
    private String extraData;

    // ==================== 计算方法 ====================

    @PrePersist
    @PreUpdate
    protected void calculateMetrics() {
        // 计算持续时长
        if (startTime != null && endTime != null) {
            durationMinutes = (int) java.time.Duration.between(startTime, endTime).toMinutes();
        }

        // 计算损耗量和损耗率
        if (inputWeight != null && outputWeight != null) {
            lossWeight = inputWeight.subtract(outputWeight);
            if (inputWeight.compareTo(BigDecimal.ZERO) > 0) {
                lossRate = lossWeight.multiply(BigDecimal.valueOf(100))
                        .divide(inputWeight, 2, java.math.RoundingMode.HALF_UP);
            }
        }

        // 计算合格率
        if (passCount != null && failCount != null) {
            int total = passCount + failCount;
            if (total > 0) {
                passRate = BigDecimal.valueOf(passCount * 100.0 / total)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
        }

        // 计算设备OEE (简化计算：运行时间/(运行时间+停机时间))
        if (equipmentRunTime != null && equipmentDowntime != null) {
            int totalTime = equipmentRunTime + equipmentDowntime;
            if (totalTime > 0) {
                equipmentOee = BigDecimal.valueOf(equipmentRunTime * 100.0 / totalTime)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
        }
    }
}
