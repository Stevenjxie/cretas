package com.cretas.aims.dto.yield;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 工人逐道报工 投入+产出双量 — POST /reports */
@Data
public class YieldReportRequest {
    private Long workProcessTaskId;
    private BigDecimal inputQuantity;     // 本道投入 (前端预填上道产出, 可改)
    private String inputUnit;
    private BigDecimal outputQuantity;    // 本道产出
    private String outputUnit;
    private Integer workMinutes;          // 本道工时(分钟), 选填
    private Integer workerCount;          // 本道人数, 选填 (张权 G4: "用了多少人")
    private Boolean forceSubmit;          // A4 超收软告警后强制提交
    /** 跨批来源 (张权 A3), 见 ProductionReport.sourceBatchRefs 形状 */
    private List<Map<String, Object>> sourceBatchRefs;
    private String reporterName;
    private Long targetWorkerId;          // 代报工 (主管替工人提交)
    /** A2b: 首道领料批次引用 (与 materialBatchRefs 字段合并到报工单, 不再单独调用 recordMaterialInput) */
    private List<MaterialBatchRef> materialBatchRefs;

    /**
     * G7 部分领用 (Wave 2): 本道领用哪个上道 WIP (semi_finished_inventory.intermediate_batch_no)。
     * 非空时: 报工保存后扣减该 WIP 的 available_quantity (防呆 inputQuantity ≤ available);
     * null 走旧路径 (首道领原料 / 老批次, 向后兼容零回归)。
     */
    private String sourceWipNo;

    /**
     * 三阶段报工 (单元1): 本次报工的阶段 INPUT/SEGMENT/OUTPUT。
     * <p>null/缺省 = 旧式整合报工 (一次提交投入+产出, 向后兼容零回归)。
     * 按阶段隔离字段 (INPUT 忽略 output, SEGMENT 忽略 input/output, OUTPUT 忽略 input),
     * 避免误填污染同 task 跨报工累加。</p>
     */
    private String reportKind;

    // ==================== 传统报工适配 (适配单元1 地基; 算法在后续任务) ====================

    /** 图片证据 URL 列表 (前端先传 OSS 拿 URL, 存入 ProductionReport.photos) */
    private List<String> evidenceImages;

    /** T161 per-photo annotation; parallel to evidenceImages, same order.
     *  null or absent = no annotations (backward-compat). */
    private List<PhotoAnnotation> photoAnnotations;

    /** 多时段×人数工时 (张权 多段开工/收工) */
    private List<LaborSegment> laborSegments;

    /** 副产物明细 (料头/肥油/骨头) */
    private List<Byproduct> byproducts;

    /** 损耗量; null=未录 */
    private BigDecimal wasteQuantity;

    /** 留样(盒/份, 末道装盒) */
    private Integer sampleRetainQuantity;

    /** T161: per-photo annotation attached to each evidence image. */
    @Data
    public static class PhotoAnnotation {
        /** The OSS URL this annotation belongs to (must match evidenceImages[i]). */
        private String url;
        /** Preset label chip. One of: 称重投入/称重产出/装盒/副产物/损耗/托盘重/工序中/留样/其它 */
        private String label;
        /** Optional free-text note, e.g. "猪舌第1车 320盒". May be null. */
        private String note;
    }

    @Data
    public static class LaborSegment {
        private String startTime;   // "HH:mm"
        private String endTime;     // "HH:mm"
        private Integer headcount;
        private String note;
        /** 本段实际处理量, 如修油/清洗/装盒过程中的本段处理 kg/盒数. */
        private BigDecimal processedQuantity;
        private String processedUnit;
        /** 本段阶段产出量, 只代表过程产出, 不等于完工入库产出. */
        private BigDecimal stageOutputQuantity;
        private String stageOutputUnit;
        /** 本段过程损耗, 与完工损耗分开记录. */
        private BigDecimal segmentWasteQuantity;
        private String segmentWasteUnit;
        /** 本段过程副产物, 如料头/肥油/骨头. */
        private List<Byproduct> byproducts;
    }

    @Data
    public static class Byproduct {
        private String name;
        private BigDecimal quantity;
        private String unit;
    }
}
