package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerDTO;
import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.service.finance.VoucherExportService;
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
 * GET  /api/mobile/{factoryId}/inventory/ledger           — 查询台账
 * GET  /api/mobile/{factoryId}/inventory/ledger/export    — 导出台账 xlsx
 * </pre>
 *
 * <p>RBAC: 读取需 inventory:read; 金额字段由 PriceFieldResponseAdvice 对非财务角色自动遮蔽.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/inventory/ledger")
@RequiredArgsConstructor
@RequirePermission({"inventory:read"})
public class InventoryLedgerController {

    private final InventoryLedgerService ledgerService;
    private final VoucherExportService voucherExportService;

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
     * @param targetSystem 目标系统 (KINGDEE/YONYOU), 默认 KINGDEE
     */
    @GetMapping("/export")
    @RequirePermission({"inventory:read", "finance:read"})
    public void exportLedger(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String materialTypeId,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) userId = 0L;

        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(startDate)
                .endDate(endDate)
                .targetSystem(targetSystem)
                .build();

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportSequentialLedger(
                factoryId, req, userId, response.getOutputStream());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] InventoryLedger export: factoryId={} file={}", factoryId, fileName);
    }
}
