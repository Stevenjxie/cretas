package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 工人日效率汇总实体
 * 用于记录工人每日的工作效率数据，包括工时、计件、效率指标等
 *
 * 效率评分说明:
 * - 90-100: 优秀
 * - 80-89: 良好
 * - 70-79: 合格
 * - 60-69: 待改进
 * - 0-59: 不合格
 *
 * 效率趋势:
 * - UP: 效率上升
 * - DOWN: 效率下降
 * - STABLE: 效率稳定
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "worker_daily_efficiency",
       indexes = {
           @Index(name = "idx_wde_factory_id", columnList = "factory_id"),
           @Index(name = "idx_wde_worker_id", columnList = "worker_id"),
           @Index(name = "idx_wde_work_date", columnList = "work_date"),
           @Index(name = "idx_wde_factory_worker_date", columnList = "factory_id, worker_id, work_date")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class WorkerDailyEfficiency extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    // ==================== 基本信息 ====================

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 工人ID
     */
    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * 工人姓名 (冗余，便于查询和报表展示)
     */
    @Column(name = "worker_name", length = 50)
    private String workerName;

    /**
     * 工作日期
     */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    // ==================== 工时数据 ====================

    /**
     * 班次类型
     * - MORNING: 早班
     * - AFTERNOON: 午班
     * - NIGHT: 夜班
     */
    @Column(name = "shift_type", length = 20)
    private String shiftType;

    /**
     * 上班时间
     */
    @Column(name = "work_start_time")
    private LocalDateTime workStartTime;

    /**
     * 下班时间
     */
    @Column(name = "work_end_time")
    private LocalDateTime workEndTime;

    /**
     * 总工作时长 (分钟)
     */
    @Column(name = "total_work_minutes")
    private Integer totalWorkMinutes;

    /**
     * 休息时长 (分钟)
     */
    @Column(name = "break_minutes")
    private Integer breakMinutes;

    /**
     * 有效工作时长 (分钟)
     */
    @Column(name = "effective_work_minutes")
    private Integer effectiveWorkMinutes;

    // ==================== 计件数据 ====================

    /**
     * 总完成件数
     */
    @Column(name = "total_piece_count")
    private Integer totalPieceCount;

    /**
     * 合格件数
     */
    @Column(name = "qualified_count")
    private Integer qualifiedCount;

    /**
     * 不合格件数
     */
    @Column(name = "defect_count")
    private Integer defectCount;

    /**
     * 合格率 (%)
     */
    @Column(name = "quality_rate", precision = 5, scale = 2)
    private BigDecimal qualityRate;

    // ==================== 效率指标 ====================

    /**
     * 每小时件数
     */
    @Column(name = "pieces_per_hour", precision = 10, scale = 2)
    private BigDecimal piecesPerHour;

    /**
     * 平均单件时间 (秒)
     */
    @Column(name = "average_time_per_piece", precision = 10, scale = 2)
    private BigDecimal averageTimePerPiece;

    /**
     * 效率评分 (0-100)
     */
    @Column(name = "efficiency_score", precision = 5, scale = 2)
    private BigDecimal efficiencyScore;

    /**
     * 效率趋势
     * - UP: 上升
     * - DOWN: 下降
     * - STABLE: 稳定
     */
    @Column(name = "efficiency_trend", length = 20)
    private String efficiencyTrend;

    // ==================== 工位和工序 ====================

    /**
     * 工位ID
     */
    @Column(name = "workstation_id", length = 50)
    private String workstationId;

    /**
     * 工位名称
     */
    @Column(name = "workstation_name", length = 100)
    private String workstationName;

    /**
     * 工序类型
     */
    @Column(name = "process_stage_type", length = 50)
    private String processStageType;

    /**
     * 产品类型ID
     */
    @Column(name = "product_type_id", length = 50)
    private String productTypeId;

    // ==================== 对比数据 ====================

    /**
     * 标准效率 (件/小时)
     */
    @Column(name = "standard_pieces_per_hour", precision = 10, scale = 2)
    private BigDecimal standardPiecesPerHour;

    /**
     * 与标准对比百分比 (%)
     * 正数表示超过标准，负数表示低于标准
     */
    @Column(name = "compared_to_standard", precision = 7, scale = 2)
    private BigDecimal comparedToStandard;

    /**
     * 团队排名
     */
    @Column(name = "rank_in_team")
    private Integer rankInTeam;

    // ==================== Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产→工资 trigger linkno ====================

    /**
     * 来源生产批次 ID (linkno).
     * 由 ProcessingServiceImpl.submitTeamBatchReport 自动 trigger 写入.
     * 允许 NULL (向后兼容: 手动创建的 efficiency 记录或非生产场景).
     */
    @Column(name = "source_batch_id")
    private Long sourceBatchId;

    // ==================== 其他 ====================

    /**
     * AI检测到的完成次数
     */
    @Column(name = "ai_detected_count")
    private Integer aiDetectedCount;

    /**
     * 人工调整次数
     */
    @Column(name = "manual_adjust_count")
    private Integer manualAdjustCount;

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

    /**
     * 计算效率指标
     * 在实体保存或更新前自动调用
     */
    @PrePersist
    @PreUpdate
    protected void calculateEfficiencyMetrics() {
        // 计算总工作时长
        if (workStartTime != null && workEndTime != null) {
            totalWorkMinutes = (int) java.time.Duration.between(workStartTime, workEndTime).toMinutes();
        }

        // 计算有效工作时长
        if (totalWorkMinutes != null) {
            int breakTime = breakMinutes != null ? breakMinutes : 0;
            effectiveWorkMinutes = totalWorkMinutes - breakTime;
            if (effectiveWorkMinutes < 0) {
                effectiveWorkMinutes = 0;
            }
        }

        // 计算合格率
        if (totalPieceCount != null && totalPieceCount > 0) {
            int qualified = qualifiedCount != null ? qualifiedCount : 0;
            qualityRate = BigDecimal.valueOf(qualified * 100.0 / totalPieceCount)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 计算不合格件数 (如果未设置)
        if (defectCount == null && totalPieceCount != null && qualifiedCount != null) {
            defectCount = totalPieceCount - qualifiedCount;
            if (defectCount < 0) {
                defectCount = 0;
            }
        }

        // 计算每小时件数
        if (effectiveWorkMinutes != null && effectiveWorkMinutes > 0 && totalPieceCount != null) {
            double hoursWorked = effectiveWorkMinutes / 60.0;
            piecesPerHour = BigDecimal.valueOf(totalPieceCount / hoursWorked)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 计算平均单件时间 (秒)
        if (effectiveWorkMinutes != null && totalPieceCount != null && totalPieceCount > 0) {
            double totalSeconds = effectiveWorkMinutes * 60.0;
            averageTimePerPiece = BigDecimal.valueOf(totalSeconds / totalPieceCount)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 计算与标准对比百分比
        if (piecesPerHour != null && standardPiecesPerHour != null
            && standardPiecesPerHour.compareTo(BigDecimal.ZERO) > 0) {
            comparedToStandard = piecesPerHour.subtract(standardPiecesPerHour)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(standardPiecesPerHour, 2, RoundingMode.HALF_UP);
        }

        // 计算效率评分 (基于与标准的对比和合格率)
        calculateEfficiencyScore();
    }

    /**
     * 计算效率评分
     * 评分公式: 基础分(与标准对比) * 0.7 + 质量分(合格率) * 0.3
     */
    private void calculateEfficiencyScore() {
        if (comparedToStandard == null && qualityRate == null) {
            return;
        }

        BigDecimal baseScore = BigDecimal.valueOf(70); // 基础分70分

        // 根据与标准对比调整分数
        if (comparedToStandard != null) {
            // 每超过标准1%，加0.3分；每低于标准1%，减0.3分
            BigDecimal adjustment = comparedToStandard.multiply(BigDecimal.valueOf(0.3));
            baseScore = baseScore.add(adjustment);
        }

        // 根据合格率调整分数
        if (qualityRate != null) {
            // 合格率权重30%，基准为100%
            BigDecimal qualityScore = qualityRate.multiply(BigDecimal.valueOf(0.3));
            baseScore = baseScore.add(qualityScore.subtract(BigDecimal.valueOf(30)));
        }

        // 限制分数范围 0-100
        if (baseScore.compareTo(BigDecimal.ZERO) < 0) {
            baseScore = BigDecimal.ZERO;
        }
        if (baseScore.compareTo(BigDecimal.valueOf(100)) > 0) {
            baseScore = BigDecimal.valueOf(100);
        }

        efficiencyScore = baseScore.setScale(2, RoundingMode.HALF_UP);
    }
}
