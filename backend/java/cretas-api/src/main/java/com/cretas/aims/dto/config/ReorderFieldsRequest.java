package com.cretas.aims.dto.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Round 10 Fix — field order request for ConfigController.reorderFields.
 *
 * Carries the new field code order plus the expected rowVersion of the draft
 * FactoryConfiguration for optimistic lock. If the version doesn't match, the
 * endpoint returns 409 and the frontend must reload + retry.
 */
@Data
public class ReorderFieldsRequest {
    @NotEmpty(message = "fieldOrder 不能为空")
    private List<String> fieldOrder;

    @NotNull(message = "expectedVersion 必填 (乐观锁)")
    private Long expectedVersion;
}
