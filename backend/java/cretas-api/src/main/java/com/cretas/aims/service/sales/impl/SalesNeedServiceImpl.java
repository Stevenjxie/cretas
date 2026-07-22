package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest.SalesOrderItemDTO;
import com.cretas.aims.dto.sales.SalesNeedCreateRequest;
import com.cretas.aims.dto.sales.SalesNeedUpdateRequest;
import com.cretas.aims.entity.enums.SalesNeedPriority;
import com.cretas.aims.entity.enums.SalesNeedStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.sales.SalesNeed;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.sales.SalesNeedRepository;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.sales.SalesNeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint 4 W2 S-NEED-1 — 销售需求实现.
 *
 * <p>状态机:
 * <pre>
 * DRAFT --confirm--> CONFIRMED --convertToSalesOrder--> CONVERTED_TO_SO
 * DRAFT/CONFIRMED --cancel--> CANCELLED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesNeedServiceImpl implements SalesNeedService {

    private final SalesNeedRepository repository;
    private final SalesService salesService;

    @Override
    @Transactional
    public SalesNeed create(String factoryId, SalesNeedCreateRequest req, Long userId) {
        SalesNeed need = new SalesNeed();
        need.setFactoryId(factoryId);
        need.setCustomerId(req.getCustomerId());
        need.setCustomerName(req.getCustomerName());
        need.setProductId(req.getProductId());
        need.setProductName(req.getProductName());
        need.setQtyDemand(req.getQtyDemand());
        need.setUnit(req.getUnit());
        need.setExpectedDeliveryDate(req.getExpectedDeliveryDate());
        need.setPriority(req.getPriority() != null ? req.getPriority() : SalesNeedPriority.NORMAL);
        need.setRemark(req.getRemark());
        need.setStatus(SalesNeedStatus.DRAFT);
        need.setCreatedBy(userId);
        return repository.save(need);
    }

    @Override
    @Transactional
    public SalesNeed update(String factoryId, String id, SalesNeedUpdateRequest req) {
        SalesNeed need = loadOrThrow(factoryId, id);
        if (need.getStatus() != SalesNeedStatus.DRAFT) {
            throw new BusinessException(400, "只能编辑 DRAFT 状态的需求");
        }
        if (req.getQtyDemand() != null) need.setQtyDemand(req.getQtyDemand());
        if (req.getUnit() != null) need.setUnit(req.getUnit());
        if (req.getExpectedDeliveryDate() != null) {
            need.setExpectedDeliveryDate(req.getExpectedDeliveryDate());
        }
        if (req.getPriority() != null) need.setPriority(req.getPriority());
        if (req.getRemark() != null) need.setRemark(req.getRemark());
        return repository.save(need);
    }

    @Override
    public SalesNeed getById(String factoryId, String id) {
        return loadOrThrow(factoryId, id);
    }

    @Override
    public Page<SalesNeed> list(String factoryId, List<SalesNeedStatus> statuses,
                                 String customerId, Pageable pageable) {
        if (customerId != null && !customerId.isBlank()) {
            return repository.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    factoryId, customerId, pageable);
        }
        if (statuses != null && !statuses.isEmpty()) {
            return repository.findByFactoryIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                    factoryId, statuses, pageable);
        }
        return repository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, pageable);
    }

    @Override
    @Transactional
    public SalesNeed confirm(String factoryId, String id) {
        SalesNeed need = loadOrThrow(factoryId, id);
        if (need.getStatus() != SalesNeedStatus.DRAFT) {
            throw new BusinessException(400, "只有 DRAFT 状态可以确认");
        }
        need.setStatus(SalesNeedStatus.CONFIRMED);
        return repository.save(need);
    }

    @Override
    @Transactional
    public SalesOrder convertToSalesOrder(String factoryId, String id, Long userId) {
        SalesNeed need = loadOrThrow(factoryId, id);
        if (need.getStatus() != SalesNeedStatus.CONFIRMED) {
            throw new BusinessException(400, "只有 CONFIRMED 状态可以转销售订单 (当前: "
                    + need.getStatus() + ")");
        }
        if (need.getConvertedSalesOrderId() != null) {
            throw new BusinessException(400, "该需求已转为销售订单 " + need.getConvertedSalesOrderId());
        }

        // Build CreateSalesOrderRequest from need.
        CreateSalesOrderRequest req = new CreateSalesOrderRequest();
        req.setCustomerId(need.getCustomerId());
        req.setOrderDate(LocalDate.now());
        req.setRequiredDeliveryDate(need.getExpectedDeliveryDate());
        req.setRemark(buildSoRemark(need));

        SalesOrderItemDTO item = new SalesOrderItemDTO();
        item.setProductTypeId(need.getProductId());
        item.setProductName(need.getProductName());
        item.setQuantity(need.getQtyDemand());
        item.setUnit(need.getUnit());
        req.setItems(List.of(item));

        SalesOrder created = salesService.createSalesOrder(factoryId, req, userId);

        // Bind back.
        need.setConvertedSalesOrderId(created.getId());
        need.setStatus(SalesNeedStatus.CONVERTED_TO_SO);
        repository.save(need);

        log.info("[S-NEED convert] need={} → SO={}", need.getId(), created.getId());
        return created;
    }

    @Override
    @Transactional
    public SalesNeed cancel(String factoryId, String id) {
        SalesNeed need = loadOrThrow(factoryId, id);
        if (need.getStatus() == SalesNeedStatus.CONVERTED_TO_SO) {
            throw new BusinessException(400, "已转为销售订单的需求不能取消, 请操作销售订单");
        }
        if (need.getStatus() == SalesNeedStatus.CANCELLED) {
            return need;
        }
        need.setStatus(SalesNeedStatus.CANCELLED);
        return repository.save(need);
    }

    private SalesNeed loadOrThrow(String factoryId, String id) {
        return repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "客户需求不存在"));
    }

    private String buildSoRemark(SalesNeed need) {
        StringBuilder sb = new StringBuilder();
        sb.append("由客户需求 ").append(need.getId()).append(" 转换生成");
        if (need.getRemark() != null && !need.getRemark().isBlank()) {
            sb.append(". 需求备注: ").append(need.getRemark());
        }
        return sb.toString();
    }
}
