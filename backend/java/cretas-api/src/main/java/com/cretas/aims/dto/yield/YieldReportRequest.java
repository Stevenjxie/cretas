package com.cretas.aims.dto.yield;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * 同单未完结续报 (部分产出/继续产出): 控制 OUTPUT 报工后是否把工序任务置 COMPLETED。
     *
     * <p>客户场景 (六扇门 t2b): "先出 300 盒成品单子留着, 明天再出 200 盒" —— 一道工序可以
     * <b>多次产出</b>, 不是一次 OUTPUT 就完工。累计产出 = Σ 各次 OUTPUT, 出成率按累计算。</p>
     *
     * <ul>
     *   <li>{@code null} / 不传 = <b>向后兼容</b>: OUTPUT/legacy 报工后任务立即 COMPLETED
     *       (与历史行为完全一致, 零回归)。</li>
     *   <li>{@code false} = 部分产出, 留单继续: 累加本次产出但任务保持 IN_PROGRESS, 可后续再报。</li>
     *   <li>{@code true} = 显式标记完工: 累加本次产出后任务 COMPLETED (出成率锁定)。</li>
     * </ul>
     *
     * <p>仅对 OUTPUT/legacy 报工 (有产出) 生效; INPUT/SEGMENT 报工不触碰任务状态 (本就不完工)。</p>
     */
    private Boolean markComplete;

    // ==================== SP1 双产出字段 ====================
    /**
     * SP1 产出种类: FINISHED / SEMI / BOTH / null(旧式兼容).
     * null → 旧路径 (普通 WIP 产出); SEMI/BOTH → 同时写 SemiFinishedInventory 台账。
     */
    private String outputKind;

    /** SP1 半成品产出量 (outputKind=SEMI/BOTH 时填写) */
    private BigDecimal semiOutputQuantity;

    /** SP1 半成品产出单位 */
    private String semiOutputUnit;

    /**
     * SP1 半成品编码 (可选, 不填则继承 WorkProcess.semiFinishedOutputCode).
     * 用于指定本次产出归入哪个半成品编号前缀。
     */
    private String semiCode;

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

    /**
     * C-074/C-075/X-10 补录时效锁: 报工业务日期 (可选)。
     * <ul>
     *   <li>null / 不传 = 服务层取今天 (正常实时报工, 不拦)</li>
     *   <li>今天或未来 = 不拦</li>
     *   <li>T-1 / T-2 = 补录窗口内, 允许</li>
     *   <li>T-3 及更早 = 超窗口, 拒绝 409 BACKDATE_WINDOW_EXCEEDED</li>
     * </ul>
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate businessDate;

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
