package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileConfirmRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.WarehouseResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequireModule("warehouse")
@RequestMapping("/api/mobile/{factoryId}/warehouse/transit-ledgers")
@Tag(name = "Mobile warehouse production receipts")
public class ProductionWarehouseReceiptMobileController {

    private static final String RN_PENDING_STATUS = "PENDING_CONFIRMATION";
    private static final String SETTLEMENT_PENDING_STATUS = "PENDING_WAREHOUSE_RECEIPT";
    private static final String DIRECTION = "FINISHED_GOODS_RECEIPT";
    private static final String FROM_LOCATION = "\u4e2d\u8f6c/\u8f66\u95f4";

    /**
     * "\u672a\u77e5\u4ea7\u54c1(\u4ea7\u54c1\u6863\u6848\u67e5\u4e0d\u5230)" = "Unknown product (not found in the product catalogue)".
     *
     * <p>This screen is used by warehouse workers. Before this constant existed the code put
     * {@code outputLine.productTypeId} into {@code productName}, so prod served a bare 36-char
     * UUID ({@code eb0aa47b-a5dd-...}) where the worker expected
     * {@code SOP-20260817-01-...}. Falling back to the UUID, to an empty string, or to null are
     * all forbidden here (repo principle: never serve fake data, surface the error) \u2014 a worker
     * cannot act on any of the three. This says plainly that the name could not be resolved,
     * while {@code outputLines[].productTypeId} still carries the id for support to trace.
     */
    private static final String UNKNOWN_PRODUCT_NAME =
            "\u672a\u77e5\u4ea7\u54c1(\u4ea7\u54c1\u6863\u6848\u67e5\u4e0d\u5230)";

    /**
     * "\u672a\u6307\u5b9a(\u672c\u5382\u672a\u914d\u7f6e\u6210\u54c1\u4ed3)" = "Not designated (this factory has no finished-goods warehouse configured)".
     *
     * <p>Previously {@code toWarehouseName} was hard-coded to {@code null}, and the RN screen
     * renders {@code toWarehouseName || '-'} \u2014 so the worker was shown "\u4e2d\u8f6c/\u8f66\u95f4 -> -" and had
     * no idea where to put the goods. Honest "not designated" beats a bare dash: it says the
     * configuration is missing rather than implying the destination is unknowable.
     */
    private static final String NO_FINISHED_WAREHOUSE =
            "\u672a\u6307\u5b9a(\u672c\u5382\u672a\u914d\u7f6e\u6210\u54c1\u4ed3)";

    /** " \u7b49 " / " \u9879\u4ea7\u51fa" compose "&lt;name&gt; \u7b49 N \u9879\u4ea7\u51fa" = "&lt;name&gt; and N outputs" for a multi-output settlement. */
    private static final String MULTI_OUTPUT_INFIX = " \u7b49 ";
    private static final String MULTI_OUTPUT_SUFFIX = " \u9879\u4ea7\u51fa";

    private final ProductionSettlementRepository productionSettlementRepository;
    private final ProductionSettlementOutputLineRepository productionSettlementOutputLineRepository;
    private final ProductionPlanService productionPlanService;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final WarehouseResolver warehouseResolver;

    @GetMapping
    @RequirePermission({"warehouse:read_write"})
    @Operation(summary = "List production warehouse receipts pending mobile confirmation")
    public ApiResponse<List<ProductionWarehouseReceiptMobileDTO>> listTransitLedgers(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = RN_PENDING_STATUS) String status) {
        if (!RN_PENDING_STATUS.equals(status)) {
            throw new BusinessException(400, "Unsupported warehouse transit ledger status: " + status)
                    .withHint("Use status=PENDING_CONFIRMATION for pending warehouse receipt confirmation")
                    .withHintTarget("status");
        }

        List<ProductionWarehouseReceiptMobileDTO> rows = productionSettlementRepository
                .findByFactoryIdAndPostingStatusAndDeletedAtIsNull(factoryId, SETTLEMENT_PENDING_STATUS)
                .stream()
                .map(settlement -> toMobileDTO(factoryId, settlement))
                .toList();
        return ApiResponse.success(rows);
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission({"warehouse:read_write"})
    @Operation(summary = "Confirm production warehouse receipt from mobile")
    public ApiResponse<ProductionWarehouseReceiptResponse> confirmTransitLedger(
            @PathVariable @NotBlank String factoryId,
            @PathVariable("id") @NotBlank String productionPlanId,
            @Valid @RequestBody ProductionWarehouseReceiptMobileConfirmRequest body) {
        Long receivedBy = currentUserId();
        ProductionWarehouseReceiptRequest request = new ProductionWarehouseReceiptRequest();
        request.setIdempotencyKey(idempotencyKey(factoryId, productionPlanId));
        request.setReceivedQuantity(body.getReceivedQuantity());
        request.setOutputLines(body.getOutputLines());
        request.setVarianceNote(trimToNull(body.getNote()));

        log.info("Mobile warehouse receipt confirm: factoryId={}, planId={}, receivedBy={}",
                factoryId, productionPlanId, receivedBy);
        ProductionWarehouseReceiptResponse response = productionPlanService
                .confirmWarehouseReceipt(factoryId, productionPlanId, request, receivedBy);
        return ApiResponse.success(response);
    }

    private ProductionWarehouseReceiptMobileDTO toMobileDTO(String factoryId, ProductionSettlement settlement) {
        ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, settlement.getProductionPlanId());
        List<ProductionSettlementOutputLine> outputLines = productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        factoryId, settlement.getId());
        String outputUnit = commonOutputUnit(outputLines);
        String unit = outputLines.isEmpty()
                ? firstNonBlank(settlement.getQuantityUnit(), plan.getProductUnit())
                : outputUnit;
        Map<String, String> resolvedNames = resolveOutputProductNames(factoryId, outputLines);
        String productName = headlineProductName(factoryId, plan, outputLines, resolvedNames);
        return ProductionWarehouseReceiptMobileDTO.builder()
                .id(settlement.getProductionPlanId())
                .direction(DIRECTION)
                .status(RN_PENDING_STATUS)
                .sourceNumber(firstNonBlank(settlement.getPlanNumber(), plan.getPlanNumber()))
                .productName(productName)
                .batchNumber(outputLines.size() == 1
                        ? outputLines.getFirst().getReportedBatchNumber()
                        : outputLines.isEmpty() ? finishedGoodsBatchNumber(settlement) : null)
                .plannedQuantity(firstNonNull(settlement.getPlannedQuantity(), plan.getPlannedQuantity()))
                .reportedQuantity(settlement.getActualFinishedQuantity())
                .receivedQuantity(settlement.getWarehouseReceivedQuantity())
                .toleranceQuantity(unit == null ? null : receiptTolerance(unit))
                .unit(unit)
                .fromLocation(FROM_LOCATION)
                .toWarehouseName(finishedGoodsWarehouseName(factoryId))
                .submittedBy(settlement.getSettledBy())
                .submittedAt(settlement.getSettledAt())
                .note(firstNonBlank(settlement.getWarehouseVarianceNote(), settlement.getPostingMessage()))
                .outputLines(outputLines.stream()
                        .map(line -> toMobileOutputLine(factoryId, line, resolvedNames))
                        .toList())
                .build();
    }

    private ProductionWarehouseReceiptMobileDTO.OutputLine toMobileOutputLine(
            String factoryId,
            ProductionSettlementOutputLine line,
            Map<String, String> resolvedNames) {
        return ProductionWarehouseReceiptMobileDTO.OutputLine.builder()
                .productTypeId(line.getProductTypeId())
                .productName(displayProductName(factoryId, line.getProductTypeId(), resolvedNames))
                .batchNumber(line.getReportedBatchNumber())
                .reportedQuantity(line.getReportedQuantity())
                .receivedQuantity(line.getReceivedQuantity())
                .unit(line.getQuantityUnit())
                .status(line.getStatus())
                .build();
    }

    /**
     * 成品仓名 —— 走 {@link WarehouseResolver#resolveFinishedGoodsName} (code {@code WH-FG}),
     * 与确认入库时真正落库的那个仓 ({@code resolveFinishedGoodsId}) 是同一个权威, 不会漂。
     *
     * <p>查不到时给一句说明性的中文而不是 {@code null} —— 前端渲染的是
     * {@code toWarehouseName || '-'}, 留 null 等于让仓管盯着一个破折号猜。
     */
    private String finishedGoodsWarehouseName(String factoryId) {
        String name = trimToNull(warehouseResolver.resolveFinishedGoodsName(factoryId));
        if (name == null) {
            log.warn("Transit ledger: factory {} has no usable WH-FG warehouse — showing 'unspecified'",
                    factoryId);
            return NO_FINISHED_WAREHOUSE;
        }
        return name;
    }

    /**
     * 把本次结单所有产出行的 {@code productTypeId} 一次性翻成品名 (每个结单 1~2 条查询, 不是 N+1)。
     *
     * <p><b>两张表都要查</b>: 主产/联产的 SKU 在 {@code product_types}, 而<b>副产</b>
     * ({@code outputRole = BY_PRODUCT}) 在本系统里是<b>物料</b>不是产品, SKU 落在
     * {@code raw_material_types} —— 只查前者会让副产行恒显示「未知产品」。
     * 与 {@code WorkflowOutputDirectoryServiceImpl.resolveNames} 同一个套路。
     *
     * <p>两个 {@code findByIdIn} 都<b>不带工厂隔离</b>(仓内既定约定), 所以这里逐行
     * 手工比 {@code factoryId}: 跨厂命中一律当作没查到, ⛔ 不拿别厂的同 id 记录顶替。
     *
     * @return productTypeId → 品名。<b>只含真正查到的</b>; 查不到的 id 不会出现在 map 里,
     *         由 {@link #displayProductName} 决定对外怎么说。
     */
    private Map<String, String> resolveOutputProductNames(
            String factoryId, List<ProductionSettlementOutputLine> outputLines) {
        Set<String> ids = outputLines.stream()
                .map(ProductionSettlementOutputLine::getProductTypeId)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<String, String> names = new LinkedHashMap<>();
        for (ProductType product : productTypeRepository.findByFactoryIdAndIdIn(factoryId, ids)) {
            String name = trimToNull(product.getName());
            if (name != null) {
                names.put(product.getId(), name);
            }
        }

        List<String> stillUnknown = ids.stream().filter(id -> !names.containsKey(id)).toList();
        if (stillUnknown.isEmpty()) {
            return names;
        }
        for (RawMaterialType material : rawMaterialTypeRepository.findByIdIn(stillUnknown)) {
            String name = trimToNull(material.getName());
            if (name != null && factoryId.equals(material.getFactoryId())) {
                names.put(material.getId(), name);
            }
        }
        return names;
    }

    /**
     * 一行产出对外显示的品名。
     *
     * <p>⛔ 查不到时<b>绝不</b>回落成 {@code productTypeId} —— 那正是本次修的缺陷:
     * prod 上这一屏给仓库工人显示的是 {@code eb0aa47b-a5dd-49dc-af20-bf48ce8e1207}。
     * ⛔ 也不留空串/null: 低技术素养用户面对空白只会以为界面坏了。
     * 给一句说得清的中文, 同时 {@code outputLines[].productTypeId} 仍带着原 id 供技术支持定位。
     */
    private String displayProductName(
            String factoryId, String productTypeId, Map<String, String> resolvedNames) {
        String id = trimToNull(productTypeId);
        String name = id == null ? null : resolvedNames.get(id);
        if (name != null) {
            return name;
        }
        log.warn("Transit ledger: cannot resolve product name for factoryId={}, productTypeId={} "
                + "(checked product_types and raw_material_types)", factoryId, productTypeId);
        return UNKNOWN_PRODUCT_NAME;
    }

    /**
     * 卡片抬头的品名。
     *
     * <p>没有产出行 → 用计划自己的品名 ({@code ProductionPlanMapper} 已从 product_types 解析好);
     * 一行 → 就是那一行; 多行 → 「首个品名 等 N 项产出」(去重后计数)。
     *
     * <p>旧实现多行时返回英文的 {@code "2 Workflow outputs"} —— 整个界面是中文,
     * 用户是仓库工人, 那串英文和 UUID 是同一类问题的两个长相。
     */
    private String headlineProductName(
            String factoryId,
            ProductionPlanDTO plan,
            List<ProductionSettlementOutputLine> outputLines,
            Map<String, String> resolvedNames) {
        if (outputLines.isEmpty()) {
            String planName = trimToNull(plan == null ? null : plan.getProductName());
            return planName != null ? planName : UNKNOWN_PRODUCT_NAME;
        }
        List<String> distinct = outputLines.stream()
                .map(line -> displayProductName(factoryId, line.getProductTypeId(), resolvedNames))
                .distinct()
                .toList();
        if (distinct.size() == 1) {
            return distinct.getFirst();
        }
        return distinct.getFirst() + MULTI_OUTPUT_INFIX + distinct.size() + MULTI_OUTPUT_SUFFIX;
    }

    private String commonOutputUnit(List<ProductionSettlementOutputLine> outputLines) {
        if (outputLines == null || outputLines.isEmpty()) {
            return null;
        }
        Set<String> units = outputLines.stream()
                .map(ProductionSettlementOutputLine::getQuantityUnit)
                .map(this::canonicalUnit)
                .filter(unit -> unit != null)
                .collect(Collectors.toSet());
        return units.size() == 1 ? units.iterator().next() : null;
    }

    /**
     * \u8d70\u7cfb\u7edf\u6743\u5a01\u522b\u540d\u8868\u3002\u539f\u6765\u53ea\u8ba4 kg/\u516c\u65a4/\u5343\u514b \u548c g/\u514b \u4e24\u7ec4\uff0c\u800c\u5b83\u5582\u7684\u662f
     * {@link #commonOutputUnit} \u7684\u53bb\u91cd\u96c6\u5408 \u2014\u2014 \u540c\u4e00\u4e2a\u5355\u4f4d\u7684\u4e24\u79cd\u5199\u6cd5 (\u300c\u888b\u300d/\u300cbag\u300d\u3001
     * \u300c\u4ef6\u300d/\u300c\u4e2a\u300d) \u4f1a\u88ab\u6570\u6210\u4e24\u79cd\uff0c\u4e8e\u662f {@code units.size() == 1} \u4e0d\u6210\u7acb\uff0c
     * \u300c\u5171\u540c\u5355\u4f4d\u300d\u88ab\u5224\u6210\u6ca1\u6709\u3002\u4e0e 2026-07-31 \u62a5\u5de5/\u7ed3\u5355\u90a3\u4e24\u5904\u540c\u4e00\u4e2a\u6839\u56e0\u3002
     */
    private String canonicalUnit(String value) {
        return com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                .crossLanguageCode(trimToNull(value));
    }

    private Long currentUserId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object raw = attrs != null ? attrs.getAttribute("userId", RequestAttributes.SCOPE_REQUEST) : null;
        if (raw instanceof Long value) {
            return value;
        }
        if (raw instanceof Integer value) {
            return value.longValue();
        }
        if (raw instanceof Number value) {
            return value.longValue();
        }
        if (raw instanceof String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                throw new BusinessException(401, "Invalid userId in request context");
            }
        }
        throw new BusinessException(401, "Missing userId in request context");
    }

    private String idempotencyKey(String factoryId, String productionPlanId) {
        UUID stable = UUID.nameUUIDFromBytes((factoryId + ":" + productionPlanId)
                .getBytes(StandardCharsets.UTF_8));
        return "mobile-wh-receipt:" + stable;
    }

    private BigDecimal receiptTolerance(String unit) {
        String normalized = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "";
        if ("kg".equals(normalized)
                || "\u516c\u65a4".equals(normalized)
                || "\u5343\u514b".equals(normalized)) {
            return new BigDecimal("10.00");
        }
        if ("g".equals(normalized) || "\u514b".equals(normalized)) {
            return new BigDecimal("10000.00");
        }
        return BigDecimal.ZERO;
    }

    private String finishedGoodsBatchNumber(ProductionSettlement settlement) {
        String planNumber = firstNonBlank(settlement.getPlanNumber(), settlement.getProductionPlanId());
        String raw = "FG-" + planNumber;
        if (raw.length() <= 64) {
            return raw;
        }
        String settlementId = settlement.getId();
        String suffix = settlementId != null && settlementId.length() >= 8
                ? settlementId.substring(0, 8)
                : "pending";
        return raw.substring(0, 55) + "-" + suffix;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
