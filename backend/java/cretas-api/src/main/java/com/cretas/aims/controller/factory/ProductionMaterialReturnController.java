package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production-material-returns")
@RequiredArgsConstructor
@RequireModule("production_plan")
public class ProductionMaterialReturnController {

    private final ProductionMaterialReturnRepository repository;
    // 防呆 Rule 2 (fool-proof-design): 列表不该显裸 GUID — 批量解析物料名/批次号/需求单号.
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FactoryMaterialRequisitionRepository requisitionRepository;

    @GetMapping
    @RequirePermission({"warehouse:read_write", "production:read_write"})
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String requisitionId,
            // 防呆 Rule 2: 前端改按人类可读的需求单号查询, 而不是要求仓管员手填 UUID.
            @RequestParam(required = false) String requisitionNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String effectiveRequisitionId = requisitionId;
        if ((effectiveRequisitionId == null || effectiveRequisitionId.isBlank())
                && requisitionNo != null && !requisitionNo.isBlank()) {
            effectiveRequisitionId = requisitionRepository
                    .findByFactoryIdAndRequisitionNoAndDeletedAtIsNull(factoryId, requisitionNo.trim())
                    .map(FactoryMaterialRequisition::getId)
                    // 查不到号 → 传一个不存在的 id, 返回空页而不是"忽略过滤条件返回全部"(防呆: 让用户
                    // 明确看到"查无此单", 不是误以为查询没生效).
                    .orElse("__NOT_FOUND__");
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProductionMaterialReturn> result = (effectiveRequisitionId == null || effectiveRequisitionId.isBlank())
                ? repository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable)
                : repository.findByFactoryIdAndRequisitionIdAndDeletedAtIsNull(factoryId, effectiveRequisitionId, pageable);

        List<ProductionMaterialReturn> rows = result.getContent();

        Set<String> materialTypeIds = rows.stream()
                .map(ProductionMaterialReturn::getMaterialTypeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> batchIds = rows.stream()
                .map(ProductionMaterialReturn::getMaterialBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> requisitionIds = rows.stream()
                .map(ProductionMaterialReturn::getRequisitionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, String> materialNameMap = materialTypeRepository.findAllById(materialTypeIds).stream()
                .collect(Collectors.toMap(RawMaterialType::getId, RawMaterialType::getName));
        Map<String, String> batchNumberMap = materialBatchRepository.findAllById(batchIds).stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNumber));
        Map<String, String> requisitionNoMap = requisitionRepository.findAllById(requisitionIds).stream()
                .collect(Collectors.toMap(FactoryMaterialRequisition::getId, FactoryMaterialRequisition::getRequisitionNo));

        List<Map<String, Object>> enriched = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("requisitionId", r.getRequisitionId());
            m.put("requisitionNo", requisitionNoMap.getOrDefault(r.getRequisitionId(), r.getRequisitionId()));
            m.put("requisitionItemId", r.getRequisitionItemId());
            m.put("materialTypeId", r.getMaterialTypeId());
            m.put("materialName", materialNameMap.getOrDefault(r.getMaterialTypeId(), r.getMaterialTypeId()));
            m.put("materialBatchId", r.getMaterialBatchId());
            m.put("batchNumber", batchNumberMap.getOrDefault(r.getMaterialBatchId(), r.getMaterialBatchId()));
            m.put("returnQuantity", r.getReturnQuantity());
            m.put("returnStatus", r.getReturnStatus());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", enriched);
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("number", result.getNumber());
        data.put("size", result.getSize());

        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
