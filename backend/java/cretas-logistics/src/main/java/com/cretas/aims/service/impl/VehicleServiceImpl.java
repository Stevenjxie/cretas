package com.cretas.aims.service.impl;

import com.cretas.aims.dto.warehouse.VehicleDTO;
import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.VehicleRepository;
import com.cretas.aims.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 车辆管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;

    @Override
    public List<VehicleDTO> getVehicles(String factoryId) {
        log.info("获取工厂所有车辆: factoryId={}", factoryId);
        return vehicleRepository.findByFactoryId(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<VehicleDTO> getAvailableVehicles(String factoryId) {
        log.info("获取可用车辆: factoryId={}", factoryId);
        return vehicleRepository.findAvailableByFactoryId(factoryId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<VehicleDTO> getVehiclesByStatus(String factoryId, String status) {
        log.info("按状态获取车辆: factoryId={}, status={}", factoryId, status);
        try {
            Vehicle.VehicleStatus vehicleStatus = Vehicle.VehicleStatus.valueOf(status.toLowerCase());
            return vehicleRepository.findByFactoryIdAndStatus(factoryId, vehicleStatus)
                    .stream()
                    .map(this::toDTO)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            log.warn("无效的车辆状态: {}", status);
            return List.of();
        }
    }

    @Override
    public VehicleDTO getVehicle(String factoryId, String vehicleId) {
        log.info("获取车辆详情: factoryId={}, vehicleId={}", factoryId, vehicleId);
        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));
        return toDTO(vehicle);
    }

    @Override
    @Transactional
    public VehicleDTO createVehicle(String factoryId, VehicleDTO request) {
        log.info("创建车辆: factoryId={}, plateNumber={}", factoryId, request.getPlateNumber());

        // 检查车牌号是否已存在
        if (vehicleRepository.findByFactoryIdAndPlateNumber(factoryId, request.getPlateNumber()).isPresent()) {
            throw new BusinessException(409, "车牌号已存在: " + request.getPlateNumber())
                    .withHint("请使用其他车牌号, 或编辑现有车辆")
                    .withHintTarget("plateNumber");
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setFactoryId(factoryId);
        vehicle.setPlateNumber(request.getPlateNumber());
        vehicle.setDriverName(request.getDriver());
        vehicle.setDriverPhone(request.getPhone());
        vehicle.setCapacity(request.getCapacity());
        vehicle.setCurrentLoad(request.getCurrentLoad() != null ? request.getCurrentLoad() : BigDecimal.ZERO);
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setNotes(request.getNotes());
        vehicle.setStatus(Vehicle.VehicleStatus.available);

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆创建成功: id={}", saved.getId());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public VehicleDTO updateVehicle(String factoryId, String vehicleId, VehicleDTO request) {
        log.info("更新车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        // 如果修改了车牌号，检查是否重复
        if (request.getPlateNumber() != null && !request.getPlateNumber().equals(vehicle.getPlateNumber())) {
            if (vehicleRepository.findByFactoryIdAndPlateNumber(factoryId, request.getPlateNumber()).isPresent()) {
                throw new BusinessException(409, "车牌号已存在: " + request.getPlateNumber())
                        .withHint("请使用其他车牌号")
                        .withHintTarget("plateNumber");
            }
            vehicle.setPlateNumber(request.getPlateNumber());
        }

        if (request.getDriver() != null) {
            vehicle.setDriverName(request.getDriver());
        }
        if (request.getPhone() != null) {
            vehicle.setDriverPhone(request.getPhone());
        }
        if (request.getCapacity() != null) {
            vehicle.setCapacity(request.getCapacity());
        }
        if (request.getVehicleType() != null) {
            vehicle.setVehicleType(request.getVehicleType());
        }
        if (request.getNotes() != null) {
            vehicle.setNotes(request.getNotes());
        }

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆更新成功: id={}", saved.getId());
        return toDTO(saved);
    }

    @Override
    @Transactional
    public VehicleDTO updateVehicleStatus(String factoryId, String vehicleId, String status) {
        log.info("更新车辆状态: factoryId={}, vehicleId={}, status={}", factoryId, vehicleId, status);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        try {
            Vehicle.VehicleStatus newStatus = Vehicle.VehicleStatus.valueOf(status.toLowerCase());
            vehicle.setStatus(newStatus);

            // 如果状态变为可用，清空当前装载量
            if (newStatus == Vehicle.VehicleStatus.available) {
                vehicle.setCurrentLoad(BigDecimal.ZERO);
            }

            Vehicle saved = vehicleRepository.save(vehicle);
            log.info("车辆状态更新成功: id={}, status={}", saved.getId(), newStatus);
            return toDTO(saved);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的车辆状态: " + status)
                    .withHint("请使用合法的状态值: available / loading / in_use / maintenance / retired")
                    .withHintTarget("status");
        }
    }

    @Override
    @Transactional
    public VehicleDTO updateCurrentLoad(String factoryId, String vehicleId, BigDecimal load) {
        log.info("更新车辆装载量: factoryId={}, vehicleId={}, load={}", factoryId, vehicleId, load);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        vehicle.setCurrentLoad(load != null ? load : BigDecimal.ZERO);

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("车辆装载量更新成功: id={}, currentLoad={}", saved.getId(), load);
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void deleteVehicle(String factoryId, String vehicleId) {
        log.info("删除车辆: factoryId={}, vehicleId={}", factoryId, vehicleId);

        Vehicle vehicle = vehicleRepository.findByIdAndFactoryId(vehicleId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("车辆不存在: " + vehicleId));

        // 软删除
        vehicle.setDeletedAt(LocalDateTime.now());
        vehicleRepository.save(vehicle);
        log.info("车辆删除成功（软删除）: id={}", vehicleId);
    }

    /**
     * 实体转DTO
     */
    private VehicleDTO toDTO(Vehicle vehicle) {
        return VehicleDTO.builder()
                .id(vehicle.getId())
                .factoryId(vehicle.getFactoryId())
                .plateNumber(vehicle.getPlateNumber())
                .driver(vehicle.getDriverName())
                .phone(vehicle.getDriverPhone())
                .capacity(vehicle.getCapacity())
                .currentLoad(vehicle.getCurrentLoad())
                .status(vehicle.getStatus() != null ? vehicle.getStatus().name() : "available")
                .vehicleType(vehicle.getVehicleType())
                .notes(vehicle.getNotes())
                .createdAt(vehicle.getCreatedAt())
                .updatedAt(vehicle.getUpdatedAt())
                .build();
    }
}
