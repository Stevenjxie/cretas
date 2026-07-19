package com.cretas.aims.service;

import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 出货记录服务层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class ShipmentRecordService {

    private final ShipmentRecordRepository shipmentRecordRepository;

    /**
     * 根据ID获取出货记录
     */
    public Optional<ShipmentRecord> getById(String id) {
        return shipmentRecordRepository.findById(id);
    }

    /**
     * 根据出货单号获取
     */
    public Optional<ShipmentRecord> getByShipmentNumber(String shipmentNumber) {
        return shipmentRecordRepository.findByShipmentNumber(shipmentNumber);
    }

    /**
     * 分页查询工厂出货记录
     */
    public Page<ShipmentRecord> getByFactoryId(String factoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return shipmentRecordRepository.findByFactoryIdOrderByShipmentDateDesc(factoryId, pageable);
    }

    /**
     * 按状态分页查询
     */
    public Page<ShipmentRecord> getByFactoryIdAndStatus(String factoryId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return shipmentRecordRepository.findByFactoryIdAndStatusOrderByShipmentDateDesc(factoryId, status, pageable);
    }

    /**
     * 按客户查询
     */
    public List<ShipmentRecord> getByCustomer(String factoryId, String customerId) {
        return shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(factoryId, customerId);
    }

    /**
     * 按日期范围查询
     */
    public List<ShipmentRecord> getByDateRange(String factoryId, LocalDate startDate, LocalDate endDate) {
        return shipmentRecordRepository.findByFactoryIdAndDateRange(factoryId, startDate, endDate);
    }

    /**
     * 根据物流单号查询
     */
    public Optional<ShipmentRecord> getByTrackingNumber(String trackingNumber) {
        return shipmentRecordRepository.findByTrackingNumber(trackingNumber);
    }

    /**
     * 统计出货数量
     */
    public long countByFactoryId(String factoryId) {
        return shipmentRecordRepository.countByFactoryId(factoryId);
    }

    /**
     * 统计指定状态的出货数量
     */
    public long countByStatus(String factoryId, String status) {
        return shipmentRecordRepository.countByFactoryIdAndStatus(factoryId, status);
    }

    /**
     * 根据ID和工厂ID获取出货记录（工厂隔离）
     */
    public Optional<ShipmentRecord> getByIdAndFactoryId(String id, String factoryId) {
        return shipmentRecordRepository.findByIdAndFactoryId(id, factoryId);
    }

    /**
     * 根据出货单号和工厂ID获取（工厂隔离）
     */
    public Optional<ShipmentRecord> getByShipmentNumberAndFactoryId(String shipmentNumber, String factoryId) {
        return shipmentRecordRepository.findByShipmentNumberAndFactoryId(shipmentNumber, factoryId);
    }

    /**
     * 根据物流单号和工厂ID获取（工厂隔离）
     */
    public Optional<ShipmentRecord> getByTrackingNumberAndFactoryId(String trackingNumber, String factoryId) {
        return shipmentRecordRepository.findByTrackingNumberAndFactoryId(trackingNumber, factoryId);
    }
}
