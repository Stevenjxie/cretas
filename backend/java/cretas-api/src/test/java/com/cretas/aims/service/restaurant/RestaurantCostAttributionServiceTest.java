package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.RestaurantCostAttributionSummary;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.restaurant.impl.RestaurantCostAttributionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantCostAttributionService")
class RestaurantCostAttributionServiceTest {

    private static final String FACTORY = "F006";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Mock MaterialRequisitionRepository materialRequisitionRepository;
    @Mock WastageRecordRepository wastageRecordRepository;
    @Mock StocktakingRecordRepository stocktakingRecordRepository;
    @Mock UserRepository userRepository;
    @InjectMocks RestaurantCostAttributionServiceImpl service;

    @Test
    @DisplayName("aggregates posted requisition, wastage, and stocktaking shortage costs")
    void getSummary_aggregatesAllConsumptionSources() {
        when(materialRequisitionRepository.getCostAttributionRows(FACTORY, START, END)).thenReturn(List.<Object[]>of(
                new Object[]{"SEAFOOD", "S01", 10L, 10L, 10L, 2L, new BigDecimal("8.0000"), new BigDecimal("120.00")}
        ));
        when(wastageRecordRepository.getCostAttributionRows(FACTORY, START, END)).thenReturn(List.<Object[]>of(
                new Object[]{"SEAFOOD", "S01", 11L, 11L, 1L, new BigDecimal("1.5000"), new BigDecimal("30.00")}
        ));
        when(stocktakingRecordRepository.getShortageCostAttributionRows(FACTORY, START, END)).thenReturn(List.<Object[]>of(
                new Object[]{"HOT_DISH", "H01", 12L, 1L, new BigDecimal("2.0000"), new BigDecimal("50.00")}
        ));

        User chef = user(10L, "Chef Zhang");
        User wasteChef = user(11L, "Chef Li");
        User counter = user(12L, "Counter Wang");
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of(chef, wasteChef, counter));

        RestaurantCostAttributionSummary result = service.getSummary(FACTORY, START, END);

        assertEquals(0, new BigDecimal("200.00").compareTo(result.getTotalCost()));
        assertEquals(4L, result.getTotalCount());
        assertBucket(result.getBySource(), "REQUISITION", "120.00", 2L);
        assertBucket(result.getBySource(), "WASTAGE", "30.00", 1L);
        assertBucket(result.getBySource(), "STOCKTAKING_SHORTAGE", "50.00", 1L);
        assertBucket(result.getBySection(), "SEAFOOD", "150.00", 3L);
        assertBucket(result.getByStall(), "S01", "150.00", 3L);
        assertBucket(result.getByPerson(), "10", "120.00", 2L);
        assertBucket(result.getByChef(), "11", "30.00", 1L);
    }

    @Test
    @DisplayName("empty data returns zero totals and no fake buckets")
    void getSummary_emptyDataReturnsZeroes() {
        when(materialRequisitionRepository.getCostAttributionRows(FACTORY, START, END)).thenReturn(List.of());
        when(wastageRecordRepository.getCostAttributionRows(FACTORY, START, END)).thenReturn(List.of());
        when(stocktakingRecordRepository.getShortageCostAttributionRows(FACTORY, START, END)).thenReturn(List.of());

        RestaurantCostAttributionSummary result = service.getSummary(FACTORY, START, END);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalCost()));
        assertEquals(0L, result.getTotalCount());
        assertTrue(result.getBySource().isEmpty());
        assertTrue(result.getBySection().isEmpty());
        assertTrue(result.getByStall().isEmpty());
        assertTrue(result.getByPerson().isEmpty());
        assertTrue(result.getByChef().isEmpty());
        verify(userRepository, never()).findByIdIn(anyCollection());
    }

    private User user(Long id, String fullName) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        return user;
    }

    private void assertBucket(List<RestaurantCostAttributionSummary.Bucket> buckets,
                              String key, String expectedCost, Long expectedCount) {
        RestaurantCostAttributionSummary.Bucket bucket = buckets.stream()
                .filter(b -> key.equals(b.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedCount, bucket.getCount());
        assertEquals(0, new BigDecimal(expectedCost).compareTo(bucket.getTotalCost()));
    }
}
