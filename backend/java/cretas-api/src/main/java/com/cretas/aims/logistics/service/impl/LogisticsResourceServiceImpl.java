package com.cretas.aims.logistics.service.impl;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.resource.DailyAvailabilityDto;
import com.cretas.aims.logistics.dto.resource.DailyAvailabilityUpsertRequest;
import com.cretas.aims.logistics.dto.resource.DriverInputRequest;
import com.cretas.aims.logistics.dto.resource.LogisticsDriverDto;
import com.cretas.aims.logistics.dto.resource.LogisticsVehicleDto;
import com.cretas.aims.logistics.dto.resource.VehicleDriverBindingDto;
import com.cretas.aims.logistics.dto.resource.VehicleProfileUpdateRequest;
import com.cretas.aims.logistics.entity.LogisticsDailyAvailability;
import com.cretas.aims.logistics.entity.LogisticsDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleDriver;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.AvailabilityResourceType;
import com.cretas.aims.logistics.entity.enums.DriverRole;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
import com.cretas.aims.logistics.repository.LogisticsDailyAvailabilityRepository;
import com.cretas.aims.logistics.repository.LogisticsDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleDriverRepository;
import com.cretas.aims.logistics.repository.LogisticsVehicleProfileRepository;
import com.cretas.aims.logistics.service.LogisticsResourceService;
import com.cretas.aims.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 物流资源管理实现 (handoff §11.2, spec §2 决策 5)。
 *
 * <p>{@code serviceAreas} 在 DB 是逗号分隔的 {@code VARCHAR(512)}（{@code List<String>} ⇄
 * {@code String} 转换见 {@link #splitCsv} / {@link #joinCsv}）— MVP 阶段不需要专门的关联表，
 * 前端/客户区域数量有限（个位数到十几个），逗号分隔足够且省一次 join。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsResourceServiceImpl implements LogisticsResourceService {

    private final VehicleRepository vehicleRepo;
    private final LogisticsVehicleProfileRepository vehicleProfileRepo;
    private final LogisticsDriverRepository driverRepo;
    private final LogisticsVehicleDriverRepository vehicleDriverRepo;
    private final LogisticsDailyAvailabilityRepository dailyAvailabilityRepo;

    // ==================== 车辆 ====================

    @Override
    public List<LogisticsVehicleDto> listVehicles(String factoryId) {
        List<Vehicle> vehicles = vehicleRepo.findByFactoryId(factoryId);
        if (vehicles.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, LogisticsVehicleProfile> profileByVehicleId = new HashMap<>();
        for (LogisticsVehicleProfile p : vehicleProfileRepo.findByFactoryId(factoryId)) {
            profileByVehicleId.put(p.getVehicleId(), p);
        }

        Map<String, List<LogisticsVehicleDriver>> bindingsByVehicleId = new HashMap<>();
        for (LogisticsVehicleDriver b : vehicleDriverRepo.findByFactoryIdAndDeletedAtIsNull(factoryId)) {
            bindingsByVehicleId.computeIfAbsent(b.getVehicleId(), k -> new ArrayList<>()).add(b);
        }

        Map<String, String> driverNameById = new HashMap<>();
        for (LogisticsDriver d : driverRepo.findByFactoryIdOrderByNameAsc(factoryId)) {
            driverNameById.put(d.getId(), d.getName());
        }

        List<LogisticsVehicleDto> result = new ArrayList<>(vehicles.size());
        for (Vehicle vehicle : vehicles) {
            LogisticsVehicleProfile profile = profileByVehicleId.get(vehicle.getId());
            List<LogisticsVehicleDriver> bindings =
                    bindingsByVehicleId.getOrDefault(vehicle.getId(), Collections.emptyList());
            result.add(toVehicleDto(vehicle, profile, bindings, driverNameById));
        }
        return result;
    }

    @Override
    @Transactional
    public LogisticsVehicleDto updateVehicleProfile(String factoryId, String vehicleId, VehicleProfileUpdateRequest request) {
        Vehicle vehicle = vehicleRepo.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "车辆不存在: " + vehicleId)
                        .withHint("请检查车辆 ID 是否正确"));

        Optional<LogisticsVehicleProfile> existing = vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull(vehicleId);
        LogisticsVehicleProfile profile;
        if (existing.isPresent()) {
            profile = existing.get();
            if (request.getVersion() != null && !request.getVersion().equals(profile.getVersion())) {
                throw new BusinessException(409, "车辆配置已被其他人修改，请刷新后重试")
                        .withCode("VERSION_CONFLICT")
                        .withHint("刷新页面获取最新数据后重试");
            }
        } else {
            profile = LogisticsVehicleProfile.builder()
                    .vehicleId(vehicleId)
                    .factoryId(factoryId)
                    .build();
        }

        if (request.getCapacityCbm() != null) profile.setCapacityCbm(request.getCapacityCbm());
        if (request.getMaxWeightKg() != null) profile.setMaxWeightKg(request.getMaxWeightKg());
        if (request.getSource() != null) profile.setSource(request.getSource());
        if (request.getBodyType() != null) profile.setBodyType(request.getBodyType());
        if (request.getServiceAreas() != null) profile.setServiceAreas(joinCsv(request.getServiceAreas()));
        if (request.getAvailableFrom() != null) profile.setAvailableFrom(request.getAvailableFrom());
        if (request.getAvailableTo() != null) profile.setAvailableTo(request.getAvailableTo());
        if (request.getActive() != null) profile.setActive(request.getActive());

        LogisticsVehicleProfile saved = vehicleProfileRepo.save(profile);
        List<LogisticsVehicleDriver> bindings = vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull(vehicleId);
        Map<String, String> driverNameById = driverNameMap(factoryId, bindings);
        return toVehicleDto(vehicle, saved, bindings, driverNameById);
    }

    // ==================== 司机 ====================

    @Override
    public List<LogisticsDriverDto> listDrivers(String factoryId) {
        List<LogisticsDriverDto> result = new ArrayList<>();
        for (LogisticsDriver d : driverRepo.findByFactoryIdOrderByNameAsc(factoryId)) {
            result.add(toDriverDto(d));
        }
        return result;
    }

    @Override
    @Transactional
    public LogisticsDriverDto createDriver(String factoryId, DriverInputRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(400, "司机姓名必填").withHintTarget("name");
        }
        LogisticsDriver driver = LogisticsDriver.builder()
                .factoryId(factoryId)
                .name(request.getName().trim())
                .phone(request.getPhone())
                .employmentType(request.getEmploymentType() != null ? request.getEmploymentType() : OwnershipType.OWNED)
                .serviceAreas(joinCsv(request.getServiceAreas()))
                .availableFrom(request.getAvailableFrom())
                .availableTo(request.getAvailableTo())
                .active(request.getActive() != null ? request.getActive() : Boolean.TRUE)
                .build();
        LogisticsDriver saved = driverRepo.save(driver);
        return toDriverDto(saved);
    }

    @Override
    @Transactional
    public LogisticsDriverDto updateDriver(String factoryId, String driverId, DriverInputRequest request) {
        LogisticsDriver driver = driverRepo.findByIdAndFactoryId(driverId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "司机不存在: " + driverId)
                        .withHint("请检查司机 ID 是否正确"));
        if (request.getVersion() != null && !request.getVersion().equals(driver.getVersion())) {
            throw new BusinessException(409, "司机信息已被其他人修改，请刷新后重试")
                    .withCode("VERSION_CONFLICT")
                    .withHint("刷新页面获取最新数据后重试");
        }

        if (request.getName() != null && !request.getName().isBlank()) driver.setName(request.getName().trim());
        if (request.getPhone() != null) driver.setPhone(request.getPhone());
        if (request.getEmploymentType() != null) driver.setEmploymentType(request.getEmploymentType());
        if (request.getServiceAreas() != null) driver.setServiceAreas(joinCsv(request.getServiceAreas()));
        if (request.getAvailableFrom() != null) driver.setAvailableFrom(request.getAvailableFrom());
        if (request.getAvailableTo() != null) driver.setAvailableTo(request.getAvailableTo());
        if (request.getActive() != null) driver.setActive(request.getActive());

        LogisticsDriver saved = driverRepo.save(driver);
        return toDriverDto(saved);
    }

    // ==================== 车辆-司机绑定 ====================

    @Override
    @Transactional
    public LogisticsVehicleDto setVehicleDrivers(String factoryId, String vehicleId, List<VehicleDriverBindingDto> bindings) {
        Vehicle vehicle = vehicleRepo.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "车辆不存在: " + vehicleId)
                        .withHint("请检查车辆 ID 是否正确"));

        List<VehicleDriverBindingDto> safeBindings = bindings != null ? bindings : Collections.emptyList();

        // 校验所有 driverId 存在且属于该工厂 —— 先全部校验完再落库，避免部分失败留下不一致绑定
        for (VehicleDriverBindingDto b : safeBindings) {
            if (b.getDriverId() == null || b.getDriverId().isBlank()) {
                throw new BusinessException(400, "绑定项缺少司机 ID").withHintTarget("driverId");
            }
            driverRepo.findByIdAndFactoryId(b.getDriverId(), factoryId)
                    .orElseThrow(() -> new BusinessException(400, "司机不存在: " + b.getDriverId())
                            .withHint("请先在司机列表中新建该司机"));
        }

        List<LogisticsVehicleDriver> existing = vehicleDriverRepo.findByVehicleIdAndDeletedAtIsNull(vehicleId);
        if (!existing.isEmpty()) {
            vehicleDriverRepo.deleteAll(existing); // @SQLDelete → 软删除
        }

        List<LogisticsVehicleDriver> toSave = new ArrayList<>(safeBindings.size());
        for (VehicleDriverBindingDto b : safeBindings) {
            toSave.add(LogisticsVehicleDriver.builder()
                    .factoryId(factoryId)
                    .vehicleId(vehicleId)
                    .driverId(b.getDriverId())
                    .role(b.getRole() != null ? b.getRole() : DriverRole.PRIMARY)
                    .shiftStart(b.getShiftStart())
                    .shiftEnd(b.getShiftEnd())
                    .priority(b.getPriority() != null ? b.getPriority() : 0)
                    .active(true)
                    .build());
        }
        List<LogisticsVehicleDriver> saved = toSave.isEmpty()
                ? Collections.emptyList()
                : vehicleDriverRepo.saveAll(toSave);

        LogisticsVehicleProfile profile = vehicleProfileRepo.findByVehicleIdAndDeletedAtIsNull(vehicleId).orElse(null);
        Map<String, String> driverNameById = driverNameMap(factoryId, saved);
        return toVehicleDto(vehicle, profile, saved, driverNameById);
    }

    // ==================== 按日可用性覆盖 ====================

    @Override
    public List<DailyAvailabilityDto> listDailyAvailability(String factoryId, LocalDate date) {
        List<DailyAvailabilityDto> result = new ArrayList<>();
        for (LogisticsDailyAvailability r : dailyAvailabilityRepo.findByFactoryIdAndAvailDateAndDeletedAtIsNull(factoryId, date)) {
            result.add(toDailyAvailabilityDto(r));
        }
        return result;
    }

    @Override
    @Transactional
    public DailyAvailabilityDto upsertDailyAvailability(String factoryId, DailyAvailabilityUpsertRequest request) {
        if (request.getResourceType() == null) {
            throw new BusinessException(400, "资源类型必填 (DRIVER/VEHICLE)").withHintTarget("resourceType");
        }
        if (request.getResourceId() == null || request.getResourceId().isBlank()) {
            throw new BusinessException(400, "资源 ID 必填").withHintTarget("resourceId");
        }
        if (request.getAvailDate() == null) {
            throw new BusinessException(400, "日期必填").withHintTarget("availDate");
        }

        // 校验资源存在且属于该工厂 —— 防呆: 不允许给不存在的司机/车辆挂可用性覆盖 (fool-proof-design Rule 1)
        if (request.getResourceType() == AvailabilityResourceType.DRIVER) {
            driverRepo.findByIdAndFactoryId(request.getResourceId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "司机不存在: " + request.getResourceId())
                            .withHint("请检查司机 ID 是否正确"));
        } else {
            vehicleRepo.findByIdAndFactoryId(request.getResourceId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "车辆不存在: " + request.getResourceId())
                            .withHint("请检查车辆 ID 是否正确"));
        }

        LogisticsDailyAvailability record = dailyAvailabilityRepo
                .findByFactoryIdAndResourceTypeAndResourceIdAndAvailDateAndDeletedAtIsNull(
                        factoryId, request.getResourceType(), request.getResourceId(), request.getAvailDate())
                .orElseGet(() -> LogisticsDailyAvailability.builder()
                        .factoryId(factoryId)
                        .resourceType(request.getResourceType())
                        .resourceId(request.getResourceId())
                        .availDate(request.getAvailDate())
                        .build());

        // 完整状态替换 (幂等 PUT 语义, 见 DailyAvailabilityUpsertRequest 类注释) —
        // available 为 null 时默认可用 (等价"只覆盖班次不标记不可用"场景)。
        record.setAvailable(request.getAvailable() != null ? request.getAvailable() : Boolean.TRUE);
        record.setShiftStart(request.getShiftStart());
        record.setShiftEnd(request.getShiftEnd());

        LogisticsDailyAvailability saved = dailyAvailabilityRepo.save(record);
        return toDailyAvailabilityDto(saved);
    }

    @Override
    @Transactional
    public void deleteDailyAvailability(String factoryId, String id) {
        LogisticsDailyAvailability record = dailyAvailabilityRepo.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "可用性覆盖记录不存在: " + id)
                        .withHint("请检查记录 ID 是否正确"));
        dailyAvailabilityRepo.delete(record); // @SQLDelete → 软删除
    }

    private DailyAvailabilityDto toDailyAvailabilityDto(LogisticsDailyAvailability r) {
        return DailyAvailabilityDto.builder()
                .id(r.getId())
                .resourceType(r.getResourceType())
                .resourceId(r.getResourceId())
                .availDate(r.getAvailDate())
                .available(Boolean.TRUE.equals(r.getAvailable()))
                .shiftStart(r.getShiftStart())
                .shiftEnd(r.getShiftEnd())
                .version(r.getVersion())
                .build();
    }

    // ==================== Helpers ====================

    private Map<String, String> driverNameMap(String factoryId, List<LogisticsVehicleDriver> bindings) {
        if (bindings.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        for (LogisticsVehicleDriver b : bindings) {
            driverRepo.findByIdAndFactoryId(b.getDriverId(), factoryId)
                    .ifPresent(d -> map.put(d.getId(), d.getName()));
        }
        return map;
    }

    private LogisticsVehicleDto toVehicleDto(Vehicle vehicle, LogisticsVehicleProfile profile,
            List<LogisticsVehicleDriver> bindings, Map<String, String> driverNameById) {
        List<VehicleDriverBindingDto> driverDtos = new ArrayList<>(bindings.size());
        for (LogisticsVehicleDriver b : bindings) {
            driverDtos.add(VehicleDriverBindingDto.builder()
                    .driverId(b.getDriverId())
                    .driverName(driverNameById.get(b.getDriverId()))
                    .role(b.getRole())
                    .shiftStart(b.getShiftStart())
                    .shiftEnd(b.getShiftEnd())
                    .priority(b.getPriority())
                    .build());
        }

        boolean hasProfile = profile != null;
        return LogisticsVehicleDto.builder()
                .id(vehicle.getId())
                .plateNumber(vehicle.getPlateNumber())
                .driverName(vehicle.getDriverName())
                .driverPhone(vehicle.getDriverPhone())
                .capacityCbm(hasProfile ? profile.getCapacityCbm() : BigDecimal.ZERO)
                // 未配置物流 profile 时, maxWeightKg 兜底用现有 Vehicle.capacity(kg 载重语义)
                // 作为初始值展示, 首次 PUT .../profile 会落地专属记录 (spec §1)
                .maxWeightKg(hasProfile ? profile.getMaxWeightKg()
                        : (vehicle.getCapacity() != null ? vehicle.getCapacity() : BigDecimal.ZERO))
                .source(hasProfile ? profile.getSource() : OwnershipType.OWNED)
                .bodyType(hasProfile ? profile.getBodyType() : vehicle.getVehicleType())
                .temperatureMode(hasProfile ? profile.getTemperatureMode() : TemperatureMode.DUAL_TEMP)
                .serviceAreas(hasProfile ? splitCsv(profile.getServiceAreas()) : Collections.emptyList())
                .availableFrom(hasProfile ? profile.getAvailableFrom() : null)
                .availableTo(hasProfile ? profile.getAvailableTo() : null)
                .active(hasProfile ? Boolean.TRUE.equals(profile.getActive()) : true)
                .drivers(driverDtos)
                .version(hasProfile ? profile.getVersion() : 0L)
                .build();
    }

    private LogisticsDriverDto toDriverDto(LogisticsDriver d) {
        return LogisticsDriverDto.builder()
                .id(d.getId())
                .factoryId(d.getFactoryId())
                .name(d.getName())
                .phone(d.getPhone())
                .employmentType(d.getEmploymentType())
                .serviceAreas(splitCsv(d.getServiceAreas()))
                .availableFrom(d.getAvailableFrom())
                .availableTo(d.getAvailableTo())
                .active(Boolean.TRUE.equals(d.getActive()))
                .version(d.getVersion())
                .build();
    }

    private static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String joinCsv(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                cleaned.add(s.trim());
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }
}
