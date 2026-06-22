package com.cretas.aims.service.recipe.impl;

import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.RecipeIngredientDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.recipe.ProductRecipeService;
import com.cretas.aims.service.recipe.RecipeCostCalculator;
import com.cretas.aims.service.recipe.SeasoningCost;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRecipeServiceImpl implements ProductRecipeService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ProductRecipeRepository recipeRepo;
    private final RecipeIngredientRepository ingredientRepo;

    @Override
    public List<ProductRecipeDTO> list(String factoryId) {
        List<ProductRecipeDTO> out = new ArrayList<>();
        for (ProductRecipe r : recipeRepo.findByFactoryId(factoryId)) {
            out.add(toDTO(r, ingredientRepo.findByRecipeIdOrderBySeqAsc(r.getId())));
        }
        return out;
    }

    @Override
    public ProductRecipeDTO get(String factoryId, String id) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        return toDTO(r, ingredientRepo.findByRecipeIdOrderBySeqAsc(r.getId()));
    }

    @Override
    @Transactional
    public ProductRecipeDTO create(String factoryId, SaveRecipeRequest req) {
        Optional<ProductRecipe> dup = recipeRepo
                .findByFactoryIdAndProductTypeIdAndStatus(factoryId, req.getProductTypeId(), STATUS_ACTIVE);
        if (dup.isPresent()) {
            throw new BusinessException(409, "该产品已有启用配方, 请先停用旧配方再新建")
                    .withCode("RECIPE_DUPLICATE");
        }
        validate(req);
        ProductRecipe r = new ProductRecipe();
        r.setFactoryId(factoryId);
        applyHead(r, req);
        r.setStatus(STATUS_ACTIVE);
        ProductRecipe saved = recipeRepo.save(r);
        List<RecipeIngredient> savedIngs = saveIngredients(factoryId, saved.getId(), req.getIngredients());
        return toDTO(saved, savedIngs);
    }

    @Override
    @Transactional
    public ProductRecipeDTO update(String factoryId, String id, SaveRecipeRequest req) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        validate(req);
        applyHead(r, req);
        recipeRepo.save(r);
        // 替换明细: 事务内硬删旧明细再插新 (master data 低频, 无审计需求; FK 保护防孤儿)
        ingredientRepo.deleteByRecipeId(id);
        List<RecipeIngredient> updatedIngs = saveIngredients(factoryId, id, req.getIngredients());
        return toDTO(r, updatedIngs);
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        if ("INACTIVE".equals(r.getStatus())) {
            return; // 已停用, 幂等 no-op
        }
        r.setStatus("INACTIVE");
        r.softDelete();
        recipeRepo.save(r);
    }

    private void validate(SaveRecipeRequest req) {
        if (req.getIngredients() == null || req.getIngredients().isEmpty()) {
            throw new BusinessException(400, "配方至少需要一条料");
        }
        BigDecimal ratio = req.getSubsequentPotRatio();
        if (ratio != null && (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(400, "第二锅起比例须在 (0,1]");
        }
        for (RecipeIngredientDTO i : req.getIngredients()) {
            if (!RecipeIngredient.SECTION_INJECTION.equals(i.getSection())
                    && !RecipeIngredient.SECTION_COOKING.equals(i.getSection())) {
                throw new BusinessException(400, "料「" + i.getName() + "」工序段非法(须 INJECTION/COOKING)");
            }
            if (i.getPriceSource1() == null && i.getPriceSource2() == null) {
                throw new BusinessException(400, "料「" + i.getName() + "」单价两源至少填一个");
            }
        }
    }

    private void applyHead(ProductRecipe r, SaveRecipeRequest req) {
        r.setProductTypeId(req.getProductTypeId());
        r.setName(req.getName());
        r.setInjectionRate(req.getInjectionRate());
        r.setCookingPotBaseKg(req.getCookingPotBaseKg());
        r.setSubsequentPotRatio(req.getSubsequentPotRatio() == null ? ProductRecipe.DEFAULT_SUBSEQUENT_POT_RATIO : req.getSubsequentPotRatio());
    }

    private List<RecipeIngredient> saveIngredients(String factoryId, String recipeId, List<RecipeIngredientDTO> items) {
        List<RecipeIngredient> result = new ArrayList<>();
        int seq = 0;
        for (RecipeIngredientDTO dto : items) {
            RecipeIngredient e = new RecipeIngredient();
            e.setRecipeId(recipeId);
            e.setFactoryId(factoryId);
            e.setSection(dto.getSection());
            e.setSeq(dto.getSeq() == null ? seq++ : dto.getSeq());
            e.setName(dto.getName());
            e.setDosagePerKgG(dto.getDosagePerKgG());
            e.setPriceSource1(dto.getPriceSource1());
            e.setPriceSource2(dto.getPriceSource2());
            e.setCountInSeasoning(dto.getCountInSeasoning() == null ? Boolean.TRUE : dto.getCountInSeasoning());
            e.setRemark(dto.getRemark());
            result.add(ingredientRepo.save(e));
        }
        return result;
    }

    private ProductRecipeDTO toDTO(ProductRecipe r, List<RecipeIngredient> ings) {
        ProductRecipeDTO dto = new ProductRecipeDTO();
        dto.setId(r.getId());
        dto.setFactoryId(r.getFactoryId());
        dto.setProductTypeId(r.getProductTypeId());
        dto.setName(r.getName());
        dto.setInjectionRate(r.getInjectionRate());
        dto.setCookingPotBaseKg(r.getCookingPotBaseKg());
        dto.setSubsequentPotRatio(r.getSubsequentPotRatio());
        dto.setStatus(r.getStatus());
        dto.setVersion(r.getVersion());

        List<RecipeIngredientDTO> idtos = new ArrayList<>();
        for (RecipeIngredient i : ings) {
            RecipeIngredientDTO id = new RecipeIngredientDTO();
            id.setId(i.getId());
            id.setSection(i.getSection());
            id.setSeq(i.getSeq());
            id.setName(i.getName());
            id.setDosagePerKgG(i.getDosagePerKgG());
            id.setPriceSource1(i.getPriceSource1());
            id.setPriceSource2(i.getPriceSource2());
            id.setCountInSeasoning(i.getCountInSeasoning());
            id.setRemark(i.getRemark());
            idtos.add(id);
        }
        dto.setIngredients(idtos);

        // per-kg 速率(展示): injectionRawKg/potRawKgs 传 1kg/单锅 1kg 只为算速率
        SeasoningCost rate = RecipeCostCalculator.compute(
                r, ings, BigDecimal.ONE, List.of(BigDecimal.ONE));
        dto.setInjectionCostPerKg(rate.getInjectionCostPerKg());
        dto.setCookingFullCostPerKg(rate.getCookingFullCostPerKg());
        dto.setCostPerKgFirstPot(rate.getInjectionCostPerKg().add(rate.getCookingFullCostPerKg())
                .setScale(4, RoundingMode.HALF_UP));
        BigDecimal ratio = r.getSubsequentPotRatio() == null ? ProductRecipe.DEFAULT_SUBSEQUENT_POT_RATIO : r.getSubsequentPotRatio();
        dto.setCostPerKgSubsequentPot(
                rate.getInjectionCostPerKg().add(rate.getCookingFullCostPerKg().multiply(ratio))
                        .setScale(4, RoundingMode.HALF_UP));
        return dto;
    }
}
