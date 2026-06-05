package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.entity.restaurant.Recipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.RecipeRepository;
import com.cretas.aims.service.restaurant.MaterialRequisitionService;
import com.cretas.aims.service.restaurant.RestaurantInventoryPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MaterialRequisitionServiceImpl implements MaterialRequisitionService {

    private static final Logger log = LoggerFactory.getLogger(MaterialRequisitionServiceImpl.class);

    private final MaterialRequisitionRepository requisitionRepository;
    private final RecipeRepository recipeRepository;
    private final RestaurantInventoryPostingService inventoryPostingService;

    public MaterialRequisitionServiceImpl(MaterialRequisitionRepository requisitionRepository,
                                          RecipeRepository recipeRepository,
                                          RestaurantInventoryPostingService inventoryPostingService) {
        this.requisitionRepository = requisitionRepository;
        this.recipeRepository = recipeRepository;
        this.inventoryPostingService = inventoryPostingService;
    }

    @Override
    @Transactional
    public MaterialRequisition createRequisition(String factoryId, MaterialRequisition requisition, Long userId) {
        log.info("创建领料单: factoryId={}, type={}", factoryId, requisition.getType());

        requisition.setFactoryId(factoryId);
        requisition.setRequestedBy(userId);
        requisition.setStatus(MaterialRequisition.Status.DRAFT);

        if (requisition.getRequisitionDate() == null) {
            requisition.setRequisitionDate(LocalDate.now());
        }

        requisition.setRequisitionNumber(generateRequisitionNumber(factoryId, requisition.getRequisitionDate()));

        // Auto-calculate from BOM if PRODUCTION type with productTypeId and dishQuantity
        if (requisition.getType() == MaterialRequisition.RequisitionType.PRODUCTION
                && requisition.getProductTypeId() != null
                && requisition.getDishQuantity() != null
                && requisition.getDishQuantity() > 0) {

            List<Recipe> recipes = recipeRepository.findActiveByFactoryIdAndProductTypeId(
                    factoryId, requisition.getProductTypeId());

            if (!recipes.isEmpty() && requisition.getRawMaterialTypeId() != null) {
                BigDecimal qty = BigDecimal.valueOf(requisition.getDishQuantity());
                recipes.stream()
                        .filter(r -> r.getRawMaterialTypeId().equals(requisition.getRawMaterialTypeId()))
                        .findFirst()
                        .ifPresent(recipe -> {
                            BigDecimal calculated = recipe.getActualQuantity()
                                    .multiply(qty)
                                    .setScale(4, RoundingMode.HALF_UP);
                            requisition.setRequestedQuantity(calculated);
                            requisition.setUnit(recipe.getUnit());
                        });
            }
        }

        MaterialRequisition saved = requisitionRepository.save(requisition);
        log.info("领料单创建成功: id={}, number={}", saved.getId(), saved.getRequisitionNumber());
        return saved;
    }

    @Override
    public PageResponse<MaterialRequisition> getRequisitions(String factoryId, LocalDate date,
                                                              MaterialRequisition.Status status,
                                                              MaterialRequisition.RequisitionType type,
                                                              int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<MaterialRequisition> result;

        if (status != null) {
            result = requisitionRepository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pageable);
        } else if (type != null) {
            result = requisitionRepository.findByFactoryIdAndTypeOrderByCreatedAtDesc(factoryId, type, pageable);
        } else {
            result = requisitionRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        }

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements());
    }

    @Override
    public MaterialRequisition getRequisitionById(String factoryId, String requisitionId) {
        return requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialRequisition", "id", requisitionId));
    }

    @Override
    @Transactional
    public MaterialRequisition submitRequisition(String factoryId, String requisitionId, Long userId) {
        log.info("提交领料单: factoryId={}, requisitionId={}", factoryId, requisitionId);

        MaterialRequisition req = requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialRequisition", "id", requisitionId));

        if (req.getStatus() != MaterialRequisition.Status.DRAFT) {
            throw new BusinessException(409, "只有草稿状态的领料单才能提交")
                    .withHint("请刷新领料单列表查看最新状态");
        }

        req.setStatus(MaterialRequisition.Status.SUBMITTED);
        req = requisitionRepository.save(req);
        log.info("领料单已提交: id={}", req.getId());
        return req;
    }

    @Override
    @Transactional
    public MaterialRequisition approveRequisition(String factoryId, String requisitionId,
                                                   Long approvedBy, BigDecimal actualQuantity) {
        log.info("审批领料单: factoryId={}, requisitionId={}", factoryId, requisitionId);

        MaterialRequisition req = requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialRequisition", "id", requisitionId));

        if (req.getStatus() == MaterialRequisition.Status.APPROVED && req.getInventoryPostedAt() != null) {
            return req;
        }
        if (req.getStatus() != MaterialRequisition.Status.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的领料单才能审批")
                    .withHint("请刷新领料单列表查看最新审批状态");
        }

        req.setActualQuantity(actualQuantity != null ? actualQuantity : req.getRequestedQuantity());
        try {
            inventoryPostingService.postMaterialRequisitionIssue(factoryId, req, approvedBy);
            req.setStatus(MaterialRequisition.Status.APPROVED);
            req.setApprovedBy(approvedBy);
            req.setApprovedAt(LocalDateTime.now());
            req = requisitionRepository.save(req);
        } catch (BusinessException e) {
            inventoryPostingService.markMaterialRequisitionPostingFailed(factoryId, requisitionId, e.getMessage());
            throw e;
        }

        log.info("领料单审批通过: id={}, actualQuantity={}", req.getId(), actualQuantity);
        return req;
    }

    @Override
    @Transactional
    public MaterialRequisition rejectRequisition(String factoryId, String requisitionId,
                                                  Long approvedBy, String reason) {
        log.info("驳回领料单: factoryId={}, requisitionId={}", factoryId, requisitionId);

        MaterialRequisition req = requisitionRepository.findByIdAndFactoryId(requisitionId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialRequisition", "id", requisitionId));

        if (req.getStatus() != MaterialRequisition.Status.SUBMITTED) {
            throw new BusinessException(409, "只有已提交状态的领料单才能驳回")
                    .withHint("请刷新领料单列表查看最新审批状态");
        }

        req.setStatus(MaterialRequisition.Status.REJECTED);
        req.setApprovedBy(approvedBy);
        req.setApprovedAt(LocalDateTime.now());
        req.setNotes(reason);
        req = requisitionRepository.save(req);

        log.info("领料单已驳回: id={}, reason={}", req.getId(), reason);
        return req;
    }

    @Override
    public List<Map<String, Object>> getDailySummary(String factoryId, LocalDate date) {
        List<Object[]> rows = requisitionRepository.getDailySummaryByMaterial(factoryId, date);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rawMaterialTypeId", row[0]);
            item.put("unit", row[1]);
            item.put("totalQuantity", row[2]);
            result.add(item);
        }

        return result;
    }

    @Override
    public Map<String, Object> getStatistics(String factoryId) {
        LocalDate today = LocalDate.now();

        long todayTotal = requisitionRepository.countByFactoryIdAndDate(factoryId, today);

        PageRequest one = PageRequest.of(0, Integer.MAX_VALUE);
        Page<MaterialRequisition> pendingPage = requisitionRepository
                .findByFactoryIdAndStatusOrderByCreatedAtAsc(factoryId, MaterialRequisition.Status.SUBMITTED, one);
        long pendingCount = pendingPage.getTotalElements();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayRequisitions", todayTotal);
        stats.put("pendingApproval", pendingCount);
        stats.put("date", today.toString());
        return stats;
    }

    private String generateRequisitionNumber(String factoryId, LocalDate date) {
        long count = requisitionRepository.countByFactoryIdAndDate(factoryId, date);
        return String.format("REQ-%s-%03d", date.format(DateTimeFormatter.BASIC_ISO_DATE), count + 1);
    }
}
