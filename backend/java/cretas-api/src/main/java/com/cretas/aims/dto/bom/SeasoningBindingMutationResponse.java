package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SeasoningBindingMutationResponse {
    private Long seasoningRevision;
    private BomSeasoningItem binding;
}
