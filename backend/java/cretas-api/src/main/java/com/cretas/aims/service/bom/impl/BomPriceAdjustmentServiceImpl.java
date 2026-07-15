package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomPriceAdjustmentAudit;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.SourceType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal.Status;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.bom.BomPriceAdjustmentAuditRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BomPriceAdjustmentServiceImpl implements BomPriceAdjustmentService {

    private final BomRecipeItemRepository itemRepository;
    private final BomRecipeRepository recipeRepository;
    private final BomPriceAdjustmentProposalRepository proposalRepository;
    private final BomPriceAdjustmentAuditRepository auditRepository;
    private final RawMaterialTypeRepository materialRepository;

    @Override
    @Transactional
    public List<BomPriceAdjustmentProposal> generateFromReceive(String factoryId, PurchaseReceiveRecord receiveRecord) {
        if (receiveRecord == null || receiveRecord.getItems() == null) {
            return List.of();
        }
        List<BomPriceAdjustmentProposal> proposals = new ArrayList<>();
        for (PurchaseReceiveItem item : receiveRecord.getItems()) {
            proposals.addAll(generateForMaterial(
                    factoryId,
                    item.getMaterialTypeId(),
                    item.getMaterialName(),
                    item.getUnitPrice(),
                    SourceType.PURCHASE_RECEIVE,
                    receiveRecord.getId(),
                    item.getId()));
        }
        return proposals;
    }

    @Override
    @Transactional
    public List<BomPriceAdjustmentProposal> generateForMaterial(
            String factoryId,
            String materialTypeId,
            String materialName,
            BigDecimal latestPreTaxPrice,
            SourceType sourceType,
            String sourceReceiveRecordId,
            Long sourceReceiveItemId) {
        if (materialTypeId == null || materialTypeId.isBlank() || latestPreTaxPrice == null) {
            return List.of();
        }

        BigDecimal proposedPrice = scaleMoney(latestPreTaxPrice);
        if (proposedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        List<BomRecipeItem> items = itemRepository.findByFactoryIdAndMaterialTypeId(factoryId, materialTypeId);
        if (items.isEmpty()) {
            return List.of();
        }

        List<String> recipeIds = items.stream()
                .map(BomRecipeItem::getRecipeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, BomRecipe> recipesById = recipeRepository.findAllById(recipeIds).stream()
                .collect(Collectors.toMap(BomRecipe::getId, recipe -> recipe, (a, b) -> a, LinkedHashMap::new));
        int affectedProductCount = (int) recipesById.values().stream()
                .map(BomRecipe::getProductTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        List<BomPriceAdjustmentProposal> proposals = new ArrayList<>();
        for (BomRecipeItem item : items) {
            if (item.getUnitPrice() == null || item.getRecipeId() == null) {
                continue;
            }
            BigDecimal currentPrice = scaleMoney(item.getUnitPrice());
            if (currentPrice.compareTo(proposedPrice) == 0) {
                continue;
            }
            BomRecipe recipe = recipesById.get(item.getRecipeId());
            BigDecimal deltaAmount = proposedPrice.subtract(currentPrice).setScale(4, RoundingMode.HALF_UP);
            BigDecimal deltaPercent = calculateDeltaPercent(currentPrice, proposedPrice);
            BomPriceAdjustmentProposal pending = proposalRepository
                    .findByFactoryIdAndRecipeItemIdAndStatusAndDeletedAtIsNull(
                            factoryId, item.getId(), Status.PENDING)
                    .orElse(null);
            if (pending != null) {
                if (scaleMoney(pending.getProposedUnitPrice()).compareTo(proposedPrice) == 0) {
                    continue;
                }
                log.info("Updating existing pending BOM price adjustment proposal: proposalId={}, factoryId={}, recipeItemId={}",
                        pending.getId(), factoryId, item.getId());
                pending.setCurrentUnitPrice(currentPrice);
                pending.setProposedUnitPrice(proposedPrice);
                pending.setDeltaAmount(deltaAmount);
                pending.setDeltaPercent(deltaPercent);
                pending.setAffectedProductCount(affectedProductCount);
                pending.setSourceType(sourceType != null ? sourceType : SourceType.MANUAL_CHECK);
                pending.setSourceReceiveRecordId(sourceReceiveRecordId);
                pending.setSourceReceiveItemId(sourceReceiveItemId);
                if (materialName != null) {
                    pending.setMaterialName(materialName);
                }
                proposals.add(proposalRepository.save(pending));
                continue;
            }
            BomPriceAdjustmentProposal proposal = new BomPriceAdjustmentProposal();
            proposal.setFactoryId(factoryId);
            proposal.setRecipeId(item.getRecipeId());
            proposal.setRecipeItemId(item.getId());
            proposal.setRecipeCode(recipe != null ? recipe.getRecipeCode() : null);
            proposal.setProductTypeId(recipe != null ? recipe.getProductTypeId() : null);
            proposal.setProductName(recipe != null ? recipe.getProductName() : null);
            proposal.setMaterialTypeId(materialTypeId);
            proposal.setMaterialName(materialName != null ? materialName : item.getMaterialName());
            proposal.setCurrentUnitPrice(currentPrice);
            proposal.setProposedUnitPrice(proposedPrice);
            proposal.setDeltaAmount(deltaAmount);
            proposal.setDeltaPercent(deltaPercent);
            proposal.setAffectedProductCount(affectedProductCount);
            proposal.setStatus(Status.PENDING);
            proposal.setSourceType(sourceType != null ? sourceType : SourceType.MANUAL_CHECK);
            proposal.setSourceReceiveRecordId(sourceReceiveRecordId);
            proposal.setSourceReceiveItemId(sourceReceiveItemId);
            proposals.add(proposalRepository.save(proposal));
        }
        return proposals;
    }

    @Override
    @Transactional
    public BomPriceAdjustmentProposal approve(String factoryId, Long proposalId, Long approverId, String comment) {
        if (approverId == null) {
            throw new BusinessException(400, "审批人不能为空");
        }
        BomPriceAdjustmentProposal proposal = proposalRepository.findByIdAndFactoryId(proposalId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("BOM调价建议不存在: " + proposalId));
        if (proposal.getStatus() != Status.PENDING) {
            throw new BusinessException(409, "只有待审批的BOM调价建议可以批准");
        }

        BomRecipeItem item = itemRepository.findByIdForUpdate(proposal.getRecipeItemId())
                .orElseThrow(() -> new ResourceNotFoundException("BOM行不存在: " + proposal.getRecipeItemId()));
        if (!factoryId.equals(item.getFactoryId())) {
            throw new BusinessException(403, "无权审批该BOM调价建议");
        }

        BigDecimal beforePrice = scaleMoney(item.getUnitPrice());
        RawMaterialType material = materialRepository.findById(item.getMaterialTypeId())
                .filter(found -> factoryId.equals(found.getFactoryId()) && found.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("物料档案不存在: " + item.getMaterialTypeId()));
        BigDecimal masterPrice = material.getMovingAvgPrice() != null
                ? material.getMovingAvgPrice()
                : material.getUnitPrice();
        if (masterPrice == null || masterPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "物料档案未配置有效价格: " + item.getMaterialTypeId());
        }
        BigDecimal afterPrice = scaleMoney(masterPrice);
        item.setUnitPrice(afterPrice);
        item.setTaxRate(material.getTaxRate() == null ? null
                : material.getTaxRate().getRate().multiply(BigDecimal.valueOf(100)));
        item.setItemCost(item.computeItemCost());
        itemRepository.save(item);

        LocalDateTime now = LocalDateTime.now();
        proposal.setStatus(Status.APPROVED);
        proposal.setApprovedBy(approverId);
        proposal.setApprovedAt(now);
        proposal.setApprovalComment(comment);
        proposal.setProposedUnitPrice(afterPrice);
        proposal.setAppliedAt(now);

        BomPriceAdjustmentAudit audit = new BomPriceAdjustmentAudit();
        audit.setProposalId(proposal.getId());
        audit.setFactoryId(factoryId);
        audit.setRecipeId(item.getRecipeId());
        audit.setRecipeItemId(item.getId());
        audit.setMaterialTypeId(item.getMaterialTypeId());
        audit.setBeforeUnitPrice(beforePrice);
        audit.setAfterUnitPrice(afterPrice);
        audit.setApprovedBy(approverId);
        audit.setApprovedAt(now);
        audit.setApprovalComment(comment);
        auditRepository.save(audit);

        return proposalRepository.save(proposal);
    }

    @Override
    public Page<BomPriceAdjustmentProposal> list(String factoryId, Status status, Pageable pageable) {
        if (status != null) {
            return proposalRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageable);
        }
        return proposalRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateDeltaPercent(BigDecimal currentPrice, BigDecimal proposedPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return proposedPrice.subtract(currentPrice)
                .divide(currentPrice, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
