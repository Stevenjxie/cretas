package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a production-plan material shortfall into one reviewable purchase
 * requisition. It deliberately does not create a purchase order: supplier,
 * price, tax, specification and delivery terms must be confirmed by purchasing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcurementSuggestionService {

    static final String SOURCE_TYPE = "PRODUCTION_PLAN_SHORTAGE";
    private static final long SYSTEM_USER_ID = 0L;

    private final PurchaseRequisitionRepository requisitionRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductionPlanRepository productionPlanRepository;

    /**
     * Generate or return the unique shortage requisition for a production plan.
     * The plan row is locked so concurrent recalculation cannot create two
     * active demand records for the same source.
     */
    @Transactional
    public PurchaseRequisition generateSuggestions(String factoryId,
                                                    String productionPlanId,
                                                    List<MaterialShortfall> shortfalls) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            throw new BusinessException(400, "原辅料缺口列表为空，无需生成采购需求")
                    .withHint("请先确认生产计划存在实际缺口")
                    .withHintTarget("shortfalls");
        }

        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(productionPlanId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在，无法生成采购需求"));
        if (!factoryId.equals(plan.getFactoryId())) {
            throw new BusinessException(403, "生产计划不属于当前工厂");
        }
        if (plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            throw new BusinessException(409, "客供料不足不能自动转为工厂采购需求")
                    .withCode("CUSTOMER_SUPPLIED_SHORTAGE_PURCHASE_FORBIDDEN")
                    .withHint("请等待客户补充来料，或在销售订单中受控变更物料供应方式并保留审计")
                    .withHintTarget("materialSupplyMode");
        }

        return requisitionRepository
                .findByFactoryIdAndSourceTypeAndSourceId(factoryId, SOURCE_TYPE, productionPlanId)
                .orElseGet(() -> createRequisition(factoryId, plan, shortfalls));
    }

    private PurchaseRequisition createRequisition(String factoryId,
                                                   ProductionPlan plan,
                                                   List<MaterialShortfall> shortfalls) {
        List<Map<String, Object>> requestedItems = new ArrayList<>();
        for (MaterialShortfall shortfall : shortfalls) {
            if (shortfall == null || shortfall.getMaterialTypeId() == null
                    || shortfall.getShortfallQuantity() == null
                    || shortfall.getShortfallQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "采购需求包含无效的物料缺口")
                        .withHint("物料、缺口数量和单位必须完整，缺口数量必须大于0");
            }

            RawMaterialType material = rawMaterialTypeRepository.findById(shortfall.getMaterialTypeId())
                    .orElseThrow(() -> new BusinessException(400,
                            "缺口物料不存在: " + shortfall.getMaterialTypeId()));
            if (!factoryId.equals(material.getFactoryId())) {
                throw new BusinessException(403, "缺口物料不属于当前工厂");
            }
            if (material.getUnit() == null || material.getUnit().isBlank()) {
                throw new BusinessException(400, "缺口物料未配置库存基本单位: " + material.getName());
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialTypeId", material.getId());
            item.put("materialName", material.getName());
            item.put("requiredQuantity", shortfall.getRequiredQuantity());
            item.put("availableQuantity", shortfall.getAvailableQuantity());
            item.put("shortfallQuantity", shortfall.getShortfallQuantity());
            item.put("quantity", shortfall.getShortfallQuantity());
            item.put("unit", material.getUnit());
            item.put("sourceProductionPlanId", plan.getId());
            item.put("sourceSalesOrderId", plan.getSourceOrderId());
            item.put("sourceSalesOrderItemId", plan.getSourceOrderItemId());
            requestedItems.add(item);
        }

        PurchaseRequisition requisition = new PurchaseRequisition();
        requisition.setFactoryId(factoryId);
        requisition.setRequisitionNumber(generateRequisitionNumber(factoryId));
        requisition.setRequesterId(SYSTEM_USER_ID);
        requisition.setRequestedItems(requestedItems);
        requisition.setStatus(PurchaseRequisitionStatus.DRAFT);
        requisition.setExpectedDate(plan.getPlannedDate());
        requisition.setReason("生产计划缺料，待采购核对供应商、规格、价格、税率与交期");
        requisition.setRemark("来源生产计划: " + plan.getPlanNumber());
        requisition.setSourceType(SOURCE_TYPE);
        requisition.setSourceId(plan.getId());
        requisition.setSourceNo(plan.getPlanNumber());

        PurchaseRequisition saved = requisitionRepository.save(requisition);
        log.info("Created purchase requisition from material shortage: factoryId={}, planId={}, requisition={}, items={}",
                factoryId, plan.getId(), saved.getRequisitionNumber(), requestedItems.size());
        return saved;
    }

    private String generateRequisitionNumber(String factoryId) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long count = requisitionRepository.countByFactoryIdAndDate(factoryId, LocalDate.now());
        return String.format("PR-%s-%03d", today, count + 1);
    }
}
