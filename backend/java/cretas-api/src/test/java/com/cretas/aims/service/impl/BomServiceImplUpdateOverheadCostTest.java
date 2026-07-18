package com.cretas.aims.service.impl;

import com.cretas.aims.entity.bom.OverheadCostConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T-R5-3 (R5 audit §3 BUG#1): BomController.updateOverheadCost previously did
 * a blind {@code repo.save(body)}, so any DB-only field absent from the client
 * payload (createdAt, version, deletedAt, audit columns) was overwritten to
 * NULL. Service must now select-then-merge: load existing, copy non-null
 * fields from body, save.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BomServiceImpl.updateOverheadCost — select-then-merge (T-R5-3)")
class BomServiceImplUpdateOverheadCostTest {

    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private LaborCostConfigRepository laborCostConfigRepository;
    @Mock private OverheadCostConfigRepository overheadCostConfigRepository;

    private BomServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository,
                overheadCostConfigRepository);
    }

    private OverheadCostConfig existing(Long id, String factoryId) {
        OverheadCostConfig c = new OverheadCostConfig();
        c.setId(id);
        c.setFactoryId(factoryId);
        c.setName("能源费");
        c.setCategory("ENERGY");
        c.setUnitPrice(new BigDecimal("12.5000"));
        c.setPriceUnit("元/kWh");
        c.setAllocationMethod("PER_UNIT");
        c.setAllocationRate(new BigDecimal("1.000000"));
        c.setIsActive(Boolean.TRUE);
        c.setSortOrder(5);
        c.setRemark("preserved remark");
        return c;
    }

    @Test
    @DisplayName("Partial body (Jackson explicit-null) merges — null fields don't overwrite")
    void mergesPartialBodyPreservingExistingFields() {
        when(overheadCostConfigRepository.findById(100L)).thenReturn(Optional.of(existing(100L, "F001")));
        when(overheadCostConfigRepository.save(any(OverheadCostConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // Models the Jackson deserialization of {"name": "...", "unitPrice": 13.0, ...null fields...}
        // — explicit null wins; field initializers on OverheadCostConfig are overridden by Jackson
        // when the entity is bound via @RequestBody (audit MO Mirror pattern from
        // BusinessRuleController.setSchedulerConfig:159-164).
        OverheadCostConfig body = new OverheadCostConfig();
        body.setName("能源费(已更新)");
        body.setUnitPrice(new BigDecimal("13.0000"));
        body.setCategory(null);
        body.setPriceUnit(null);
        body.setAllocationMethod(null);
        body.setAllocationRate(null);
        body.setIsActive(null);
        body.setSortOrder(null);
        body.setRemark(null);

        OverheadCostConfig out = service.updateOverheadCost("F001", 100L, body);

        ArgumentCaptor<OverheadCostConfig> captor = ArgumentCaptor.forClass(OverheadCostConfig.class);
        verify(overheadCostConfigRepository).save(captor.capture());
        OverheadCostConfig saved = captor.getValue();

        assertEquals("能源费(已更新)", saved.getName(), "name merged");
        assertEquals(new BigDecimal("13.0000"), saved.getUnitPrice(), "unitPrice merged");
        assertEquals("ENERGY", saved.getCategory(), "category preserved (body null)");
        assertEquals("元/kWh", saved.getPriceUnit(), "priceUnit preserved");
        assertEquals(5, saved.getSortOrder(), "sortOrder preserved");
        assertEquals("preserved remark", saved.getRemark(), "remark preserved");
        assertEquals(Boolean.TRUE, saved.getIsActive(), "isActive preserved");
        assertEquals(100L, saved.getId(), "id preserved");
        assertEquals("F001", saved.getFactoryId(), "factoryId preserved");
        assertSame(saved, out);
    }

    @Test
    @DisplayName("Cross-factory update → 403 BusinessException")
    void rejectsCrossFactoryUpdate() {
        when(overheadCostConfigRepository.findById(100L)).thenReturn(Optional.of(existing(100L, "F999")));

        OverheadCostConfig body = new OverheadCostConfig();
        body.setName("hijack attempt");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateOverheadCost("F001", 100L, body));
        assertEquals(403, ex.getCode());
        verify(overheadCostConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("Missing entity → EntityNotFoundException, no save")
    void rejectsMissingEntity() {
        when(overheadCostConfigRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.updateOverheadCost("F001", 404L, new OverheadCostConfig()));
        verify(overheadCostConfigRepository, never()).save(any());
    }
}
