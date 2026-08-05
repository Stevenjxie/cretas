package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitUsageScope;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SupplierMaterialPurchaseSpecServiceImpl implements SupplierMaterialPurchaseSpecService {
    private final SupplierMaterialPurchaseSpecRepository repository;
    private final SupplierMaterialRepository relationRepository;
    private final RawMaterialTypeRepository materialRepository;
    private final UnitContractService unitContractService;

    @Override @Transactional(readOnly = true)
    public List<SupplierMaterialPurchaseSpecDTO> list(String factoryId, String supplierId, String relationId) {
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        return repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc(factoryId, relationId)
                .stream().map(spec -> withDerivedPrice(toDto(spec), spec, relation)).toList();
    }

    /**
     * 规格没有自己的报价时, 用**本规格行自己声明的换算系数**把供应关系上的采购价折到该规格的包装单位。
     *
     * <p>客户 2026-07-30 表格第 38 行: 配了采购规格后计价单位变成「箱」, 而供应关系价是「元/kg」,
     * 前端 {@code applyRelationPrice} 单位不等就把价清空 —— 于是「设完换算规格反而不带价了」。
     *
     * <p>刻意只认两种情形, 跨不过去就留 null:
     * <ul>
     *   <li>关系价单位 == 规格包装单位 → 原样带过来 (<b>不能再乘一次系数</b>)</li>
     *   <li>关系价单位 == 规格库存基本单位 → 价 × 本规格的 conversionFactor</li>
     * </ul>
     * 不引入通用单位引擎: 换算依据必须是这一行自己写着的「1 箱 = N kg」, 否则就是替用户猜价格。
     */
    private SupplierMaterialPurchaseSpecDTO withDerivedPrice(SupplierMaterialPurchaseSpecDTO dto,
                                                             SupplierMaterialPurchaseSpec spec,
                                                             SupplierMaterial relation) {
        if (spec.getQuotedPrice() != null) return dto;          // 有自己的报价, 不推导
        BigDecimal relationPrice = relation.getDefaultPurchasePrice();
        if (relationPrice == null || relationPrice.compareTo(BigDecimal.ZERO) <= 0) return dto;

        String relationUnit = normalizedUnit(relation.getPurchaseUnit());
        String packageUnit = normalizedUnit(spec.getPurchasePackageUnit());
        String baseUnit = normalizedUnit(spec.getInventoryBaseUnit());
        if (relationUnit == null || packageUnit == null) return dto;

        if (relationUnit.equals(packageUnit)) {
            dto.setDerivedPrice(relationPrice);
            dto.setDerivedPriceUnit(spec.getPurchasePackageUnit());
            dto.setDerivedPriceSource("SUPPLIER_RELATION_SAME_UNIT");
            return dto;
        }
        BigDecimal factor = spec.getConversionFactor();
        if (baseUnit != null && baseUnit.equals(relationUnit)
                && factor != null && factor.compareTo(BigDecimal.ZERO) > 0) {
            dto.setDerivedPrice(relationPrice.multiply(factor));
            dto.setDerivedPriceUnit(spec.getPurchasePackageUnit());
            dto.setDerivedPriceSource("SUPPLIER_RELATION_CONVERTED");
        }
        return dto;
    }

    private String normalizedUnit(String unit) {
        if (unit == null) return null;
        String trimmed = unit.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    @Override @Transactional
    public SupplierMaterialPurchaseSpecDTO create(String factoryId, String supplierId, String relationId,
                                                   SupplierMaterialPurchaseSpecRequest request) {
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        if (!Boolean.TRUE.equals(relation.getActive())) throw new BusinessException(409, "供应关系已停用，不能新增采购包装规格");
        RawMaterialType material = ownedMaterial(factoryId, relation.getMaterialTypeId());
        SupplierMaterialPurchaseSpec spec = new SupplierMaterialPurchaseSpec();
        spec.setFactoryId(factoryId);
        spec.setSupplierMaterialId(relationId);
        spec.setMaterialTypeId(material.getId());
        apply(factoryId, material, spec, request);
        enforceDefault(factoryId, relationId, spec);
        return toDto(repository.saveAndFlush(spec));
    }

    @Override @Transactional
    public SupplierMaterialPurchaseSpecDTO update(String factoryId, String supplierId, String relationId, String specId,
                                                   SupplierMaterialPurchaseSpecRequest request) {
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        SupplierMaterialPurchaseSpec spec = ownedSpec(factoryId, relationId, specId);
        if (request.getVersion() != null && !request.getVersion().equals(spec.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterialPurchaseSpec.class, specId);
        }
        apply(factoryId, ownedMaterial(factoryId, relation.getMaterialTypeId()), spec, request);
        enforceDefault(factoryId, relationId, spec);
        return toDto(repository.saveAndFlush(spec));
    }

    @Override @Transactional
    public SupplierMaterialPurchaseSpecDTO deactivate(String factoryId, String supplierId, String relationId,
                                                       String specId, Long version) {
        ownedRelation(factoryId, supplierId, relationId);
        SupplierMaterialPurchaseSpec spec = ownedSpec(factoryId, relationId, specId);
        if (version != null && !version.equals(spec.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterialPurchaseSpec.class, specId);
        }
        if (!Boolean.TRUE.equals(spec.getActive())) return toDto(spec);
        spec.setActive(false);
        spec.setDefaultSpec(false);
        return toDto(repository.save(spec));
    }

    private void apply(String factoryId, RawMaterialType material, SupplierMaterialPurchaseSpec spec,
                       SupplierMaterialPurchaseSpecRequest request) {
        String packageUnit = canonical(factoryId, request.getPurchasePackageUnit(),
                UnitUsageScope.PURCHASE_QUANTITY, "purchasePackageUnit");
        String inventoryUnit = canonical(factoryId, request.getInventoryBaseUnit(),
                UnitUsageScope.INVENTORY_QUANTITY, "inventoryBaseUnit");
        String materialUnit = canonical(factoryId, material.getUnit(),
                UnitUsageScope.INVENTORY_QUANTITY, "materialTypeId");
        if (!materialUnit.equals(inventoryUnit)) {
            throw new BusinessException(400, "库存基本单位必须与物料主数据单位一致")
                    .withCode("PURCHASE_SPEC_BASE_UNIT_MISMATCH").withHintTarget("inventoryBaseUnit");
        }
        spec.setName(request.getName().trim());
        spec.setPurchasePackageUnit(packageUnit);
        spec.setInventoryBaseUnit(inventoryUnit);
        if (request.getFactor() == null || request.getFactor().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "采购包装换算系数必须大于0")
                    .withCode("PURCHASE_SPEC_FACTOR_INVALID")
                    .withHintTarget("factor");
        }
        if (request.getQuotedPrice() != null
                && request.getQuotedPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "采购规格报价必须大于0")
                    .withCode("PURCHASE_SPEC_PRICE_INVALID")
                    .withHintTarget("quotedPrice");
        }
        spec.setConversionFactor(request.getFactor());
        spec.setQuotedPrice(request.getQuotedPrice());
        spec.setCurrency(request.getCurrency() == null ? "CNY" : request.getCurrency());
        spec.setMinOrderQuantity(request.getMinOrderQuantity());
        spec.setLeadTimeDays(request.getLeadTimeDays());
        if (request.getDefaultSpec() != null) spec.setDefaultSpec(request.getDefaultSpec());
        else if (spec.getDefaultSpec() == null) spec.setDefaultSpec(false);
        if (request.getActive() != null) spec.setActive(request.getActive());
        else if (spec.getActive() == null) spec.setActive(true);
        if (!Boolean.TRUE.equals(spec.getActive())) spec.setDefaultSpec(false);
    }

    private void enforceDefault(String factoryId, String relationId, SupplierMaterialPurchaseSpec target) {
        if (!Boolean.TRUE.equals(target.getDefaultSpec()) || !Boolean.TRUE.equals(target.getActive())) return;
        List<SupplierMaterialPurchaseSpec> active = repository
                .findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc(factoryId, relationId);
        active.stream().filter(x -> !java.util.Objects.equals(x.getId(), target.getId()))
                .filter(x -> Boolean.TRUE.equals(x.getDefaultSpec())).forEach(x -> x.setDefaultSpec(false));
        repository.saveAll(active);
    }

    private SupplierMaterial ownedRelation(String factoryId, String supplierId, String relationId) {
        SupplierMaterial relation = relationRepository.findByIdAndFactoryId(relationId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应关系不存在"));
        if (!supplierId.equals(relation.getSupplierId())) throw new BusinessException(403, "供应关系不属于该供应商");
        return relation;
    }
    private RawMaterialType ownedMaterial(String factoryId, String materialTypeId) {
        RawMaterialType material = materialRepository.findById(materialTypeId)
                .orElseThrow(() -> new BusinessException(404, "物料不存在"));
        if (!factoryId.equals(material.getFactoryId())) throw new BusinessException(403, "物料不属于当前工厂");
        return material;
    }
    private SupplierMaterialPurchaseSpec ownedSpec(String factoryId, String relationId, String specId) {
        SupplierMaterialPurchaseSpec spec = repository.findByIdAndFactoryId(specId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "采购包装规格不存在"));
        if (!relationId.equals(spec.getSupplierMaterialId())) throw new BusinessException(403, "采购包装规格不属于该供应关系");
        return spec;
    }
    private String canonical(String factoryId, String unit, UnitUsageScope usageScope, String hintTarget) {
        var normalized = unitContractService.normalize(factoryId, unit);
        if (!normalized.recognized()) throw new BusinessException(400, "未登记的计量单位: " + unit);
        if (!unitContractService.supportsUsage(factoryId, normalized.code(), usageScope)) {
            throw new BusinessException(400, "该计量单位不允许用于当前采购规格字段: " + normalized.code())
                    .withCode("PURCHASE_SPEC_UNIT_SCOPE_INVALID")
                    .withHintTarget(hintTarget);
        }
        return normalized.code();
    }
    private SupplierMaterialPurchaseSpecDTO toDto(SupplierMaterialPurchaseSpec s) {
        return SupplierMaterialPurchaseSpecDTO.builder().id(s.getId()).supplierMaterialId(s.getSupplierMaterialId())
                .materialTypeId(s.getMaterialTypeId()).name(s.getName()).purchasePackageUnit(s.getPurchasePackageUnit())
                .inventoryBaseUnit(s.getInventoryBaseUnit()).factor(s.getConversionFactor()).quotedPrice(s.getQuotedPrice())
                .quotedPriceUnit(s.getPurchasePackageUnit())
                .currency(s.getCurrency()).minOrderQuantity(s.getMinOrderQuantity()).leadTimeDays(s.getLeadTimeDays())
                .defaultSpec(s.getDefaultSpec()).active(s.getActive()).version(s.getVersion()).build();
    }
}
