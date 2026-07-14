package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.SystemEnum;
import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.SystemEnumRepository;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.service.SystemEnumService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统枚举配置服务实现
 *
 * 提供枚举和计量单位的配置化管理:
 * - 支持工厂级别覆盖 (优先级: 工厂级 > 全局)
 * - 带有本地缓存 (10分钟TTL，配置在CacheConfig)
 * - 去重逻辑：同一enumCode只返回最高优先级的配置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@Service
public class SystemEnumServiceImpl implements SystemEnumService {

    private final SystemEnumRepository systemEnumRepository;
    private final UnitOfMeasurementRepository unitOfMeasurementRepository;
    private final UnitContractService unitContractService;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemEnumServiceImpl(
            SystemEnumRepository systemEnumRepository,
            UnitOfMeasurementRepository unitOfMeasurementRepository,
            UnitContractService unitContractService) {
        this.systemEnumRepository = systemEnumRepository;
        this.unitOfMeasurementRepository = unitOfMeasurementRepository;
        this.unitContractService = unitContractService;
    }

    /** Backward-compatible constructor for CRUD-only unit tests. */
    @Deprecated(forRemoval = true)
    public SystemEnumServiceImpl(
            SystemEnumRepository systemEnumRepository,
            UnitOfMeasurementRepository unitOfMeasurementRepository) {
        this.systemEnumRepository = systemEnumRepository;
        this.unitOfMeasurementRepository = unitOfMeasurementRepository;
        this.unitContractService = null;
    }

    // ==================== 枚举查询 ====================

    @Override
    @Cacheable(value = "systemEnums", key = "#factoryId + ':' + #enumGroup")
    public List<SystemEnum> getEnumsByGroup(String factoryId, String enumGroup) {
        List<SystemEnum> enums = systemEnumRepository.findByFactoryIdAndEnumGroup(factoryId, enumGroup);

        // 去重：同一enumCode只保留优先级最高的 (工厂级 > 全局)
        Map<String, SystemEnum> deduped = new LinkedHashMap<>();
        for (SystemEnum e : enums) {
            if (!deduped.containsKey(e.getEnumCode())) {
                deduped.put(e.getEnumCode(), e);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    @Override
    public Optional<SystemEnum> getEnum(String factoryId, String enumGroup, String enumCode) {
        List<SystemEnum> enums = systemEnumRepository.findByFactoryIdAndEnumGroupAndEnumCode(
                factoryId, enumGroup, enumCode);
        return enums.isEmpty() ? Optional.empty() : Optional.of(enums.get(0));
    }

    @Override
    public String getEnumLabel(String factoryId, String enumGroup, String enumCode) {
        return getEnum(factoryId, enumGroup, enumCode)
                .map(SystemEnum::getEnumLabel)
                .orElse(enumCode);
    }

    @Override
    @Cacheable(value = "enumLabelsMap", key = "#factoryId + ':' + #enumGroup")
    public Map<String, String> getEnumLabelsMap(String factoryId, String enumGroup) {
        return getEnumsByGroup(factoryId, enumGroup).stream()
                .collect(Collectors.toMap(
                        SystemEnum::getEnumCode,
                        SystemEnum::getEnumLabel,
                        (v1, v2) -> v1,
                        LinkedHashMap::new
                ));
    }

    @Override
    @Cacheable(value = "allEnumGroups")
    public List<String> getAllEnumGroups() {
        return systemEnumRepository.findAllEnumGroups();
    }

    @Override
    public boolean isValidEnum(String factoryId, String enumGroup, String enumCode) {
        return getEnum(factoryId, enumGroup, enumCode).isPresent();
    }

    // ==================== 枚举管理 ====================

    @Override
    @Transactional
    @CacheEvict(value = {"systemEnums", "enumLabelsMap"}, key = "#systemEnum.factoryId + ':' + #systemEnum.enumGroup")
    public SystemEnum createEnum(SystemEnum systemEnum) {
        if (systemEnumRepository.existsByFactoryIdAndEnumGroupAndEnumCode(
                systemEnum.getFactoryId(), systemEnum.getEnumGroup(), systemEnum.getEnumCode())) {
            throw new BusinessException(409, "枚举配置已存在: " + systemEnum.getEnumCode())
                    .withHint("请使用其他枚举编码, 或编辑现有配置")
                    .withHintTarget("enumCode");
        }

        if (systemEnum.getSortOrder() == null) {
            Long count = systemEnumRepository.countByFactoryIdAndEnumGroup(
                    systemEnum.getFactoryId(), systemEnum.getEnumGroup());
            systemEnum.setSortOrder(count.intValue());
        }

        return systemEnumRepository.save(systemEnum);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"systemEnums", "enumLabelsMap"}, key = "#systemEnum.factoryId + ':' + #systemEnum.enumGroup")
    public SystemEnum updateEnum(SystemEnum systemEnum) {
        SystemEnum existing = systemEnumRepository
                .findByFactoryIdAndEnumGroupAndEnumCodeAndDeletedAtIsNull(
                        systemEnum.getFactoryId(), systemEnum.getEnumGroup(), systemEnum.getEnumCode())
                .orElseThrow(() -> new IllegalArgumentException("枚举配置不存在"));

        existing.setEnumLabel(systemEnum.getEnumLabel());
        existing.setEnumDescription(systemEnum.getEnumDescription());
        existing.setEnumValue(systemEnum.getEnumValue());
        existing.setSortOrder(systemEnum.getSortOrder());
        existing.setMetadata(systemEnum.getMetadata());
        existing.setIcon(systemEnum.getIcon());
        existing.setColor(systemEnum.getColor());
        existing.setIsActive(systemEnum.getIsActive());

        return systemEnumRepository.save(existing);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"systemEnums", "enumLabelsMap"}, key = "#factoryId + ':' + #enumGroup")
    public void deleteEnum(String factoryId, String enumGroup, String enumCode) {
        SystemEnum existing = systemEnumRepository
                .findByFactoryIdAndEnumGroupAndEnumCodeAndDeletedAtIsNull(factoryId, enumGroup, enumCode)
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("枚举配置", "enumCode", enumCode));

        if (Boolean.TRUE.equals(existing.getIsSystem())) {
            throw new BusinessException(409, "系统内置枚举不可删除")
                    .withHint("系统内置枚举受保护, 仅可禁用 (toggle isActive=false)");
        }

        existing.setDeletedAt(LocalDateTime.now());
        systemEnumRepository.save(existing);
    }

    // ==================== 计量单位查询 ====================

    @Override
    @Cacheable(value = "unitsByCategory", key = "#factoryId + ':' + #category")
    public List<UnitOfMeasurement> getUnitsByCategory(String factoryId, String category) {
        List<UnitOfMeasurement> units = unitOfMeasurementRepository.findByFactoryIdAndCategory(factoryId, category);

        // 去重：同一unitCode只保留优先级最高的
        Map<String, UnitOfMeasurement> deduped = new LinkedHashMap<>();
        for (UnitOfMeasurement u : units) {
            if (!deduped.containsKey(u.getUnitCode())) {
                deduped.put(u.getUnitCode(), u);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    @Override
    @Cacheable(value = "allUnits", key = "#factoryId")
    public List<UnitOfMeasurement> getAllUnits(String factoryId) {
        List<UnitOfMeasurement> units = unitOfMeasurementRepository.findAllByFactoryId(factoryId);

        // 去重
        Map<String, UnitOfMeasurement> deduped = new LinkedHashMap<>();
        for (UnitOfMeasurement u : units) {
            if (!deduped.containsKey(u.getUnitCode())) {
                deduped.put(u.getUnitCode(), u);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    @Override
    public Optional<UnitOfMeasurement> getUnit(String factoryId, String unitCode) {
        List<UnitOfMeasurement> units = unitOfMeasurementRepository.findByFactoryIdAndUnitCode(factoryId, unitCode);
        return units.isEmpty() ? Optional.empty() : Optional.of(units.get(0));
    }

    @Override
    public Optional<UnitOfMeasurement> getBaseUnit(String factoryId, String category) {
        List<UnitOfMeasurement> baseUnits = unitOfMeasurementRepository.findBaseUnitByFactoryIdAndCategory(
                factoryId, category);
        return baseUnits.isEmpty() ? Optional.empty() : Optional.of(baseUnits.get(0));
    }

    @Override
    @Cacheable(value = "unitCategories")
    public List<String> getAllUnitCategories() {
        return unitOfMeasurementRepository.findAllCategories();
    }

    // ==================== 单位换算 ====================

    @Override
    public BigDecimal convertUnit(String factoryId, BigDecimal value, String fromUnit, String toUnit) {
        if (unitContractService == null) {
            throw new IllegalStateException("UnitContractService is required for unit conversion");
        }
        UnitConversionResult result = unitContractService.convert(value, new UnitConversionContext(
                factoryId, null, fromUnit, toUnit, LocalDateTime.now(), null, 6, RoundingMode.HALF_UP));
        if (!result.succeeded()) {
            throw new BusinessException(400, result.message() == null ? "单位无法换算" : result.message())
                    .withCode(result.status().name())
                    .withHintTarget("toUnit");
        }
        return result.quantity();
    }

    @Override
    public BigDecimal toBaseUnit(String factoryId, BigDecimal value, String fromUnit) {
        if (value == null) {
            return null;
        }

        UnitOfMeasurement from = getUnit(factoryId, fromUnit)
                .orElseThrow(() -> new IllegalArgumentException("未知单位: " + fromUnit));

        return from.toBaseUnit(value);
    }

    @Override
    public BigDecimal fromBaseUnit(String factoryId, BigDecimal baseValue, String toUnit) {
        if (baseValue == null) {
            return null;
        }

        UnitOfMeasurement to = getUnit(factoryId, toUnit)
                .orElseThrow(() -> new IllegalArgumentException("未知单位: " + toUnit));

        return to.fromBaseUnit(baseValue);
    }

    // ==================== 计量单位管理 ====================

    @Override
    @Transactional
    @CacheEvict(value = {"unitsByCategory", "allUnits"}, allEntries = true)
    public UnitOfMeasurement createUnit(UnitOfMeasurement unit) {
        if (unitOfMeasurementRepository.existsByFactoryIdAndUnitCode(unit.getFactoryId(), unit.getUnitCode())) {
            throw new BusinessException(409, "单位配置已存在: " + unit.getUnitCode())
                    .withHint("请使用其他单位编码, 或编辑现有配置")
                    .withHintTarget("unitCode");
        }

        return unitOfMeasurementRepository.save(unit);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"unitsByCategory", "allUnits"}, allEntries = true)
    public UnitOfMeasurement updateUnit(UnitOfMeasurement unit) {
        UnitOfMeasurement existing = unitOfMeasurementRepository
                .findByFactoryIdAndUnitCodeAndDeletedAtIsNull(unit.getFactoryId(), unit.getUnitCode())
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("计量单位", "unitCode", unit.getUnitCode()));

        existing.setUnitName(unit.getUnitName());
        existing.setUnitSymbol(unit.getUnitSymbol());
        existing.setConversionFactor(unit.getConversionFactor());
        existing.setDecimalPlaces(unit.getDecimalPlaces());
        existing.setSortOrder(unit.getSortOrder());
        existing.setIsActive(unit.getIsActive());

        return unitOfMeasurementRepository.save(existing);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"unitsByCategory", "allUnits"}, allEntries = true)
    public void deleteUnit(String factoryId, String unitCode) {
        UnitOfMeasurement existing = unitOfMeasurementRepository
                .findByFactoryIdAndUnitCodeAndDeletedAtIsNull(factoryId, unitCode)
                .orElseThrow(() -> new com.cretas.aims.exception.ResourceNotFoundException("计量单位", "unitCode", unitCode));

        if (Boolean.TRUE.equals(existing.getIsSystem())) {
            throw new BusinessException(409, "系统内置单位不可删除")
                    .withHint("系统内置单位受保护, 仅可禁用 (toggle isActive=false)");
        }

        existing.setDeletedAt(LocalDateTime.now());
        unitOfMeasurementRepository.save(existing);
    }

    // ==================== 缓存管理 ====================

    @Override
    @CacheEvict(value = {"systemEnums", "enumLabelsMap"}, allEntries = true)
    public void clearEnumCache(String factoryId) {
        log.info("Cleared enum cache for factory: {}", factoryId);
    }

    @Override
    @CacheEvict(value = {"unitsByCategory", "allUnits"}, allEntries = true)
    public void clearUnitCache(String factoryId) {
        log.info("Cleared unit cache for factory: {}", factoryId);
    }

    @Override
    @CacheEvict(value = {"systemEnums", "enumLabelsMap", "unitsByCategory", "allUnits",
                         "allEnumGroups", "unitCategories"}, allEntries = true)
    public void clearAllCache() {
        log.info("Cleared all system enum and unit caches");
    }
}
