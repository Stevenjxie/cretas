package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.VoucherExportConfigDTO;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.entity.finance.VoucherExportConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.VoucherExportConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SP11 F6: 凭证导出配置 CRUD API.
 *
 * <pre>
 * GET    /api/mobile/{factoryId}/finance/export-config          — 查询全部配置
 * POST   /api/mobile/{factoryId}/finance/export-config          — 新建配置
 * PUT    /api/mobile/{factoryId}/finance/export-config/{id}     — 更新配置
 * DELETE /api/mobile/{factoryId}/finance/export-config/{id}     — 软删除
 * </pre>
 *
 * <p>R5 约束: 同工厂同 targetSystem 只能有一个配置 (UNIQUE factory_id + target_system).
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/export-config")
@RequiredArgsConstructor
@RequirePermission({"finance:read"})
public class VoucherExportConfigController {

    private final VoucherExportConfigRepository configRepo;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VoucherExportConfigDTO>>> list(
            @PathVariable String factoryId) {
        List<VoucherExportConfigDTO> configs = configRepo
                .findByFactoryIdAndDeletedAtIsNullOrderByTargetSystemAsc(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @PostMapping
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<VoucherExportConfigDTO>> create(
            @PathVariable String factoryId,
            @RequestBody VoucherExportConfigDTO dto) {
        // R5: 同 factory + targetSystem 唯一
        configRepo.findByFactoryIdAndTargetSystemAndDeletedAtIsNull(
                        factoryId, dto.getTargetSystem())
                .ifPresent(existing -> {
                    throw new BusinessException(409,
                            "工厂 " + factoryId + " 已有 " + dto.getTargetSystem() + " 导出配置, 请使用更新操作");
                });

        VoucherExportConfig entity = VoucherExportConfig.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .targetSystem(dto.getTargetSystem() != null ? dto.getTargetSystem() : VoucherTargetSystem.KINGDEE)
                .colVoucherNo(nvl(dto.getColVoucherNo(), "凭证字号"))
                .colDate(nvl(dto.getColDate(), "日期"))
                .colSummary(nvl(dto.getColSummary(), "摘要"))
                .colSubjectCode(nvl(dto.getColSubjectCode(), "科目编码"))
                .colSubjectName(nvl(dto.getColSubjectName(), "科目名称"))
                .colDebit(nvl(dto.getColDebit(), "借方金额"))
                .colCredit(nvl(dto.getColCredit(), "贷方金额"))
                .colAuxiliary(nvl(dto.getColAuxiliary(), "辅助核算"))
                .colCurrency(nvl(dto.getColCurrency(), "币别"))
                .isActive(Boolean.TRUE)
                .build();
        VoucherExportConfig saved = configRepo.save(entity);
        log.info("[SP11] Created VoucherExportConfig factoryId={} targetSystem={}", factoryId, dto.getTargetSystem());
        return ResponseEntity.ok(ApiResponse.success(toDTO(saved)));
    }

    @PutMapping("/{configId}")
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<VoucherExportConfigDTO>> update(
            @PathVariable String factoryId,
            @PathVariable String configId,
            @RequestBody VoucherExportConfigDTO dto) {
        VoucherExportConfig entity = configRepo.findByIdAndFactoryIdAndDeletedAtIsNull(configId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "导出配置不存在: " + configId));

        if (dto.getColVoucherNo() != null) entity.setColVoucherNo(dto.getColVoucherNo());
        if (dto.getColDate() != null) entity.setColDate(dto.getColDate());
        if (dto.getColSummary() != null) entity.setColSummary(dto.getColSummary());
        if (dto.getColSubjectCode() != null) entity.setColSubjectCode(dto.getColSubjectCode());
        if (dto.getColSubjectName() != null) entity.setColSubjectName(dto.getColSubjectName());
        if (dto.getColDebit() != null) entity.setColDebit(dto.getColDebit());
        if (dto.getColCredit() != null) entity.setColCredit(dto.getColCredit());
        if (dto.getColAuxiliary() != null) entity.setColAuxiliary(dto.getColAuxiliary());
        if (dto.getColCurrency() != null) entity.setColCurrency(dto.getColCurrency());
        if (dto.getIsActive() != null) entity.setIsActive(dto.getIsActive());
        entity.setUpdatedAt(LocalDateTime.now());

        VoucherExportConfig saved = configRepo.save(entity);
        return ResponseEntity.ok(ApiResponse.success(toDTO(saved)));
    }

    @DeleteMapping("/{configId}")
    @RequirePermission({"finance:read_write"})
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String factoryId,
            @PathVariable String configId) {
        VoucherExportConfig entity = configRepo.findByIdAndFactoryIdAndDeletedAtIsNull(configId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "导出配置不存在: " + configId));
        entity.setDeletedAt(LocalDateTime.now());
        configRepo.save(entity);
        return ResponseEntity.ok(ApiResponse.successMessage("删除成功"));
    }

    private VoucherExportConfigDTO toDTO(VoucherExportConfig e) {
        return VoucherExportConfigDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .targetSystem(e.getTargetSystem())
                .colVoucherNo(e.getColVoucherNo())
                .colDate(e.getColDate())
                .colSummary(e.getColSummary())
                .colSubjectCode(e.getColSubjectCode())
                .colSubjectName(e.getColSubjectName())
                .colDebit(e.getColDebit())
                .colCredit(e.getColCredit())
                .colAuxiliary(e.getColAuxiliary())
                .colCurrency(e.getColCurrency())
                .isActive(e.getIsActive())
                .build();
    }

    private String nvl(String v, String def) {
        return v != null ? v : def;
    }
}
