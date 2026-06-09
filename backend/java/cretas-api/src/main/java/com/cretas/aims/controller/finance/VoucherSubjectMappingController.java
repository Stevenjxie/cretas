package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.service.finance.VoucherSubjectMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * SP11: 凭证科目映射管理 API.
 *
 * <pre>
 * GET    /api/mobile/{factoryId}/finance/subject-mappings                          — 查询全部
 * GET    /api/mobile/{factoryId}/finance/subject-mappings/find                     — 查询特定映射
 * POST   /api/mobile/{factoryId}/finance/subject-mappings                          — upsert
 * DELETE /api/mobile/{factoryId}/finance/subject-mappings/{mappingId}              — 软删除
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/subject-mappings")
@RequiredArgsConstructor
@RequirePermission({"finance:read"})
public class VoucherSubjectMappingController {

    private final VoucherSubjectMappingService mappingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VoucherSubjectMappingDTO>>> list(
            @PathVariable String factoryId) {
        return ResponseEntity.ok(ApiResponse.success(mappingService.listByFactory(factoryId)));
    }

    @GetMapping("/find")
    public ResponseEntity<ApiResponse<VoucherSubjectMappingDTO>> find(
            @PathVariable String factoryId,
            @RequestParam SettlementType settlementType,
            @RequestParam(required = false) String businessType) {
        Optional<VoucherSubjectMappingDTO> result =
                mappingService.findMapping(factoryId, settlementType, businessType);
        return result
                .map(m -> ResponseEntity.ok(ApiResponse.success(m)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(null)));
    }

    @PostMapping
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<VoucherSubjectMappingDTO>> upsert(
            @PathVariable String factoryId,
            @RequestBody VoucherSubjectMappingDTO dto) {
        VoucherSubjectMappingDTO saved = mappingService.upsert(factoryId, dto);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @DeleteMapping("/{mappingId}")
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String factoryId,
            @PathVariable String mappingId) {
        mappingService.delete(factoryId, mappingId);
        return ResponseEntity.ok(ApiResponse.successMessage("删除成功"));
    }
}
