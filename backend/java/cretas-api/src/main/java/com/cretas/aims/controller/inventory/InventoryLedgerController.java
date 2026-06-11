package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.InventoryLedgerDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * SP11: 进销存台账 REST API.
 *
 * <pre>
 * GET  /api/mobile/{factoryId}/inventory/ledger                        — 查询台账 (按单个物料类型过滤)
 * GET  /api/mobile/{factoryId}/inventory/ledger/export                 — 导出台账 xlsx
 * GET  /api/mobile/{factoryId}/inventory/ledger/by-kind                — P11: 按物料大类(原料/辅料/包材)查询
 * GET  /api/mobile/{factoryId}/inventory/ledger/by-kind/export         — P11: 按物料大类导出 xlsx
 * </pre>
 *
 * <p>RBAC: 读取需 inventory:read; 金额字段由 PriceFieldResponseAdvice 对非财务角色自动遮蔽 (JSON 响应).
 * 导出 xlsx 走 binary 流, 绕过 advice, 由本 controller 手动计算 includePrices 遮蔽金额列.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/inventory/ledger")
@RequiredArgsConstructor
@RequirePermission({"inventory:read"})
public class InventoryLedgerController {

    /** 与 PriceFieldResponseAdvice 一致的双权限门 — 同时持有才可见金额. */
    private static final String PRICE_VIEW_PERMISSION = "procurement:price:view";
    private static final String FINANCE_READ_PERMISSION = "finance:read_write";

    private final InventoryLedgerService ledgerService;
    private final PermissionService permissionService;
    private final UserRepository userRepository;

    /**
     * 查询进销存台账.
     *
     * @param startDate      期间开始 (yyyy-MM-dd)
     * @param endDate        期间结束 (yyyy-MM-dd)
     * @param materialTypeId 可选: 按物料类型过滤
     */
    @GetMapping
    public ResponseEntity<ApiResponse<InventoryLedgerDTO>> getLedger(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String materialTypeId) {

        List<InventoryLedgerLineDTO> lines = ledgerService.getLedger(
                factoryId, startDate, endDate, materialTypeId);

        InventoryLedgerDTO dto = InventoryLedgerDTO.builder()
                .factoryId(factoryId)
                .startDate(startDate.toString())
                .endDate(endDate.toString())
                .lines(lines)
                .build();

        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 导出进销存台账 xlsx.
     *
     * <p>修复审计 Tier0 #04 "货不对板": 旧实现错调
     * {@code VoucherExportService.exportSequentialLedger} 导出的是<b>凭证序时账</b>(财务凭证流水),
     * 客户下载到的并非进销存台账. 现改为调
     * {@link InventoryLedgerService#exportInventoryLedger} —— 与查询接口 {@link #getLedger} 同源的
     * 进销存台账数据 (期初/入库/生产领用/销售出货/调拨进出/盘盈损/期末).
     *
     * <p>金额列遮蔽: binary 流绕过 PriceFieldResponseAdvice, 故此处手动计算 includePrices
     * (双权限门 procurement:price:view + finance:read_write, 与 advice "full access" 短路一致),
     * 非财务角色导出的 xlsx 不含金额列. Fail-CLOSED: 用户解析失败 / 权限检查异常 → 不含金额.
     */
    @GetMapping("/export")
    @RequirePermission({"inventory:read", "finance:read"})
    public void exportLedger(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String materialTypeId,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        boolean includePrices = resolveCanViewPrices(request);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = ledgerService.exportInventoryLedger(
                factoryId, startDate, endDate, materialTypeId, includePrices,
                response.getOutputStream());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] InventoryLedger export: factoryId={} includePrices={} file={}",
                factoryId, includePrices, fileName);
    }

    // ==================== P11: 按物料大类(原料/辅料/包材)查询 ====================

    /**
     * P11: 查询进销存台账 — 按物料大类筛选.
     *
     * <p>materialKind = 对应 raw_material_types.category 字段的大类标签:
     * "原料" / "辅料" / "包材". 不传 = 全部.
     *
     * <p>GET /api/mobile/{factoryId}/inventory/ledger/by-kind
     *       ?startDate=2026-06-01&endDate=2026-06-30&materialKind=包材
     */
    @GetMapping("/by-kind")
    public ResponseEntity<ApiResponse<InventoryLedgerDTO>> getLedgerByKind(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String materialKind) {

        List<InventoryLedgerLineDTO> lines = ledgerService.getLedgerByKind(
                factoryId, startDate, endDate, materialKind);

        InventoryLedgerDTO dto = InventoryLedgerDTO.builder()
                .factoryId(factoryId)
                .startDate(startDate.toString())
                .endDate(endDate.toString())
                .lines(lines)
                .build();

        log.info("[P11] getLedgerByKind: factoryId={} kind={} lines={}", factoryId, materialKind, lines.size());
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * P11: 导出进销存台账 xlsx — 按物料大类筛选.
     *
     * <p>GET /api/mobile/{factoryId}/inventory/ledger/by-kind/export
     *       ?startDate=...&endDate=...&materialKind=包材
     */
    @GetMapping("/by-kind/export")
    @RequirePermission({"inventory:read", "finance:read"})
    public void exportLedgerByKind(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String materialKind,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        boolean includePrices = resolveCanViewPrices(request);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = ledgerService.exportInventoryLedgerByKind(
                factoryId, startDate, endDate, materialKind, includePrices,
                response.getOutputStream());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[P11] InventoryLedger by-kind export: factoryId={} kind={} includePrices={} file={}",
                factoryId, materialKind, includePrices, fileName);
    }

    // ==================== End P11 ====================

    /**
     * 计算导出 xlsx 是否包含金额列 — 镜像 PriceFieldResponseAdvice 的双权限门.
     * 仅当用户同时持有 procurement:price:view + finance:read_write 才返回 true.
     * Fail-CLOSED: 无法解析用户 / 权限检查抛异常 → false (不含金额).
     */
    private boolean resolveCanViewPrices(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return false;
        }
        Long userId;
        if (userIdObj instanceof Long l) {
            userId = l;
        } else if (userIdObj instanceof Integer i) {
            userId = i.longValue();
        } else {
            try {
                userId = Long.parseLong(userIdObj.toString());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                return false;
            }
            return permissionService.hasPermission(user, PRICE_VIEW_PERMISSION)
                    && permissionService.hasPermission(user, FINANCE_READ_PERMISSION);
        } catch (Exception e) {
            log.warn("[SP11] resolveCanViewPrices failed for userId={}, fail-CLOSED (no amounts): {}",
                    userId, e.getMessage());
            return false;
        }
    }
}
