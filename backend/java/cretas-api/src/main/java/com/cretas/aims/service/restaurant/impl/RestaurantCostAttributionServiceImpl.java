package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.restaurant.RestaurantCostAttributionSummary;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.WastageSection;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.restaurant.RestaurantCostAttributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
public class RestaurantCostAttributionServiceImpl implements RestaurantCostAttributionService {

    private static final String UNASSIGNED = "UNASSIGNED";
    private static final String UNASSIGNED_LABEL = "未指定";

    private final MaterialRequisitionRepository materialRequisitionRepository;
    private final WastageRecordRepository wastageRecordRepository;
    private final StocktakingRecordRepository stocktakingRecordRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public RestaurantCostAttributionSummary getSummary(String factoryId, LocalDate startDate, LocalDate endDate) {
        Map<String, Accumulator> bySource = new LinkedHashMap<>();
        Map<String, Accumulator> bySection = new HashMap<>();
        Map<String, Accumulator> byStall = new HashMap<>();
        Map<String, Accumulator> byPerson = new HashMap<>();
        Map<String, Accumulator> byChef = new HashMap<>();
        Set<Long> userIds = new HashSet<>();

        materialRequisitionRepository.getCostAttributionRows(factoryId, startDate, endDate)
                .forEach(row -> addRequisitionRow(row, bySource, bySection, byStall, byPerson, byChef, userIds));
        wastageRecordRepository.getCostAttributionRows(factoryId, startDate, endDate)
                .forEach(row -> addWastageRow(row, bySource, bySection, byStall, byPerson, byChef, userIds));
        stocktakingRecordRepository.getShortageCostAttributionRows(factoryId, startDate, endDate)
                .forEach(row -> addStocktakingRow(row, bySource, bySection, byStall, byPerson, userIds));

        Map<Long, String> userNames = loadUserNames(userIds);

        BigDecimal totalCost = bySource.values().stream()
                .map(a -> a.totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalCount = bySource.values().stream().mapToLong(a -> a.count).sum();

        RestaurantCostAttributionSummary result = new RestaurantCostAttributionSummary();
        result.setStartDate(startDate.toString());
        result.setEndDate(endDate.toString());
        result.setTotalCost(totalCost);
        result.setTotalCount(totalCount);
        result.setBySource(toBuckets(bySource.values(), Map.of()));
        result.setBySection(toBuckets(bySection.values(), Map.of()));
        result.setByStall(toBuckets(byStall.values(), Map.of()));
        result.setByPerson(toBuckets(byPerson.values(), userNames));
        result.setByChef(toBuckets(byChef.values(), userNames));
        return result;
    }

    private void addRequisitionRow(Object[] row,
                                   Map<String, Accumulator> bySource,
                                   Map<String, Accumulator> bySection,
                                   Map<String, Accumulator> byStall,
                                   Map<String, Accumulator> byPerson,
                                   Map<String, Accumulator> byChef,
                                   Set<Long> userIds) {
        String sectionCode = (String) row[0];
        String stallCode = (String) row[1];
        Long operatorId = (Long) row[2];
        Long requestedBy = (Long) row[3];
        Long chefId = (Long) row[4];
        long count = toLong(row[5]);
        BigDecimal quantity = toBigDecimal(row[6]);
        BigDecimal cost = toBigDecimal(row[7]);

        add(bySource, "REQUISITION", "领料出库", count, quantity, cost);
        add(bySection, sectionKey(sectionCode), sectionLabel(sectionCode), count, quantity, cost);
        add(byStall, key(stallCode), label(stallCode), count, quantity, cost);
        Long personId = firstNonNull(operatorId, requestedBy);
        addUserBucket(byPerson, personId, count, quantity, cost, userIds);
        addUserBucket(byChef, firstNonNull(chefId, requestedBy), count, quantity, cost, userIds);
    }

    private void addWastageRow(Object[] row,
                               Map<String, Accumulator> bySource,
                               Map<String, Accumulator> bySection,
                               Map<String, Accumulator> byStall,
                               Map<String, Accumulator> byPerson,
                               Map<String, Accumulator> byChef,
                               Set<Long> userIds) {
        String sectionCode = (String) row[0];
        String stallCode = (String) row[1];
        Long operatorId = (Long) row[2];
        Long chefId = (Long) row[3];
        long count = toLong(row[4]);
        BigDecimal quantity = toBigDecimal(row[5]);
        BigDecimal cost = toBigDecimal(row[6]);

        add(bySource, "WASTAGE", "损耗", count, quantity, cost);
        add(bySection, sectionKey(sectionCode), sectionLabel(sectionCode), count, quantity, cost);
        add(byStall, key(stallCode), label(stallCode), count, quantity, cost);
        addUserBucket(byPerson, operatorId, count, quantity, cost, userIds);
        addUserBucket(byChef, chefId, count, quantity, cost, userIds);
    }

    private void addStocktakingRow(Object[] row,
                                   Map<String, Accumulator> bySource,
                                   Map<String, Accumulator> bySection,
                                   Map<String, Accumulator> byStall,
                                   Map<String, Accumulator> byPerson,
                                   Set<Long> userIds) {
        String sectionCode = (String) row[0];
        String stallCode = (String) row[1];
        Long countedBy = (Long) row[2];
        long count = toLong(row[3]);
        BigDecimal quantity = toBigDecimal(row[4]);
        BigDecimal cost = toBigDecimal(row[5]);

        add(bySource, "STOCKTAKING_SHORTAGE", "盘点短缺", count, quantity, cost);
        add(bySection, sectionKey(sectionCode), sectionLabel(sectionCode), count, quantity, cost);
        add(byStall, key(stallCode), label(stallCode), count, quantity, cost);
        addUserBucket(byPerson, countedBy, count, quantity, cost, userIds);
    }

    private void addUserBucket(Map<String, Accumulator> buckets, Long userId, long count,
                               BigDecimal quantity, BigDecimal cost, Set<Long> userIds) {
        if (userId != null) {
            userIds.add(userId);
        }
        add(buckets, userId == null ? UNASSIGNED : userId.toString(),
                userId == null ? UNASSIGNED_LABEL : "User#" + userId, count, quantity, cost);
    }

    private void add(Map<String, Accumulator> buckets, String key, String label, long count,
                     BigDecimal quantity, BigDecimal cost) {
        buckets.computeIfAbsent(key, k -> new Accumulator(k, label)).add(count, quantity, cost);
    }

    private List<RestaurantCostAttributionSummary.Bucket> toBuckets(Collection<Accumulator> accumulators,
                                                                    Map<Long, String> userNames) {
        return accumulators.stream()
                .filter(a -> a.count > 0)
                .sorted((a, b) -> b.totalCost.compareTo(a.totalCost))
                .map(a -> new RestaurantCostAttributionSummary.Bucket(
                        a.key,
                        userLabel(a, userNames),
                        a.count,
                        a.totalQuantity,
                        a.totalCost))
                .toList();
    }

    private String userLabel(Accumulator accumulator, Map<Long, String> userNames) {
        if (UNASSIGNED.equals(accumulator.key)) {
            return UNASSIGNED_LABEL;
        }
        try {
            Long id = Long.valueOf(accumulator.key);
            return userNames.getOrDefault(id, accumulator.label);
        } catch (NumberFormatException ignored) {
            return accumulator.label;
        }
    }

    private Map<Long, String> loadUserNames(Set<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findByIdIn(userIds)) {
            String name = StringUtils.hasText(user.getFullName()) ? user.getFullName() : "User#" + user.getId();
            names.put(user.getId(), name);
        }
        return names;
    }

    private String sectionKey(String sectionCode) {
        return key(sectionCode);
    }

    private String sectionLabel(String sectionCode) {
        return StringUtils.hasText(sectionCode) ? WastageSection.labelOf(sectionCode) : UNASSIGNED_LABEL;
    }

    private String key(String value) {
        return StringUtils.hasText(value) ? value : UNASSIGNED;
    }

    private String label(String value) {
        return StringUtils.hasText(value) ? value : UNASSIGNED_LABEL;
    }

    private Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        return ((Number) o).longValue();
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        return new BigDecimal(o.toString());
    }

    private static class Accumulator {
        private final String key;
        private final String label;
        private long count;
        private BigDecimal totalQuantity = BigDecimal.ZERO;
        private BigDecimal totalCost = BigDecimal.ZERO;

        private Accumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void add(long count, BigDecimal quantity, BigDecimal cost) {
            this.count += count;
            this.totalQuantity = this.totalQuantity.add(quantity == null ? BigDecimal.ZERO : quantity);
            this.totalCost = this.totalCost.add(cost == null ? BigDecimal.ZERO : cost);
        }
    }
}
