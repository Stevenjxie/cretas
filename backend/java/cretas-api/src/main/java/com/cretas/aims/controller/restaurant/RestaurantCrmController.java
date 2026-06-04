package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.restaurant.RestaurantGuest;
import com.cretas.aims.entity.restaurant.RestaurantVisit;
import com.cretas.aims.entity.restaurant.enums.RestaurantGuestLifecycle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.RestaurantGuestRepository;
import com.cretas.aims.service.restaurant.RestaurantCrmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 餐饮 CRM 生命周期 + 营销员归属 Controller（#59 Phase 1）。
 *
 * <p>RBAC：读端点 {@code restaurant:read}，写端点 {@code restaurant:read_write}
 * （mirror 现有餐饮控制器与 PermissionService 的 module:action 二元矩阵；
 * 不用 3 段式 {@code restaurant:crm:*}——PermissionService.hasPermission 仅支持
 * 恰好 2 段 module:action，3 段码对非超管恒 false，等于永远拒绝）。</p>
 *
 * <p>手机号 PII 脱敏在 service 层：仅管理角色（工厂总监 / 平台管理员 / 餐饮管理）
 * 可见完整手机号，其余角色见脱敏值（前 3 后 4）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/crm")
@RequiredArgsConstructor
@Tag(name = "餐饮-CRM 生命周期与营销员归属")
public class RestaurantCrmController {

    private final RestaurantCrmService crmService;
    private final RestaurantGuestRepository guestRepository;

    /** For detaching managed entities before in-place phone masking (see maskGuestPhoneInPlace). */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /** 可见完整手机号的管理角色（其余脱敏）。 */
    private static final Set<String> PHONE_UNMASK_ROLES = Set.of(
            "factory_super_admin", "platform_admin", "restaurant_manager");

    // ==================== 登记 ====================

    @RequirePermission({"restaurant:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/guests")
    @Operation(summary = "登记散客", description = "幂等：同手机号已存在返回 409 + 已有客户 ID")
    public ApiResponse<RestaurantGuest> registerGuest(
            @PathVariable String factoryId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody @Valid RestaurantGuest guest) {
        RestaurantGuest saved = crmService.registerGuest(factoryId, guest, userId);
        return ApiResponse.success("散客登记成功", saved);
    }

    // ==================== 列表 / 详情 ====================

    @RequirePermission({"restaurant:read"})
    @RequireModule("restaurant")
    @GetMapping("/guests")
    @Operation(summary = "散客列表", description = "支持按营销员 / 生命周期阶段筛选")
    public ApiResponse<Page<RestaurantGuest>> listGuests(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long repId,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role) {
        RestaurantGuestLifecycle stage = parseLifecycle(lifecycle);
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);
        Page<RestaurantGuest> result = guestRepository.findByFilters(factoryId, repId, stage, pageable);
        // PII 脱敏（非管理角色）
        if (!canUnmaskPhone(role)) {
            result.getContent().forEach(this::maskGuestPhoneInPlace);
        }
        return ApiResponse.success(result);
    }

    @RequirePermission({"restaurant:read"})
    @RequireModule("restaurant")
    @GetMapping("/guests/{guestId}")
    @Operation(summary = "散客详情（含到访历史）")
    public ApiResponse<Map<String, Object>> guestDetail(
            @PathVariable String factoryId,
            @PathVariable String guestId,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role) {
        return ApiResponse.success(crmService.getGuestDetail(factoryId, guestId, canUnmaskPhone(role)));
    }

    @RequirePermission({"restaurant:read"})
    @RequireModule("restaurant")
    @GetMapping("/guests/vip")
    @Operation(summary = "重点客户（到访 3 次+）", description = "VIP 必须安排包厢")
    public ApiResponse<List<RestaurantGuest>> vipGuests(
            @PathVariable String factoryId,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role) {
        List<RestaurantGuest> vips = crmService.getVipGuests(factoryId);
        if (!canUnmaskPhone(role)) {
            vips.forEach(this::maskGuestPhoneInPlace);
        }
        return ApiResponse.success(vips);
    }

    @RequirePermission({"restaurant:read"})
    @RequireModule("restaurant")
    @GetMapping("/guests/at-risk")
    @Operation(summary = "即将流失客户", description = "默认 30 天未到访视为即将流失")
    public ApiResponse<List<RestaurantGuest>> atRiskGuests(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "30") int thresholdDays,
            @RequestAttribute(value = "role", required = false) @Parameter(hidden = true) String role) {
        List<RestaurantGuest> guests = crmService.getAtRiskGuests(factoryId, thresholdDays);
        if (!canUnmaskPhone(role)) {
            guests.forEach(this::maskGuestPhoneInPlace);
        }
        return ApiResponse.success(guests);
    }

    // ==================== 到访 ====================

    @RequirePermission({"restaurant:read"})
    @RequireModule("restaurant")
    @GetMapping("/guests/{guestId}/visit-limits")
    @Operation(summary = "到访预显边界（防呆）", description = "dialog 打开即显: 已到访N次, 本次第N+1次[首次不计/复购计业绩]")
    public ApiResponse<Map<String, Object>> visitLimits(
            @PathVariable String factoryId,
            @PathVariable String guestId) {
        return ApiResponse.success(crmService.getVisitLimits(factoryId, guestId));
    }

    @RequirePermission({"restaurant:read_write"})
    @RequireModule("restaurant")
    @PostMapping("/guests/{guestId}/visits")
    @Operation(summary = "记录到访", description = "首次不计业绩, 第二次复购起计业绩并归属维护营销员")
    public ApiResponse<RestaurantVisit> recordVisit(
            @PathVariable String factoryId,
            @PathVariable String guestId,
            @RequestAttribute("userId") @Parameter(hidden = true) Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body != null ? body : Collections.emptyMap();
        BigDecimal spend = toBigDecimal(b.get("spendAmount"));
        String channel = asString(b.get("channel"));
        String tableId = asString(b.get("tableId"));
        String notes = asString(b.get("notes"));
        RestaurantVisit visit = crmService.recordVisit(factoryId, guestId, spend, channel, tableId, notes, userId);
        String msg = visit.getIsQualifying()
                ? String.format("已记录第 %d 次到访（复购，计业绩）", visit.getVisitNumber())
                : String.format("已记录第 %d 次到访（首次，不计业绩）", visit.getVisitNumber());
        return ApiResponse.success(msg, visit);
    }

    // ==================== 营销员 / 权限 ====================

    @RequirePermission({"restaurant:read_write"})
    @RequireModule("restaurant")
    @PatchMapping("/guests/{guestId}/rep")
    @Operation(summary = "绑定/换绑营销员", description = "只改当前归属, 不回写历史到访业绩归属")
    public ApiResponse<RestaurantGuest> bindRep(
            @PathVariable String factoryId,
            @PathVariable String guestId,
            @RequestBody Map<String, Object> body) {
        Long repId = toLong(body.get("repId"));
        if (repId == null) {
            throw new BusinessException(400, "缺少营销员 ID (repId)")
                    .withSeverity("warning").withHintTarget("repId");
        }
        return ApiResponse.success("营销员绑定成功", crmService.bindRep(factoryId, guestId, repId));
    }

    @RequirePermission({"restaurant:read_write"})
    @RequireModule("restaurant")
    @PatchMapping("/guests/{guestId}/perks")
    @Operation(summary = "更新营销员权限配置", description = "进包厢/折扣(1-100)/赠果盘/啤酒(0-6)")
    public ApiResponse<RestaurantGuest> updatePerks(
            @PathVariable String factoryId,
            @PathVariable String guestId,
            @RequestBody Map<String, Object> perkConfig) {
        return ApiResponse.success("权限配置已更新", crmService.updatePerks(factoryId, guestId, perkConfig));
    }

    // ==================== 内部辅助 ====================

    private boolean canUnmaskPhone(String role) {
        return role != null && PHONE_UNMASK_ROLES.contains(role);
    }

    private void maskGuestPhoneInPlace(RestaurantGuest g) {
        if (g != null && g.getPhone() != null) {
            // Detach BEFORE mutating: these are managed JPA entities from the read query.
            // Under OSIV (open-in-view=true by default) a later flush would otherwise
            // persist the masked "138****1234" back to restaurant_guests.phone (data
            // corruption). Detaching makes the mask a response-only transformation.
            entityManager.detach(g);
            g.setPhone(com.cretas.aims.service.restaurant.impl.RestaurantCrmServiceImpl.maskPhone(g.getPhone()));
        }
    }

    private RestaurantGuestLifecycle parseLifecycle(String lifecycle) {
        if (!StringUtils.hasText(lifecycle)) return null;
        try {
            return RestaurantGuestLifecycle.valueOf(lifecycle.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的生命周期阶段: " + lifecycle)
                    .withHint("可选: " + Arrays.stream(RestaurantGuestLifecycle.values())
                            .map(Enum::name).collect(Collectors.joining(" / ")))
                    .withSeverity("warning").withHintTarget("lifecycle");
        }
    }

    private static String asString(Object v) {
        return v != null ? String.valueOf(v) : null;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
