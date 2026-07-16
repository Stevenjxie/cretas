package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 逐条选择来源 BOM 规则并复制为目标产品草稿。 */
@Data
public class BomCopyToDraftRequest {

    @NotBlank(message = "targetProductTypeId 不能为空")
    private String targetProductTypeId;

    @NotBlank(message = "sourceRecipeId 不能为空")
    private String sourceRecipeId;

    private List<Long> bomItemIds = new ArrayList<>();
    private List<Long> seasoningItemIds = new ArrayList<>();
    private List<Long> processSeasoningParamIds = new ArrayList<>();
}
