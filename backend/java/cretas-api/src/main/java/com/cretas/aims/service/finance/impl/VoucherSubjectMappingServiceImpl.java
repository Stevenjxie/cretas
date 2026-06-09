package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.finance.VoucherSubjectMapping;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.VoucherSubjectMappingRepository;
import com.cretas.aims.service.finance.VoucherSubjectMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SP11: 凭证科目映射服务实现.
 *
 * <p>首次访问工厂时若无映射, 从 {@code __default__} 工厂复制 6 条种子数据
 * (Flyway V20260911_31 已写入 __default__).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoucherSubjectMappingServiceImpl implements VoucherSubjectMappingService {

    private static final String DEFAULT_FACTORY = "__default__";

    private final VoucherSubjectMappingRepository mappingRepo;

    @Override
    public List<VoucherSubjectMappingDTO> listByFactory(String factoryId) {
        // 若工厂无映射, 自动从 __default__ 初始化
        if (!mappingRepo.existsByFactoryIdAndDeletedAtIsNull(factoryId)) {
            autoInitFromDefault(factoryId);
        }
        return mappingRepo.findByFactoryIdAndDeletedAtIsNull(factoryId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<VoucherSubjectMappingDTO> findMapping(String factoryId,
                                                           SettlementType settlementType,
                                                           String businessType) {
        if (!mappingRepo.existsByFactoryIdAndDeletedAtIsNull(factoryId)) {
            autoInitFromDefault(factoryId);
        }
        // 精确匹配 (factoryId + settlementType + businessType)
        Optional<VoucherSubjectMapping> exact =
                mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                        factoryId, settlementType, businessType);
        if (exact.isPresent()) {
            return exact.map(this::toDTO);
        }
        // 兜底: businessType = null 的通用映射
        return mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                factoryId, settlementType, null).map(this::toDTO);
    }

    @Override
    @Transactional
    public VoucherSubjectMappingDTO upsert(String factoryId, VoucherSubjectMappingDTO dto) {
        Optional<VoucherSubjectMapping> existing =
                mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                        factoryId, dto.getSettlementType(), dto.getBusinessType());

        VoucherSubjectMapping entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new VoucherSubjectMapping();
            entity.setId(UUID.randomUUID().toString());
            entity.setFactoryId(factoryId);
        }

        entity.setSettlementType(dto.getSettlementType());
        entity.setBusinessType(dto.getBusinessType());
        entity.setDebitSubjectCode(dto.getDebitSubjectCode());
        entity.setDebitSubjectName(dto.getDebitSubjectName());
        entity.setCreditSubjectCode(dto.getCreditSubjectCode());
        entity.setCreditSubjectName(dto.getCreditSubjectName());
        entity.setRemark(dto.getRemark());
        entity.setUpdatedAt(LocalDateTime.now());

        VoucherSubjectMapping saved = mappingRepo.save(entity);
        log.info("[SP11] Upserted subject mapping factoryId={} settlementType={} businessType={}",
                factoryId, dto.getSettlementType(), dto.getBusinessType());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void delete(String factoryId, String mappingId) {
        VoucherSubjectMapping entity = mappingRepo.findById(mappingId)
                .filter(m -> m.getFactoryId().equals(factoryId))
                .orElseThrow(() -> new BusinessException("科目映射不存在或无权限删除: " + mappingId));
        entity.setDeletedAt(LocalDateTime.now());
        mappingRepo.save(entity);
        log.info("[SP11] Soft-deleted subject mapping id={}", mappingId);
    }

    // ===================== 私有方法 =====================

    @Transactional
    void autoInitFromDefault(String targetFactoryId) {
        List<VoucherSubjectMapping> defaults = mappingRepo.findByFactoryIdAndDeletedAtIsNull(DEFAULT_FACTORY);
        if (defaults.isEmpty()) {
            log.warn("[SP11] __default__ 工厂无科目映射种子数据, 跳过自动初始化");
            return;
        }
        for (VoucherSubjectMapping def : defaults) {
            VoucherSubjectMapping copy = new VoucherSubjectMapping();
            copy.setId(UUID.randomUUID().toString());
            copy.setFactoryId(targetFactoryId);
            copy.setSettlementType(def.getSettlementType());
            copy.setBusinessType(def.getBusinessType());
            copy.setDebitSubjectCode(def.getDebitSubjectCode());
            copy.setDebitSubjectName(def.getDebitSubjectName());
            copy.setCreditSubjectCode(def.getCreditSubjectCode());
            copy.setCreditSubjectName(def.getCreditSubjectName());
            copy.setRemark("自动从默认配置复制");
            mappingRepo.save(copy);
        }
        log.info("[SP11] Auto-initialized {} subject mappings for factoryId={}",
                defaults.size(), targetFactoryId);
    }

    private VoucherSubjectMappingDTO toDTO(VoucherSubjectMapping e) {
        return VoucherSubjectMappingDTO.builder()
                .id(e.getId())
                .factoryId(e.getFactoryId())
                .settlementType(e.getSettlementType())
                .businessType(e.getBusinessType())
                .debitSubjectCode(e.getDebitSubjectCode())
                .debitSubjectName(e.getDebitSubjectName())
                .creditSubjectCode(e.getCreditSubjectCode())
                .creditSubjectName(e.getCreditSubjectName())
                .remark(e.getRemark())
                .build();
    }
}
