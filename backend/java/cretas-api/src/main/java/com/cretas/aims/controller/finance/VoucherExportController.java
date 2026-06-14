package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.service.finance.VoucherExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * SP11: 凭证序时账/科目余额表导出 API.
 *
 * <pre>
 * POST /api/mobile/{factoryId}/finance/voucher-export           — 序时账导出 xlsx
 * GET  /api/mobile/{factoryId}/finance/subject-balance/export   — 科目余额表导出 xlsx
 * </pre>
 *
 * <p>RBAC: finance:read_write (财务人员), 仓管无权导出.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance")
@RequiredArgsConstructor
@RequirePermission({"finance:read_write"})
public class VoucherExportController {

    private final VoucherExportService voucherExportService;

    /**
     * 导出凭证序时账 xlsx.
     *
     * <p>Request body: {@link VoucherExportRequestDTO} (startDate, endDate, targetSystem).
     */
    @PostMapping("/voucher-export")
    public void exportSequentialLedger(
            @PathVariable String factoryId,
            @RequestBody @Valid VoucherExportRequestDTO req,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) userId = 0L;

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportSequentialLedger(
                factoryId, req, userId, response.getOutputStream());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] VoucherExport sequential: factoryId={} file={}", factoryId, fileName);
    }

    /**
     * 导出科目余额表 xlsx.
     *
     * <p>Query params: startDate, endDate, targetSystem.
     */
    @GetMapping("/subject-balance/export")
    public void exportSubjectBalance(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) userId = 0L;

        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(java.time.LocalDate.parse(startDate))
                .endDate(java.time.LocalDate.parse(endDate))
                .targetSystem(targetSystem)
                .build();

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportSubjectBalance(
                factoryId, req, userId, response.getOutputStream());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] VoucherExport subjectBalance: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/chronological-ledger/export")
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportChronologicalLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportChronologicalLedger(
                factoryId, req, userId, response.getOutputStream());
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] chronological export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/general-ledger/export")
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportGeneralLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportGeneralLedger(
                factoryId, req, userId, response.getOutputStream());
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] general ledger export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/subsidiary-ledger/export")
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportSubsidiaryLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportSubsidiaryLedger(
                factoryId, req, userId, response.getOutputStream());
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] subsidiary ledger export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/trial-balance/export")
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportTrialBalance(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = voucherExportService.exportTrialBalance(
                factoryId, req, userId, response.getOutputStream());
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] trial balance export: factoryId={} file={}", factoryId, fileName);
    }

    private Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return userId == null ? 0L : userId;
    }

    private VoucherExportRequestDTO buildRequest(String startDate, String endDate, VoucherTargetSystem targetSystem) {
        return VoucherExportRequestDTO.builder()
                .startDate(java.time.LocalDate.parse(startDate))
                .endDate(java.time.LocalDate.parse(endDate))
                .targetSystem(targetSystem)
                .build();
    }

    private void setAttachmentHeader(HttpServletResponse response, String fileName) {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
    }
}
