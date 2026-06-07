package com.cretas.aims.service.config;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.service.SystemEnumService;
import com.cretas.aims.service.impl.SystemEnumServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T123: UnitOfMeasurement CRUD 单测 — 验证工厂隔离 + 409 幂等防重复 + 系统内置不可删.
 *
 * 纯 Mockito, 不启动 Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnitOfMeasurement CRUD (T123 单位字典管理)")
class UnitOfMeasurementCrudTest {

    private static final String FACTORY_ID = "F006";
    private static final String UNIT_CODE = "KUang";

    @Mock
    private UnitOfMeasurementRepository unitRepo;

    // Other repos/services needed by SystemEnumServiceImpl constructor
    @Mock
    private com.cretas.aims.repository.config.SystemEnumRepository enumRepo;

    private SystemEnumService service;

    @BeforeEach
    void setUp() {
        // SystemEnumServiceImpl requires: systemEnumRepository, unitRepo
        // Constructor: (SystemEnumRepository, UnitOfMeasurementRepository)
        service = new SystemEnumServiceImpl(enumRepo, unitRepo);
    }

    // ---- createUnit ----

    @Test
    @DisplayName("createUnit: 新建一个工厂级单位成功")
    void createUnit_success() {
        UnitOfMeasurement unit = buildUnit(FACTORY_ID, UNIT_CODE, "筐", false);
        when(unitRepo.existsByFactoryIdAndUnitCode(FACTORY_ID, UNIT_CODE)).thenReturn(false);
        when(unitRepo.save(any())).thenReturn(unit);

        UnitOfMeasurement result = service.createUnit(unit);

        assertThat(result.getUnitCode()).isEqualTo(UNIT_CODE);
        assertThat(result.getUnitName()).isEqualTo("筐");
        verify(unitRepo).save(unit);
    }

    @Test
    @DisplayName("createUnit: 重复 unitCode → 409 BusinessException (幂等防重)")
    void createUnit_duplicate_throws409() {
        UnitOfMeasurement unit = buildUnit(FACTORY_ID, UNIT_CODE, "筐", false);
        when(unitRepo.existsByFactoryIdAndUnitCode(FACTORY_ID, UNIT_CODE)).thenReturn(true);

        assertThatThrownBy(() -> service.createUnit(unit))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");

        verify(unitRepo, never()).save(any());
    }

    @Test
    @DisplayName("createUnit: 工厂隔离 — 不同 factoryId 查询不冲突")
    void createUnit_factoryIsolation() {
        String otherFactory = "F001";
        UnitOfMeasurement unit = buildUnit(otherFactory, UNIT_CODE, "筐", false);
        when(unitRepo.existsByFactoryIdAndUnitCode(otherFactory, UNIT_CODE)).thenReturn(false);
        when(unitRepo.save(any())).thenReturn(unit);

        service.createUnit(unit);

        verify(unitRepo).existsByFactoryIdAndUnitCode(otherFactory, UNIT_CODE);
        verify(unitRepo, never()).existsByFactoryIdAndUnitCode(eq(FACTORY_ID), any());
    }

    // ---- updateUnit ----

    @Test
    @DisplayName("updateUnit: 更新单位名称成功")
    void updateUnit_success() {
        UnitOfMeasurement existing = buildUnit(FACTORY_ID, UNIT_CODE, "筐", false);
        UnitOfMeasurement update = buildUnit(FACTORY_ID, UNIT_CODE, "框(已更名)", false);
        update.setConversionFactor(new BigDecimal("1.0000"));

        when(unitRepo.findByFactoryIdAndUnitCodeAndDeletedAtIsNull(FACTORY_ID, UNIT_CODE))
                .thenReturn(Optional.of(existing));
        when(unitRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UnitOfMeasurement result = service.updateUnit(update);

        assertThat(result.getUnitName()).isEqualTo("框(已更名)");
    }

    @Test
    @DisplayName("updateUnit: 不存在的 unitCode → ResourceNotFoundException")
    void updateUnit_notFound_throws() {
        UnitOfMeasurement update = buildUnit(FACTORY_ID, "NONEXISTENT", "不存在", false);
        when(unitRepo.findByFactoryIdAndUnitCodeAndDeletedAtIsNull(FACTORY_ID, "NONEXISTENT"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUnit(update))
                .isInstanceOf(com.cretas.aims.exception.ResourceNotFoundException.class);
    }

    // ---- deleteUnit ----

    @Test
    @DisplayName("deleteUnit: 用户自定义单位可以软删除")
    void deleteUnit_customUnit_softDelete() {
        UnitOfMeasurement custom = buildUnit(FACTORY_ID, UNIT_CODE, "筐", false);
        custom.setIsSystem(false);

        when(unitRepo.findByFactoryIdAndUnitCodeAndDeletedAtIsNull(FACTORY_ID, UNIT_CODE))
                .thenReturn(Optional.of(custom));
        when(unitRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteUnit(FACTORY_ID, UNIT_CODE);

        verify(unitRepo).save(argThat(u -> u.getDeletedAt() != null));
    }

    @Test
    @DisplayName("deleteUnit: 系统内置单位 isSystem=true → 409 拒绝删除")
    void deleteUnit_systemUnit_throws409() {
        UnitOfMeasurement systemUnit = buildUnit(FACTORY_ID, "kg", "公斤", true);
        systemUnit.setIsSystem(true);

        when(unitRepo.findByFactoryIdAndUnitCodeAndDeletedAtIsNull(FACTORY_ID, "kg"))
                .thenReturn(Optional.of(systemUnit));

        assertThatThrownBy(() -> service.deleteUnit(FACTORY_ID, "kg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统内置");

        verify(unitRepo, never()).save(any());
    }

    // ---- getAllUnits ----

    @Test
    @DisplayName("getAllUnits: 返回工厂全部单位列表")
    void getAllUnits_returnsFactoryUnits() {
        List<UnitOfMeasurement> units = List.of(
                buildUnit(FACTORY_ID, "kuang", "筐", false),
                buildUnit(FACTORY_ID, "he", "盒", false),
                buildUnit(FACTORY_ID, "kg", "公斤", true)
        );
        when(unitRepo.findAllByFactoryId(FACTORY_ID)).thenReturn(units);

        List<UnitOfMeasurement> result = service.getAllUnits(FACTORY_ID);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(UnitOfMeasurement::getUnitCode)
                .containsExactly("kuang", "he", "kg");
    }

    // ---- helpers ----

    private UnitOfMeasurement buildUnit(String factoryId, String code, String name, boolean isSystem) {
        return UnitOfMeasurement.builder()
                .factoryId(factoryId)
                .unitCode(code)
                .unitName(name)
                .unitSymbol(name)
                .baseUnit("g")
                .category("COUNT")
                .isSystem(isSystem)
                .isActive(true)
                .sortOrder(0)
                .build();
    }
}
