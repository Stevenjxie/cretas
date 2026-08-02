package com.cretas.aims.controller;

import com.cretas.aims.dto.WhitelistDTO;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.WhitelistService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import com.cretas.aims.config.RequireRole;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 白名单管理控制器
 *
 * <h2>授权 (APP-RBAC-003, 2026-08-02 Steve 拍板放开给 HR)</h2>
 *
 * <p>本控制器此前每个接口都是 {@code @RequireRole({"factory_super_admin","permission_admin"})},
 * 而这个 Tab 在移动端只挂在 HR 导航下 —— 也就是<b>唯一能看见它的角色必然 403,
 * 从来没有人用成功过</b>。注解与产品意图矛盾 (它明显是为 HR 建的: 住在 HR 导航里、
 * HR 首页有「待激活白名单」卡片)。2026-08-02 由 Steve 拍板: 直接放开给 {@code hr_admin}。
 *
 * <p>放开做了两件事, 都刻意<b>不扩大 system 模块的授权面</b>:
 * <ol>
 *   <li>每个 {@code @RequireRole} 加 {@code "hr_admin"};</li>
 *   <li>写接口的 {@code @RequirePermission} 从 {@code system:read_write} 改成
 *       {@code hr:read_write} —— 员工注册白名单本就是 HR 域资源。
 *       改前后对<b>已获准角色</b>零影响: {@code factory_super_admin} /
 *       {@code permission_admin} 在 {@code PermissionServiceImpl} 里都是全模块
 *       read_write, 两个权限都拿得到; {@code hr_admin} 有 {@code hr:read_write}
 *       但没有 {@code system} 任何权限, 所以只改 {@code @RequireRole} 是不够的 ——
 *       写接口仍会被 {@code @RequirePermission} 挡掉 (两个注解是 AND 关系,
 *       分别由 {@code RequireRoleInterceptor} 与 {@code PermissionInterceptor} 执行)。
 *       只放一半 = 列表能看、「新增」按钮点了 403, 正是本次要根治的那个症状。</li>
 * </ol>
 *
 * <p>⚠️ 前端 {@code permissionHelper.ts} 的 {@code WHITELIST_ACCESS_ROLES} 是这份
 * 注解的镜像, 必须同步改, 否则入口不显示 (fail-closed 那侧)。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/whitelist")
@Tag(name = "白名单管理", description = "员工注册白名单管理接口，包括白名单的批量添加、查询、更新、删除，手机号验证，使用次数统计，过期管理，导入导出等功能")
@RequiredArgsConstructor
public class WhitelistController {

    private final WhitelistService whitelistService;
    private final MobileService mobileService;

    @RequirePermission({"hr:read_write"})
    @PostMapping("/batch")
    @Operation(summary = "批量添加白名单", description = "批量添加多个手机号到白名单，返回成功和失败的详细结果")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO.BatchResult> batchAdd(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestHeader("Authorization") @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...") String authorization,
            @RequestBody @Valid @Parameter(description = "批量添加请求，包含entries数组") WhitelistDTO.BatchAddRequest request) {
        log.info("批量添加白名单: factoryId={}, count={}", factoryId, request.getEntries().size());
        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        WhitelistDTO.BatchResult result = whitelistService.batchAdd(factoryId, userId, request);
        return ApiResponse.success(result);
    }

    /**
     * 单条添加白名单（前端 web-admin 表单直连）
     * 内部包装为 batchAdd 的 size=1 请求，复用 BatchResult 逻辑。
     * 前端 POST /api/mobile/{factoryId}/whitelist 由此方法处理。
     */
    @RequirePermission({"hr:read_write"})
    @PostMapping
    @Operation(summary = "添加单条白名单", description = "添加单个手机号到白名单，内部包装为 batch-of-1 调用 batchAdd")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO.BatchResult> addSingle(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestHeader("Authorization") @Parameter(description = "访问令牌") String authorization,
            @RequestBody @Valid @Parameter(description = "单条白名单添加请求") WhitelistDTO.SingleAddRequest request) {
        log.info("添加单条白名单: factoryId={}, phone={}", factoryId, request.getPhoneNumber());

        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        // 将前端 expirationDate (YYYY-MM-DD) 转成后端 BatchAddRequest.expiresAt (LocalDateTime)
        LocalDateTime expiresAt = null;
        if (request.getExpirationDate() != null && !request.getExpirationDate().isBlank()) {
            // 日期当天 23:59:59 作为过期时刻
            expiresAt = LocalDateTime.of(LocalDate.parse(request.getExpirationDate()), LocalTime.of(23, 59, 59));
        }

        WhitelistDTO.WhitelistEntry entry = WhitelistDTO.WhitelistEntry.builder()
                .phoneNumber(request.getPhoneNumber())
                .name(request.getName())
                .build();

        WhitelistDTO.BatchAddRequest batchRequest = WhitelistDTO.BatchAddRequest.builder()
                .entries(List.of(entry))
                .expiresAt(expiresAt)
                .maxUsageCount(request.getMaxUsageCount())
                .department(request.getDepartmentId())
                .role(request.getRole())
                .notes(request.getNotes())
                .build();

        WhitelistDTO.BatchResult result = whitelistService.batchAdd(factoryId, userId, batchRequest);
        return ApiResponse.success(result);
    }

    @GetMapping
    @Operation(summary = "获取白名单列表", description = "分页获取白名单列表，支持按状态、部门、角色筛选和关键词搜索")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<PageResponse<WhitelistDTO>> getWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态：ACTIVE-激活/EXPIRED-过期/LIMIT_REACHED-达上限", example = "ACTIVE") String status,
            @RequestParam(required = false) @Parameter(description = "部门筛选", example = "生产部") String department,
            @RequestParam(required = false) @Parameter(description = "角色筛选", example = "worker") String role,
            @RequestParam(required = false) @Parameter(description = "搜索关键词（手机号/姓名）", example = "138") String keyword,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码（1-based）", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size,
            @RequestParam(defaultValue = "createdAt") @Parameter(description = "排序字段：createdAt/usageCount/lastUsedAt", example = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") @Parameter(description = "排序方向：ASC/DESC", example = "DESC") String sortDirection) {

        log.debug("获取白名单列表: factoryId={}, status={}, keyword={}", factoryId, status, keyword);

        WhitelistDTO.QueryRequest queryRequest = WhitelistDTO.QueryRequest.builder()
                .status(status)
                .department(department)
                .role(role)
                .keyword(keyword)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ?
                Sort.Direction.ASC : Sort.Direction.DESC;
        // 前端使用1-based索引，Spring Data使用0-based索引，需要减1
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size, Sort.by(direction, sortBy));

        PageResponse<WhitelistDTO> response = whitelistService.getWhitelist(factoryId, queryRequest, pageable);
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取白名单详情", description = "根据ID获取白名单记录的详细信息")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO> getWhitelistById(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "白名单ID", example = "1") Integer id) {
        log.debug("获取白名单详情: factoryId={}, id={}", factoryId, id);
        WhitelistDTO whitelist = whitelistService.getWhitelistById(factoryId, id);
        return ApiResponse.success(whitelist);
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新白名单", description = "更新白名单记录信息，包括姓名、部门、角色、有效期等")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO> updateWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "白名单ID", example = "1") Integer id,
            @RequestBody @Valid @Parameter(description = "更新请求内容") WhitelistDTO.UpdateRequest request) {
        log.info("更新白名单: factoryId={}, id={}", factoryId, id);
        WhitelistDTO updated = whitelistService.updateWhitelist(factoryId, id, request);
        return ApiResponse.success(updated);
    }

    @RequirePermission({"hr:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "删除白名单", description = "删除指定的白名单记录（软删除）")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Void> deleteWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "白名单ID", example = "1") Integer id) {
        log.info("删除白名单: factoryId={}, id={}", factoryId, id);
        whitelistService.deleteWhitelist(factoryId, id);
        return ApiResponse.success();
    }

    @RequirePermission({"hr:read_write"})
    @DeleteMapping("/batch")
    @Operation(summary = "批量删除白名单", description = "批量删除多个白名单记录，返回删除的数量")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Integer> batchDelete(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody @Parameter(description = "白名单ID列表") List<Integer> ids) {
        log.info("批量删除白名单: factoryId={}, ids={}", factoryId, ids);
        Integer count = whitelistService.batchDelete(factoryId, ids);
        return ApiResponse.success(count);
    }

    @GetMapping("/stats")
    @Operation(summary = "获取白名单统计信息", description = "获取白名单的总数、激活数、过期数等统计信息")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO.WhitelistStats> getStats(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.debug("获取白名单统计: factoryId={}", factoryId);
        WhitelistDTO.WhitelistStats stats = whitelistService.getStats(factoryId);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/expired")
    @Operation(summary = "更新过期的白名单状态", description = "将已过有效期的白名单状态更新为EXPIRED，返回更新数量")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Integer> updateExpired(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("更新过期的白名单状态: factoryId={}", factoryId);
        Integer count = whitelistService.updateExpiredWhitelist(factoryId);
        return ApiResponse.success(count);
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/limit-reached")
    @Operation(summary = "更新达到使用上限的白名单状态", description = "将使用次数达上限的白名单状态更新为LIMIT_REACHED，返回更新数量")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Integer> updateLimitReached(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        log.info("更新达到使用上限的白名单状态: factoryId={}", factoryId);
        Integer count = whitelistService.updateLimitReachedWhitelist();
        return ApiResponse.success(count);
    }

    @GetMapping("/validate/{phoneNumber}")
    @Operation(summary = "验证手机号是否在白名单中", description = "检查手机号是否在有效的白名单中，用于注册前验证")
    public ApiResponse<WhitelistDTO.ValidationResponse> validatePhoneNumber(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "手机号", example = "13812345678") String phoneNumber) {
        log.debug("验证手机号: factoryId={}, phone={}", factoryId, phoneNumber);
        WhitelistDTO.ValidationResponse response = whitelistService.validatePhoneNumber(factoryId, phoneNumber);
        return ApiResponse.success(response);
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/usage/{phoneNumber}")
    @Operation(summary = "增加白名单使用次数", description = "用户注册成功后增加对应白名单的使用次数")
    public ApiResponse<Void> incrementUsage(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "手机号", example = "13812345678") String phoneNumber) {
        log.debug("增加使用次数: factoryId={}, phone={}", factoryId, phoneNumber);
        whitelistService.incrementUsage(factoryId, phoneNumber);
        return ApiResponse.success();
    }

    @GetMapping("/search")
    @Operation(summary = "搜索白名单", description = "根据关键词搜索白名单，匹配手机号、姓名等字段")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<PageResponse<WhitelistDTO>> searchWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @Parameter(description = "搜索关键词（手机号/姓名）", example = "张三") String keyword,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码（1-based）", example = "1") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页大小", example = "20") Integer size) {
        log.debug("搜索白名单: factoryId={}, keyword={}", factoryId, keyword);
        // 前端使用1-based索引，Spring Data使用0-based索引，需要减1
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size);
        PageResponse<WhitelistDTO> response = whitelistService.searchWhitelist(factoryId, keyword, pageable);
        return ApiResponse.success(response);
    }

    @GetMapping("/expiring")
    @Operation(summary = "获取即将过期的白名单", description = "获取指定天数内即将过期的白名单列表，用于提前提醒")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<List<WhitelistDTO>> getExpiringSoon(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "7") @Parameter(description = "即将过期的天数范围", example = "7") Integer days) {
        log.debug("获取即将过期的白名单: factoryId={}, days={}", factoryId, days);
        List<WhitelistDTO> expiring = whitelistService.getExpiringSoon(factoryId, days);
        return ApiResponse.success(expiring);
    }

    @GetMapping("/most-active")
    @Operation(summary = "获取最活跃的白名单用户", description = "按使用次数排序获取最活跃的白名单记录")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<List<WhitelistDTO>> getMostActiveUsers(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "10") @Parameter(description = "返回记录数量", example = "10") Integer limit) {
        log.debug("获取最活跃用户: factoryId={}, limit={}", factoryId, limit);
        List<WhitelistDTO> users = whitelistService.getMostActiveUsers(factoryId, limit);
        return ApiResponse.success(users);
    }

    @GetMapping("/recently-used")
    @Operation(summary = "获取最近使用的白名单", description = "按最后使用时间排序获取最近使用的白名单记录")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<List<WhitelistDTO>> getRecentlyUsed(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "10") @Parameter(description = "返回记录数量", example = "10") Integer limit) {
        log.debug("获取最近使用: factoryId={}, limit={}", factoryId, limit);
        List<WhitelistDTO> users = whitelistService.getRecentlyUsed(factoryId, limit);
        return ApiResponse.success(users);
    }

    @GetMapping("/export")
    @Operation(summary = "导出白名单", description = "将白名单数据导出为CSV格式字符串")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<String> exportWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态筛选：ACTIVE/EXPIRED/LIMIT_REACHED", example = "ACTIVE") String status) {
        log.info("导出白名单: factoryId={}, status={}", factoryId, status);
        String csvData = whitelistService.exportWhitelist(factoryId, status);
        return ApiResponse.success(csvData);
    }

    @RequirePermission({"hr:read_write"})
    @PostMapping("/import")
    @Operation(summary = "导入白名单", description = "从CSV格式数据批量导入白名单，返回导入结果")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO.BatchResult> importWhitelist(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody @Parameter(description = "CSV格式数据") String csvData) {
        log.info("导入白名单: factoryId={}", factoryId);
        WhitelistDTO.BatchResult result = whitelistService.importWhitelist(factoryId, csvData);
        return ApiResponse.success(result);
    }

    @RequirePermission({"hr:read_write"})
    @DeleteMapping("/cleanup")
    @Operation(summary = "清理已删除的记录", description = "物理删除指定天数之前已软删除的记录，释放空间")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Integer> cleanupDeleted(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(defaultValue = "30") @Parameter(description = "删除多少天前的记录", example = "30") Integer daysOld) {
        log.info("清理已删除记录: factoryId={}, daysOld={}", factoryId, daysOld);
        Integer count = whitelistService.cleanupDeleted(daysOld);
        return ApiResponse.success(count);
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/{id}/reset-usage")
    @Operation(summary = "重置使用次数", description = "将指定白名单的使用次数重置为0")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<Void> resetUsageCount(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "白名单ID", example = "1") Integer id) {
        log.info("重置使用次数: factoryId={}, id={}", factoryId, id);
        whitelistService.resetUsageCount(factoryId, id);
        return ApiResponse.success();
    }

    @RequirePermission({"hr:read_write"})
    @PutMapping("/{id}/extend")
    @Operation(summary = "延长有效期", description = "延长指定白名单的有效期，在当前过期时间基础上增加天数")
    @RequireRole({"factory_super_admin", "permission_admin", "hr_admin"})
    public ApiResponse<WhitelistDTO> extendExpiration(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @PathVariable @Parameter(description = "白名单ID", example = "1") Integer id,
            @RequestParam @Parameter(description = "延长天数", example = "30") Integer days) {
        log.info("延长有效期: factoryId={}, id={}, days={}", factoryId, id, days);
        WhitelistDTO updated = whitelistService.extendExpiration(factoryId, id, days);
        return ApiResponse.success(updated);
    }
}