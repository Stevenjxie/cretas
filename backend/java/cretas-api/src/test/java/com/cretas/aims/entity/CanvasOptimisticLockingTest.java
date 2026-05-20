package com.cretas.aims.entity;

import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.notify.NotifyTemplate;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.entity.rules.RuleActionType;
import com.cretas.aims.entity.rules.RuleScope;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.notify.NotifyTemplateRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-4 P1 Lost Update prevention — verify {@code @Version} is wired correctly on
 * the 5 Canvas Phase 2-5 entities and that two parallel updates to the same row
 * (both starting from {@code version=N}) cause the second one to throw
 * {@link ObjectOptimisticLockingFailureException}.
 *
 * <p>Background (Canvas Phase 2-5 QA audit 2026-05-20): with no {@code @Version},
 * two factory admins editing the same {@code PricingStrategy} / {@code AlertRule} /
 * {@code BusinessRule} / {@code NotifyTemplate} / {@code ScheduledTask} both
 * succeeded silently — last writer won, the other user's edits vanished without
 * any feedback. {@code @Version} forces Hibernate to add a
 * {@code WHERE id=? AND version=?} clause to every UPDATE so a stale snapshot
 * fails-fast, and {@link com.cretas.aims.exception.GlobalExceptionHandler}
 * surfaces the 409 with a clear "请刷新页面查看最新数据后再编辑" hint.
 *
 * <p><b>Why the test uses {@link EntityManager#clear()} between loads</b>:
 * within a single persistence context Hibernate's first-level cache returns the
 * SAME managed instance on repeated {@code findById}, so without clear the
 * "two browser tabs" simulation degenerates into a single-tab no-op. {@code clear()}
 * detaches everything, forcing the next {@code findById} to fetch fresh from DB
 * (and produce a genuinely independent entity copy).
 *
 * <p>Each per-entity test follows the same shape:
 * <ol>
 *   <li>Save fresh entity (initial {@code version=0} assigned by Hibernate)</li>
 *   <li>Load copy A, mutate, save — succeeds, {@code version=1}</li>
 *   <li>Clear persistence context so the next find returns a fresh entity</li>
 *   <li>Load copy B by id — but we override its in-memory version back to 0L
 *       to mimic a stale snapshot held by the other browser tab</li>
 *   <li>Save copy B — expect {@link ObjectOptimisticLockingFailureException}</li>
 * </ol>
 *
 * <p>The "force-stale" approach (step 4) is the canonical way to test JPA
 * optimistic locking in a single-thread test — true parallel threads would need
 * {@code @SpringBootTest} + a separate DB connection pool, which is overkill for
 * a unit assertion of entity-level locking semantics. The behavior under real
 * concurrent HTTP traffic is identical: each request loads its own version
 * snapshot, mutates, and the second flush hits the stale-version path.
 *
 * <p>Uses {@link DataJpaTest} JPA slice with H2 in PostgreSQL compatibility mode.
 *
 * @since 2026-05-20 (AUD-4 P1 hotfix)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = {
        "com.cretas.aims.repository.pricing",
        "com.cretas.aims.repository.alerts",
        "com.cretas.aims.repository.rules",
        "com.cretas.aims.repository.notify",
        "com.cretas.aims.repository.cron"
})
@DisplayName("AUD-4 P1 — Canvas entities @Version optimistic locking")
class CanvasOptimisticLockingTest {

    private static final String FACTORY_ID = "F-AUD4-IT";

    @Autowired private PricingStrategyRepository pricingRepo;
    @Autowired private BusinessRuleRepository businessRuleRepo;
    @Autowired private NotifyTemplateRepository notifyTemplateRepo;
    @Autowired private ScheduledTaskRepository scheduledTaskRepo;
    @Autowired private EntityManager em;

    // ============================================================
    // PricingStrategy
    // ============================================================

    @Test
    @DisplayName("PricingStrategy: version increments on save; parallel update fails 2nd write")
    void pricingStrategy_optimisticLock_blocksLostUpdate() {
        // 1. Initial save — version=0 assigned by Hibernate @Version semantics
        PricingStrategy fresh = new PricingStrategy();
        fresh.setFactoryId(FACTORY_ID);
        fresh.setStrategyCode("AUD4-PS-1");
        fresh.setStrategyName("AUD-4 baseline");
        fresh.setStrategyType(PricingStrategyType.TIERED);
        fresh.setPriority(100);
        fresh.setEnabled(true);
        fresh.setValidFrom(LocalDate.now());
        PricingStrategy saved = pricingRepo.saveAndFlush(fresh);
        String id = saved.getId();
        assertNotNull(saved.getVersion(), "version assigned after first save");
        assertEquals(0L, saved.getVersion().longValue(), "first save → version=0");

        // 2. Capture User B's stale snapshot BEFORE User A's update lands. Detach
        //    immediately so it stays at version=0 even after User A bumps DB to version=1.
        em.clear();
        PricingStrategy copyB = pricingRepo.findById(id).orElseThrow();
        em.detach(copyB);
        copyB.setStrategyName("AUD-4 user B loses");

        // 3. User A loads + saves — version bumps to 1 in DB.
        em.clear();
        PricingStrategy copyA = pricingRepo.findById(id).orElseThrow();
        copyA.setStrategyName("AUD-4 user A wins");
        PricingStrategy winner = pricingRepo.saveAndFlush(copyA);
        assertEquals(1L, winner.getVersion().longValue(),
                "After successful update, Hibernate bumps version to 1");

        // 4. User B's detached snapshot (still version=0) gets merged — Hibernate sees
        //    the stale version, fires UPDATE ... WHERE id=? AND version=0, 0 rows match,
        //    throws ObjectOptimisticLockingFailureException.
        em.clear();
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> pricingRepo.saveAndFlush(copyB),
                "Stale version=0 update against current version=1 must trip optimistic lock");

        // 5. Reload — value from User A wins, NOT User B (no silent overwrite)
        em.clear();
        PricingStrategy reloaded = pricingRepo.findById(id).orElseThrow();
        assertEquals("AUD-4 user A wins", reloaded.getStrategyName(),
                "Lost Update prevented — User A's value remains, User B's update was rejected");
        assertEquals(1L, reloaded.getVersion().longValue(),
                "Version did not advance past 1 (failed update did not increment)");
    }

    // ============================================================
    // AlertRule
    // ============================================================

    /**
     * AlertRule is NOT covered by the runtime H2 integration test because the entity's
     * {@code @PrePersist onPersistRule()} unconditionally initializes empty
     * {@code notifyChannels} / {@code notifyRoles} to {@code new ArrayList<>()}.
     * Those columns use {@code @Type(JsonBinaryType.class)} from io.hypersistence-utils
     * which round-trips OK on real PostgreSQL but breaks on H2 (PG-compat mode stores
     * JSONB as VARCHAR; the type adapter cannot decode {@code "[]"} back to a List
     * because Jackson's StringCollectionDeserializer rejects {@code ""[]""} from
     * VARCHAR re-quoting). The @PrePersist runs before our flush so we can't
     * dodge the empty-list default.
     *
     * <p>Structural reflection test below (alertRule_hasVersionAnnotation) verifies
     * the {@code @Version} contract; behavior under PG is identical to the 4 entities
     * that pass the runtime test — JPA optimistic locking is column-independent. Real
     * end-to-end on prod PG is exercised when sister chats run their existing
     * integration tests against the deployed env.
     */
    @Test
    @DisplayName("AlertRule: @Version annotation present on entity field (structural)")
    void alertRule_hasVersionAnnotation() throws NoSuchFieldException {
        assertVersionAnnotationPresent(AlertRule.class);
    }

    // ============================================================
    // BusinessRule (Canvas-Rules)
    // ============================================================

    @Test
    @DisplayName("BusinessRule: version increments on save; parallel update fails 2nd write")
    void businessRule_optimisticLock_blocksLostUpdate() {
        // actionConfigJson defaults to HashMap{} via @Builder.Default → serializes as "{}"
        // which JsonBinaryType then fails to round-trip on H2 (see AlertRule note above).
        // Force null so we exercise pure version semantics without the JSON-on-H2 issue.
        BusinessRule fresh = BusinessRule.builder()
                .factoryId(FACTORY_ID)
                .ruleCode("AUD4-BR-1")
                .ruleName("AUD-4 rule baseline")
                .scope(RuleScope.ORDER)
                .actionType(RuleActionType.LOG)
                .priority(100)
                .enabled(true)
                .actionConfigJson(null)
                .build();
        BusinessRule saved = businessRuleRepo.saveAndFlush(fresh);
        UUID id = saved.getId();
        assertEquals(0L, saved.getVersion().longValue(), "first save → version=0");

        em.clear();
        BusinessRule copyB = businessRuleRepo.findById(id).orElseThrow();
        em.detach(copyB);
        copyB.setRuleName("AUD-4 rule user B loses");

        em.clear();
        BusinessRule copyA = businessRuleRepo.findById(id).orElseThrow();
        copyA.setRuleName("AUD-4 rule user A wins");
        BusinessRule winner = businessRuleRepo.saveAndFlush(copyA);
        assertEquals(1L, winner.getVersion().longValue());

        em.clear();
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> businessRuleRepo.saveAndFlush(copyB));

        em.clear();
        BusinessRule reloaded = businessRuleRepo.findById(id).orElseThrow();
        assertEquals("AUD-4 rule user A wins", reloaded.getRuleName());
    }

    // ============================================================
    // NotifyTemplate (CRUD currently stubbed — entity-level lock still verified
    // so when Phase 3 sister chat fills in the controller it Just Works)
    // ============================================================

    @Test
    @DisplayName("NotifyTemplate: version increments on save; parallel update fails 2nd write")
    void notifyTemplate_optimisticLock_blocksLostUpdate() {
        // channels / variablesSchemaJson left null — JsonBinaryType + H2 round-trip
        // limitation (see AlertRule note above). Version semantics are JSON-independent.
        NotifyTemplate fresh = NotifyTemplate.builder()
                .factoryId(FACTORY_ID)
                .templateCode("AUD4-NT-1")
                .title("AUD-4 baseline title")
                .bodyTemplate("hello {{name}}")
                .channels(null)
                .variablesSchemaJson(null)
                .build();
        NotifyTemplate saved = notifyTemplateRepo.saveAndFlush(fresh);
        UUID id = saved.getId();
        assertEquals(0L, saved.getVersion().longValue(), "first save → version=0");

        em.clear();
        NotifyTemplate copyB = notifyTemplateRepo.findById(id).orElseThrow();
        em.detach(copyB);
        copyB.setTitle("AUD-4 notify user B loses");

        em.clear();
        NotifyTemplate copyA = notifyTemplateRepo.findById(id).orElseThrow();
        copyA.setTitle("AUD-4 notify user A wins");
        NotifyTemplate winner = notifyTemplateRepo.saveAndFlush(copyA);
        assertEquals(1L, winner.getVersion().longValue());

        em.clear();
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> notifyTemplateRepo.saveAndFlush(copyB));

        em.clear();
        NotifyTemplate reloaded = notifyTemplateRepo.findById(id).orElseThrow();
        assertEquals("AUD-4 notify user A wins", reloaded.getTitle());
    }

    // ============================================================
    // ScheduledTask
    // ============================================================

    @Test
    @DisplayName("ScheduledTask: version increments on save; parallel update fails 2nd write")
    void scheduledTask_optimisticLock_blocksLostUpdate() {
        ScheduledTask fresh = ScheduledTask.builder()
                .factoryId(FACTORY_ID)
                .taskCode("AUD4-ST-1")
                .taskName("AUD-4 task baseline")
                .cronExpression("0 0 9 1 * ?")
                .handlerBeanName("echoTaskHandler")
                .enabled(true)
                .build();
        ScheduledTask saved = scheduledTaskRepo.saveAndFlush(fresh);
        UUID id = saved.getId();
        assertEquals(0L, saved.getVersion().longValue(), "first save → version=0");

        em.clear();
        ScheduledTask copyB = scheduledTaskRepo.findById(id).orElseThrow();
        em.detach(copyB);
        copyB.setTaskName("AUD-4 task user B loses");

        em.clear();
        ScheduledTask copyA = scheduledTaskRepo.findById(id).orElseThrow();
        copyA.setTaskName("AUD-4 task user A wins");
        ScheduledTask winner = scheduledTaskRepo.saveAndFlush(copyA);
        assertEquals(1L, winner.getVersion().longValue());

        em.clear();
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> scheduledTaskRepo.saveAndFlush(copyB));

        em.clear();
        ScheduledTask reloaded = scheduledTaskRepo.findById(id).orElseThrow();
        assertEquals("AUD-4 task user A wins", reloaded.getTaskName());
    }

    // ============================================================
    // Structural sweep — verify all 5 entities have @Version on a Long field
    // mapped to a "version" column. Catches drift if a sister refactor accidentally
    // removes the annotation or renames the field/column.
    // ============================================================

    @Test
    @DisplayName("All 5 Canvas entities carry @Version Long version field (structural sweep)")
    void allFiveCanvasEntities_haveVersionAnnotation() throws NoSuchFieldException {
        assertVersionAnnotationPresent(PricingStrategy.class);
        assertVersionAnnotationPresent(AlertRule.class);
        assertVersionAnnotationPresent(BusinessRule.class);
        assertVersionAnnotationPresent(NotifyTemplate.class);
        assertVersionAnnotationPresent(ScheduledTask.class);
    }

    /**
     * Reflection assertion: entity class declares a {@code version} field annotated
     * with {@code @Version} (JPA optimistic locking marker). Field type must be
     * {@code Long} (boxed) so Hibernate can distinguish "not yet persisted" (null)
     * from "version=0" (managed first save). Catches accidental annotation removal
     * during future refactors.
     */
    private static void assertVersionAnnotationPresent(Class<?> entityClass) throws NoSuchFieldException {
        Field field = entityClass.getDeclaredField("version");
        Version annotation = field.getAnnotation(Version.class);
        assertNotNull(annotation,
                entityClass.getSimpleName() + ".version must carry @jakarta.persistence.Version " +
                        "for AUD-4 Lost Update prevention");
        assertEquals(Long.class, field.getType(),
                entityClass.getSimpleName() + ".version must be boxed Long " +
                        "(Hibernate distinguishes null=transient vs 0=managed-fresh); got " +
                        field.getType().getSimpleName());
        jakarta.persistence.Column col = field.getAnnotation(jakarta.persistence.Column.class);
        assertNotNull(col, entityClass.getSimpleName() + ".version must carry @Column to bind to DB column");
        assertEquals("version", col.name(),
                entityClass.getSimpleName() + ".version must map to DB column 'version'");
        assertTrue(!col.nullable(),
                entityClass.getSimpleName() + ".version @Column(nullable=false) — V20260626_02 " +
                        "migration sets DEFAULT 0 NOT NULL so the schema also enforces non-null");
    }
}
