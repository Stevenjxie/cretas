package com.cretas.aims.controller.restaurant;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.restaurant.WastageAccountability;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.restaurant.WastageRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.cretas.aims.annotation.RequireModule;

/**
 * 损耗记录管理 Controller
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/wastage")
@RequiredArgsConstructor
@Tag(name = "餐饮-损耗管理")
public class WastageRecordController {

    private final WastageRecordRepository wastageRepository;
    private final WastageRecordService wastageRecordService;

    /**
     * AUD-5 B-A3 sister sweep batch 4b (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code wastage_records} table (see {@link WastageRecord}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@code reason} and {@code notes} are {@code @Column(columnDefinition="TEXT")}
     * (unbounded) so no length check needed for them. {@code rawMaterialTypeId} (length=191)
     * is a UUID/ID reference (not free-form text), so over-length is unlikely.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
     */
    private static final int UNIT_MAX_LENGTH = 20;

    // ==================== 列表查询 ====================

    @GetMapping
    @Operation(summary = "损耗记录列表", description = "支持按日期范围、状态、类型组合筛选；任意参数可为空")
    public ApiResponse<Page<WastageRecord>> list(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);

        // May 9 fix (Bug 5-7): 旧 if-else 链不支持组合筛选，且空字符串
        // 当作 non-null 触发 IllegalArgumentException — 改用 findByFilters 统一处理。
        WastageRecord.Status statusEnum = null;
        if (StringUtils.hasText(status)) {
            try {
                statusEnum = WastageRecord.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的状态值: " + status)
                        .withHint("可选: DRAFT / SUBMITTED / APPROVED / REJECTED");
            }
        }
        WastageRecord.WastageType typeEnum = null;
        if (StringUtils.hasText(type)) {
            try {
                typeEnum = WastageRecord.WastageType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的损耗类型: " + type)
                        .withHint("查看 WastageType 枚举可选值");
            }
        }
        return ApiResponse.success(
                wastageRepository.findByFilters(factoryId, statusEnum, typeEnum, startDate, endDate, pageable));
    }

    // ==================== 详情 ====================

    @GetMapping("/{wastageId}")
    @Operation(summary = "损耗记录详情")
    public ApiResponse<WastageRecord> detail(
            @PathVariable String factoryId,
            @PathVariable String wastageId) {
        return wastageRepository.findByIdAndFactoryId(wastageId, factoryId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(404, "损耗记录不存在: " + wastageId).withHint("请检查 ID 是否正确"));
    }

    // ==================== 创建 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping
    @Operation(summary = "创建损耗记录")
    public ApiResponse<WastageRecord> create(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Valid WastageRecord record) {
        log.info("创建损耗记录: factoryId={}, type={}, materialId={}",
                factoryId, record.getType(), record.getRawMaterialTypeId());

        // AUD-5 B-A3 sister sweep batch 4b: length pre-check for unit (VARCHAR 20) BEFORE
        // dispatching to repository where PG would surface a generic 409.
        validateEntityLengths(record);

        record.setId(null);
        record.setFactoryId(factoryId);
        record.setReportedBy(userId);
        record.setStatus(WastageRecord.Status.DRAFT);
        if (record.getWastageDate() == null) {
            record.setWastageDate(LocalDate.now());
        }

        // 自动生成单号
        long todayCount = wastageRepository.countByFactoryIdAndDate(factoryId, record.getWastageDate());
        String dateStr = record.getWastageDate().toString().replace("-", "");
        record.setWastageNumber(String.format("WST-%s-%03d", dateStr, todayCount + 1));

        WastageRecord saved = wastageRepository.save(record);
        return ApiResponse.success("损耗记录创建成功", saved);
    }

    // ==================== 提交 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{wastageId}/submit")
    @Operation(summary = "提交损耗记录", description = "将草稿提交审批")
    public ApiResponse<WastageRecord> submit(
            @PathVariable String factoryId,
            @PathVariable String wastageId) {
        WastageRecord record = wastageRepository.findByIdAndFactoryId(wastageId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("损耗记录", "id", wastageId));
        if (record.getStatus() != WastageRecord.Status.DRAFT
                && record.getStatus() != WastageRecord.Status.REJECTED) {
            throw new BusinessException(409, "只有草稿或已驳回的损耗记录可以提交")
                    .withHint("请刷新损耗记录列表查看最新状态");
        }
        record.setStatus(WastageRecord.Status.SUBMITTED);
        WastageRecord updated = wastageRepository.save(record);
        return ApiResponse.success("损耗记录已提交", updated);
    }

    // ==================== 审批 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{wastageId}/approve")
    @Operation(summary = "审批损耗记录")
    public ApiResponse<WastageRecord> approve(
            @PathVariable String factoryId,
            @PathVariable String wastageId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long approverId) {
        WastageRecord updated = wastageRecordService.approveWastageRecord(factoryId, wastageId, approverId);
        return ApiResponse.success("损耗记录已审批", updated);
    }

    // ==================== 驳回 ====================

    @RequirePermission({"inventory:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/{wastageId}/reject")
    @Operation(summary = "驳回损耗记录")
    public ApiResponse<WastageRecord> reject(
            @PathVariable String factoryId,
            @PathVariable String wastageId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long approverId,
            @RequestBody(required = false) Map<String, Object> body) {
        WastageRecord record = wastageRepository.findByIdAndFactoryId(wastageId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("损耗记录", "id", wastageId));
        if (record.getStatus() != WastageRecord.Status.SUBMITTED) {
            throw new BusinessException(409, "只有已提交的损耗记录可以驳回")
                    .withHint("请刷新损耗记录列表查看最新状态");
        }
        record.setStatus(WastageRecord.Status.REJECTED);
        record.setApprovedBy(approverId);
        record.setApprovedAt(LocalDateTime.now());
        if (body != null && body.get("reason") != null) {
            record.setNotes(String.valueOf(body.get("reason")));
        }
        WastageRecord updated = wastageRepository.save(record);
        return ApiResponse.success("损耗记录已驳回", updated);
    }

    // ==================== 统计 ====================

    @RequirePermission({"procurement:price:view", "finance:read", "finance:read_write"})
    @GetMapping("/statistics")
    @Operation(summary = "损耗统计", description = "按损耗类型和食材统计损耗数量与金额")
    public ApiResponse<Map<String, Object>> statistics(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();

        BigDecimal totalCost = wastageRepository.getTotalEstimatedCost(factoryId, start, end);

        List<Object[]> byType = wastageRepository.getStatisticsByType(factoryId, start, end);
        List<Map<String, Object>> typeStats = byType.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", row[0]);
            m.put("count", row[1]);
            m.put("totalQuantity", row[2]);
            m.put("totalCost", row[3]);
            return m;
        }).collect(Collectors.toList());

        List<Object[]> byMaterial = wastageRepository.getStatisticsByMaterial(factoryId, start, end);
        List<Map<String, Object>> materialStats = byMaterial.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rawMaterialTypeId", row[0]);
            m.put("unit", row[1]);
            m.put("totalQuantity", row[2]);
            m.put("totalCost", row[3]);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("totalEstimatedCost", totalCost);
        result.put("byType", typeStats);
        result.put("byMaterial", materialStats);
        return ApiResponse.success(result);
    }

    // ==================== 责任制汇总 (Wave2 损耗按人/档口) ====================

    /**
     * 损耗责任制汇总：按责任人 + 档口聚合 APPROVED 损耗。
     *
     * <p>兑现邓总诉求："今天损耗明天屌你" / "同样 1 万营业额哪个档口哪个人成本涨了"。
     * 与 {@link #statistics} 同 RBAC 模型：金额敏感，整体门控 price/finance，
     * 无价权角色无法调用（R6 sibling sweep 防泄露）。</p>
     */
    @RequirePermission({"procurement:price:view", "finance:read", "finance:read_write"})
    @GetMapping("/accountability")
    @Operation(summary = "损耗责任制汇总", description = "按责任人和档口聚合损耗金额/数量/记录数，支持日期范围")
    public ApiResponse<WastageAccountability> accountability(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        return ApiResponse.success(wastageRecordService.getAccountability(factoryId, start, end));
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 4b) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 4b (edge audit 2026-05-20): length pre-check for
     * {@code unit} (PG VARCHAR 20) on the create path. Mirrors PR #48 / PR #78 / PR #92
     * pattern.
     *
     * <p>{@code reason} (TEXT, unbounded) and {@code notes} (TEXT, unbounded) are not
     * length-bounded by PG, so no pre-check is needed for them.
     *
     * <p>Without this, an over-length {@code unit} input lets the request reach PG and
     * surfaces as {@link org.springframework.dao.DataIntegrityViolationException} →
     * generic 409 "数据处理异常". Pre-check delivers a specific 400 with the actual vs
     * allowed length so the user can immediately fix the input.
     */
    private void validateEntityLengths(WastageRecord record) {
        String unit = record.getUnit();
        if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "计量单位最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.length() + ")")
                    .withHint("请使用更短的计量单位 (如 kg / 箱 / 件)")
                    .withSeverity("warning")
                    .withHintTarget("unit");
        }

        // Wave2: section_code 必须是合法档口枚举 (防呆 Rule 3 — 约束选择)。
        // 容忍 null/空 (档口可选)，非法值给具体 400 而非脏数据落库。
        String section = record.getSectionCode();
        if (section != null && !section.trim().isEmpty()) {
            com.cretas.aims.entity.enums.WastageSection parsed =
                    com.cretas.aims.entity.enums.WastageSection.fromCode(section);
            if (parsed == null) {
                throw new BusinessException(400,
                        "无效的档口编码: " + section)
                        .withHint("可选: " + String.join(" / ", com.cretas.aims.entity.enums.WastageSection.codes()))
                        .withSeverity("warning")
                        .withHintTarget("sectionCode");
            }
            // 规范化为枚举标准 code, 避免大小写差异 (seafood vs SEAFOOD) 导致
            // getStatisticsBySection 的 GROUP BY w.sectionCode 把同一档口拆成两行。
            // 与 AI 工具路径 (RestaurantWastageRecordTool) 的归一行为一致。
            record.setSectionCode(parsed.name());
        }
    }
}
