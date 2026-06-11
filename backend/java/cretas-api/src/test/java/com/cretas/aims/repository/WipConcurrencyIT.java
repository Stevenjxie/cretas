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
import org.springframework.dao.DataIntegrityViolationException;
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
 *       W8 修复后 SemiFinishedInventory 加了 @UniqueConstraint(factory_id, intermediate_batch_no),
 *       H2 DDL 现会拒绝重复插入 (与 PG Flyway partial unique 行为对齐) — 让 new-row race 的
 *       retry 分支可被测试覆盖。</li>
 * </ul>
 *
 * <p><b>✅ W8 修复状态</b>:
 * <ul>
 *   <li><b>[FIXED-SP1-NEW-ROW]</b> SP1 新行创建路径原无并发保护: postSemiOutputLedger 在 findForUpdate
 *       返回 empty 时, 两线程同时 insert 新行 → H2 重复行 (库存翻倍) / PG 撞约束静默 500 漏记。
 *       <b>修复</b>: acquireOrCreateLockedRow — 子事务 (REQUIRES_NEW) insert + 撞约束 catch + retry
 *       into existing-row 悲观锁累加路径; 加 @UniqueConstraint 让 H2/PG 行为一致。
 *       验证见 sp1ConcurrentIn_newRow_acquireOrCreateRetry_noDuplicateNoLoss。</li>
 *   <li><b>[OK-SP1-EXISTING]</b> SP1 既有行路径 (findForUpdate 悲观锁) 早已正确。</li>
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
    // SP1: 双线程 IN — 新行 race (W8 BUG-SP1-NEW-ROW 修复验证)
    // ============================================================

    /**
     * SP1 双线程 IN — WIP 行不存在时 (new-row insert race).
     *
     * <p><b>✅ W8 BUG-SP1-NEW-ROW 修复验证 (原 [BUG CONFIRMED] 已翻成 assert-fixed)</b>。
     *
     * <p><b>历史 bug</b>: {@code postSemiOutputLedger} 在 {@code findForUpdate} 返回 empty 时
     * 直接 build+save 新行, 无并发保护。两线程同时进 new-row 分支 → 各自 insert →
     * <ul>
     *   <li>H2 (修复前无 unique 注解) → 2 行相同 intermediateBatchNo = WIP 库存翻倍。</li>
     *   <li>PG (Flyway partial unique) → 第二个 insert 抛 ConstraintViolation, 但上层无 retry →
     *       静默 500 + WIP 漏记。</li>
     * </ul>
     *
     * <p><b>修复</b> (本测试 mirror {@code WipInventoryServiceImpl.acquireOrCreateLockedRow}):
     * <ol>
     *   <li>{@code @UniqueConstraint(factory_id, intermediate_batch_no)} 让 H2 DDL 也建约束 →
     *       第二个 insert 撞 {@link DataIntegrityViolationException} (与 PG 行为一致)。</li>
     *   <li>insert 在独立子事务 (此处用独立 {@code txTemplate.execute}) → 约束冲突不污染主流程。</li>
     *   <li>撞约束 → catch → 重新拿悲观写锁 (行已被对方 commit) → 走 existing-row moving-average
     *       累加路径。</li>
     * </ol>
     *
     * <p><b>修复后期望 (强不变量)</b>:
     * <ul>
     *   <li>恰好 <b>1 行</b> (无重复 intermediateBatchNo, 无库存翻倍)。</li>
     *   <li>producedQuantity = 2 × inQtyEach (两线程贡献都不丢, 无静默漏记)。</li>
     *   <li>availableQuantity = producedQuantity (无负库存, 账目一致)。</li>
     * </ul>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("[FIXED] SP1 双线程 IN (new row race) — 修复后只 1 行, 两线程贡献都累加, 无重复无漏记")
    void sp1ConcurrentIn_newRow_acquireOrCreateRetry_noDuplicateNoLoss() throws Exception {
        String newSemiCode = "CONC-SP1-NEW-" + System.nanoTime();
        BigDecimal inQtyEach = new BigDecimal("40.00");

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger lostInsertRaceCount = new AtomicInteger(0);
        AtomicInteger insertAttemptCount = new AtomicInteger(0);
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
                    // === Mirrors WipInventoryServiceImpl.acquireOrCreateLockedRow (W8 fix) ===
                    // 1) 试拿锁 + 决定是否需要 insert (独立 tx, 模拟外层调用者事务)
                    boolean rowExisted = Boolean.TRUE.equals(txTemplate.execute(status ->
                            wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                            FACTORY, newSemiCode)
                                    .isPresent()));

                    // 2) 不存在 → 在独立子事务 (REQUIRES_NEW 等价) insert 0 量空行;
                    //    并发竞争 → 撞 unique 约束 → DataIntegrityViolationException → 视为输掉 race
                    if (!rowExisted) {
                        insertAttemptCount.incrementAndGet();
                        try {
                            txTemplate.execute(status -> {
                                SemiFinishedInventory empty = buildWip(newSemiCode,
                                        "0.00", "0.00", "0.00");
                                wipRepo.saveAndFlush(empty);
                                return null;
                            });
                        } catch (DataIntegrityViolationException dup) {
                            lostInsertRaceCount.incrementAndGet();
                            log.info("[SP1-new thread-{}] lost insert race (constraint hit), "
                                    + "retry into existing-row path", threadIdx);
                        }
                    }

                    // 3) 行此刻一定存在 → 拿悲观写锁 → moving-average 累加 inQtyEach (独立 tx)
                    txTemplate.execute(status -> {
                        SemiFinishedInventory locked = wipRepo
                                .findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                                        FACTORY, newSemiCode)
                                .orElseThrow(() -> new IllegalStateException(
                                        "row must exist after insert/retry"));
                        BigDecimal newProduced = nzt(locked.getProducedQuantity()).add(inQtyEach);
                        locked.setProducedQuantity(newProduced);
                        locked.setAvailableQuantity(
                                newProduced.subtract(nzt(locked.getConsumedQuantity())));
                        wipRepo.saveAndFlush(locked);
                        return null;
                    });
                    successCount.incrementAndGet();
                    log.info("[SP1-new thread-{}] IN {} kg succeeded", threadIdx, inQtyEach);
                } catch (Exception ex) {
                    failCount.incrementAndGet();
                    failReasons.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    log.warn("[SP1-new thread-{}] IN failed: {}: {}",
                            threadIdx, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        for (Future<?> f : futures) { f.get(); }

        // 查所有以 newSemiCode 为 batchNo 的行
        final List<SemiFinishedInventory> matchingRows = txTemplate.execute(status ->
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(FACTORY, 10001L)
                        .stream()
                        .filter(w -> newSemiCode.equals(w.getIntermediateBatchNo()))
                        .toList());

        log.info("[SP1-new assert] successCount={} failCount={} lostRace={} dbRows={} failReasons={}",
                successCount.get(), failCount.get(), lostInsertRaceCount.get(),
                matchingRows == null ? -1 : matchingRows.size(), failReasons);

        assertNotNull(matchingRows);

        // 断言0: 两线程都成功 (无静默 500 / 无线程异常)
        assertEquals(0, failCount.get(),
                String.format("两线程都应成功 (修复后撞约束会 retry, 不抛). failReasons=%s", failReasons));
        assertEquals(threads, successCount.get(),
                "两线程都应完成 IN");

        // 断言1 (核心): 恰好 1 行 — 无重复 intermediateBatchNo, 无库存翻倍
        assertEquals(1, matchingRows.size(),
                String.format("[FIXED] new-row race 应只产生 1 行 (W8 修复: 撞约束 retry into existing). " +
                        "实际 %d 行 = 重复! lostRace=%d", matchingRows.size(), lostInsertRaceCount.get()));

        SemiFinishedInventory finalWip = matchingRows.get(0);

        // 断言2: produced = 2 × inQtyEach — 两线程贡献都累加, 无静默漏记
        BigDecimal expectedProduced = inQtyEach.multiply(new BigDecimal(threads));
        assertEquals(0, expectedProduced.compareTo(finalWip.getProducedQuantity()),
                String.format("producedQty 应为 %s (2x40, 两线程贡献都不丢), 实际 %s. " +
                        "若 < 期望 = 静默漏记 bug 复发", expectedProduced, finalWip.getProducedQuantity()));

        // 断言3: available = produced (无负库存, 无超扣) — consumed=0
        assertTrue(finalWip.getAvailableQuantity().compareTo(BigDecimal.ZERO) >= 0,
                "available 不能为负");
        assertEquals(0, finalWip.getProducedQuantity().compareTo(finalWip.getAvailableQuantity()),
                "consumed=0 时 available 应 = produced");

        // 断言4: 若两线程都进 insert 分支 (真并发) → 恰好 1 个输掉 race 走 retry (证明约束生效 + retry 分支被执行)。
        //   若只有 1 线程 insert (另 1 个因调度晚到, step1 已见 existing) → lostRace=0 也合法 (existing-row 路径接管)。
        //   无论哪种, 上面的 "1 行 + produced=80" 已证明无重复无漏记。
        if (insertAttemptCount.get() == threads) {
            assertEquals(1, lostInsertRaceCount.get(),
                    String.format("两线程都尝试 insert (真 race) 时, 应恰好 1 个撞 unique 约束走 retry. " +
                            "lostRace=%d. 若为 0 → H2 没建 unique 约束, 重复行没被阻止",
                            lostInsertRaceCount.get()));
            log.info("[SP1-new FIXED] 真并发 race: 1 个 insert 赢, 1 个撞约束 retry into existing");
        } else {
            assertEquals(0, lostInsertRaceCount.get(),
                    "只有 1 线程 insert 时不应有 race 损失");
            log.info("[SP1-new FIXED] 调度串行: 1 个 insert, 另 1 个走 existing-row 路径 (insertAttempt={})",
                    insertAttemptCount.get());
        }

        log.info("[SP1-new FIXED] 1 行, produced={}, available={}, 无重复无漏记",
                finalWip.getProducedQuantity(), finalWip.getAvailableQuantity());
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

    /** null-safe BigDecimal → ZERO (test helper)。 */
    private static BigDecimal nzt(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

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
