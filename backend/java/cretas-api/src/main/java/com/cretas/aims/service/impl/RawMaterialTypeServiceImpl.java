package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.dto.materialtype.MaterialTypeExportDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 原材料类型服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class RawMaterialTypeServiceImpl implements RawMaterialTypeService {
    private static final Logger log = LoggerFactory.getLogger(RawMaterialTypeServiceImpl.class);

    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ConversionRepository conversionRepository;
    private final MaterialPackagingHierarchyRepository packagingRepository;  // C-6: enrich getById with packaging
    private final MaterialCodeSegmentRepository materialCodeSegmentRepository; // SP8: 16位分段字典
    private final ExcelUtil excelUtil;
    private final WorkflowUnitReviewService workflowUnitReviewService;

    @PersistenceContext
    private EntityManager entityManager;

    // ========== 系统默认原材料类型数据 ==========
    private static final List<Map<String, String>> DEFAULT_MATERIAL_TYPES = new ArrayList<>();

    static {
        // 海水鱼类
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("带鱼", "DY", "海水鱼", "kg", "冷冻"));
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("黄花鱼", "HHY", "海水鱼", "kg", "冷冻"));
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("鲳鱼", "CY", "海水鱼", "kg", "冷冻"));
        // 淡水鱼类
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("鲈鱼", "LY", "淡水鱼", "kg", "冷藏"));
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("草鱼", "CYU", "淡水鱼", "kg", "冷藏"));
        // 虾类
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("对虾", "DX", "虾类", "kg", "冷冻"));
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("基围虾", "JWX", "虾类", "kg", "冷藏"));
        // 贝类
        DEFAULT_MATERIAL_TYPES.add(createDefaultMaterial("扇贝", "SB", "贝类", "kg", "冷藏"));
    }

    private static Map<String, String> createDefaultMaterial(String name, String code, String category, String unit, String storageType) {
        Map<String, String> material = new HashMap<>();
        material.put("name", name);
        material.put("code", code);
        material.put("category", category);
        material.put("unit", unit);
        material.put("storageType", storageType);
        return material;
    }

    @Override
    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public RawMaterialTypeDTO createMaterialType(String factoryId, RawMaterialTypeDTO dto) {
        // T159-B-codegen: auto-generate code when caller does not provide one.
        // SP8: if segmentCode is provided (10-digit), use 16-digit generator; else fallback SP4 flat.
        if (dto.getCode() == null || dto.getCode().trim().isEmpty()) {
            String generated = generateNextCode(factoryId, dto.getCategory(), dto.getSegmentCode());
            dto.setCode(generated);
            log.info("自动生成原材料编码: factoryId={}, segmentCode={}, code={}", factoryId, dto.getSegmentCode(), generated);
        } else if (isSegmentDictionaryEnabled(factoryId) && !dto.getCode().trim().matches("[0-9]{16}")) {
            throw strict16CodeException(dto.getCode());
        }

        log.info("创建原材料类型: factoryId={}, code={}", factoryId, dto.getCode());

        // 检查编码是否已存在 (handles collision on manually-supplied codes)
        if (materialTypeRepository.existsByFactoryIdAndCode(factoryId, dto.getCode())) {
            throw new BusinessException(409, "原材料编码已存在: " + dto.getCode())
                    .withHint("请使用其他原材料编码").withHintTarget("code");
        }

        RawMaterialType materialType = new RawMaterialType();
        // 生成唯一ID：如果传入了ID则使用传入的，否则自动生成
        if (dto.getId() != null && !dto.getId().isEmpty()) {
            materialType.setId(dto.getId());
        } else {
            // 使用编码作为ID前缀，加上时间戳确保唯一性
            String generatedId = "RMT_" + System.currentTimeMillis();
            materialType.setId(generatedId);
        }
        materialType.setFactoryId(factoryId);
        materialType.setCode(dto.getCode());
        materialType.setName(dto.getName());
        materialType.setCategory(dto.getCategory());
        materialType.setUnit(dto.getUnit());
        materialType.setStorageType(dto.getStorageType());
        materialType.setShelfLifeDays(dto.getShelfLifeDays());
        materialType.setMinStock(dto.getMinStock());
        materialType.setMaxStock(dto.getMaxStock());
        materialType.setNotes(dto.getNotes());
        materialType.setIsActive(true);
        // 使用从Controller传入的createdBy，如果为null则使用默认值1
        materialType.setCreatedBy(dto.getCreatedBy() != null ? dto.getCreatedBy() : 1L);
        materialType.setCreatedAt(LocalDateTime.now());
        materialType.setUpdatedAt(LocalDateTime.now());

        // SP4-A8: 税率 + 含税单价 → 自动换算未税单价
        materialType.setTaxRate(dto.getTaxRate());
        materialType.setTaxIncludedUnitPrice(dto.getTaxIncludedUnitPrice());
        if (dto.getTaxRate() != null && dto.getTaxIncludedUnitPrice() != null) {
            materialType.setUnitPrice(dto.getTaxRate().preTaxPrice(dto.getTaxIncludedUnitPrice()));
        }

        // SP8: primaryCode — 优先 DTO 传入, 否则从 code 前三位自动提取
        if (dto.getPrimaryCode() != null && !dto.getPrimaryCode().isBlank()) {
            materialType.setPrimaryCode(dto.getPrimaryCode());
        } else if (materialType.getCode() != null && materialType.getCode().length() >= 3) {
            materialType.setPrimaryCode(materialType.getCode().substring(0, 3));
        }

        // 包材规格: 随 create 写入 (nullable, 仅 category=PACKAGING 有业务意义)
        materialType.setPackQtyPerProduct(dto.getPackQtyPerProduct());
        // P8: 包材关联客户 (nullable, 非包材物料传 null 即留空)
        materialType.setAssociatedCustomerId(dto.getAssociatedCustomerId());

        materialType = materialTypeRepository.save(materialType);

        log.info("原材料类型创建成功: id={}", materialType.getId());
        return convertToDTO(materialType);
    }

    @Override
    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public RawMaterialTypeDTO updateMaterialType(String factoryId, String id, RawMaterialTypeDTO dto) {
        log.info("更新原材料类型: factoryId={}, id={}", factoryId, id);

        RawMaterialType materialType = materialTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在: " + id));

        String previousUnit = materialType.getUnit();

        if (!materialType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限操作此原材料类型")
                    .withHint("当前原材料类型不属于该工厂, 无法操作");
        }

        // 检查编码是否重复
        if (dto.getCode() != null && !dto.getCode().equals(materialType.getCode())) {
            if (materialTypeRepository.existsByFactoryIdAndCode(factoryId, dto.getCode())) {
                throw new BusinessException(409, "原材料编码已存在: " + dto.getCode())
                    .withHint("请使用其他原材料编码").withHintTarget("code");
            }
            materialType.setCode(dto.getCode());
        }

        // 更新其他字段
        if (dto.getName() != null) materialType.setName(dto.getName());
        if (dto.getCategory() != null) materialType.setCategory(dto.getCategory());
        if (dto.getUnit() != null) materialType.setUnit(dto.getUnit());
        if (dto.getStorageType() != null) materialType.setStorageType(dto.getStorageType());
        if (dto.getShelfLifeDays() != null) materialType.setShelfLifeDays(dto.getShelfLifeDays());
        if (dto.getMinStock() != null) materialType.setMinStock(dto.getMinStock());
        if (dto.getMaxStock() != null) materialType.setMaxStock(dto.getMaxStock());
        if (dto.getNotes() != null) materialType.setNotes(dto.getNotes());
        if (dto.getIsActive() != null) materialType.setIsActive(dto.getIsActive());

        // SP4-A8: 税率 + 含税单价 null-guard 更新 → 自动换算未税单价
        if (dto.getTaxRate() != null) materialType.setTaxRate(dto.getTaxRate());
        if (dto.getTaxIncludedUnitPrice() != null) materialType.setTaxIncludedUnitPrice(dto.getTaxIncludedUnitPrice());
        // 仅当 taxRate + taxIncludedUnitPrice 都已配置 (含本次更新后的值) 才换算
        if (materialType.getTaxRate() != null && materialType.getTaxIncludedUnitPrice() != null) {
            materialType.setUnitPrice(materialType.getTaxRate().preTaxPrice(materialType.getTaxIncludedUnitPrice()));
        }

        // SP8: primaryCode null-guard 更新
        if (dto.getPrimaryCode() != null) {
            materialType.setPrimaryCode(dto.getPrimaryCode());
        }

        // 包材规格: null-guard 更新 (传 null 视为"不修改"; 若要清除规格前端传 0 由服务端忽略负值即可)
        // 设计选择: 使用 packQtyPerProduct != null 作为"有意更新"信号, 与 SP8 primaryCode 模式一致
        if (dto.getPackQtyPerProduct() != null) {
            materialType.setPackQtyPerProduct(dto.getPackQtyPerProduct());
        }
        // P8: 包材关联客户 — 允许显式传 null 来解除关联; 传 undefined/不传则保持原值
        // 约定: 前端编辑包材时明确传 associatedCustomerId (null = 解除, "" = 视为 null)
        if (dto.getAssociatedCustomerId() != null && dto.getAssociatedCustomerId().isBlank()) {
            materialType.setAssociatedCustomerId(null);
        } else {
            materialType.setAssociatedCustomerId(dto.getAssociatedCustomerId());
        }

        materialType.setUpdatedAt(LocalDateTime.now());
        materialType = materialTypeRepository.save(materialType);

        if (!Objects.equals(previousUnit, materialType.getUnit())) {
            workflowUnitReviewService.markPublishedWorkflowsForReview(factoryId);
        }

        log.info("原材料类型更新成功: id={}", materialType.getId());
        return convertToDTO(materialType);
    }

    @Override
    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public void deleteMaterialType(String factoryId, String id) {
        log.info("删除原材料类型: factoryId={}, id={}", factoryId, id);

        RawMaterialType materialType = materialTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在: " + id));

        if (!materialType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限操作此原材料类型")
                    .withHint("当前原材料类型不属于该工厂, 无法操作");
        }

        // 检查是否有关联的批次（使用原生SQL查询，避免触发枚举转换错误）
        try {
            // 使用原生SQL查询count，避免加载实体时触发枚举问题
            Long batchCount = ((Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM material_batches WHERE factory_id = ? AND material_type_id = ?")
                    .setParameter(1, factoryId)
                    .setParameter(2, id)
                    .getSingleResult()).longValue();
            
            if (batchCount > 0) {
                log.warn("原材料类型有关联的批次，无法删除: id={}, batchCount={}", id, batchCount);
                throw new BusinessException(409, "原材料类型有关联的批次（" + batchCount + "个），无法删除")
                        .withHint("请先删除或转移相关批次后再删除该原材料类型");
            }
        } catch (BusinessException e) {
            // 如果是业务异常（有关联数据），直接抛出
            throw e;
        } catch (Exception e) {
            // 其他异常（如SQL问题）记录日志但不阻止删除
            log.warn("检查关联批次时出错: {}", e.getMessage());
        }

        // 检查是否有关联的转换率（使用原生SQL查询，避免加载关联实体）
        try {
            // 使用原生SQL查询count，避免加载实体
            Long conversionCount = ((Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM material_product_conversions WHERE factory_id = ? AND material_type_id = ?")
                    .setParameter(1, factoryId)
                    .setParameter(2, id)
                    .getSingleResult()).longValue();
            
            if (conversionCount > 0) {
                log.warn("原材料类型有关联的转换率，无法删除: id={}, conversionCount={}", id, conversionCount);
                throw new BusinessException(409, "原材料类型有关联的转换率（" + conversionCount + "个），无法删除")
                        .withHint("请先删除相关转换率后再删除该原材料类型");
            }
        } catch (BusinessException e) {
            // 如果是业务异常（有关联数据），直接抛出
            throw e;
        } catch (Exception e) {
            log.warn("检查关联转换率时出错: {}", e.getMessage());
        }

        try {
            materialTypeRepository.delete(materialType);
            log.info("原材料类型删除成功: id={}", id);
        } catch (Exception e) {
            log.error("删除原材料类型失败: id={}, error={}", id, e.getMessage(), e);
            if (e.getMessage() != null && e.getMessage().contains("foreign key constraint")) {
                throw new BusinessException(409, "原材料类型有关联数据，无法删除")
                        .withHint("请先删除相关批次或转换率后再删除该原材料类型");
            }
            throw new BusinessException(500, "删除失败: " + e.getMessage())
                    .withHint("请稍后重试, 如果问题持续请联系管理员");
        }
    }

    @Override
    public RawMaterialTypeDTO getMaterialTypeById(String factoryId, String id) {
        log.info("获取原材料类型详情: factoryId={}, id={}", factoryId, id);

        RawMaterialType materialType = materialTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在: " + id));

        if (!materialType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限查看此原材料类型")
                    .withHint("当前原材料类型不属于该工厂, 无法查看");
        }

        RawMaterialTypeDTO dto = convertToDTO(materialType);

        // C-6 Canvas Reactive Default (2026-05-09): enrich with packaging hierarchy fields
        // for the by-id endpoint only (frontend ReferenceSelector projectFields fetchById path).
        // List endpoints intentionally don't enrich (avoids N+1 query on /raw-material-types).
        // Returns null when no packaging configured — frontend boxQuantity computed expr
        // does null-guard via `_level1PerLevel2 != null && _level1PerLevel2 > 0 ? ... : null`.
        packagingRepository.findByMaterialTypeId(id).ifPresent(pkg -> {
            dto.setLevel1PerLevel2(pkg.getLevel1PerLevel2());
            dto.setLevel2Unit(pkg.getLevel2Unit());
        });

        return dto;
    }

    @Override
    public PageResponse<RawMaterialTypeDTO> getMaterialTypes(String factoryId, PageRequest pageRequest) {
        log.info("获取原材料类型列表: factoryId={}, page={}, size={}",
                factoryId, pageRequest.getPage(), pageRequest.getSize());

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<RawMaterialType> page = materialTypeRepository.findByFactoryId(factoryId, pageable);

        List<RawMaterialTypeDTO> dtos = page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return PageResponse.of(
                dtos,
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    // ==================== P11: 按物料大类分页查询 ====================

    @Override
    public PageResponse<RawMaterialTypeDTO> getMaterialTypesByKind(
            String factoryId, String materialKind, PageRequest pageRequest) {

        if (materialKind == null || materialKind.isBlank()) {
            // 退化到全量查询
            return getMaterialTypes(factoryId, pageRequest);
        }

        log.info("[P11] getMaterialTypesByKind: factoryId={}, kind={}, page={}, size={}",
                factoryId, materialKind, pageRequest.getPage(), pageRequest.getSize());

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        // category 字段是自由文本大类标签: 原料/辅料/包材.
        // 使用大小写不敏感匹配, 与 getLedgerByKind 保持一致.
        Page<RawMaterialType> page = materialTypeRepository.findByFactoryIdAndCategoryIgnoreCase(
                factoryId, materialKind, pageable);

        List<RawMaterialTypeDTO> dtos = page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return PageResponse.of(dtos, pageRequest.getPage(), pageRequest.getSize(), page.getTotalElements());
    }

    // ==================== End P11 ====================

    @Override
    @Cacheable(value = "materialTypes", key = "#factoryId")
    public List<RawMaterialTypeDTO> getActiveMaterialTypes(String factoryId) {
        log.info("获取激活的原材料类型: factoryId={}", factoryId);

        List<RawMaterialType> materialTypes = materialTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        return materialTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<RawMaterialTypeDTO> getMaterialTypesByPrimaryCode(String factoryId, String primaryCode) {
        String normalized = primaryCode == null ? null : primaryCode.trim();
        if (normalized == null || !normalized.matches("[0-9]{3}")) {
            throw new BusinessException(400, "主编码必须是3位数字: primaryCode=" + primaryCode)
                    .withHint("请在BOM选料器中选择001/002/003等3位主编码分组")
                    .withHintTarget("primaryCode")
                    .withSeverity("BLOCKING");
        }
        return materialTypeRepository.findByFactoryIdAndPrimaryCodeOrderByCodeAsc(factoryId, normalized).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<RawMaterialTypeDTO> getMaterialTypesByCategory(String factoryId, String category) {
        log.info("根据类别获取原材料类型: factoryId={}, category={}", factoryId, category);

        List<RawMaterialType> materialTypes = materialTypeRepository.findByFactoryIdAndCategory(factoryId, category);
        return materialTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<RawMaterialTypeDTO> getMaterialTypesByStorageType(String factoryId, String storageType) {
        log.info("根据存储类型获取原材料类型: factoryId={}, storageType={}", factoryId, storageType);

        List<RawMaterialType> materialTypes = materialTypeRepository.findByFactoryIdAndStorageType(factoryId, storageType);
        return materialTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PageResponse<RawMaterialTypeDTO> searchMaterialTypes(String factoryId, String keyword, PageRequest pageRequest) {
        log.info("搜索原材料类型: factoryId={}, keyword={}", factoryId, keyword);

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<RawMaterialType> page = materialTypeRepository.searchMaterialTypes(factoryId,
            com.cretas.aims.util.SqlLikeEscaper.escape(keyword), pageable);

        List<RawMaterialTypeDTO> dtos = page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        return PageResponse.of(
                dtos,
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public List<String> getMaterialCategories(String factoryId) {
        log.info("获取原材料类别列表: factoryId={}", factoryId);

        return materialTypeRepository.findByFactoryId(factoryId).stream()
                .map(RawMaterialType::getCategory)
                .filter(category -> category != null && !category.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public List<RawMaterialTypeDTO> getLowStockMaterials(String factoryId) {
        log.info("获取库存预警的原材料: factoryId={}", factoryId);

        List<RawMaterialType> materialTypes = materialTypeRepository.findMaterialTypesWithStockWarning(factoryId);
        return materialTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CacheEvict(value = "materialTypes", key = "#factoryId")
    public void updateMaterialTypesStatus(String factoryId, List<String> ids, Boolean isActive) {
        log.info("批量更新原材料类型状态: factoryId={}, ids={}, isActive={}", factoryId, ids, isActive);

        for (String id : ids) {
            RawMaterialType materialType = materialTypeRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在: " + id));

            if (!materialType.getFactoryId().equals(factoryId)) {
                throw new BusinessException(403, "无权限操作原材料类型: " + id)
                        .withHint("批量操作中包含其他工厂的原材料类型, 请重新选择");
            }

            materialType.setIsActive(isActive);
            materialType.setUpdatedAt(LocalDateTime.now());
            materialTypeRepository.save(materialType);
        }

        log.info("批量更新原材料类型状态成功: count={}", ids.size());
    }

    @Override
    public boolean checkCodeExists(String factoryId, String code, String excludeId) {
        log.info("检查原材料编码是否存在: factoryId={}, code={}, excludeId={}", factoryId, code, excludeId);

        if (excludeId != null) {
            RawMaterialType existing = materialTypeRepository.findByFactoryIdAndCode(factoryId, code).orElse(null);
            return existing != null && !existing.getId().equals(excludeId);
        }

        return materialTypeRepository.existsByFactoryIdAndCode(factoryId, code);
    }

    /**
     * 转换实体到DTO
     */
    private RawMaterialTypeDTO convertToDTO(RawMaterialType materialType) {
        return RawMaterialTypeDTO.builder()
                .id(materialType.getId())
                .factoryId(materialType.getFactoryId())
                .code(materialType.getCode())
                .name(materialType.getName())
                .category(materialType.getCategory())
                .unit(materialType.getUnit())
                .storageType(materialType.getStorageType())
                .shelfLifeDays(materialType.getShelfLifeDays())
                .minStock(materialType.getMinStock())
                .maxStock(materialType.getMaxStock())
                .isActive(materialType.getIsActive())
                .notes(materialType.getNotes())
                .createdBy(materialType.getCreatedBy())
                .createdAt(materialType.getCreatedAt())
                .updatedAt(materialType.getUpdatedAt())
                .movingAvgPrice(materialType.getMovingAvgPrice())
                // SP4-A8: 税率 + 含税单价
                .taxRate(materialType.getTaxRate())
                .taxIncludedUnitPrice(materialType.getTaxIncludedUnitPrice())
                // SP8: 前三位主编码
                .primaryCode(materialType.getPrimaryCode())
                // 包材规格
                .packQtyPerProduct(materialType.getPackQtyPerProduct())
                // P8: 包材关联客户 (id 直接映射; 名称留 null — 单点查询 getMaterialTypeById 可 JOIN 填充)
                .associatedCustomerId(materialType.getAssociatedCustomerId())
                .build();
    }

    // ========== 导出导入功能 ==========

    @Override
    public byte[] exportMaterialTypes(String factoryId) {
        log.info("导出原材料类型列表: factoryId={}", factoryId);

        List<RawMaterialType> materialTypes = materialTypeRepository.findByFactoryId(factoryId);

        List<MaterialTypeExportDTO> exportDTOs = materialTypes.stream()
                .map(MaterialTypeExportDTO::fromRawMaterialType)
                .collect(Collectors.toList());

        return excelUtil.exportToExcel(exportDTOs, MaterialTypeExportDTO.class, "原材料类型列表");
    }

    @Override
    public byte[] generateImportTemplate() {
        log.info("生成原材料类型导入模板");
        return excelUtil.generateTemplate(MaterialTypeExportDTO.class, "原材料类型导入模板");
    }

    @Override
    public ImportResult<RawMaterialType> importMaterialTypesFromExcel(String factoryId, InputStream inputStream) {
        log.info("开始从Excel批量导入原材料类型: factoryId={}", factoryId);

        // 1. 解析Excel文件
        List<MaterialTypeExportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream, MaterialTypeExportDTO.class);
        } catch (Exception e) {
            log.error("Excel文件解析失败: factoryId={}", factoryId, e);
            throw new RuntimeException("Excel文件格式错误或无法解析: " + e.getMessage());
        }

        ImportResult<RawMaterialType> result = ImportResult.create(excelData.size());

        // 2. 逐行验证并导入
        for (int i = 0; i < excelData.size(); i++) {
            MaterialTypeExportDTO exportDTO = excelData.get(i);
            int rowNumber = i + 2; // Excel行号（从2开始，1是表头）

            try {
                // 2.1 验证必填字段
                if (exportDTO.getName() == null || exportDTO.getName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "原材料名称不能为空", toJsonString(exportDTO));
                    continue;
                }

                // 2.2 验证编码唯一性（如果提供了编码）
                if (exportDTO.getMaterialCode() != null && !exportDTO.getMaterialCode().trim().isEmpty()) {
                    if (materialTypeRepository.existsByFactoryIdAndCode(factoryId, exportDTO.getMaterialCode())) {
                        result.addFailure(rowNumber, "原材料编码已存在: " + exportDTO.getMaterialCode(),
                                toJsonString(exportDTO));
                        continue;
                    }
                }

                // 2.3 转换为Entity
                RawMaterialType materialType = convertFromExportDTO(exportDTO, factoryId);

                // 2.4 保存
                RawMaterialType saved = materialTypeRepository.save(materialType);

                // 2.5 记录成功
                result.addSuccess(saved);

                log.debug("成功导入原材料类型: row={}, name={}", rowNumber, exportDTO.getName());

            } catch (Exception e) {
                log.error("导入原材料类型失败: factoryId={}, row={}, data={}", factoryId, rowNumber, exportDTO, e);
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJsonString(exportDTO));
            }
        }

        log.info("原材料类型批量导入完成: factoryId={}, total={}, success={}, failure={}",
                factoryId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    @Override
    @Transactional
    public int initializeDefaults(String factoryId) {
        log.info("初始化默认原材料类型: factoryId={}", factoryId);
        int count = 0;

        for (Map<String, String> defaultMaterial : DEFAULT_MATERIAL_TYPES) {
            String code = defaultMaterial.get("code");

            // 检查是否已存在
            if (!materialTypeRepository.existsByFactoryIdAndCode(factoryId, code)) {
                RawMaterialType materialType = new RawMaterialType();
                materialType.setFactoryId(factoryId);
                materialType.setCode(code);
                materialType.setName(defaultMaterial.get("name"));
                materialType.setCategory(defaultMaterial.get("category"));
                materialType.setUnit(defaultMaterial.get("unit"));
                materialType.setStorageType(defaultMaterial.get("storageType"));
                materialType.setIsActive(true);
                materialType.setCreatedBy(1L);
                materialType.setCreatedAt(LocalDateTime.now());
                materialType.setUpdatedAt(LocalDateTime.now());

                materialTypeRepository.save(materialType);
                count++;
            }
        }

        log.info("初始化默认原材料类型完成: factoryId={}, count={}", factoryId, count);
        return count;
    }

    @Override
    public long countMaterialTypes(String factoryId, Boolean isActive) {
        if (isActive != null) {
            return materialTypeRepository.countByFactoryIdAndIsActive(factoryId, isActive);
        } else {
            return materialTypeRepository.countByFactoryId(factoryId);
        }
    }

    @Override
    public String suggestUnit(String factoryId, String name, String category) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String keyword = name.trim();
        String categoryFilter = (category != null && !category.trim().isEmpty()) ? category.trim() : null;
        // 全限定 Spring PageRequest 避免跟 com.cretas.aims.dto.common.PageRequest 二义
        Pageable top1 = org.springframework.data.domain.PageRequest.of(0, 1);

        List<RawMaterialType> matches = materialTypeRepository.findSimilarByNameAndCategory(
                factoryId, keyword, categoryFilter, top1);
        if (!matches.isEmpty()) {
            return matches.get(0).getUnit();
        }

        // 退化: 全名没匹配时取首字符再试一次 (e.g. 输入"三文鱼柳"时找"三文鱼")
        if (keyword.length() >= 2) {
            matches = materialTypeRepository.findSimilarByNameAndCategory(
                    factoryId, keyword.substring(0, Math.min(2, keyword.length())), categoryFilter, top1);
            if (!matches.isEmpty()) {
                return matches.get(0).getUnit();
            }
        }
        return null;
    }

    // ========== T159-B-codegen: 编码生成 + 多字段建议 ==========

    /**
     * 根据 category 决定编码前缀.
     * <ul>
     *   <li>原料 → YL</li>
     *   <li>肉类 → RL</li>
     *   <li>包材 → BC</li>
     *   <li>其他/null → WL</li>
     * </ul>
     */
    static String getMaterialCategoryPrefix(String category) {
        if (category == null || category.trim().isEmpty()) {
            return "WL";
        }
        switch (category.trim()) {
            case "原料": return "YL";
            case "肉类": return "RL";
            case "包材": return "BC";
            default:   return "WL";
        }
    }

    // ========== SP4-T4: 一物一码标签数字前缀 (2位) ==========

    /** 一物一码标签编码数字前缀映射表 (category → 2-digit numeric code). */
    static final java.util.Map<String, String> NUMERIC_PREFIX_MAP =
            java.util.Map.of(
                    "肉类",   "01",
                    "禽类",   "02",
                    "水产类", "03",
                    "海水鱼", "03",
                    "淡水鱼", "03",
                    "虾类",   "03",
                    "贝类",   "03",
                    "调料",   "04",
                    "包材",   "05",
                    "原料",   "06"
            );

    /**
     * 根据物料类别返回一物一码标签编码的2位数字前缀 (SP4-T4).
     *
     * <ul>
     *   <li>肉类   → "01"</li>
     *   <li>禽类   → "02"</li>
     *   <li>水产类/海水鱼/淡水鱼/虾类/贝类 → "03"</li>
     *   <li>调料   → "04"</li>
     *   <li>包材   → "05"</li>
     *   <li>原料   → "06"</li>
     *   <li>null/空/未知 → "99"</li>
     * </ul>
     *
     * <p>与 {@link #getMaterialCategoryPrefix(String)} 完全独立 — 后者产出字母编码 (YL/RL/BC/WL)
     * 供物料编号自增序列使用; 本方法产出数字编码供一物一码标签生成使用。
     *
     * @param category 物料类别字符串 (允许 null)
     * @return 2位数字字符串, 未知类别返回 "99"
     * @since SP4 V20261002_05
     */
    static String getNumericPrefix(String category) {
        if (category == null || category.trim().isEmpty()) {
            return "99";
        }
        return NUMERIC_PREFIX_MAP.getOrDefault(category.trim(), "99");
    }

    /**
     * SP4 扁平方案: 扫描工厂内该前缀的所有编码, 取最大数字后缀 +1, 零填充3位.
     * 无同前缀编码时从 001 开始.
     * 结果编码不做唯一性检查 — 调用方在写库前做 existsByFactoryIdAndCode 兜底.
     */
    String generateNextCode(String factoryId, String category) {
        String prefix = getMaterialCategoryPrefix(category);
        List<String> existingCodes = materialTypeRepository.findCodesByFactoryIdAndCodePrefix(factoryId, prefix);
        int maxSeq = 0;
        int prefixLen = prefix.length();
        for (String code : existingCodes) {
            if (code.length() > prefixLen) {
                String suffix = code.substring(prefixLen);
                try {
                    int seq = Integer.parseInt(suffix);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (NumberFormatException ignored) {
                    // non-numeric suffix — skip
                }
            }
        }
        return String.format("%s%03d", prefix, maxSeq + 1);
    }

    /**
     * SP8: 16位分段编码生成器.
     * 当工厂已配置分段字典 AND segmentCode 为10位数字串时, 走16位路径:
     *   前10位 = segmentCode (L3 cumulative code), 后6位 = 同前缀最大序号+1 零填充.
     * 否则 fallback 到 SP4 扁平方案.
     *
     * @param factoryId   工厂ID
     * @param category    物料类别 (fallback 路径用)
     * @param segmentCode 前端级联选择的 L3 cumulative segment code (10位纯数字), 可为 null
     * @return 生成的物料编码
     */
    String generateNextCode(String factoryId, String category, String segmentCode) {
        boolean dictionaryEnabled = isSegmentDictionaryEnabled(factoryId);
        if (dictionaryEnabled) {
            if (segmentCode == null || !segmentCode.matches("[0-9]{10}")) {
                throw strict16CodeException(segmentCode);
            }
            List<String> existing = materialTypeRepository
                    .findCodesByFactoryIdAndSegmentPrefix(factoryId, segmentCode);
            int maxSeq = 0;
            for (String code : existing) {
                if (code.length() == 16) {
                    String seqPart = code.substring(10); // last 6 digits
                    try {
                        int seq = Integer.parseInt(seqPart);
                        if (seq > maxSeq) maxSeq = seq;
                    } catch (NumberFormatException ignored) {
                        // skip non-numeric suffix
                    }
                }
            }
            return String.format("%s%06d", segmentCode, maxSeq + 1);
        }
        // Fallback: SP4 扁平方案
        return generateNextCode(factoryId, category);
    }

    private boolean isSegmentDictionaryEnabled(String factoryId) {
        return materialCodeSegmentRepository.countByFactoryIdAndLevel(factoryId, (short) 1) > 0;
    }

    private BusinessException strict16CodeException(String codeOrSegment) {
        return new BusinessException(400,
                "本工厂启用 16 位编码，请用分段选择器生成。当前编码/分段值无效: " + codeOrSegment)
                .withHint("请先选择L1类型、L2部位、L3品类，再点击生成16位编码")
                .withHintTarget("segmentCode")
                .withSeverity("BLOCKING");
    }

    @Override
    public String previewMaterialCode(String factoryId, String category) {
        return generateNextCode(factoryId, category);
    }

    @Override
    public String previewMaterialCode(String factoryId, String category, String segmentCode) {
        return generateNextCode(factoryId, category, segmentCode);
    }

    @Override
    public MaterialSuggestDTO suggestFields(String factoryId, String name, String category) {
        if (name == null || name.trim().isEmpty()) {
            return MaterialSuggestDTO.builder().build();
        }
        String keyword = name.trim();
        String categoryFilter = (category != null && !category.trim().isEmpty()) ? category.trim() : null;
        Pageable top1 = org.springframework.data.domain.PageRequest.of(0, 1);

        List<RawMaterialType> matches = materialTypeRepository.findSimilarByNameAndCategory(
                factoryId, keyword, categoryFilter, top1);

        // 退化: 取前2字再匹配
        if (matches.isEmpty() && keyword.length() >= 2) {
            matches = materialTypeRepository.findSimilarByNameAndCategory(
                    factoryId, keyword.substring(0, Math.min(2, keyword.length())), categoryFilter, top1);
        }

        if (matches.isEmpty()) {
            return MaterialSuggestDTO.builder().build();
        }

        RawMaterialType match = matches.get(0);
        MaterialSuggestDTO.MaterialSuggestDTOBuilder builder = MaterialSuggestDTO.builder()
                .unit(match.getUnit())
                .category(match.getCategory())
                .storageType(match.getStorageType())
                .shelfLifeDays(match.getShelfLifeDays());

        // Enrich packaging hierarchy fields (same pattern as getMaterialTypeById)
        packagingRepository.findByMaterialTypeId(match.getId()).ifPresent(pkg -> {
            builder.level1PerLevel2(pkg.getLevel1PerLevel2());
            builder.level2Unit(pkg.getLevel2Unit());
        });

        return builder.build();
    }

    @Override
    public List<RawMaterialTypeDTO> searchByCodePrefix(String factoryId, String codePrefix) {
        if (codePrefix == null || codePrefix.isBlank()) {
            return Collections.emptyList();
        }
        Pageable top50 = org.springframework.data.domain.PageRequest.of(0, 50);
        List<RawMaterialType> types = materialTypeRepository
                .findByFactoryIdAndCodeStartingWith(factoryId, codePrefix.trim(), top50);
        return types.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    // ==================== R14: 研发试样价格区间选料 ====================

    @Override
    public List<RawMaterialTypeDTO> suggestMaterialsByPriceRange(
            String factoryId, java.math.BigDecimal minPrice, java.math.BigDecimal maxPrice) {

        log.info("[R14] suggestMaterialsByPriceRange: factoryId={} min={} max={}",
                factoryId, minPrice, maxPrice);

        Pageable top100 = org.springframework.data.domain.PageRequest.of(0, 100);
        List<RawMaterialType> candidates = materialTypeRepository
                .findByPriceRange(factoryId, minPrice, maxPrice, top100);

        return candidates.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    // ==================== End R14 ====================

    /**
     * 从MaterialTypeExportDTO转换为RawMaterialType实体
     */
    private RawMaterialType convertFromExportDTO(MaterialTypeExportDTO dto, String factoryId) {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setFactoryId(factoryId);
        materialType.setCode(dto.getMaterialCode());
        materialType.setName(dto.getName());
        materialType.setCategory(dto.getCategory());
        materialType.setUnit(dto.getUnit());
        materialType.setStorageType(dto.getStorageType());
        materialType.setNotes(dto.getDescription());
        materialType.setIsActive("启用".equals(dto.getStatus()));
        materialType.setCreatedBy(1L);
        materialType.setCreatedAt(LocalDateTime.now());
        materialType.setUpdatedAt(LocalDateTime.now());
        return materialType;
    }

    /**
     * 将对象转换为JSON字符串
     */
    private String toJsonString(Object obj) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
