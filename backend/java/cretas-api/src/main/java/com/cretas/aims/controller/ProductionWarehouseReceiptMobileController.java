package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileConfirmRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.service.ProductionPlanService;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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

    private final ProductionSettlementRepository productionSettlementRepository;
    private final ProductionPlanService productionPlanService;

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
        request.setVarianceNote(trimToNull(body.getNote()));

        log.info("Mobile warehouse receipt confirm: factoryId={}, planId={}, receivedBy={}",
                factoryId, productionPlanId, receivedBy);
        ProductionWarehouseReceiptResponse response = productionPlanService
                .confirmWarehouseReceipt(factoryId, productionPlanId, request, receivedBy);
        return ApiResponse.success(response);
    }

    private ProductionWarehouseReceiptMobileDTO toMobileDTO(String factoryId, ProductionSettlement settlement) {
        ProductionPlanDTO plan = productionPlanService.getProductionPlanById(factoryId, settlement.getProductionPlanId());
        String unit = firstNonBlank(settlement.getQuantityUnit(), plan.getProductUnit());
        return ProductionWarehouseReceiptMobileDTO.builder()
                .id(settlement.getProductionPlanId())
                .direction(DIRECTION)
                .status(RN_PENDING_STATUS)
                .sourceNumber(firstNonBlank(settlement.getPlanNumber(), plan.getPlanNumber()))
                .productName(plan.getProductName())
                .batchNumber(finishedGoodsBatchNumber(settlement))
                .plannedQuantity(firstNonNull(settlement.getPlannedQuantity(), plan.getPlannedQuantity()))
                .reportedQuantity(settlement.getActualFinishedQuantity())
                .receivedQuantity(settlement.getWarehouseReceivedQuantity())
                .toleranceQuantity(receiptTolerance(unit))
                .unit(unit)
                .fromLocation(FROM_LOCATION)
                .toWarehouseName(null)
                .submittedBy(settlement.getSettledBy())
                .submittedAt(settlement.getSettledAt())
                .note(firstNonBlank(settlement.getWarehouseVarianceNote(), settlement.getPostingMessage()))
                .build();
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
