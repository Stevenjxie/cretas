package com.cretas.aims.service.sales;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.unit.UnitDisplayNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 销售下单时「这东西发得出去吗」的<b>明示</b> —— 三档判定, <b>第一步只提示、不拦截</b>。
 *
 * <h2>🔴 判据为什么是「当前有在手库存」而不是「历史上入过库」(Steve 2026-08-18 拍板)</h2>
 * 「入过库」是<b>不可撤销的历史事实</b>: 一个曾经入过库、现在早卖完也不再经营的商品照样能卖,
 * 那道闸什么都没守住。用户真正要的是<b>发得出货</b>, 所以判据是<b>当前在手 + 在途/在产</b>。
 *
 * <h2>三档</h2>
 * <ol>
 *   <li><b>有在手</b> → 放行, <b>不出提示</b>(不刷屏)</li>
 *   <li><b>无在手但有在途采购或在产计划</b> → 放行 + 明示「当前 0, 预计 X 到货」</li>
 *   <li><b>两者都没有</b> → 明示 + 告诉下一步(去采购 / 去建生产计划)</li>
 * </ol>
 *
 * <h2>⛔ 为什么默认不拦 —— 实测数字(prod, 2026-08-18)</h2>
 * <pre>
 * F006        目录 197 product_types + 319 raw_material_types = 516 个可下单对象
 *             有在手成品   1 个 (finished_goods_batches 全库仅 1 行)
 *             有在手物料  10 个 (material_batches 65 行活行, 只落在 10 个物料上)
 *             在途采购     0   (5 张采购单全是 COMPLETED/FINANCE_REJECTED, 未收量 0)
 *             在产计划     1 个 (IN_PROGRESS 1 张)
 *             ⇒ 有信号的约 12/516 ≈ 2.3%
 * LIUSHANMEN  目录 22 + 139 = 161 个可下单对象
 *             material_batches 0 行、finished_goods_batches 0 行 —— 一条库存记录都没有
 *             而它有 8 张真实销售订单 ⇒ <b>硬上任何一档都会把这家 100% 卡死</b>
 * </pre>
 *
 * <p>本仓形态 E:「宁可窄而可信, 不要宽到被人关掉」; 形态 D′:「一个会误报的提示比没有提示更糟」。
 * 在入库覆盖率上来之前, 第三档的措辞刻意只陈述<b>记录事实</b>(「系统里查不到入库记录」),
 * ⛔ 不说「你发不出货」—— 2.3% 的覆盖率下, 后者绝大多数时候是<b>误报</b>。
 *
 * <h2>开关</h2>
 * {@link #ENFORCE_GATE_PROPERTY} 默认 {@code false}。打开它的前提写在该常量的 javadoc 上。
 * ⚠️ 刻意<b>不</b>写成 {@code static final boolean} 编译期常量 —— 那样 javac 会把整个分支消掉,
 * 拦截逻辑变成<b>永远到不了的代码</b>, 测试也验不了它(本仓形态 B「机制在、没接上」)。
 */
@Service
public class SalesOrderStockAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderStockAvailabilityService.class);

    /**
     * 拦截开关的属性名。默认 {@code false} —— <b>只明示不拦截</b>。
     *
     * <h3>🔴 打开它之前必须同时成立的前提</h3>
     * <ol>
     *   <li><b>入库覆盖率</b>: 该租户「可下单对象里有在手/在途/在产信号的比例」不能再是 2.3%。
     *       建议阈值 ≥ 80%, 且<b>逐租户</b>开(本属性可按租户覆盖), 不要全局一把开 ——
     *       LIUSHANMEN 今天是 0%, 全局开等于把它整个停业。</li>
     *   <li><b>原料/辅材/包材也要有在手口径</b>: 目前物料侧读 {@code material_batches},
     *       它只覆盖「走采购入库」的物料; 客供料 (ownership≠COMPANY_OWNED) 被
     *       {@code findAvailableBatchesFEFO} 明确排除, 这类订单会被误判成第三档。</li>
     *   <li><b>先让第三档的提示跑一段时间</b>, 确认排在最前面的那批命中<b>经得起用户去查</b>
     *       (形态 D′)。提示天天误报再改成拦截, 拦的就是同一批误报。</li>
     * </ol>
     */
    public static final String ENFORCE_GATE_PROPERTY = "cretas.sales.stock-availability-gate.enforce";

    /**
     * 在途采购: 这些状态的采购单算「还会到货」。
     *
     * <p>⛔ 刻意<b>不</b>含 {@code DRAFT}/{@code SUBMITTED}/{@code WORKFLOW_RUNNING} —— 还没批,
     * 不能拿它当「货在路上」向销售承诺; 也不含 {@code COMPLETED}/{@code CLOSED}/
     * {@code CANCELLED}/{@code FINANCE_REJECTED} —— 不会再有量进来。
     * 取值逐条对着 {@link PurchaseOrderStatus} 的枚举核过, ⛔ 不是猜的。
     */
    private static final Collection<PurchaseOrderStatus> INBOUND_PURCHASE_STATUSES = EnumSet.of(
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.PENDING_FINANCE_REVIEW,
            PurchaseOrderStatus.FINANCE_APPROVED,
            PurchaseOrderStatus.PARTIAL_RECEIVED);

    /** 在产计划: 这些状态的计划算「还会产出」。COMPLETED/CANCELLED 不算。 */
    private static final Collection<ProductionPlanStatus> INBOUND_PLAN_STATUSES = EnumSet.of(
            ProductionPlanStatus.PLANNED,
            ProductionPlanStatus.PENDING,
            ProductionPlanStatus.IN_PROGRESS,
            ProductionPlanStatus.PAUSED);

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final MaterialBatchRepository materialBatchRepository;

    /** Optional —— 未注册时在途采购一档读不到, 由 {@link Availability#inboundKnown()} 显式说明「没量到」。 */
    @Autowired(required = false)
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired(required = false)
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired(required = false)
    private ProductionPlanRepository productionPlanRepository;

    @Value("${" + ENFORCE_GATE_PROPERTY + ":false}")
    private boolean enforceGate;

    public SalesOrderStockAvailabilityService(
            FinishedGoodsBatchRepository finishedGoodsBatchRepository,
            MaterialBatchRepository materialBatchRepository) {
        this.finishedGoodsBatchRepository = finishedGoodsBatchRepository;
        this.materialBatchRepository = materialBatchRepository;
    }

    /** 三档。 */
    public enum Tier {
        /** 有在手 → 放行, 不出提示。 */
        IN_STOCK,
        /** 无在手, 但有在途采购或在产计划 → 放行 + 明示预计到货。 */
        INBOUND_ONLY,
        /** 两者都没有 → 明示 + 下一步。开关打开时才拦。 */
        NONE
    }

    /**
     * 一行的判定读数。
     *
     * <p>🔴 {@code inboundKnown=false} 表示<b>在途这一档没量到</b>(仓库未注册),
     * 与「量到了、是 0」是两件事 —— 不区分就会把「仪器没起来」读成「确实没有在途」。
     */
    public record Availability(
            String referenceId,
            String displayName,
            Tier tier,
            BigDecimal onHandQty,
            BigDecimal inboundQty,
            boolean inboundKnown,
            LocalDate earliestInboundDate,
            String unit,
            String message) {
    }

    /** 一行的入参 —— 只要判定需要的三个字段, 不依赖 SalesOrderItem 实体。 */
    public record Line(String referenceId, String displayName, String unit) {
    }

    /**
     * 逐行判定。⛔ 不抛异常 —— 拦不拦由调用方按 {@link #isEnforcing()} 决定。
     */
    public List<Availability> assess(String factoryId, List<Line> lines) {
        List<Availability> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        for (Line line : lines) {
            if (line == null || line.referenceId() == null || line.referenceId().isBlank()) {
                continue;
            }
            out.add(assessOne(factoryId, line));
        }
        return out;
    }

    private Availability assessOne(String factoryId, Line line) {
        String refId = line.referenceId();
        BigDecimal onHand = onHandQuantity(factoryId, refId);
        InboundReading inbound = inboundQuantity(factoryId, refId);
        String unitLabel = UnitDisplayNames.display(line.unit());
        String suffix = unitLabel == null || unitLabel.isBlank() ? "" : unitLabel;
        String name = line.displayName() == null || line.displayName().isBlank()
                ? refId : line.displayName();

        if (onHand.signum() > 0) {
            return new Availability(refId, name, Tier.IN_STOCK, onHand, inbound.qty(),
                    inbound.known(), inbound.earliestDate(), line.unit(), null);
        }
        if (inbound.qty().signum() > 0) {
            String eta = inbound.earliestDate() == null
                    ? "到货日期未填" : "预计 " + inbound.earliestDate() + " 到货";
            return new Availability(refId, name, Tier.INBOUND_ONLY, onHand, inbound.qty(),
                    inbound.known(), inbound.earliestDate(), line.unit(),
                    "「" + name + "」当前在手 0" + suffix
                            + "，在途/在产 " + trim(inbound.qty()) + suffix + "，" + eta + "。");
        }
        // 第三档: 只陈述记录事实, 不断言「你发不出货」—— 见类 javadoc 的覆盖率数字。
        String notMeasured = inbound.known() ? "" : "（在途采购这一档本次没量到）";
        return new Availability(refId, name, Tier.NONE, onHand, inbound.qty(),
                inbound.known(), null, line.unit(),
                "「" + name + "」在系统里查不到在手库存记录，也没有在途采购或在产计划"
                        + notMeasured + "。若确实有货请先做入库；否则请先去采购或建生产计划。");
    }

    /**
     * 在手 = 成品批次可用量 + 物料批次可用量。
     *
     * <p>⚠️ 销售行的 {@code productTypeId} 既可能指向 {@code product_types}(成品),
     * 也可能指向物料字典 —— 销售下拉本来就是「成品 + 物料」两类合一。所以两边都查,
     * ⛔ 不靠猜它是哪一类。
     *
     * <p>可用量一律取实体自己的 {@code getAvailableQuantity()} ——
     * ⛔ 不在这里重写一遍减法(本仓形态 D: 同一个东西有两份, 它一定会漂)。
     */
    private BigDecimal onHandQuantity(String factoryId, String referenceId) {
        BigDecimal total = BigDecimal.ZERO;
        List<FinishedGoodsBatch> fg =
                finishedGoodsBatchRepository.findAvailableBatches(factoryId, referenceId);
        if (fg != null) {
            for (FinishedGoodsBatch b : fg) {
                BigDecimal q = b.getAvailableQuantity();
                if (q != null) {
                    total = total.add(q);
                }
            }
        }
        List<MaterialBatch> mb =
                materialBatchRepository.findAvailableBatchesFEFO(factoryId, referenceId);
        if (mb != null) {
            for (MaterialBatch b : mb) {
                // ⚠️ 物料侧叫 getCurrentQuantity(), 成品侧叫 getAvailableQuantity() —— 名字不同,
                // 算法都是「收 - 用 - 预留」。一律用实体自己的那支, ⛔ 不在这里重写减法。
                BigDecimal q = b.getCurrentQuantity();
                if (q != null) {
                    total = total.add(q);
                }
            }
        }
        return total;
    }

    private record InboundReading(BigDecimal qty, boolean known, LocalDate earliestDate) {
    }

    private InboundReading inboundQuantity(String factoryId, String referenceId) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDate earliest = null;
        boolean known = true;

        // ① 在途采购 (物料侧)
        if (purchaseOrderRepository == null || purchaseOrderItemRepository == null) {
            known = false;
        } else {
            List<PurchaseOrder> orders = purchaseOrderRepository
                    .findByFactoryIdAndStatusIn(factoryId, INBOUND_PURCHASE_STATUSES);
            if (orders != null && !orders.isEmpty()) {
                List<String> ids = orders.stream().map(PurchaseOrder::getId)
                        .filter(Objects::nonNull).toList();
                List<PurchaseOrderItem> items = ids.isEmpty()
                        ? List.of() : purchaseOrderItemRepository.findByPurchaseOrderIdIn(ids);
                for (PurchaseOrderItem it : items == null ? List.<PurchaseOrderItem>of() : items) {
                    if (it == null || !referenceId.equals(it.getMaterialTypeId())) {
                        continue;
                    }
                    BigDecimal ordered = it.getQuantity() == null ? BigDecimal.ZERO : it.getQuantity();
                    BigDecimal received = it.getReceivedQuantity() == null
                            ? BigDecimal.ZERO : it.getReceivedQuantity();
                    BigDecimal outstanding = ordered.subtract(received);
                    if (outstanding.signum() > 0) {
                        total = total.add(outstanding);
                        earliest = earlier(earliest, expectedDate(orders, it.getPurchaseOrderId()));
                    }
                }
            }
        }

        // ② 在产计划 (成品侧)
        if (productionPlanRepository == null) {
            known = false;
        } else {
            List<ProductionPlan> plans =
                    productionPlanRepository.findByFactoryIdAndProductTypeId(factoryId, referenceId);
            for (ProductionPlan p : plans == null ? List.<ProductionPlan>of() : plans) {
                if (p == null || p.getStatus() == null || !INBOUND_PLAN_STATUSES.contains(p.getStatus())) {
                    continue;
                }
                BigDecimal planned = p.getPlannedQuantity() == null
                        ? BigDecimal.ZERO : p.getPlannedQuantity();
                BigDecimal produced = p.getActualQuantity() == null
                        ? BigDecimal.ZERO : p.getActualQuantity();
                BigDecimal outstanding = planned.subtract(produced);
                if (outstanding.signum() > 0) {
                    total = total.add(outstanding);
                    earliest = earlier(earliest, p.getExpectedCompletionDate());
                }
            }
        }
        return new InboundReading(total, known, earliest);
    }

    private LocalDate expectedDate(List<PurchaseOrder> orders, String orderId) {
        if (orderId == null) {
            return null;
        }
        return orders.stream()
                .filter(o -> orderId.equals(o.getId()))
                .map(PurchaseOrder::getExpectedDeliveryDate)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isBefore(a) ? b : a;
    }

    private static String trim(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 拦截开关当前是否打开。默认 false —— 只明示不拦截。 */
    public boolean isEnforcing() {
        return enforceGate;
    }

    /** 仅供测试翻转开关 —— 让「拦截」这条分支真的跑得到。 */
    void setEnforcingForTest(boolean enforcing) {
        this.enforceGate = enforcing;
        log.debug("stock availability gate enforcing set to {}", enforcing);
    }
}
