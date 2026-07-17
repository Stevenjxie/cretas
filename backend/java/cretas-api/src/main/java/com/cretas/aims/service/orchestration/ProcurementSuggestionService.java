package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购建议服务
 *
 * <p>根据BOM展开后发现的原辅料短缺，自动生成草稿状态的采购订单（一种短缺物料对应一张采购单）。
 * 生成的采购单处于 {@link PurchaseOrderStatus#DRAFT} 状态，供采购员补充供应商、单价等信息后提交。</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>自动生成的采购单中 {@code supplierId} 默认填充占位值 {@code "PENDING"}，
 *       采购员必须在提交前更新为真实供应商ID。</li>
 *   <li>{@code createdBy} 使用系统账号标识 {@code 0L}，可在后续流程中替换为实际操作人。</li>
 *   <li>单价默认为 0，需采购员填写。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class ProcurementSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ProcurementSuggestionService.class);

    /**
     * 自动生成采购单时使用的供应商ID占位值。
     * 采购员提交前必须替换为真实供应商。
     */
    private static final String SUPPLIER_PENDING = "PENDING";

    /** 系统自动生成使用的 createdBy 标识（0 = 系统账号） */
    private static final long SYSTEM_USER_ID = 0L;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * 根据原辅料短缺清单，为每种短缺物料生成一张草稿采购订单。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>验证短缺列表非空。</li>
     *   <li>为每种短缺物料创建一张 {@link PurchaseOrderStatus#DRAFT} 状态的采购单。</li>
     *   <li>从 {@link RawMaterialType} 中获取计量单位（若查询失败则默认为 "kg"）。</li>
     *   <li>先保存采购单主体（获得UUID），再保存行项目，最后更新金额。</li>
     * </ol>
     *
     * @param factoryId        工厂ID
     * @param productionPlanId 触发此建议的生产计划ID（写入备注，便于追溯）
     * @param shortfalls       原辅料短缺信息列表
     * @return 已持久化的草稿采购订单列表
     * @throws BusinessException 若 shortfalls 为空
     */
    @Transactional
    public List<PurchaseOrder> generateSuggestions(String factoryId,
                                                    String productionPlanId,
                                                    List<MaterialShortfall> shortfalls) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            throw new BusinessException(400, "原辅料短缺列表为空，无需生成采购建议")
                    .withHint("请先确认生产计划存在原辅料短缺").withHintTarget("shortfalls");
        }

        List<PurchaseOrder> suggestions = new ArrayList<>();
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (int i = 0; i < shortfalls.size(); i++) {
            MaterialShortfall sf = shortfalls.get(i);

            // ── 1. 构建采购订单主体 ──────────────────────────────────────────
            PurchaseOrder po = new PurchaseOrder();
            po.setFactoryId(factoryId);
            po.setOrderNumber("PO-AUTO-" + dateStr + "-" + (i + 1));
            po.setOrderDate(LocalDate.now());
            po.setStatus(PurchaseOrderStatus.DRAFT);
            po.setPurchaseType(PurchaseType.DIRECT);
            po.setRemark("系统自动生成 - 生产计划[" + productionPlanId + "]原辅料缺口采购建议");
            // supplierId 必填但未知，使用占位值；采购员提交前需更新
            po.setSupplierId(SUPPLIER_PENDING);
            po.setCreatedBy(SYSTEM_USER_ID);
            po.setTotalAmount(BigDecimal.ZERO);
            po.setTaxAmount(BigDecimal.ZERO);
            // @PrePersist 会自动生成 UUID
            PurchaseOrder savedPo = purchaseOrderRepository.save(po);

            // ── 2. 查询物料类型以获取计量单位 ───────────────────────────────
            String unit = "kg"; // 默认单位
            String materialName = sf.getMaterialTypeName();
            try {
                RawMaterialType mt = rawMaterialTypeRepository
                        .findById(sf.getMaterialTypeId())
                        .orElse(null);
                if (mt != null) {
                    if (mt.getUnit() != null && !mt.getUnit().isBlank()) {
                        unit = mt.getUnit();
                    }
                    if (mt.getName() != null && !mt.getName().isBlank()) {
                        materialName = mt.getName();
                    }
                }
            } catch (Exception e) {
                log.warn("采购建议: 查询物料类型失败，使用默认单位。materialTypeId={}, error={}",
                        sf.getMaterialTypeId(), e.getMessage());
            }

            // ── 3. 构建行项目 ────────────────────────────────────────────────
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(savedPo.getId());
            item.setMaterialTypeId(sf.getMaterialTypeId());
            item.setMaterialName(materialName);
            item.setQuantity(sf.getShortfallQuantity());
            item.setUnit(unit);
            item.setPriceUnit(unit);
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setUnitPrice(BigDecimal.ZERO); // 采购员填写实际单价
            item.setTaxRate(BigDecimal.ZERO);
            item.setReceivedQuantity(BigDecimal.ZERO);
            item.setRemark("缺口量: " + sf.getShortfallQuantity() + " " + unit
                    + "，已有库存: " + sf.getAvailableQuantity() + " " + unit);
            purchaseOrderItemRepository.save(item);

            suggestions.add(savedPo);

            log.info("采购建议已生成: material={} ({}), shortfall={} {}, PO={}",
                    materialName, sf.getMaterialTypeId(),
                    sf.getShortfallQuantity(), unit,
                    savedPo.getOrderNumber());
        }

        log.info("采购建议批量生成完成: factoryId={}, planId={}, 共生成{}张草稿采购单",
                factoryId, productionPlanId, suggestions.size());
        return suggestions;
    }
}
