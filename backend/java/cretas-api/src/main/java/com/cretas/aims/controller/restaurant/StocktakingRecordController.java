package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.service.restaurant.StocktakingRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;

/**
 * 库存盘点管理 Controller
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/stocktaking")
@RequiredArgsConstructor
@Tag(name = "餐饮-盘点管理")
public class StocktakingRecordController {

    private final StocktakingRecordRepository stocktakingRepository;
    private final StocktakingRecordService stocktakingRecordService;

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code stocktaking_records} table (see {@link StocktakingRecord}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@code adjustmentReason} and {@code notes} are {@code @Column(columnDefinition="TEXT")}
     * (unbounded) so no length check needed for them.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
     */
    private static final int UNIT_MAX_LENGTH = 20;
    private static final int STALL_CODE_MAX_LENGTH = 64;

    // ==================== 列表查询 ====================

    @GetMapping
    @Operation(summary = "盘点记录列表", description = "支持按状态筛选")
    public ApiResponse<Page<StocktakingRecord>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);

        if (status != null) {
            StocktakingRecord.Status s = StocktakingRecord.Status.valueOf(status);
            return ApiResponse.success(
                    stocktakingRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, s, pageable));
        }
        return ApiResponse.success(
                stocktakingRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable));
    }

    // ==================== 详情 ====================

    @GetMapping("/{recordId}")
    @Operation(summary = "盘点记录详情")
    public ApiResponse<StocktakingRecord> detail(
            @PathVariable String factoryId,
            @PathVariable String recordId) {
        return stocktakingRepository.findByIdAndFactoryId(recordId, factoryId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(404, "盘点记录不存在: " + recordId).withHint("请检查 ID 是否正确"));
    }

    // ==================== 创建 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping
    @Operation(summary = "创建盘点单", description = "创建食材盘点单，自动读取系统库存")
    public ApiResponse<StocktakingRecord> create(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Valid StocktakingRecord record) {
        log.info("创建盘点单: factoryId={}, materialId={}", factoryId, record.getRawMaterialTypeId());

        // AUD-5 B-A3 sister sweep batch 3: length pre-check for unit (VARCHAR 20) BEFORE
        // dispatching to repository where PG would surface a generic 409.
        validateEntityLengths(record);

        // GAP-04-C-SYS: route through service so systemQuantity is snapshotted at
        // creation time.  The previous direct stocktakingRepository.save() bypassed
        // StocktakingRecordServiceImpl.createRecord(), which is the only place that
        // computes and persists the per-batch 账面库存 the operator needs before counting.
        record.setId(null);
        StocktakingRecord saved = stocktakingRecordService.createRecord(factoryId, record, userId);
        return ApiResponse.success("盘点单创建成功", saved);
    }

    // ==================== 完成盘点 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{recordId}/complete")
    @Operation(summary = "完成盘点", description = "录入实盘数量并计算差异")
    public ApiResponse<StocktakingRecord> complete(
            @PathVariable String factoryId,
            @PathVariable String recordId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("actualQuantity")) {
            throw new BusinessException(400, "请填写实盘数量 (actualQuantity)")
                    .withHint("请在表单中填写实盘数量").withHintTarget("actualQuantity");
        }

        StocktakingRecord updated = stocktakingRecordService.completeRecord(
                factoryId, recordId, new BigDecimal(body.get("actualQuantity").toString()), userId);
        if (body.containsKey("adjustmentReason")) {
            updated.setAdjustmentReason(body.get("adjustmentReason").toString());
            updated = stocktakingRepository.save(updated);
        }
        return ApiResponse.success("盘点完成", updated);
    }

    // ==================== 取消 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{recordId}/cancel")
    @Operation(summary = "取消盘点")
    public ApiResponse<StocktakingRecord> cancel(
            @PathVariable String factoryId,
            @PathVariable String recordId) {
        StocktakingRecord record = stocktakingRepository.findByIdAndFactoryId(recordId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("盘点记录", "id", recordId));
        if (record.getStatus() != StocktakingRecord.Status.IN_PROGRESS) {
            throw new BusinessException(409, "只有进行中的盘点单可以取消")
                    .withHint("请刷新盘点列表查看最新状态");
        }
        record.setStatus(StocktakingRecord.Status.CANCELLED);
        StocktakingRecord updated = stocktakingRepository.save(record);
        return ApiResponse.success("盘点已取消", updated);
    }

    // ==================== 最新盘点汇总 ====================

    @GetMapping("/latest-summary")
    @Operation(summary = "最新盘点汇总", description = "最近一次完成盘点的差异统计")
    public ApiResponse<Map<String, Object>> latestSummary(@PathVariable String factoryId) {
        Optional<LocalDate> latestDate = stocktakingRepository.findLatestCompletedDate(factoryId);
        if (latestDate.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("latestDate", null);
            empty.put("message", "暂无已完成的盘点记录");
            return ApiResponse.success(empty);
        }

        LocalDate date = latestDate.get();
        List<Object[]> summaryRows = stocktakingRepository.getSummaryByDate(factoryId, date);

        List<Map<String, Object>> items = summaryRows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("differenceType", row[0]);
            m.put("count", row[1]);
            m.put("totalDifferenceAmount", row[2]);
            return m;
        }).collect(Collectors.toList());

        // 最近几条完成的盘点明细
        List<StocktakingRecord> latestRecords = stocktakingRepository.findLatestCompleted(
                factoryId, PageRequest.of(0, 10));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latestDate", date.toString());
        result.put("summary", items);
        result.put("recentRecords", latestRecords);
        return ApiResponse.success(result);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 3) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): length pre-check for
     * {@code unit} (PG VARCHAR 20) on the create path. Mirrors PR #48 / PR #78
     * pattern.
     *
     * <p>{@code adjustmentReason} (TEXT, unbounded) and {@code notes} (TEXT, unbounded)
     * are not length-bounded by PG, so no pre-check is needed for them.
     *
     * <p>Without this, an over-length {@code unit} input lets the request reach PG and
     * surfaces as {@link org.springframework.dao.DataIntegrityViolationException} →
     * generic 409 "数据处理异常". Pre-check delivers a specific 400 with the actual vs
     * allowed length so the user can immediately fix the input.
     */
    private void validateEntityLengths(StocktakingRecord record) {
        String unit = record.getUnit();
        if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "计量单位最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.length() + ")")
                    .withHint("请使用更短的计量单位 (如 kg / 箱 / 件)")
                    .withSeverity("warning")
                    .withHintTarget("unit");
        }
        String section = record.getSectionCode();
        if (section != null && !section.trim().isEmpty()) {
            com.cretas.aims.entity.enums.WastageSection parsed =
                    com.cretas.aims.entity.enums.WastageSection.fromCode(section);
            if (parsed == null) {
                throw new BusinessException(400, "Invalid sectionCode: " + section)
                        .withHint("Allowed values: " + String.join(" / ",
                                com.cretas.aims.entity.enums.WastageSection.codes()))
                        .withSeverity("warning")
                        .withHintTarget("sectionCode");
            }
            record.setSectionCode(parsed.name());
        }
        String stallCode = record.getStallCode();
        if (stallCode != null && stallCode.length() > STALL_CODE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "stallCode max length is " + STALL_CODE_MAX_LENGTH + " characters (current "
                            + stallCode.length() + ")")
                    .withHint("Use a shorter stable stall code")
                    .withSeverity("warning")
                    .withHintTarget("stallCode");
        }
    }
}
