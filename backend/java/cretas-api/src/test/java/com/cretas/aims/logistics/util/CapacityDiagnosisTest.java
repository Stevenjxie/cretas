package com.cretas.aims.logistics.util;

import com.cretas.aims.logistics.dto.plan.CapacityDiagnosisDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsTrip;
import com.cretas.aims.logistics.entity.LogisticsVehicleProfile;
import com.cretas.aims.logistics.entity.enums.CapacityVerdict;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import com.cretas.aims.logistics.entity.enums.TemperatureMode;
import com.cretas.aims.logistics.entity.enums.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯计算单测（不启 Spring 上下文，不碰 DB）— 覆盖运力诊断三种 verdict。
 *
 * <p>INSUFFICIENT 场景数据镜像
 * {@code service/routing/LogisticsRoutingDemoScenarioTest}（只读该测试的 21 门店订单量/
 * 4 车容量数字对齐真实 demo 场景，不导入、不依赖、不改动该测试或 routing 包任何类 —
 * 见 feat/logi-capacity-diagnosis brief 硬约束）：21 门店 / 4 车队 47m³ / 需求 57.2m³ /
 * 6 趟分配到 4 辆车 → INSUFFICIENT，suggestedAddCbm = ceil(57.2-47) = 11。
 */
class CapacityDiagnosisTest {

    @Test
    @DisplayName("SUFFICIENT — 需求在车队单轮运力内，且每车只跑一趟")
    void sufficientWhenDemandFitsAndNoVehicleRunsTwice() {
        List<LogisticsDeliveryOrder> orders = List.of(
                order("o1", "5.0", "1200"),
                order("o2", "4.0", "900"));
        List<LogisticsVehicleProfile> fleet = List.of(
                vehicle("V-01", "10", "3200"),
                vehicle("V-02", "10", "3200"));
        List<LogisticsTrip> trips = List.of(
                trip("t1", "V-01"),
                trip("t2", "V-02"));

        CapacityDiagnosisDto result = CapacityDiagnosis.diagnose(orders, trips, fleet, 0);

        assertThat(result.getVerdict()).isEqualTo(CapacityVerdict.SUFFICIENT);
        assertThat(result.getTotalDemandCbm()).isEqualByComparingTo("9.0");
        assertThat(result.getFleetSingleRoundCbm()).isEqualByComparingTo("20.0");
        assertThat(result.getVehicleCount()).isEqualTo(2);
        assertThat(result.getUsedTripCount()).isEqualTo(2);
        assertThat(result.getMultiTripVehicleCount()).isZero();
        assertThat(result.getUnassignedCount()).isZero();
        assertThat(result.getSuggestedAddCbm()).isEqualByComparingTo("0");
        assertThat(result.getMessage())
                .contains("运力充足")
                .contains("2 辆车")
                .contains("9.0m³");
    }

    @Test
    @DisplayName("INSUFFICIENT — demo 场景：21 店 / 4 车 47m³ / 需求 57.2m³ / 6 趟 (2 车回仓补货再出发)")
    void insufficientMirrorsDemoScenario() {
        List<LogisticsDeliveryOrder> orders = demoOrders();
        List<LogisticsVehicleProfile> fleet = List.of(
                vehicle("V-01", "10", "3200"),
                vehicle("V-02", "15", "3800"),
                vehicle("V-03", "10", "3000"),
                vehicle("V-04", "12", "3800"));
        // 6 趟分配到 4 辆车：V-01×1, V-02×2, V-03×2, V-04×1（同 LogisticsRoutingDemoScenarioTest 断言）。
        List<LogisticsTrip> trips = List.of(
                trip("t1", "V-01"),
                trip("t2", "V-02"), trip("t3", "V-02"),
                trip("t4", "V-03"), trip("t5", "V-03"),
                trip("t6", "V-04"));

        CapacityDiagnosisDto result = CapacityDiagnosis.diagnose(orders, trips, fleet, 0);

        assertThat(result.getVerdict()).isEqualTo(CapacityVerdict.INSUFFICIENT);
        assertThat(result.getTotalDemandCbm()).isEqualByComparingTo("57.2");
        assertThat(result.getFleetSingleRoundCbm()).isEqualByComparingTo("47.0");
        assertThat(result.getVehicleCount()).isEqualTo(4);
        assertThat(result.getUsedTripCount()).isEqualTo(6);
        assertThat(result.getMultiTripVehicleCount()).isEqualTo(2); // V-02, V-03
        assertThat(result.getUnassignedCount()).isZero();
        assertThat(result.getSuggestedAddCbm()).isEqualByComparingTo("11"); // ceil(57.2-47.0)
        assertThat(result.getMessage())
                .contains("车队单轮运力不足")
                .contains("57.2m³")
                .contains("47.0m³")
                .contains("6 趟")
                .contains("11m³");
    }

    @Test
    @DisplayName("UNSERVABLE — 有订单未能排入任何车次（无车覆盖区域/超最大车）")
    void unservableWhenOrdersUnassigned() {
        List<LogisticsDeliveryOrder> orders = List.of(
                order("o1", "5.0", "1200"),
                order("o2", "4.0", "900"));
        List<LogisticsVehicleProfile> fleet = List.of(vehicle("V-01", "10", "3200"));
        List<LogisticsTrip> trips = List.of(trip("t1", "V-01"));

        CapacityDiagnosisDto result = CapacityDiagnosis.diagnose(orders, trips, fleet, 3);

        assertThat(result.getVerdict()).isEqualTo(CapacityVerdict.UNSERVABLE);
        assertThat(result.getUnassignedCount()).isEqualTo(3);
        assertThat(result.getMessage())
                .contains("3 单暂无法派送")
                .contains("增派车辆或联系管理员");
    }

    @Test
    @DisplayName("CANCELLED 订单不计入需求 — 与算法/mapper 既有口径一致")
    void cancelledOrdersExcludedFromDemand() {
        LogisticsDeliveryOrder cancelled = order("o-cancelled", "100.0", "9999");
        cancelled.setStatus(DeliveryOrderStatus.CANCELLED);
        List<LogisticsDeliveryOrder> orders = List.of(order("o1", "5.0", "1200"), cancelled);
        List<LogisticsVehicleProfile> fleet = List.of(vehicle("V-01", "10", "3200"));
        List<LogisticsTrip> trips = List.of(trip("t1", "V-01"));

        CapacityDiagnosisDto result = CapacityDiagnosis.diagnose(orders, trips, fleet, 0);

        assertThat(result.getTotalDemandCbm()).isEqualByComparingTo("5.0");
        assertThat(result.getVerdict()).isEqualTo(CapacityVerdict.SUFFICIENT);
    }

    // ---- fixtures ----

    private static LogisticsDeliveryOrder order(String id, String volumeCbm, String weightKg) {
        return LogisticsDeliveryOrder.builder()
                .id(id)
                .factoryId("F001")
                .batchId("B001")
                .storeCode(id)
                .storeName(id)
                .volumeCbm(new BigDecimal(volumeCbm))
                .weightKg(new BigDecimal(weightKg))
                .status(DeliveryOrderStatus.PLANNED)
                .build();
    }

    private static LogisticsVehicleProfile vehicle(String vehicleId, String capacityCbm, String maxWeightKg) {
        return LogisticsVehicleProfile.builder()
                .id(vehicleId + "-profile")
                .vehicleId(vehicleId)
                .factoryId("F001")
                .capacityCbm(new BigDecimal(capacityCbm))
                .maxWeightKg(new BigDecimal(maxWeightKg))
                .source(OwnershipType.OWNED)
                .temperatureMode(TemperatureMode.DUAL_TEMP)
                .active(true)
                .build();
    }

    private static LogisticsTrip trip(String id, String vehicleId) {
        return LogisticsTrip.builder()
                .id(id)
                .factoryId("F001")
                .planId("P001")
                .tripNo(1)
                .vehicleId(vehicleId)
                .status(TripStatus.DRAFT)
                .build();
    }

    /** 体积数字对齐 {@code LogisticsRoutingDemoScenarioTest#demoOrders} — 总计 57.2m³ / 21 店。 */
    private static List<LogisticsDeliveryOrder> demoOrders() {
        String[] vols = {
                "2.500", "2.200", "2.400", "1.900", "2.000", "2.700", "4.500", "3.600", "3.200",
                "3.800", "2.000", "3.000", "2.700", "2.300", "2.400", "2.900", "2.000", "3.400",
                "2.200", "3.000", "2.500",
        };
        List<LogisticsDeliveryOrder> orders = new ArrayList<>();
        for (int i = 0; i < vols.length; i++) {
            orders.add(order("demo-" + i, vols[i], "600"));
        }
        return orders;
    }
}
