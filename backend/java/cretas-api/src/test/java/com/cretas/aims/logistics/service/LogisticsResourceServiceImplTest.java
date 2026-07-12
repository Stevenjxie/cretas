package com.cretas.aims.logistics.service;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.resource.DriverInputRequest;
import com.cretas.aims.logistics.dto.resource.LogisticsDriverDto;
import com.cretas.aims.logistics.dto.resource.LogisticsVehicleDto;
import com.cretas.aims.logistics.dto.resource.VehicleDriverBindingDto;
import com.cretas.aims.logistics.dto.resource.VehicleProfileUpdateRequest;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
import com.cretas.aims.logistics.repository.LogisticsDailyAvailabilityRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsStoreMasterRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import com.cretas.aims.logistics.service.impl.LogisticsResourceServiceImpl;
import com.cretas.aims.repository.VehicleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link LogisticsResourceServiceImpl} (handoff §11.2, spec §2 决策 5) —
 * Mockito-mocked repositories (no DB round trip needed; logic under test is merge/mapping +
 * optimistic-lock precondition + partial-update semantics, not DB constraints).
 *
 * <p>Covers:
 * <ol>
 *   <li>listVehicles — merges Vehicle + LogisticsVehicleProfile + bindings; sane defaults
 *       when no profile exists yet</li>
 *   <li>updateVehicleProfile — creates profile on first write; partial update preserves
 *       untouched fields; version mismatch → 409</li>
 *   <li>createDriver / updateDriver — required-field validation, version conflict → 409</li>
 *   <li>setVehicleDrivers — replaces binding set; unknown driverId → 400</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LogisticsResourceServiceImpl — vehicle profile / driver / binding")
class LogisticsResourceServiceImplTest {

    private static final String F1 = "F-LOG-RES-1";

    @Mock private VehicleRepository vehicleRepo;
    @Mock private LogisticsVehicleProfileRepository vehicleProfileRepo;
    @Mock private LogisticsDriverRepository driverRepo;
    @Mock private LogisticsVehicleDriverRepository vehicleDriverRepo;
    @Mock private LogisticsDailyAvailabilityRepository dailyAvailabilityRepo;
    @Mock private LogisticsStoreMasterRepository storeMasterRepo;

    private LogisticsResourceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LogisticsResourceServiceImpl(vehicleRepo, vehicleProfileRepo, driverRepo, vehicleDriverRepo, dailyAvailabilityRepo, storeMasterRepo);
    }

    private Vehicle vehicle(String id, String plate) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setFactoryId(F1);
        v.setPlateNumber(plate);
        v.setDriverName("旧司机");
        v.setDriverPhone("13800000000");
        v.setCapacity(new BigDecimal("2000"));
        return v;
    }

    private LogisticsVehicleProfile profile(String vehicleId, long version) {
        LogisticsVehicleProfile p = LogisticsVehicleProfile.builder()
                .id("profile-" + vehicleId)
                .vehicleId(vehicleId)
                .factoryId(F1)
                .capacityCbm(new BigDecimal("10.000"))
                .maxWeightKg(new BigDecimal("3000.000"))
                .source(OwnershipType.OWNED)
                .temperatureMode(TemperatureMode.DUAL_TEMP)
                .serviceAreas("区域A,区域B")
                .active(true)
                .build();
        p.setVersion(version);
        return p;
    }

    // ==================== listVehicles ====================

    @Test
    @DisplayName("listVehicles — 车辆有 profile + 绑定司机 → 合并读模型")
    void listVehiclesMergesProfileAndBindings() {
        Vehicle v1 = vehicle("V1", "苏A12345");
        when(vehicleRepo.findByFactoryId(F1)).thenReturn(List.of(v1));
        when(vehicleProfileRepo.findByFactoryId(F1)).thenReturn(List.of(profile("V1", 3L)));

        LogisticsVehicleDriver binding = LogisticsVehicleDriver.builder()
                .id("b1").factoryId(F1).vehicleId("V1").driverId("D1")
                .role(DriverRole.PRIMARY).priority(0).active(true).build();
        when(vehicleDriverRepo.findByFactoryIdAndDeletedAtIsNull(F1)).thenReturn(List.of(binding));

        LogisticsDriver driver = LogisticsDriver.builder()
                .id("D1").factoryId(F1).name("张三").employmentType(OwnershipType.OWNED).active(true).build();
        when(driverRepo.findByFactoryIdOrderByNameAsc(F1)).thenReturn(List.of(driver));

        List<LogisticsVehicleDto> result = service.listVehicles(F1);

        assertThat(result).hasSize(1);
        LogisticsVehicleDto dto = result.get(0);
        assertThat(dto.getId()).isEqualTo("V1");
        assertThat(dto.getCapacityCbm()).isEqualByComparingTo("10.000");
        assertThat(dto.getServiceAreas()).containsExactly("区域A", "区域B");
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getDrivers()).hasSize(1);
        assertThat(dto.getDrivers().get(0).getDriverName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("listVehicles — 车辆无 profile → 用 Vehicle.capacity 兜底默认值")
    void listVehiclesNoProfileUsesDefaults() {
        Vehicle v1 = vehicle("V2", "苏B54321");
        when(vehicleRepo.findByFactoryId(F1)).thenReturn(List.of(v1));
        when(vehicleProfileRepo.findByFactoryId(F1)).thenReturn(List.of());
        when(vehicleDriverRepo.findByFactoryIdAndDeletedAtIsNull(F1)).thenReturn(List.of());
        when(driverRepo.findByFactoryIdOrderByNameAsc(F1)).thenReturn(List.of());

        LogisticsVehicleDto dto = service.listVehicles(F1).get(0);

        assertThat(dto.getCapacityCbm()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getMaxWeightKg()).isEqualByComparingTo("2000"); // 兜底 Vehicle.capacity
        assertThat(dto.getSource()).isEqualTo(OwnershipType.OWNED);
        assertThat(dto.getTemperatureMode()).isEqualTo(TemperatureMode.DUAL_TEMP);
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.getVersion()).isEqualTo(0L);
        assertThat(dto.getDrivers()).isEmpty();
    }

    // ==================== updateVehicleProfile ====================

    @Test
    @DisplayName("updateVehicleProfile — 首次调用 (无既有 profile) → 创建新 profile")
    void updateVehicleProfileCreatesNew() {
        Vehicle v1 = vehicle("V3", "苏C11111");
        when(vehicleRepo.findByIdAndFactoryId("V3", F1)).thenReturn(Optional.of(v1));
        when(vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull("V3")).thenReturn(Optional.empty());
        when(vehicleProfileRepo.save(any(LogisticsVehicleProfile.class))).thenAnswer(inv -> {
            LogisticsVehicleProfile p = inv.getArgument(0);
            p.setId("new-profile-id");
            p.setVersion(0L);
            return p;
        });
        lenient().when(vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull("V3")).thenReturn(List.of());

        VehicleProfileUpdateRequest req = new VehicleProfileUpdateRequest();
        req.setCapacityCbm(new BigDecimal("12.5"));
        req.setSource(OwnershipType.OUTSOURCED);

        LogisticsVehicleDto dto = service.updateVehicleProfile(F1, "V3", req);

        assertThat(dto.getCapacityCbm()).isEqualByComparingTo("12.5");
        assertThat(dto.getSource()).isEqualTo(OwnershipType.OUTSOURCED);
        verify(vehicleProfileRepo).save(any(LogisticsVehicleProfile.class));
    }

    @Test
    @DisplayName("updateVehicleProfile — partial update 只改传入字段, 未传字段保持原值")
    void updateVehicleProfilePartialUpdatePreservesUntouched() {
        Vehicle v1 = vehicle("V4", "苏D22222");
        LogisticsVehicleProfile existing = profile("V4", 1L);
        when(vehicleRepo.findByIdAndFactoryId("V4", F1)).thenReturn(Optional.of(v1));
        when(vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull("V4")).thenReturn(Optional.of(existing));
        when(vehicleProfileRepo.save(any(LogisticsVehicleProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull("V4")).thenReturn(List.of());

        VehicleProfileUpdateRequest req = new VehicleProfileUpdateRequest();
        req.setBodyType("厢式货车"); // 只改这一个字段

        LogisticsVehicleDto dto = service.updateVehicleProfile(F1, "V4", req);

        assertThat(dto.getBodyType()).isEqualTo("厢式货车");
        assertThat(dto.getCapacityCbm()).isEqualByComparingTo("10.000"); // 原值保留
        assertThat(dto.getSource()).isEqualTo(OwnershipType.OWNED); // 原值保留
    }

    @Test
    @DisplayName("updateVehicleProfile — version 不匹配 → 409")
    void updateVehicleProfileVersionConflict() {
        Vehicle v1 = vehicle("V5", "苏E33333");
        LogisticsVehicleProfile existing = profile("V5", 5L);
        when(vehicleRepo.findByIdAndFactoryId("V5", F1)).thenReturn(Optional.of(v1));
        when(vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull("V5")).thenReturn(Optional.of(existing));

        VehicleProfileUpdateRequest req = new VehicleProfileUpdateRequest();
        req.setVersion(4L); // 客户端 stale version

        assertThatThrownBy(() -> service.updateVehicleProfile(F1, "V5", req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);
        verify(vehicleProfileRepo, never()).save(any());
    }

    @Test
    @DisplayName("updateVehicleProfile — 车辆不存在 → 404")
    void updateVehicleProfileVehicleNotFound() {
        when(vehicleRepo.findByIdAndFactoryId("VX", F1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateVehicleProfile(F1, "VX", new VehicleProfileUpdateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== drivers ====================

    @Test
    @DisplayName("createDriver — 姓名为空 → 400")
    void createDriverRequiresName() {
        DriverInputRequest req = new DriverInputRequest();
        assertThatThrownBy(() -> service.createDriver(F1, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("姓名必填");
    }

    @Test
    @DisplayName("createDriver — 成功创建, 默认 OWNED + active")
    void createDriverSuccess() {
        when(driverRepo.save(any(LogisticsDriver.class))).thenAnswer(inv -> {
            LogisticsDriver d = inv.getArgument(0);
            d.setId("D-new");
            d.setVersion(0L);
            return d;
        });
        DriverInputRequest req = new DriverInputRequest();
        req.setName("李四");
        req.setServiceAreas(List.of("区域C"));

        LogisticsDriverDto dto = service.createDriver(F1, req);

        assertThat(dto.getName()).isEqualTo("李四");
        assertThat(dto.getEmploymentType()).isEqualTo(OwnershipType.OWNED);
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.getServiceAreas()).containsExactly("区域C");
    }

    @Test
    @DisplayName("updateDriver — version 不匹配 → 409")
    void updateDriverVersionConflict() {
        LogisticsDriver existing = LogisticsDriver.builder()
                .id("D2").factoryId(F1).name("王五").employmentType(OwnershipType.OWNED).active(true).build();
        existing.setVersion(2L);
        when(driverRepo.findByIdAndFactoryId("D2", F1)).thenReturn(Optional.of(existing));

        DriverInputRequest req = new DriverInputRequest();
        req.setVersion(1L);

        assertThatThrownBy(() -> service.updateDriver(F1, "D2", req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(409);
        verify(driverRepo, never()).save(any());
    }

    @Test
    @DisplayName("updateDriver — 司机不存在 → 404")
    void updateDriverNotFound() {
        when(driverRepo.findByIdAndFactoryId("DX", F1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateDriver(F1, "DX", new DriverInputRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== setVehicleDrivers ====================

    @Test
    @DisplayName("setVehicleDrivers — 替换绑定集合 (旧绑定软删除, 新绑定落库)")
    void setVehicleDriversReplacesBindings() {
        Vehicle v1 = vehicle("V6", "苏F44444");
        when(vehicleRepo.findByIdAndFactoryId("V6", F1)).thenReturn(Optional.of(v1));

        LogisticsDriver d1 = LogisticsDriver.builder().id("D3").factoryId(F1).name("赵六")
                .employmentType(OwnershipType.OWNED).active(true).build();
        LogisticsDriver d2 = LogisticsDriver.builder().id("D4").factoryId(F1).name("孙七")
                .employmentType(OwnershipType.OWNED).active(true).build();
        when(driverRepo.findByIdAndFactoryId("D3", F1)).thenReturn(Optional.of(d1));
        when(driverRepo.findByIdAndFactoryId("D4", F1)).thenReturn(Optional.of(d2));

        LogisticsVehicleDriver oldBinding = LogisticsVehicleDriver.builder()
                .id("old-b").factoryId(F1).vehicleId("V6").driverId("D-old")
                .role(DriverRole.PRIMARY).priority(0).active(true).build();
        when(vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull("V6")).thenReturn(List.of(oldBinding));
        when(vehicleDriverRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull("V6")).thenReturn(Optional.empty());

        List<VehicleDriverBindingDto> newBindings = List.of(
                VehicleDriverBindingDto.builder().driverId("D3").role(DriverRole.PRIMARY).shiftStart("08:00").build(),
                VehicleDriverBindingDto.builder().driverId("D4").role(DriverRole.BACKUP).shiftStart("20:00").build());

        LogisticsVehicleDto dto = service.setVehicleDrivers(F1, "V6", newBindings);

        verify(vehicleDriverRepo).deleteAll(List.of(oldBinding));
        verify(vehicleDriverRepo).saveAll(anyList());
        assertThat(dto.getDrivers()).hasSize(2);
        assertThat(dto.getDrivers()).extracting("driverName").containsExactlyInAnyOrder("赵六", "孙七");
    }

    @Test
    @DisplayName("setVehicleDrivers — 未知 driverId → 400, 不落库")
    void setVehicleDriversUnknownDriverRejected() {
        Vehicle v1 = vehicle("V7", "苏G55555");
        when(vehicleRepo.findByIdAndFactoryId("V7", F1)).thenReturn(Optional.of(v1));
        when(driverRepo.findByIdAndFactoryId("D-unknown", F1)).thenReturn(Optional.empty());

        List<VehicleDriverBindingDto> bindings = List.of(
                VehicleDriverBindingDto.builder().driverId("D-unknown").build());

        assertThatThrownBy(() -> service.setVehicleDrivers(F1, "V7", bindings))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("司机不存在");
        verify(vehicleDriverRepo, never()).deleteAll(anyList());
        verify(vehicleDriverRepo, never()).saveAll(anyList());
    }
}
