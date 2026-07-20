package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SupplierMaterialServiceImpl implements SupplierMaterialService {
    private final SupplierMaterialRepository repository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository materialRepository;
    private final UnitContractService unitContractService;

    @Override @Transactional(readOnly = true)
    public List<SupplierMaterialDTO> listBySupplier(String factoryId, String supplierId) {
        ownedSupplier(factoryId, supplierId, false);
        return repository.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, supplierId)
                .stream().map(this::toDto).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<SupplierMaterialDTO> listByMaterial(String factoryId, String materialTypeId) {
        ownedMaterial(factoryId, materialTypeId);
        return repository.findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, materialTypeId)
                .stream().map(this::toDto).toList();
    }

    @Override @Transactional
    public SupplierMaterialDTO create(String factoryId, String supplierId, SupplierMaterialRequest request) {
        ownedSupplier(factoryId, supplierId, true);
        RawMaterialType material = ownedMaterial(factoryId, request.getMaterialTypeId());
        if (repository.findByFactoryIdAndSupplierIdAndMaterialTypeId(factoryId, supplierId, request.getMaterialTypeId()).isPresent()) {
            throw new BusinessException(409, "该供应商与物料的供应关系已存在").withHintTarget("materialTypeId");
        }
        SupplierMaterial relation = new SupplierMaterial();
        relation.setFactoryId(factoryId); relation.setSupplierId(supplierId);
        relation.setMaterialTypeId(material.getId()); relation.setActive(request.getActive() == null || request.getActive());
        apply(relation, request, material);
        enforcePreferred(factoryId, relation);
        return toDto(repository.saveAndFlush(relation));
    }

    @Override @Transactional
    public SupplierMaterialDTO update(String factoryId, String supplierId, String relationId, SupplierMaterialRequest request) {
        ownedSupplier(factoryId, supplierId, true);
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        if (request.getVersion() != null && !request.getVersion().equals(relation.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterial.class, relationId);
        }
        if (request.getMaterialTypeId() != null && !request.getMaterialTypeId().equals(relation.getMaterialTypeId())) {
            throw new BusinessException(400, "已建立的供应关系不能更换物料，请停用后新建").withHintTarget("materialTypeId");
        }
        RawMaterialType material = ownedMaterial(factoryId, relation.getMaterialTypeId());
        apply(relation, request, material);
        enforcePreferred(factoryId, relation);
        return toDto(repository.saveAndFlush(relation));
    }

    @Override @Transactional
    public SupplierMaterialDTO deactivate(String factoryId, String supplierId, String relationId, Long version) {
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        if (version != null && !version.equals(relation.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterial.class, relationId);
        }
        if (!Boolean.TRUE.equals(relation.getActive())) return toDto(relation);
        relation.setActive(false); relation.setPreferred(false);
        return toDto(repository.save(relation));
    }

    private void enforcePreferred(String factoryId, SupplierMaterial target) {
        if (!Boolean.TRUE.equals(target.getActive())) target.setPreferred(false);
        if (!Boolean.TRUE.equals(target.getPreferred())) return;
        List<SupplierMaterial> active = repository.findByFactoryIdAndMaterialTypeIdAndActiveTrue(factoryId, target.getMaterialTypeId());
        active.stream().filter(other -> !java.util.Objects.equals(other.getId(), target.getId()))
                .filter(other -> Boolean.TRUE.equals(other.getPreferred()))
                .forEach(other -> other.setPreferred(false));
        repository.saveAll(active);
    }

    private void apply(SupplierMaterial target, SupplierMaterialRequest request, RawMaterialType material) {
        if (request.getSupplierMaterialCode() != null) target.setSupplierMaterialCode(SupplierProfileValidator.trimToNull(request.getSupplierMaterialCode()));
        if (request.getDefaultPurchasePrice() != null) target.setDefaultPurchasePrice(request.getDefaultPurchasePrice());
        target.setCurrency(request.getCurrency() != null ? request.getCurrency() : target.getCurrency() != null ? target.getCurrency() : "CNY");
        String requestedUnit = request.getPurchaseUnit() != null
                ? request.getPurchaseUnit()
                : target.getPurchaseUnit() != null ? target.getPurchaseUnit() : material.getUnit();
        var normalizedUnit = unitContractService.normalize(material.getFactoryId(), requestedUnit);
        if (!normalizedUnit.recognized()) {
            throw new BusinessException(400, "未登记的采购计量单位: " + requestedUnit)
                    .withCode("SUPPLIER_MATERIAL_UNIT_UNRECOGNIZED")
                    .withHintTarget("purchaseUnit");
        }
        target.setPurchaseUnit(normalizedUnit.code());
        if (request.getMinOrderQuantity() != null) target.setMinOrderQuantity(request.getMinOrderQuantity());
        if (request.getLeadTimeDays() != null) target.setLeadTimeDays(request.getLeadTimeDays());
        if (request.getPreferred() != null) target.setPreferred(request.getPreferred());
        else if (target.getPreferred() == null) target.setPreferred(false);
        if (request.getActive() != null) target.setActive(request.getActive());
    }

    private Supplier ownedSupplier(String factoryId, String supplierId, boolean requireActive) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在或不属于当前工厂"));
        if (requireActive && !Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new BusinessException(409, "供应商已暂停合作，不能新增或修改供应关系");
        }
        return supplier;
    }
    private RawMaterialType ownedMaterial(String factoryId, String id) {
        RawMaterialType material = materialRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "物料不存在"));
        if (!factoryId.equals(material.getFactoryId())) throw new BusinessException(403, "物料不属于当前工厂");
        return material;
    }
    private SupplierMaterial ownedRelation(String factoryId, String supplierId, String id) {
        SupplierMaterial relation = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应关系不存在"));
        if (!supplierId.equals(relation.getSupplierId())) throw new BusinessException(403, "供应关系不属于该供应商");
        return relation;
    }
    private SupplierMaterialDTO toDto(SupplierMaterial relation) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(relation.getSupplierId(), relation.getFactoryId()).orElse(null);
        RawMaterialType material = materialRepository.findById(relation.getMaterialTypeId()).orElse(null);
        return SupplierMaterialDTO.builder().id(relation.getId()).factoryId(relation.getFactoryId())
                .supplierId(relation.getSupplierId()).supplierName(supplier == null ? null : supplier.getName())
                .materialTypeId(relation.getMaterialTypeId()).materialCode(material == null ? null : material.getCode())
                .materialName(material == null ? null : material.getName()).baseUnit(material == null ? null : material.getUnit())
                .supplierMaterialCode(relation.getSupplierMaterialCode()).defaultPurchasePrice(relation.getDefaultPurchasePrice())
                .currency(relation.getCurrency()).purchaseUnit(relation.getPurchaseUnit()).minOrderQuantity(relation.getMinOrderQuantity())
                .leadTimeDays(relation.getLeadTimeDays()).preferred(relation.getPreferred()).active(relation.getActive())
                .version(relation.getVersion()).build();
    }
}
