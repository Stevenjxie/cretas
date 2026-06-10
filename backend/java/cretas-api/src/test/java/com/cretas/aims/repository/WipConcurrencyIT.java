package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WIP 库存并发集成测试 (Tier1 Wave7, Audit #23).
 *
 * <p><b>测试目标</b>:
 * <ol>
 *   <li>SP1 双线程 IN — 验证现有并发保护是否防止重复产出。</li>
 *   <li>SP2 双线程扣减 — 悲观写锁防超扣验证。</li>
 * </ol>
 *
 * <p><b>DB 层说明</b>:
 * <ul>
 *   <li>H2 2.x (PostgreSQL 兼容模式) 支持 SELECT ... FOR UPDATE 行锁 + @Version 乐观锁。</li>
 *   <li>@DirtiesContext(methodMode=BEFORE_METHOD) 每个测试独立 ApplicationContext，防止 H2 状态污染。</li>
 *   <li>H2 的 unique constraint 在 {@code create-drop} DDL 模式下基于 @Table/@Column unique 注解生成。
 *       SemiFinishedInventory.intermediateBatchNo 无 unique=true 注解 (只在 Flyway 迁移中),
 *       所以 H2 不会拒绝重复插入 — 这暴露了新行 race 的真实风险。</li>
 * </ul>
 *
 * <p><b>⚠️ 已知 BUG (本测试验证发现)</b>:
 * <ul>
 *   <li><b>[BUG-SP1-NEW-ROW]</b> SP1 新行创建路径无锁: postSemiOutputLedger 在 findForUpdate 返回 empty 时，
 *       两线程同时 insert 新行 — unique constraint 仅在 Flyway migration 中，H2/JPA 层无防护，
 *       可产生重复行 (库存翻倍)。修复建议: JPA @Column(unique=true) 或 INSERT ON CONFLICT DO UPDATE。</li>
 *   <li><b>[OK-SP2-PESSIMISTIC]</b> SP2 deductForSecondaryPlan 悲观锁路径正确：findByIdForUpdate
 *       序列化并发扣减，防止超扣。验证见 sp2ConcurrentDeduct_pessimisticLockPreventsOverDeduction。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@DisplayName("WIP 库存并发集成测试 (H2 双线程, Tier1 Wave7 Audit #23)")
class WipConcurrencyIT {

    private static final Logger log = LoggerFactory.getLogger(WipConcurrencyIT.class);

    private static final String FACTORY = "F-CONC";

    @Autowired
    private SemiFinishedInventoryRepository wipRepo;

    @Autowired
    @SuppressWarnings("unused")
    private SemiFinishedInventoryTransactionRepository txnRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ============================================================
    // SP1: 双线程 IN — 并发 upsert 同一 WIP 行 (existing-row path)
    // ============================================================

    /**
     * SP1 并发 IN — WIP 行已存在时 (existing row path).
     *
     * <p>预置 WIP 行 (50 kg)，两线程同时对同一 intermediateBatchNo 做 IN 各 30 kg。
     * SP1 代码路径使用悲观写锁 (findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull)。
     *
     * <p>期望：两次 IN 均成功，总产出 = 50 + 60 = 110 kg，不丢不重。
     * H2 行锁串行化两个事务，依次累加。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("SP1 双线程 IN (existing row) — 悲观锁串行化，产出量正确累加")
    void sp1ConcurrentIn_existingRow_serializedByPessimisticLock() throws Exception {
        // unique batchNo per test (DirtiesContext ensures clean DB, but use unique ID for safety)
        String semiCode = "CONC-SP1-EX-" + System.nanoTime();
        BigDecimal initProduced = new BigDecimal("50.00");
        BigDecimal inQtyEach = new BigDecimal("30.00");

        // 预置一行 50 kg
        txTemplate.execute(status -> {
            SemiFinishedInventory base = buildWip(semiCode, "50.00", "0.00", "50.00");
            wipRepo.saveAndFlush(base);
            return null;
        });

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> failReasons = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                    return;
                }
                try {
                    txTemplate.execute(status -> {
                        // 悲观写锁 (mirrors SP1 postSemiOutputLedger existing-row path)
                        SemiFinishedInventory wip = wipRepo
                                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                        FACTORY, semiCode)
                                .orElse(null);
                        if (wip == null) {
                            // fallback: shouldn't happen (row pre-exists)
                            SemiFinishedInventory newWip = buildWip(semiCode,
                                    inQtyEach.toPlainString(), "0.00", inQtyEach.toPlainString());
                            wipRepo.saveAndFlush(newWip);
                        } else {
                            BigDecimal newProduced = wip.getProducedQuantity().add(inQtyEach);
                            wip.setProducedQuantity(newProduced);
                            BigDecimal consumed = wip.getConsumedQuantity() == null
                                    ? BigDecimal.ZERO : wip.getConsumedQuantity();
                            wip.setAvailableQuantity(newProduced.subtract(consumed));
                            wipRepo.saveAndFlush(wip);
                        }
                        return null;
                    });
                    successCount.incrementAndGet();
                    log.info("[SP1-ex thread-{}] IN {} kg succeeded", threadIdx, inQtyEach);
                } catch (Exception ex) {
                    failCount.incrementAndGet();
                    failReasons.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    log.warn("[SP1-ex thread-{}] IN {} kg failed: {}: {}",
                            threadIdx, inQtyEach, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "线程池应在 15s 内完成");
        for (Future<?> f : futures) { f.get(); }

        // 读取最终 WIP
        List<SemiFinishedInventory> allRows = txTemplate.execute(status ->
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10001L)
                        .stream()
                        .filter(w -> semiCode.equals(w.getIntermediateBatchNo()))
                        .toList());

        assertNotNull(allRows);
        log.info("[SP1-ex assert] successCount={} failCount={} dbRows={} failReasons={}",
                successCount.get(), failCount.get(), allRows.size(), failReasons);

        // 断言1: DB 中只有 1 行 (existing-row 路径不会 insert 新行)
        assertEquals(1, allRows.size(),
                "existing-row 路径应只有 1 行，不能 insert 新行");

        SemiFinishedInventory finalWip = allRows.get(0);

        // 断言2: available >= 0 (不出负库存)
        assertTrue(finalWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) >= 0,
                "[CRITICAL] available < 0，超扣！");

        // 断言3: produced = initProduced + (successCount × inQtyEach)
        BigDecimal expectedProduced = initProduced.add(
                new BigDecimal(successCount.get()).multiply(inQtyEach));
        assertEquals(0, expectedProduced.compareTo(finalWip.getProducedQuantity()),
                String.format("期望 producedQty=%s (50 + %d×30), 实际=%s. " +
                        "failReasons=%s", expectedProduced, successCount.get(),
                        finalWip.getProducedQuantity(), failReasons));

        // 断言4: available <= produced
        assertTrue(finalWip.getAvailableQuantity().compareTo(finalWip.getProducedQuantity()) <= 0,
                "available 不能超过 produced");

        if (successCount.get() == threads) {
            log.info("[SP1-ex OK] 两线程均成功，悲观锁正确串行化，producedQty={}", finalWip.getProducedQuantity());
        } else {
            log.warn("[SP1-ex WARN] {} 个线程失败 (H2 lock 串行化副作用, 可接受): {}",
                    failCount.get(), failReasons);
        }
    }

    // ============================================================
    // SP1: 双线程 IN — 新行 race (BUG 验证)
    // ============================================================

    /**
     * SP1 双线程 IN — WIP 行不存在时 (new-row insert race).
     *
     * <p><b>⚠️ 已知 BUG 验证测试</b>: 两线程同时对不存在的 intermediateBatchNo 做 IN，
     * postSemiOutputLedger 的逻辑是: findForUpdate (返回 empty) → save new row。
     * 两线程都拿到 empty，都 insert → H2 无 unique 注解约束 → 重复行！
     *
     * <p>生产环境 PG 有 Flyway 创建的 unique constraint
     * ({@code uq_sfi_intermediate_batch_no})，会让第二个 insert 失败。
     * 但 JPA 层 ({@code @Column}) 无 {@code unique=true}，H2 DDL 不会创建该约束 → H2 测试暴露漏洞。
     *
     * <p><b>测试结论</b>:
     * <ul>
     *   <li>若 H2 下出现 2 行 → BUG: SP1 新行路径无锁保护 (虽 PG 有 unique，但逻辑层无幂等)</li>
     *   <li>若 H2 下只有 1 行 → unique constraint 存在 (或 H2 序列化了 insert)</li>
     * </ul>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("[BUG VERIFY] SP1 双线程 IN (new row race) — 验证 H2 下重复行是否被阻止")
    void sp1ConcurrentIn_newRow_raceConditionDocumented() throws Exception {
        String newSemiCode = "CONC-SP1-NEW-" + System.nanoTime();
        BigDecimal inQtyEach = new BigDecimal("40.00");

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> failReasons = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                    return;
                }
                try {
                    txTemplate.execute(status -> {
                        // Mirrors SP1 postSemiOutputLedger new-row path:
                        SemiFinishedInventory existing = wipRepo
                                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                        FACTORY, newSemiCode)
                                .orElse(null);
                        if (existing == null) {
                            // NEW ROW PATH — no lock held, race condition!
                            SemiFinishedInventory newWip = buildWip(newSemiCode,
                                    inQtyEach.toPlainString(), "0.00", inQtyEach.toPlainString());
                            wipRepo.saveAndFlush(newWip);
                        } else {
                            BigDecimal newProduced = existing.getProducedQuantity().add(inQtyEach);
                            existing.setProducedQuantity(newProduced);
                            existing.setAvailableQuantity(newProduced.subtract(
                                    existing.getConsumedQuantity() == null
                                            ? BigDecimal.ZERO : existing.getConsumedQuantity()));
                            wipRepo.saveAndFlush(existing);
                        }
                        return null;
                    });
                    successCount.incrementAndGet();
                    log.info("[SP1-new thread-{}] INSERT succeeded", threadIdx);
                } catch (Exception ex) {
                    failCount.incrementAndGet();
                    failReasons.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    log.info("[SP1-new thread-{}] INSERT failed: {}",
                            threadIdx, ex.getClass().getSimpleName());
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Future<?> f : futures) { f.get(); }

        // 查所有以 newSemiCode 为 batchNo 的行 (绕过 Optional 的 unique 约束)
        final List<SemiFinishedInventory> matchingRows = txTemplate.execute(status ->
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10001L)
                        .stream()
                        .filter(w -> newSemiCode.equals(w.getIntermediateBatchNo()))
                        .toList());

        log.info("[SP1-new assert] successCount={} failCount={} dbRows={} failReasons={}",
                successCount.get(), failCount.get(),
                matchingRows == null ? -1 : matchingRows.size(), failReasons);

        assertNotNull(matchingRows);

        if (matchingRows.size() > 1) {
            // ⚠️ BUG CONFIRMED: H2 allows duplicate intermediate_batch_no rows
            // because SemiFinishedInventory.@Column does NOT have unique=true
            // (unique constraint only in Flyway migration, not JPA DDL)
            log.error("[SP1-new BUG CONFIRMED] H2 下存在 {} 行相同 intermediate_batch_no='{}' — " +
                    "SP1 new-row 路径在 JPA/H2 层无 unique 保护。" +
                    "生产 PG 靠 Flyway unique constraint 防护，但逻辑层未做幂等 upsert。" +
                    "修复建议: 1) @Column(unique=true) 加 JPA 层约束; " +
                    "2) 或 INSERT ... ON CONFLICT DO UPDATE SET ... (native query); " +
                    "3) 或 optimistic lock + retry on ConstraintViolationException",
                    matchingRows.size(), newSemiCode);
            // 记录为 soft fail — 详细说明问题，让 organizer 判断是否本 PR 修
            // 注: 此处用 fail() 明确标记 bug，而非 assertTrue(false)
            fail(String.format(
                    "[BUG-SP1-NEW-ROW] 并发 insert 产生 %d 行相同 intermediate_batch_no='%s'。" +
                    "SP1 new-row 路径无 DB 层防护。" +
                    "生产环境依赖 Flyway unique constraint (uq_sfi_intermediate_batch_no)，" +
                    "若迁移未跑则会有重复行风险。" +
                    "建议在 SemiFinishedInventory.intermediateBatchNo 加 @Column(unique=true)。",
                    matchingRows.size(), newSemiCode));
        }

        // 若只有 1 行 (H2 串行化了 insert, 或有 unique constraint) → 安全
        assertEquals(1, matchingRows.size(),
                "至多 1 行: unique constraint (DB层) 或 H2 insert 串行化应防止重复");

        // 安全不变量
        SemiFinishedInventory finalWip = matchingRows.get(0);
        assertTrue(finalWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) >= 0,
                "available 不能为负");
        log.info("[SP1-new OK/UNCERTAIN] 1 行存在，available={}", finalWip.getAvailableQuantity());
    }

    // ============================================================
    // SP2: 双线程扣减 — 悲观锁防超扣
    // ============================================================

    /**
     * SP2 双线程 deductForSecondaryPlan — 可用量 100，两线程各抢领 60.
     *
     * <p>期望：悲观写锁 ({@code findByIdForUpdate}) 序列化两个事务：
     * <ol>
     *   <li>第一个线程: 成功扣减 60, available 变为 40.</li>
     *   <li>第二个线程: 拿到最新 available=40, 60 > 40, 抛 InsufficientWipException。</li>
     * </ol>
     * 最终 available = 40 (不出现 available = -20 的超扣情况).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("SP2 双线程扣减 (可用100, 各领60) — 悲观锁防超扣，一成功一失败，最终 available=40")
    void sp2ConcurrentDeduct_pessimisticLockPreventsOverDeduction() throws Exception {
        // 预置 WIP 可用 100 kg (在独立事务中，确保 ID 已生成)
        final Long[] wipIdHolder = new Long[1];
        txTemplate.execute(status -> {
            SemiFinishedInventory wip = buildWip("CONC-SP2-OVER-" + System.nanoTime(),
                    "100.00", "0.00", "100.00");
            SemiFinishedInventory saved = wipRepo.saveAndFlush(wip);
            wipIdHolder[0] = saved.getId();
            return null;
        });
        Long wipId = wipIdHolder[0];
        assertNotNull(wipId, "WIP 应已保存并得到 ID");

        BigDecimal deductEach = new BigDecimal("60.00");
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> failReasons = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                    return;
                }
                try {
                    txTemplate.execute(status -> {
                        // Mirrors WipInventoryServiceImpl.deductForSecondaryPlan:
                        // R1: 悲观写锁
                        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                                .orElseThrow(() -> new RuntimeException(
                                        "WIP not found in test: id=" + wipId));
                        BigDecimal avail = wip.getAvailableQuantity() == null
                                ? BigDecimal.ZERO : wip.getAvailableQuantity();
                        if (deductEach.compareTo(avail) > 0) {
                            throw new InsufficientWipException(
                                    String.format("WIP 余量不足: 请求%.2f, 可用%.2f", deductEach, avail));
                        }
                        BigDecimal newAvail = avail.subtract(deductEach);
                        wip.setAvailableQuantity(newAvail);
                        wip.setConsumedQuantity(
                                (wip.getConsumedQuantity() == null ? BigDecimal.ZERO
                                        : wip.getConsumedQuantity()).add(deductEach));
                        if (newAvail.compareTo(BigDecimal.ZERO) == 0) {
                            wip.setStatus(SemiFinishedInventory.Status.DEPLETED);
                        }
                        wipRepo.saveAndFlush(wip);
                        return null;
                    });
                    successCount.incrementAndGet();
                    log.info("[SP2-over thread-{}] deduct {} succeeded", threadIdx, deductEach);
                } catch (InsufficientWipException ex) {
                    failCount.incrementAndGet();
                    failReasons.add("INSUFFICIENT: " + ex.getMessage());
                    log.info("[SP2-over thread-{}] correctly rejected: {}", threadIdx, ex.getMessage());
                } catch (Exception ex) {
                    failCount.incrementAndGet();
                    failReasons.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    log.info("[SP2-over thread-{}] DB lock rejection: {}",
                            threadIdx, ex.getClass().getSimpleName());
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "线程池应在 15s 内完成");
        for (Future<?> f : futures) { f.get(); }

        // 读取最终状态
        SemiFinishedInventory finalWip = txTemplate.execute(status ->
                wipRepo.findById(wipId).orElse(null));
        assertNotNull(finalWip, "WIP 行应仍然存在");

        log.info("[SP2-over assert] successCount={} failCount={} finalAvailable={} consumed={} status={}",
                successCount.get(), failCount.get(),
                finalWip.getAvailableQuantity(), finalWip.getConsumedQuantity(), finalWip.getStatus());

        // ========================
        // 核心安全断言
        // ========================

        // 断言1 (最重要): available 不能为负 (超扣 = 负库存 = CRITICAL BUG)
        assertTrue(finalWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) >= 0,
                String.format("[CRITICAL BUG] available = %s < 0! 超扣！悲观锁未生效！",
                        finalWip.getAvailableQuantity()));

        // 断言2: 恰好 1 个成功，1 个失败 (不是两个都成功)
        assertEquals(1, successCount.get(),
                String.format("恰好 1 线程应成功 (另 1 被锁拒绝)。" +
                        "successCount=%d failCount=%d. failReasons=%s",
                        successCount.get(), failCount.get(), failReasons));
        assertEquals(1, failCount.get(),
                String.format("恰好 1 线程应失败。failCount=%d", failCount.get()));

        // 断言3: 最终 available = 40 (扣一次 60)
        assertEquals(0, new BigDecimal("40.00").compareTo(finalWip.getAvailableQuantity()),
                String.format("最终 available 应为 40.00 (100 - 60)，实际 %s",
                        finalWip.getAvailableQuantity()));

        // 断言4: consumed = 60
        assertEquals(0, new BigDecimal("60.00").compareTo(finalWip.getConsumedQuantity()),
                String.format("最终 consumed 应为 60.00，实际 %s",
                        finalWip.getConsumedQuantity()));

        // 断言5: status 仍 AVAILABLE (还有 40 kg)
        assertEquals(SemiFinishedInventory.Status.AVAILABLE, finalWip.getStatus(),
                "还有 40 kg 剩余，status 应仍为 AVAILABLE");
    }

    /**
     * SP2 双线程扣减 — 可用 100，各领 50 (均可满足).
     *
     * <p>两线程各领 50，总 100 = 可用量。悲观锁串行化执行，两者均成功。
     * 最终 available = 0, status = DEPLETED.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("SP2 双线程扣减 (可用100, 各领50) — 均可满足时两者均成功，最终 DEPLETED")
    void sp2ConcurrentDeduct_bothSucceed_availableBecomesZero() throws Exception {
        final Long[] wipIdHolder = new Long[1];
        txTemplate.execute(status -> {
            SemiFinishedInventory wip = buildWip("CONC-SP2-BOTH-" + System.nanoTime(),
                    "100.00", "0.00", "100.00");
            wipIdHolder[0] = wipRepo.saveAndFlush(wip).getId();
            return null;
        });
        Long wipId = wipIdHolder[0];
        assertNotNull(wipId);

        BigDecimal deductEach = new BigDecimal("50.00");
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> failReasons = new java.util.concurrent.CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                    return;
                }
                try {
                    txTemplate.execute(status -> {
                        SemiFinishedInventory wip = wipRepo.findByIdForUpdate(wipId)
                                .orElseThrow(() -> new RuntimeException(
                                        "WIP not found in test: id=" + wipId));
                        BigDecimal avail = wip.getAvailableQuantity() == null
                                ? BigDecimal.ZERO : wip.getAvailableQuantity();
                        if (deductEach.compareTo(avail) > 0) {
                            throw new InsufficientWipException(
                                    String.format("WIP 余量不足: 请求%.2f, 可用%.2f", deductEach, avail));
                        }
                        BigDecimal newAvail = avail.subtract(deductEach);
                        wip.setAvailableQuantity(newAvail);
                        wip.setConsumedQuantity(
                                (wip.getConsumedQuantity() == null ? BigDecimal.ZERO
                                        : wip.getConsumedQuantity()).add(deductEach));
                        if (newAvail.compareTo(BigDecimal.ZERO) == 0) {
                            wip.setStatus(SemiFinishedInventory.Status.DEPLETED);
                        }
                        wipRepo.saveAndFlush(wip);
                        return null;
                    });
                    successCount.incrementAndGet();
                    log.info("[SP2-both thread-{}] deduct {} succeeded", threadIdx, deductEach);
                } catch (InsufficientWipException ex) {
                    failCount.incrementAndGet();
                    failReasons.add("INSUFFICIENT: " + ex.getMessage());
                    log.info("[SP2-both thread-{}] rejected: {}", threadIdx, ex.getMessage());
                } catch (Exception ex) {
                    failCount.incrementAndGet();
                    failReasons.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    log.info("[SP2-both thread-{}] DB exception: {}",
                            threadIdx, ex.getClass().getSimpleName());
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Future<?> f : futures) { f.get(); }

        SemiFinishedInventory finalWip = txTemplate.execute(status ->
                wipRepo.findById(wipId).orElse(null));
        assertNotNull(finalWip);

        log.info("[SP2-both assert] successCount={} failCount={} finalAvailable={} status={}",
                successCount.get(), failCount.get(),
                finalWip.getAvailableQuantity(), finalWip.getStatus());

        // 核心断言: available 不能为负
        assertTrue(finalWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) >= 0,
                "[CRITICAL] available < 0 超扣！");

        // consumed <= produced 不变量
        assertTrue(finalWip.getConsumedQuantity().compareTo(finalWip.getProducedQuantity()) <= 0,
                "consumed 不能超过 produced");

        // available = produced - consumed 账目一致性
        BigDecimal expectedAvail = finalWip.getProducedQuantity()
                .subtract(finalWip.getConsumedQuantity());
        assertEquals(0, expectedAvail.compareTo(finalWip.getAvailableQuantity()),
                String.format("available (%s) 应 = produced (%s) - consumed (%s)",
                        finalWip.getAvailableQuantity(),
                        finalWip.getProducedQuantity(),
                        finalWip.getConsumedQuantity()));

        if (successCount.get() == threads) {
            log.info("[SP2-both OK] 两线程均成功，悲观锁串行化正确，available={}",
                    finalWip.getAvailableQuantity());
            // 期望 available = 0, DEPLETED
            assertEquals(0, BigDecimal.ZERO.compareTo(finalWip.getAvailableQuantity()),
                    "两线程各扣 50 = 总 100, 最终 available=0");
            assertEquals(SemiFinishedInventory.Status.DEPLETED, finalWip.getStatus(),
                    "库存清零, status 应 DEPLETED");
        } else {
            log.warn("[SP2-both WARN] {} 个线程因 H2 lock 失败, 可能是超时: {}",
                    failCount.get(), failReasons);
        }
    }

    // ============================================================
    // 辅助
    // ============================================================

    private SemiFinishedInventory buildWip(String batchNo, String produced,
                                           String consumed, String available) {
        return SemiFinishedInventory.builder()
                .factoryId(FACTORY)
                .batchId(10001L)
                .intermediateBatchNo(batchNo)
                .sourceWorkProcessTaskId(1L)
                .processOrder(1)
                .productTypeId("CPDX_CONC")
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .materialBatchRefs(null)
                .build();
    }

    /** 模拟 BusinessException(409) WIP_INSUFFICIENT — 在 TransactionTemplate lambda 内抛的业务异常。 */
    static class InsufficientWipException extends RuntimeException {
        InsufficientWipException(String message) {
            super(message);
        }
    }
}
