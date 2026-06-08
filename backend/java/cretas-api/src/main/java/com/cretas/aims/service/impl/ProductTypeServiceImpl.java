package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 产品类型服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class ProductTypeServiceImpl implements ProductTypeService {

    private static final Logger log = LoggerFactory.getLogger(ProductTypeServiceImpl.class);

    private final ProductTypeRepository productTypeRepository;
    private final ObjectMapper objectMapper;
    private final CustomerRepository customerRepository;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public ProductTypeServiceImpl(ProductTypeRepository productTypeRepository, ObjectMapper objectMapper,
                                  CustomerRepository customerRepository) {
        this.productTypeRepository = productTypeRepository;
        this.objectMapper = objectMapper;
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional
    @CacheEvict(value = "productTypes", key = "#factoryId")
    public ProductTypeDTO createProductType(String factoryId, ProductTypeDTO dto) {
        // 如果没有提供 code，自动生成 SKU
        // 规则: CP{客户首字母拼音}{4位序号}，如 CPDD0001
        if (dto.getCode() == null || dto.getCode().trim().isEmpty()) {
            String category = dto.getCategory() != null ? dto.getCategory() : dto.getProductCategory();
            String generatedCode = buildGeneratedCode(factoryId, category, dto.getRelatedCustomer());
            dto.setCode(generatedCode);
            log.info("自动生成产品代码: {}", generatedCode);
        }

        log.info("创建产品类型: factoryId={}, code={}", factoryId, dto.getCode());

        // 检查产品编码是否已存在
        if (productTypeRepository.existsByFactoryIdAndCode(factoryId, dto.getCode())) {
            throw new BusinessException(409, "产品编码已存在: " + dto.getCode())
                    .withHint("请使用其他产品编码").withHintTarget("code");
        }

        ProductType productType = new ProductType();
        productType.setId(java.util.UUID.randomUUID().toString());
        productType.setFactoryId(factoryId);
        productType.setCode(dto.getCode());
        productType.setName(dto.getName());
        productType.setCategory(dto.getCategory());
        productType.setUnit(dto.getUnit());
        productType.setUnitPrice(dto.getUnitPrice());
        productType.setProductionTimeMinutes(dto.getProductionTimeMinutes());
        productType.setShelfLifeDays(dto.getShelfLifeDays());
        productType.setPackageSpec(dto.getPackageSpec());
        productType.setSpecification(dto.getSpecification());
        productType.setRelatedCustomer(dto.getRelatedCustomer());
        productType.setImageUrl(dto.getImageUrl());
        productType.setProductCategory(dto.getProductCategory());
        productType.setTemperatureZone(dto.getTemperatureZone());
        productType.setGramsPerUnit(dto.getGramsPerUnit());
        productType.setWipToFgYield(dto.getWipToFgYield());
        productType.setNotes(dto.getNotes());
        productType.setIsActive(true);
        productType.setCreatedBy(dto.getCreatedBy() != null ? Long.valueOf(dto.getCreatedBy()) : null);
        productType.setCreatedAt(LocalDateTime.now());
        productType.setUpdatedAt(LocalDateTime.now());

        // Sprint 2 S2-1: Form Template Association
        productType.setFormTemplateId(dto.getFormTemplateId());

        // Sprint 2 S2-5: SOP Configuration Association
        productType.setDefaultSopConfigId(dto.getDefaultSopConfigId());

        // Phase 5: SKU Configuration fields
        productType.setWorkHours(dto.getWorkHours() != null ? dto.getWorkHours() : BigDecimal.ONE);
        productType.setComplexityScore(dto.getComplexityScore() != null ? dto.getComplexityScore() : 3);
        productType.setProcessingSteps(serializeToJson(dto.getProcessingSteps()));
        productType.setSkillRequirements(serializeToJson(dto.getSkillRequirements()));
        productType.setEquipmentIds(serializeToJson(dto.getEquipmentIds()));
        productType.setQualityCheckIds(serializeToJson(dto.getQualityCheckIds()));

        // Custom Form Schema Configuration
        productType.setCustomSchemaOverrides(dto.getCustomSchemaOverrides());

        productType = productTypeRepository.save(productType);
        log.info("产品类型创建成功: id={}", productType.getId());

        return convertToDTO(productType);
    }

    @Override
    @Transactional
    @CacheEvict(value = "productTypes", key = "#factoryId")
    public ProductTypeDTO updateProductType(String factoryId, String id, ProductTypeDTO dto) {
        log.info("更新产品类型: factoryId={}, id={}", factoryId, id);

        ProductType productType = productTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + id));

        if (!productType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限操作此产品类型")
                    .withHint("当前产品类型不属于该工厂, 无法操作");
        }

        // 检查产品编码是否重复
        if (dto.getCode() != null && !dto.getCode().equals(productType.getCode())) {
            if (productTypeRepository.existsByFactoryIdAndCode(factoryId, dto.getCode())) {
                throw new BusinessException(409, "产品编码已存在: " + dto.getCode())
                    .withHint("请使用其他产品编码").withHintTarget("code");
            }
            productType.setCode(dto.getCode());
        }

        // 更新其他字段
        if (dto.getName() != null) productType.setName(dto.getName());
        if (dto.getCategory() != null) productType.setCategory(dto.getCategory());
        if (dto.getUnit() != null) productType.setUnit(dto.getUnit());
        if (dto.getUnitPrice() != null) productType.setUnitPrice(dto.getUnitPrice());
        if (dto.getProductionTimeMinutes() != null) productType.setProductionTimeMinutes(dto.getProductionTimeMinutes());
        if (dto.getShelfLifeDays() != null) productType.setShelfLifeDays(dto.getShelfLifeDays());
        if (dto.getPackageSpec() != null) productType.setPackageSpec(dto.getPackageSpec());
        if (dto.getNotes() != null) productType.setNotes(dto.getNotes());
        if (dto.getIsActive() != null) productType.setIsActive(dto.getIsActive());
        if (dto.getSpecification() != null) productType.setSpecification(dto.getSpecification());
        if (dto.getRelatedCustomer() != null) productType.setRelatedCustomer(dto.getRelatedCustomer());
        if (dto.getImageUrl() != null) productType.setImageUrl(dto.getImageUrl());
        if (dto.getProductCategory() != null) productType.setProductCategory(dto.getProductCategory());
        if (dto.getTemperatureZone() != null) productType.setTemperatureZone(dto.getTemperatureZone());
        if (dto.getGramsPerUnit() != null) productType.setGramsPerUnit(dto.getGramsPerUnit());
        if (dto.getWipToFgYield() != null) productType.setWipToFgYield(dto.getWipToFgYield());

        // Sprint 2 S2-1: Form Template Association
        if (dto.getFormTemplateId() != null) productType.setFormTemplateId(dto.getFormTemplateId());

        // Sprint 2 S2-5: SOP Configuration Association
        if (dto.getDefaultSopConfigId() != null) productType.setDefaultSopConfigId(dto.getDefaultSopConfigId());

        // Phase 5: SKU Configuration fields
        if (dto.getWorkHours() != null) productType.setWorkHours(dto.getWorkHours());
        if (dto.getComplexityScore() != null) productType.setComplexityScore(dto.getComplexityScore());
        if (dto.getProcessingSteps() != null) productType.setProcessingSteps(serializeToJson(dto.getProcessingSteps()));
        if (dto.getSkillRequirements() != null) productType.setSkillRequirements(serializeToJson(dto.getSkillRequirements()));
        if (dto.getEquipmentIds() != null) productType.setEquipmentIds(serializeToJson(dto.getEquipmentIds()));
        if (dto.getQualityCheckIds() != null) productType.setQualityCheckIds(serializeToJson(dto.getQualityCheckIds()));

        // Custom Form Schema Configuration
        if (dto.getCustomSchemaOverrides() != null) productType.setCustomSchemaOverrides(dto.getCustomSchemaOverrides());

        productType.setUpdatedAt(LocalDateTime.now());
        productType = productTypeRepository.save(productType);

        log.info("产品类型更新成功: id={}", productType.getId());
        return convertToDTO(productType);
    }

    @Override
    @Transactional
    @CacheEvict(value = "productTypes", key = "#factoryId")
    public void deleteProductType(String factoryId, String id) {
        log.info("删除产品类型: factoryId={}, id={}", factoryId, id);

        ProductType productType = productTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + id));

        if (!productType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限操作此产品类型")
                    .withHint("当前产品类型不属于该工厂, 无法操作");
        }

        // TODO: 检查是否有关联的生产计划
        // if (productType.getProductionPlans() != null && !productType.getProductionPlans().isEmpty()) {
        //     throw new BusinessException("产品类型有关联的生产计划，无法删除");
        // }

        productTypeRepository.delete(productType);
        log.info("产品类型删除成功: id={}", id);
    }

    @Override
    public ProductTypeDTO getProductTypeById(String factoryId, String id) {
        log.info("获取产品类型详情: factoryId={}, id={}", factoryId, id);

        ProductType productType = productTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + id));

        if (!productType.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权限查看此产品类型")
                    .withHint("当前产品类型不属于该工厂, 无法查看");
        }

        return convertToDTO(productType);
    }

    @Override
    public PageResponse<ProductTypeDTO> getProductTypes(String factoryId, PageRequest pageRequest) {
        // 兼容老调用 — 不传 productCategory / keyword
        return getProductTypes(factoryId, null, null, pageRequest);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public PageResponse<ProductTypeDTO> getProductTypes(String factoryId, String productCategory,
                                                         String keyword, PageRequest pageRequest) {
        log.info("获取产品类型列表: factoryId={}, productCategory={}, keyword={}, page={}, size={}",
                factoryId, productCategory, keyword, pageRequest.getPage(), pageRequest.getSize());

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        // V3 P0-2 修复 — 按 productCategory 真正隔离查询
        boolean hasCategory = productCategory != null && !productCategory.trim().isEmpty();
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();

        String safeKeyword = hasKeyword ? com.cretas.aims.util.SqlLikeEscaper.escape(keyword) : keyword;
        Page<ProductType> page;
        if (hasCategory && hasKeyword) {
            page = productTypeRepository.searchByFactoryIdAndProductCategory(
                    factoryId, productCategory, safeKeyword, pageable);
        } else if (hasCategory) {
            page = productTypeRepository.findByFactoryIdAndProductCategory(factoryId, productCategory, pageable);
        } else if (hasKeyword) {
            page = productTypeRepository.searchProductTypes(factoryId, safeKeyword, pageable);
        } else {
            page = productTypeRepository.findByFactoryId(factoryId, pageable);
        }

        List<ProductTypeDTO> dtos = page.getContent().stream()
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
    @Cacheable(value = "productTypes", key = "#factoryId")
    public List<ProductTypeDTO> getActiveProductTypes(String factoryId) {
        log.info("获取激活的产品类型: factoryId={}", factoryId);

        List<ProductType> productTypes = productTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        return productTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductTypeDTO> getProductTypesByCategory(String factoryId, String category) {
        log.info("根据类别获取产品类型: factoryId={}, category={}", factoryId, category);

        List<ProductType> productTypes = productTypeRepository.findByFactoryIdAndCategory(factoryId, category);
        return productTypes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public PageResponse<ProductTypeDTO> searchProductTypes(String factoryId, String keyword, PageRequest pageRequest) {
        log.info("搜索产品类型: factoryId={}, keyword={}", factoryId, keyword);

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<ProductType> page = productTypeRepository.searchProductTypes(factoryId,
            com.cretas.aims.util.SqlLikeEscaper.escape(keyword), pageable);
        List<ProductTypeDTO> dtos = page.getContent().stream()
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
    public List<String> getProductCategories(String factoryId) {
        log.info("获取产品类别列表: factoryId={}", factoryId);

        return productTypeRepository.findByFactoryId(factoryId).stream()
                .map(ProductType::getCategory)
                .filter(category -> category != null && !category.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateProductTypesStatus(String factoryId, List<String> ids, Boolean isActive) {
        log.info("批量更新产品类型状态: factoryId={}, ids={}, isActive={}", factoryId, ids, isActive);

        for (String id : ids) {
            ProductType productType = productTypeRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("产品类型不存在: " + id));

            if (!productType.getFactoryId().equals(factoryId)) {
                throw new BusinessException(403, "无权限操作产品类型: " + id)
                        .withHint("批量操作中包含其他工厂的产品类型, 请重新选择");
            }

            productType.setIsActive(isActive);
            productType.setUpdatedAt(LocalDateTime.now());
            productTypeRepository.save(productType);
        }

        log.info("批量更新产品类型状态成功: count={}", ids.size());
    }

    /**
     * 获取客户名称的拼音首字母缩写（用于自动编码）
     * 如 "大东" → "DD", "六扇门" → "LSM"
     * 无客户则使用 factoryId 作为后备
     */
    private String getCustomerInitials(String customerName, String factoryId) {
        if (customerName == null || customerName.trim().isEmpty()) {
            return factoryId != null ? factoryId : "";
        }
        StringBuilder initials = new StringBuilder();
        for (char c : customerName.trim().toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) {
                // 简易拼音首字母映射（基于 Unicode 区间粗略映射）
                initials.append(getPinyinInitial(c));
            } else if (Character.isLetter(c)) {
                initials.append(Character.toUpperCase(c));
            }
        }
        return initials.length() > 0 ? initials.toString() : (factoryId != null ? factoryId : "");
    }

    /**
     * 简易汉字拼音首字母（基于 GB2312 区间近似映射）
     */
    private char getPinyinInitial(char ch) {
        // 按 GB2312 转换后的拼音声母区间
        byte[] bytes;
        try {
            bytes = String.valueOf(ch).getBytes("GBK");
        } catch (Exception e) {
            return 'X';
        }
        if (bytes.length < 2) return 'X';
        int code = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        if (code < 0xB0A1) return 'X';
        if (code < 0xB0C5) return 'A';
        if (code < 0xB2C1) return 'B';
        if (code < 0xB4EE) return 'C';
        if (code < 0xB6EA) return 'D';
        if (code < 0xB7A2) return 'E';
        if (code < 0xB8C1) return 'F';
        if (code < 0xB9FE) return 'G';
        if (code < 0xBBF7) return 'H';
        if (code < 0xBFA6) return 'J';
        if (code < 0xC0AC) return 'K';
        if (code < 0xC2E8) return 'L';
        if (code < 0xC4C3) return 'M';
        if (code < 0xC5B6) return 'N';
        if (code < 0xC5BE) return 'O';
        if (code < 0xC6DA) return 'P';
        if (code < 0xC8BB) return 'Q';
        if (code < 0xC8F6) return 'R';
        if (code < 0xCBFA) return 'S';
        if (code < 0xCDDA) return 'T';
        if (code < 0xCEF4) return 'W';
        if (code < 0xD1B9) return 'X';
        if (code < 0xD4D1) return 'Y';
        if (code <= 0xD7F9) return 'Z';
        return 'X';
    }

    private String getCategoryPrefix(String category) {
        if (category == null) return "PT";
        switch (category.toUpperCase()) {
            case "FINISHED_PRODUCT": return "CP";
            case "RAW_MATERIAL": return "YL";
            case "PACKAGING": return "BF";
            case "SEASONING": return "TW";
            case "CUSTOMER_MATERIAL": return "KH";
            default: return "PT";
        }
    }

    /**
     * T147 Fix2: 统一的编号生成逻辑 (前缀 + 客户首字母 + 4位序号),
     * createProductType 与 previewGeneratedCode 共用, 保证预览与最终落库一致.
     */
    private String buildGeneratedCode(String factoryId, String category, String relatedCustomer) {
        String prefix = getCategoryPrefix(category);
        String customerInitials = getCustomerInitials(relatedCustomer, factoryId);
        long count = productTypeRepository.countByFactoryId(factoryId);
        return String.format("%s%s%04d", prefix, customerInitials, count + 1);
    }

    @Override
    public String previewGeneratedCode(String factoryId, String productCategory,
                                       String customerId, String relatedCustomer) {
        // 优先用 customerId 解析客户名 (与表单的 entity link 对齐); 解析不到则用传入的 relatedCustomer 名后备
        String customerName = relatedCustomer;
        if (customerId != null && !customerId.trim().isEmpty()) {
            customerName = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                    .map(c -> c.getName())
                    .orElse(relatedCustomer);
        }
        String code = buildGeneratedCode(factoryId, productCategory, customerName);
        log.info("预览产品编号: factoryId={}, category={}, customerId={}, name={}, code={}",
                factoryId, productCategory, customerId, customerName, code);
        return code;
    }

    @Override
    public boolean checkCodeExists(String factoryId, String code, String excludeId) {
        log.info("检查产品编码是否存在: factoryId={}, code={}, excludeId={}", factoryId, code, excludeId);

        if (excludeId != null) {
            ProductType existing = productTypeRepository.findByFactoryIdAndCode(factoryId, code).orElse(null);
            return existing != null && !existing.getId().equals(excludeId);
        }

        return productTypeRepository.existsByFactoryIdAndCode(factoryId, code);
    }

    @Override
    @Transactional
    public void initializeDefaultProductTypes(String factoryId) {
        log.info("初始化默认产品类型: factoryId={}", factoryId);

        // 检查是否已有产品类型
        long count = productTypeRepository.countByFactoryId(factoryId);
        if (count > 0) {
            log.info("工厂已有产品类型，跳过初始化: count={}", count);
            return;
        }

        // 创建默认产品类型
        List<ProductType> defaultTypes = Arrays.asList(
                createDefaultProductType(factoryId, "P001", "面包", "烘焙", "个", BigDecimal.valueOf(5.00), 120, 3),
                createDefaultProductType(factoryId, "P002", "蛋糕", "烘焙", "个", BigDecimal.valueOf(25.00), 180, 5),
                createDefaultProductType(factoryId, "P003", "饼干", "烘焙", "盒", BigDecimal.valueOf(15.00), 60, 30),
                createDefaultProductType(factoryId, "P004", "月饼", "糕点", "盒", BigDecimal.valueOf(50.00), 240, 60),
                createDefaultProductType(factoryId, "P005", "牛奶", "乳制品", "瓶", BigDecimal.valueOf(8.00), 30, 7)
        );

        productTypeRepository.saveAll(defaultTypes);
        log.info("默认产品类型初始化成功: count={}", defaultTypes.size());
    }

    private ProductType createDefaultProductType(String factoryId, String code, String name,
                                                 String category, String unit, BigDecimal price,
                                                 Integer productionTime, Integer shelfLife) {
        ProductType productType = new ProductType();
        productType.setFactoryId(factoryId);
        productType.setCode(code);
        productType.setName(name);
        productType.setCategory(category);
        productType.setUnit(unit);
        productType.setUnitPrice(price);
        productType.setProductionTimeMinutes(productionTime);
        productType.setShelfLifeDays(shelfLife);
        productType.setIsActive(true);
        productType.setCreatedBy(1L); // 系统创建
        productType.setCreatedAt(LocalDateTime.now());
        productType.setUpdatedAt(LocalDateTime.now());
        return productType;
    }

    private ProductTypeDTO convertToDTO(ProductType productType) {
        return ProductTypeDTO.builder()
                .id(productType.getId())
                .factoryId(productType.getFactoryId())
                .code(productType.getCode())
                .name(productType.getName())
                .category(productType.getCategory())
                .unit(productType.getUnit())
                .unitPrice(productType.getUnitPrice())
                .productionTimeMinutes(productType.getProductionTimeMinutes())
                .shelfLifeDays(productType.getShelfLifeDays())
                .packageSpec(productType.getPackageSpec())
                .relatedCustomer(productType.getRelatedCustomer())
                .imageUrl(productType.getImageUrl())
                .specification(productType.getSpecification())
                .productCategory(productType.getProductCategory())
                .temperatureZone(productType.getTemperatureZone())
                .gramsPerUnit(productType.getGramsPerUnit())
                .wipToFgYield(productType.getWipToFgYield())
                .isActive(productType.getIsActive())
                .notes(productType.getNotes())
                .createdBy(productType.getCreatedBy())
                .createdAt(productType.getCreatedAt())
                .updatedAt(productType.getUpdatedAt())
                // Sprint 2 S2-1: Form Template Association
                .formTemplateId(productType.getFormTemplateId())
                // Sprint 2 S2-5: SOP Configuration Association
                .defaultSopConfigId(productType.getDefaultSopConfigId())
                // Phase 5: SKU Configuration fields
                .workHours(productType.getWorkHours())
                .complexityScore(productType.getComplexityScore())
                .processingSteps(parseProcessingSteps(productType.getProcessingSteps()))
                .skillRequirements(parseSkillRequirements(productType.getSkillRequirements()))
                .equipmentIds(parseStringList(productType.getEquipmentIds()))
                .qualityCheckIds(parseStringList(productType.getQualityCheckIds()))
                // Custom Form Schema Configuration
                .customSchemaOverrides(productType.getCustomSchemaOverrides())
                .build();
    }

    // ==================== Phase 5: JSON Serialization/Deserialization Helpers ====================

    /**
     * Serialize object to JSON string
     */
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse processing steps from JSON string
     */
    private List<ProductTypeDTO.ProcessingStepDTO> parseProcessingSteps(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<ProductTypeDTO.ProcessingStepDTO>>() {});
        } catch (JsonProcessingException e) {
            log.error("解析 processingSteps JSON 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse skill requirements from JSON string
     */
    private ProductTypeDTO.SkillRequirementDTO parseSkillRequirements(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProductTypeDTO.SkillRequirementDTO.class);
        } catch (JsonProcessingException e) {
            log.error("解析 skillRequirements JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse string list from JSON string
     */
    private List<String> parseStringList(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("解析 JSON 数组失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
