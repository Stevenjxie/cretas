package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.entity.restaurant.RestaurantCommission;
import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.RestaurantCommissionRepository;
import com.cretas.aims.service.restaurant.RestaurantCommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 餐饮营销员阶梯提成 REST controller（#59 Phase 2）。
 *
 * <p>Base URL: {@code /api/mobile/{factoryId}/restaurant/commission}
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET                       — paged list (filter by status / repId)</li>
 *   <li>GET /summary              — 某营销员某月累计汇总 (repId + month)</li>
 *   <li>PUT /{id}/mark-paid       — 标记发放</li>
 * </ul>
 *
 * <p>金额字段 {@code @PriceSensitive}，REST 路径由 {@code PriceFieldResponseAdvice} 按价权角色自动剥离。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/commission")
@RequiredArgsConstructor
public class RestaurantCommissionController {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final RestaurantCommissionService service;
    private final RestaurantCommissionRepository commissionRepository;

    @GetMapping
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long repId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        var pageReq = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        CommissionStatus statusEnum = parseStatusOrNull(status);

        Page<RestaurantCommission> result;
        if (repId != null && statusEnum != null) {
            result = commissionRepository
                    .findByFactoryIdAndRepIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                            factoryId, repId, statusEnum, pageReq);
        } else if (repId != null) {
            result = commissionRepository
                    .findByFactoryIdAndRepIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, repId, pageReq);
        } else if (statusEnum != null) {
            result = commissionRepository
                    .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, statusEnum, pageReq);
        } else {
            result = commissionRepository
                    .findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, pageReq);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "content", result.getContent(),
                        "totalElements", result.getTotalElements(),
                        "totalPages", result.getTotalPages(),
                        "count", result.getTotalElements()
                )
        ));
    }

    @GetMapping("/summary")
    @RequirePermission("sales:view")
    public ResponseEntity<Map<String, Object>> repSummary(
            @PathVariable String factoryId,
            @RequestParam Long repId,
            @RequestParam(required = false) String month
    ) {
        String periodKey = normalizeMonth(month);
        Optional<RestaurantRepCommissionSummary> opt = service.getRepSummary(factoryId, repId, periodKey);

        if (opt.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("repId", repId);
            empty.put("month", periodKey);
            empty.put("hasData", false);
            empty.put("attributedVisitCount", 0);
            empty.put("currentTier", null);
            return ResponseEntity.ok(Map.of("success", true, "data", empty));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", opt.get()));
    }

    @PutMapping("/{id}/mark-paid")
    @RequirePermission("finance:edit")
    public ResponseEntity<Map<String, Object>> markPaid(
            @PathVariable String factoryId,
            @PathVariable String id
    ) {
        RestaurantCommission c = service.markPaid(factoryId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", c,
                "message", "提成已标记为已发放"
        ));
    }

    // ==================== Helpers ====================

    private CommissionStatus parseStatusOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return CommissionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "status 非法: " + value
                    + ". 合法值: PENDING/PAID/CANCELLED");
        }
    }

    private String normalizeMonth(String month) {
        if (month != null && month.trim().matches("\\d{4}-\\d{2}")) {
            return month.trim();
        }
        return LocalDate.now().format(PERIOD_FMT);
    }
}
