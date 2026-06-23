package com.cretas.aims.service;

import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 出货记录服务层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentRecordService {

    private final ShipmentRecordRepository shipmentRecordRepository;
    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    @Transactional
    public ShipmentRecord createShipment(ShipmentRecord shipment) {
        if (validationRuleEvaluator != null && shipment.getFactoryId() != null) {
            try {
                validationRuleEvaluator.validate(shipment.getFactoryId(), "shipment", "CREATE",
                        java.util.Map.of("customerId", shipment.getCustomerId() != null ? shipment.getCustomerId() : ""));
            } catch (com.cretas.aims.exception.BusinessException e) { throw e; }
            catch (Exception e) { log.warn("Canvas validation non-blocking: {}", e.getMessage()); }
        }
        // 防呆 R4 (幂等防双击, edge-case 审计 2026-06-24): 60s 内同 工厂/客户/数量 的出货 → 409。
        // 仅当 factoryId/customerId/quantity 齐备时校验; 误拦仅"60s 内对同客户出完全相同数量"(双击)。
        if (shipment.getFactoryId() != null && shipment.getCustomerId() != null && shipment.getQuantity() != null) {
            java.util.List<ShipmentRecord> dupes = shipmentRecordRepository.findRecentDuplicateShipments(
                    shipment.getFactoryId(), shipment.getCustomerId(), shipment.getQuantity(),
                    java.time.LocalDateTime.now().minusSeconds(60));
            if (!dupes.isEmpty()) {
                ShipmentRecord existing = dupes.get(0);
                throw new com.cretas.aims.exception.BusinessException(409, String.format(
                        "60 秒内已对该客户创建相同数量出货 (%s), 如确为另一单请稍候再建",
                        existing.getShipmentNumber()))
                        .withHint("如需查看已有出货记录请打开 " + existing.getShipmentNumber())
                        .withHintTarget(existing.getId());
            }
        }
        if (shipment.getId() == null) {
            shipment.setId(UUID.randomUUID().toString());
        }
        if (shipment.getShipmentNumber() == null) {
            shipment.setShipmentNumber(generateShipmentNumber(shipment.getFactoryId()));
        }
        if (shipment.getUnitPrice() != null && shipment.getQuantity() != null) {
            shipment.setTotalAmount(shipment.getUnitPrice().multiply(shipment.getQuantity()));
        }
        if (shipment.getStatus() == null) {
            shipment.setStatus("pending");
        }
        ShipmentRecord saved = shipmentRecordRepository.save(shipment);
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.ShipmentCreatedEvent(
                    this, saved.getFactoryId(), saved.getId(), saved.getShipmentNumber(),
                    saved.getCustomerId(), saved.getOrderNumber(), saved.getQuantity(), saved.getTotalAmount()));
        } catch (Exception e) { log.warn("Publish ShipmentCreatedEvent failed: {}", e.getMessage()); }
        log.info("创建出货记录: {}", saved.getShipmentNumber());
        return saved;
    }

    /**
     * 更新出货记录
     */
    @Transactional
    public ShipmentRecord updateShipment(String id, ShipmentRecord updateData) {
        ShipmentRecord existing = shipmentRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("出货记录不存在: " + id));

        // 更新可修改字段
        if (updateData.getCustomerId() != null) {
            existing.setCustomerId(updateData.getCustomerId());
        }
        if (updateData.getOrderNumber() != null) {
            existing.setOrderNumber(updateData.getOrderNumber());
        }
        if (updateData.getProductName() != null) {
            existing.setProductName(updateData.getProductName());
        }
        if (updateData.getQuantity() != null) {
            existing.setQuantity(updateData.getQuantity());
        }
        if (updateData.getUnit() != null) {
            existing.setUnit(updateData.getUnit());
        }
        if (updateData.getUnitPrice() != null) {
            existing.setUnitPrice(updateData.getUnitPrice());
        }
        if (updateData.getShipmentDate() != null) {
            existing.setShipmentDate(updateData.getShipmentDate());
        }
        if (updateData.getDeliveryAddress() != null) {
            existing.setDeliveryAddress(updateData.getDeliveryAddress());
        }
        if (updateData.getLogisticsCompany() != null) {
            existing.setLogisticsCompany(updateData.getLogisticsCompany());
        }
        if (updateData.getTrackingNumber() != null) {
            existing.setTrackingNumber(updateData.getTrackingNumber());
        }
        if (updateData.getNotes() != null) {
            existing.setNotes(updateData.getNotes());
        }

        // 重新计算总金额
        if (existing.getUnitPrice() != null && existing.getQuantity() != null) {
            existing.setTotalAmount(existing.getUnitPrice().multiply(existing.getQuantity()));
        }

        log.info("更新出货记录: {}", existing.getShipmentNumber());
        return shipmentRecordRepository.save(existing);
    }

    /**
     * 更新出货状态
     */
    @Transactional
    public ShipmentRecord updateStatus(String id, String status) {
        ShipmentRecord shipment = shipmentRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("出货记录不存在: " + id));

        // 验证状态值
        if (!isValidStatus(status)) {
            throw new IllegalArgumentException("无效的状态值: " + status);
        }

        shipment.setStatus(status);
        log.info("更新出货状态: {} -> {}", shipment.getShipmentNumber(), status);
        return shipmentRecordRepository.save(shipment);
    }

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
     * 删除出货记录（软删除）
     */
    @Transactional
    public void deleteShipment(String id) {
        ShipmentRecord shipment = shipmentRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("出货记录不存在: " + id));
        // 使用软删除 - 将删除时间设置为当前时间
        shipment.softDelete();
        shipmentRecordRepository.save(shipment);
        log.info("删除出货记录: {}", shipment.getShipmentNumber());
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
     * 生成出货单号
     */
    private String generateShipmentNumber(String factoryId) {
        String dateStr = LocalDate.now().toString().replace("-", "");
        String randomStr = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "SH-" + factoryId + "-" + dateStr + "-" + randomStr;
    }

    /**
     * 验证状态值
     */
    private boolean isValidStatus(String status) {
        return status != null && (
            "pending".equals(status) ||
            "shipped".equals(status) ||
            "delivered".equals(status) ||
            "returned".equals(status) ||
            "cancelled".equals(status)
        );
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
